package com.trubus.tams.data.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.FileProvider
import com.trubus.tams.BuildConfig
import com.trubus.tams.data.model.VersionInfoDto
import com.trubus.tams.data.repository.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * The whole OTA update flow as one state machine: no update known yet ->
 * a newer version is available (dialog shown) -> downloading -> either
 * ready to install or failed -> (if the OS blocks installs from this app)
 * needs a one-time settings permission first. `info` rides along on every
 * state past `Available` so the dialog can keep showing the same
 * version name/release notes/force-update flag throughout, without the UI
 * needing to separately track "which update is this download for".
 */
sealed class UpdateFlowState {
    object None : UpdateFlowState()
    data class Available(val info: VersionInfoDto) : UpdateFlowState()

    /** [percent] is -1 when the server didn't send a Content-Length header -- the dialog falls back to showing [downloadedBytes] instead of a percentage in that case. */
    data class Downloading(
        val info: VersionInfoDto,
        val percent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateFlowState()

    data class DownloadFailed(val info: VersionInfoDto, val message: String) : UpdateFlowState()
    data class NeedsInstallPermission(val info: VersionInfoDto, val apkFile: File) : UpdateFlowState()
    data class ReadyToInstall(val info: VersionInfoDto, val apkFile: File) : UpdateFlowState()
}

/**
 * Orchestrates the OTA update flow: check -> optional dialog / forced block
 * -> download -> install hand-off. Owned privately by `MainViewModel`
 * (never exposed to the UI layer directly -- CLAUDE.md's architecture rule
 * is that MainViewModel is the only thing ui/screens/ talks to);
 * MainViewModel forwards `state` as its own StateFlow and delegates its
 * update-related functions here, the same composition pattern it already
 * uses for `MemberRepository`.
 *
 * Deliberately a plain class, not a ViewModel of its own: it needs no
 * SavedStateHandle or lifecycle awareness beyond the Application context it
 * already gets handed, matching how MemberRepository is constructed.
 */
class UpdateManager(
    context: Context,
    private val updateRepository: UpdateRepository
) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val PREFS_NAME = "tams_update_manager"
        private const val KEY_VERSION_CODE = "pending_version_code"
        private const val KEY_VERSION_NAME = "pending_version_name"
        private const val KEY_APK_URL = "pending_apk_url"
        private const val KEY_RELEASE_NOTES = "pending_release_notes"
    }

    private val appContext = context.applicationContext
    private val downloadManager = ApkDownloadManager(appContext)

    // Durable record of the last MANDATORY update this device was told
    // about, surviving process death, Force Stop, and device restarts --
    // see restorePendingForceUpdate()'s doc comment for why this exists.
    // Deliberately its own dedicated prefs file, never shared with
    // MemberRepository/RemoteConfigRepository's own files.
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateFlowState>(UpdateFlowState.None)
    val state: StateFlow<UpdateFlowState> = _state.asStateFlow()

    init {
        // Runs synchronously during construction -- i.e. before
        // MainViewModel's own init{} block or MainAppScreen's first
        // composition ever reads [state] -- so a still-outstanding
        // mandatory update blocks the very first frame, with no gap where
        // the real login/dashboard screen could flash underneath while an
        // async re-check is still in flight (or, if the device is offline,
        // a gap that would otherwise never close at all).
        restorePendingForceUpdate()
    }

    /**
     * Checks the server for a version newer than [currentVersionCode].
     * Silent on failure (server unreachable, malformed row, timeout, etc.)
     * -- this runs on every app launch, so surfacing an error dialog for a
     * failed *check* would false-alarm on every offline launch and train
     * users to reflexively dismiss update prompts. Only a successful check
     * changes [state]: a genuinely newer version moves it to
     * [UpdateFlowState.Available] (persisting it first if mandatory -- see
     * [persistPendingForceUpdate]); confirming the running build is already
     * current resets back to [UpdateFlowState.None] (and clears any
     * persisted mandatory-update record) UNLESS a download is actively in
     * flight, which this must never clobber.
     */
    suspend fun checkForUpdate(currentVersionCode: Int) {
        updateRepository.fetchLatestVersion()
            .onSuccess { info ->
                if (info.version_code > currentVersionCode) {
                    _state.value = UpdateFlowState.Available(info)
                    if (info.force_update) {
                        persistPendingForceUpdate(info)
                    } else {
                        clearPersistedPendingForceUpdate()
                    }
                } else if (_state.value !is UpdateFlowState.Downloading) {
                    // Either genuinely up to date, or the server-side
                    // required version was lowered / force_update turned
                    // off after this device was already blocked -- either
                    // way nothing further should be required of it. Never
                    // clobbers an in-flight download; MainViewModel's
                    // onAppResumed() already avoids calling this while
                    // Downloading/NeedsInstallPermission for the same
                    // reason, but this is guarded independently too.
                    _state.value = UpdateFlowState.None
                    clearPersistedPendingForceUpdate()
                }
            }
            .onFailure { e ->
                Log.w(TAG, "Version check failed (non-fatal, app continues normally): ${e.message}")
            }
    }

    /**
     * Restores a previously-detected MANDATORY update from local storage if
     * one is still outstanding (its version_code is still newer than this
     * running build) -- without this, a device already told "you must
     * update" could dodge that requirement indefinitely just by being
     * offline (or Force Stopped, or restarted) the next time the app opens,
     * since [checkForUpdate] alone is silent-on-failure and would otherwise
     * leave [state] at [UpdateFlowState.None] with no memory of the block.
     *
     * Deliberately restores into [UpdateFlowState.Available], never
     * whichever sub-state (Downloading/ReadyToInstall/NeedsInstallPermission)
     * was active when the previous process ended -- an in-progress download
     * or a downloaded APK from a previous process isn't trusted to still be
     * intact, so the safest resume point is "known mandatory, not yet
     * (re-)downloaded". Also self-cleans: if the persisted version_code is
     * no longer newer than this build (a real install succeeded, or the
     * record is otherwise stale), the record is discarded instead of kept
     * around forever.
     */
    private fun restorePendingForceUpdate() {
        val versionCode = prefs.getInt(KEY_VERSION_CODE, -1)
        if (versionCode <= BuildConfig.VERSION_CODE) {
            if (versionCode != -1) clearPersistedPendingForceUpdate()
            return
        }
        val versionName = prefs.getString(KEY_VERSION_NAME, null) ?: return
        val apkUrl = prefs.getString(KEY_APK_URL, null) ?: return
        val releaseNotes = decodeReleaseNotes(prefs.getString(KEY_RELEASE_NOTES, null))
        _state.value = UpdateFlowState.Available(
            VersionInfoDto(
                version_code = versionCode,
                version_name = versionName,
                force_update = true,
                apk_url = apkUrl,
                release_notes = releaseNotes
            )
        )
    }

    private fun persistPendingForceUpdate(info: VersionInfoDto) {
        prefs.edit {
            putInt(KEY_VERSION_CODE, info.version_code)
            putString(KEY_VERSION_NAME, info.version_name)
            putString(KEY_APK_URL, info.apk_url)
            putString(KEY_RELEASE_NOTES, JSONArray(info.release_notes).toString())
        }
    }

    private fun clearPersistedPendingForceUpdate() {
        prefs.edit { clear() }
    }

    private fun decodeReleaseNotes(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * "Nanti" -- dismisses the dialog for this session. Only ever wired up
     * by the UI for a non-forced update (see UpdateDialog's Later button,
     * which the force-update variant never renders), but this itself
     * doesn't re-check force_update -- the caller (MainViewModel) is
     * responsible for not calling this when [UpdateFlowState.info] says
     * otherwise. Callable from any state (including mid-download) so the
     * user can back out at any point up until the install hand-off.
     */
    fun dismiss() {
        _state.value = UpdateFlowState.None
    }

    /**
     * Downloads [info]'s APK, updating [state] as it progresses. Must be
     * launched on a cancellable coroutine the caller owns (MainViewModel's
     * viewModelScope) -- cancelling that job aborts the in-flight download
     * (see ApkDownloadManager.download's own cancellation handling) rather
     * than leaving an orphaned background transfer.
     */
    suspend fun startDownload(info: VersionInfoDto) {
        downloadManager.download(info.apk_url).collect { progress ->
            _state.value = when (progress) {
                is DownloadProgress.InProgress ->
                    UpdateFlowState.Downloading(info, progress.percent, progress.downloadedBytes, progress.totalBytes)
                is DownloadProgress.Success ->
                    resolveAfterDownload(info, progress.file)
                is DownloadProgress.Failed ->
                    UpdateFlowState.DownloadFailed(info, progress.message)
            }
        }
    }

    private fun resolveAfterDownload(info: VersionInfoDto, apkFile: File): UpdateFlowState =
        if (canInstallPackages()) {
            UpdateFlowState.ReadyToInstall(info, apkFile)
        } else {
            UpdateFlowState.NeedsInstallPermission(info, apkFile)
        }

    /**
     * True if this app is currently allowed to install packages from its
     * own downloaded files. Always true below API 26 -- pre-Oreo, "install
     * from unknown sources" was a single global Settings toggle the OS
     * itself enforces at install time, not a per-app grant this code can
     * query; nothing to gate here on those versions.
     */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the OS settings screen where the user grants "install unknown apps" for this app specifically (API 26+ only -- see [canInstallPackages]). */
    fun installPermissionSettingsIntent(): Intent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
        } else {
            Settings.ACTION_SECURITY_SETTINGS
        }
        return Intent(action, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Re-checks install permission and, if now granted, advances
     * [UpdateFlowState.NeedsInstallPermission] to [UpdateFlowState.ReadyToInstall].
     * Call this when the app resumes (e.g. an `ON_RESUME` lifecycle event)
     * after the user was sent to [installPermissionSettingsIntent] -- a
     * no-op in every other state.
     */
    fun recheckInstallPermission() {
        val current = _state.value
        if (current is UpdateFlowState.NeedsInstallPermission && (canInstallPackages())) {
            _state.value = UpdateFlowState.ReadyToInstall(current.info, current.apkFile)
        }
    }

    /**
     * Launches the system Package Installer for [apkFile] via a
     * FileProvider content:// URI -- never a raw file:// Uri, which throws
     * FileUriExposedException on every API level this app supports.
     *
     * On the rare device with no component able to handle the install
     * intent at all, this falls back [state] to [UpdateFlowState.DownloadFailed]
     * (reusing that state rather than adding a one-off variant just for this
     * edge case) instead of leaving the caller to silently do nothing -- for
     * a non-forced update, UpdateDialog renders no UI for
     * [UpdateFlowState.ReadyToInstall] itself (see its doc comment), so
     * without this the user would see the dialog simply vanish with no
     * explanation.
     */
    fun launchInstall(apkFile: File): Boolean {
        val current = _state.value
        val info = (current as? UpdateFlowState.ReadyToInstall)?.info ?: current.let {
            (it as? UpdateFlowState.NeedsInstallPermission)?.info
        }

        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}")
            info?.let {
                _state.value = UpdateFlowState.DownloadFailed(
                    it,
                    "No package installer app was found on this device."
                )
            }
            false
        }
    }

    // Deliberately no cleanup call right after launchInstall(): the
    // installer reads apkFile asynchronously through the granted content://
    // URI after this returns, so deleting it immediately could race a
    // still-in-progress install and corrupt it. A successful install always
    // kills and relaunches this app's process anyway (Android does this for
    // any app updating itself), which discards this whole in-memory state
    // machine along with it. The next update cycle's
    // ApkDownloadManager.clearDownloadedApk() (called at the top of
    // download()) is what actually reclaims the file's space, whether the
    // previous install succeeded or the user backed out of it.
}
