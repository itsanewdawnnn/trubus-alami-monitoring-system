package com.trubus.tams.data.geocoding

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Turns raw GPS coordinates into a short "Near X, Jalan Y" label using
 * OpenStreetMap's free Nominatim reverse-geocoding API -- the same data
 * source osmdroid already renders tiles from, so no new API key is needed.
 *
 * A plain singleton `object` with its own tiny OkHttpClient rather than
 * ApiService/Retrofit: Nominatim is a different host, needs none of
 * ApiService's auth interceptor, and uses short timeouts since a slow
 * response should never block anything tracking-critical.
 *
 * Caching is required, not optional: Nominatim's usage policy caps free
 * usage at ~1 request/second and asks that identical lookups be cached
 * client-side. Coordinates are rounded to 4 decimals (~11m) as the cache
 * key so GPS jitter around the same spot reuses the cached label.
 */
object ReverseGeocodingService {

    private const val TAG = "ReverseGeocoding"

    // Full clear on overflow instead of real LRU bookkeeping -- this cache
    // only avoids redundant requests for recently-seen coordinates, so
    // occasionally losing entries costs nothing but one extra network call.
    private const val MAX_CACHE_ENTRIES = 200
    private val cache = ConcurrentHashMap<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun cacheKey(latitude: Double, longitude: Double): String {
        return "%.4f,%.4f".format(Locale.US, latitude, longitude)
    }

    /**
     * Returns a short nearby-landmark label for the given coordinates, or
     * null if the lookup fails or nothing usable was returned -- callers
     * should fall back to showing raw coordinates in that case, never block
     * or show an error for what is only a supplementary detail.
     */
    suspend fun getNearbyLabel(latitude: Double, longitude: Double): String? {
        val key = cacheKey(latitude, longitude)
        cache[key]?.let { return it }

        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=jsonv2&lat=$latitude&lon=$longitude&zoom=18&addressdetails=0"
        val request = Request.Builder()
            .url(url)
            // Required by Nominatim's usage policy -- the default OkHttp user
            // agent is known to get rejected/throttled.
            .header("User-Agent", "TAMS-TrubusAlamiMonitoring/1.0")
            .build()

        // suspendCancellableCoroutine + Call.enqueue (not withContext + the
        // blocking execute()) so that cancelling the caller (e.g. the user
        // closes the point-detail card before the lookup returns) actually
        // aborts the in-flight HTTP call via invokeOnCancellation, instead of
        // leaving a blocked IO thread running for up to the 6s timeout for
        // a result nothing will ever read.
        val label = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "Reverse geocoding failed: ${e.message}")
                        continuation.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!it.isSuccessful) {
                                Log.w(TAG, "Reverse geocoding HTTP ${it.code}")
                                continuation.resume(null)
                                return
                            }
                            val body = it.body?.string()
                            if (body == null) {
                                continuation.resume(null)
                                return
                            }
                            val displayName = try {
                                JSONObject(body).optString("display_name", "")
                            } catch (_: org.json.JSONException) {
                                Log.w(TAG, "Reverse geocoding: malformed JSON response")
                                ""
                            }
                            continuation.resume(formatLabel(displayName))
                        }
                    }
                },
            )
        }

        if (label != null) {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                cache.clear()
            }
            cache[key] = label
        }
        return label
    }

    /**
     * Nominatim's `display_name` lists components from most to least
     * specific, comma-separated. Takes just the first two segments and
     * prefixes "Near " for a short landmark hint instead of the full address.
     */
    private fun formatLabel(displayName: String): String? {
        if (displayName.isBlank()) return null
        val segments = displayName.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (segments.isEmpty()) return null
        val shortAddress = segments.take(2).joinToString(", ")
        return "Near $shortAddress"
    }
}
