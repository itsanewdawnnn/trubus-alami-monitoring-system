package com.trubus.tams.data.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Emitted progress states for [ApkDownloadManager.download]. */
sealed class DownloadProgress {
    data class InProgress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadProgress()
    data class Success(val file: File) : DownloadProgress()
    data class Failed(val message: String) : DownloadProgress()
}

/**
 * Downloads the update APK to app-private storage and reports progress as
 * it streams. Deliberately a plain OkHttp GET with manual byte-copying
 * rather than Android's system `DownloadManager` service: this keeps the
 * download on the same HTTP stack/timeout conventions as the rest of the
 * app, gives real-time byte-level progress without polling a
 * ContentResolver cursor, and needs no extra permissions or a broadcast
 * receiver to learn when it finished.
 *
 * Storage: `context.filesDir/updates/` (app-private internal storage) --
 * requires zero permissions under scoped storage on every supported API
 * level (minSdk 24), and is exposed to the system package installer only
 * via a FileProvider content:// URI (see AndroidManifest.xml's
 * `<provider>` + res/xml/file_paths.xml), never a raw file:// path.
 */
class ApkDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ApkDownloadManager"
        private const val UPDATES_DIR_NAME = "updates"
        const val APK_FILE_NAME = "tams_update.apk"
        private const val TEMP_FILE_NAME = "$APK_FILE_NAME.part"
        private const val MAX_ATTEMPTS = 3 // 1 initial try + 2 retries
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updatesDir(): File {
        val dir = File(context.filesDir, UPDATES_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun apkFile(): File = File(updatesDir(), APK_FILE_NAME)

    /**
     * Deletes any previously-downloaded (or partially-downloaded) update
     * APK. Called before every new download attempt, and should also be
     * called once the app hands the file off to the package installer --
     * a stale APK left behind serves no purpose once either superseded by
     * a newer one or already installed, and would otherwise accumulate
     * silently on every update cycle.
     */
    fun clearDownloadedApk() {
        listOf(apkFile(), File(updatesDir(), TEMP_FILE_NAME)).forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete stale update file at ${file.path}")
            }
        }
    }

    /**
     * Streams [apkUrl] to app-private storage, emitting [DownloadProgress]
     * as it goes. Retries up to [MAX_ATTEMPTS] times on a transient I/O
     * failure (dropped connection mid-download, timeout, non-2xx response)
     * before giving up with [DownloadProgress.Failed]. Cancelling the
     * collecting coroutine aborts the in-flight OkHttp call via
     * `Call.cancel()` on the next suspension point, not just detaching a
     * listener -- no orphaned background download keeps running after the
     * caller stops collecting.
     */
    fun download(apkUrl: String): Flow<DownloadProgress> = flow {
        clearDownloadedApk()
        val targetFile = apkFile()
        val tempFile = File(updatesDir(), TEMP_FILE_NAME)

        var attempt = 0
        var lastError: String? = null
        var succeeded = false

        while (attempt < MAX_ATTEMPTS && (!succeeded)) {
            attempt++
            var call: Call? = null
            try {
                val request = Request.Builder().url(apkUrl).build()
                call = client.newCall(request)
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Server returned HTTP ${response.code}.")
                    }
                    val body = response.body ?: throw IOException("Empty download response.")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    // Distinct sentinels so the very first chunk always
                    // triggers an emit regardless of which branch below runs.
                    var lastEmittedPercent = -2
                    var lastEmittedBytes = -1L

                    body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                if (totalBytes > 0) {
                                    val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    // Only emit on an actual percent change --
                                    // emitting per 8KB chunk would flood the
                                    // UI (and the collecting StateFlow) with
                                    // hundreds of updates a second on a fast
                                    // connection for no visible benefit.
                                    if (percent != lastEmittedPercent) {
                                        lastEmittedPercent = percent
                                        emit(DownloadProgress.InProgress(percent, downloadedBytes, totalBytes))
                                    }
                                } else {
                                    // No Content-Length header -- can't compute
                                    // a percent at all. Emit on byte
                                    // milestones instead of every chunk, so
                                    // the UI shows "X MB downloaded" moving
                                    // rather than looking frozen for the
                                    // whole download.
                                    if (downloadedBytes - lastEmittedBytes >= 256 * 1024) {
                                        lastEmittedBytes = downloadedBytes
                                        emit(DownloadProgress.InProgress(-1, downloadedBytes, -1))
                                    }
                                }
                            }
                        }
                    }
                }

                if (!tempFile.renameTo(targetFile)) {
                    throw IOException("Failed to save the downloaded APK file.")
                }
                succeeded = true
            } catch (e: CancellationException) {
                call?.cancel()
                tempFile.delete()
                throw e
            } catch (e: IOException) {
                lastError = friendlyIoErrorMessage(e)
                Log.w(TAG, "Download attempt $attempt/$MAX_ATTEMPTS failed: ${e.message}")
                tempFile.delete()
            }
        }

        if (succeeded) {
            emit(DownloadProgress.Success(targetFile))
        } else {
            emit(DownloadProgress.Failed(lastError ?: "Download failed after several attempts."))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Maps common low-level I/O failures to short messages a Member/Admin
     * can actually act on, instead of a raw exception message (e.g. "ENOSPC
     * (No space left on device)").
     */
    private fun friendlyIoErrorMessage(e: IOException): String {
        val raw = e.message ?: ""
        return when {
            raw.contains("ENOSPC", ignoreCase = true) || raw.contains("No space left", ignoreCase = true) ->
                "Not enough storage space to download the update."
            e is java.net.SocketTimeoutException ->
                "Connection interrupted while downloading the update."
            e is java.net.UnknownHostException || e is java.net.ConnectException ->
                "Could not connect to the server. Check your internet connection."
            // Falls back to a generic message rather than the raw `raw`
            // string -- an unrecognized IOException (e.g. an SSLException,
            // or a low-level "unexpected end of stream" from the HTTP
            // client) is exactly the kind of raw exception text this
            // function exists to avoid showing a Member/Admin; the previous
            // fallback defeated that for anything not in the three cases
            // above. The raw message is still visible in Logcat via the
            // Log.w call at this function's call site.
            else -> "Failed to download the update. Please try again."
        }
    }
}
