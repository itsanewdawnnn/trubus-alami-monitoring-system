package com.trubus.tams.data.geocoding

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** One forward-geocoding match -- a short label plus the coordinates it resolves to. */
data class AddressSearchResult(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Forward-geocoding counterpart to [ReverseGeocodingService]: turns a typed
 * address query into a short list of candidate places, using the same
 * OpenStreetMap Nominatim API (no new API key, same data source osmdroid
 * already renders tiles from). The Outlet feature's "Search Address" field
 * (see ui/screens/OutletScreen.kt's OutletFormScreen) does not call this
 * class directly -- it goes through [AddressSearchService], which tries
 * this provider first and only falls back to [PhotonSearchService] if this
 * one comes back empty, and also owns the shared cache both providers'
 * results are stored in (see that class's own doc comment). This class has
 * no knowledge of the fallback or the cache -- it only knows how to search
 * Nominatim itself.
 *
 * Deliberately its own singleton with its own tiny OkHttpClient, exactly
 * like [ReverseGeocodingService] and for the same reasons: a different host
 * than ApiService's backend, none of that client's auth interceptor, short
 * timeouts since a slow response should never block the rest of the form.
 *
 * Caching against repeated identical queries is required, not optional --
 * Nominatim's usage policy caps free usage at ~1 request/second -- but lives
 * one layer up, in [AddressSearchService], not here: that's the only class
 * every caller goes through, and a cache shared across both providers there
 * catches the case this class's own cache couldn't -- a query Nominatim
 * found nothing for but Photon did, repeated later, which would otherwise
 * still re-hit Nominatim's network every time despite the final answer
 * already being known. See [AddressSearchService]'s own doc comment.
 * Callers are also expected to debounce keystrokes themselves (~500ms, see
 * OutletFormScreen) -- this class does not debounce on its own.
 */
object NominatimSearchService : ForwardGeocodingProvider {

    private const val TAG = "NominatimSearch"

    // Below this length, Nominatim's own results are too noisy to be useful
    // and the request is pure waste -- mirrors the "wait for real intent"
    // rationale a debounce already provides, just for length instead of time.
    private const val MIN_QUERY_LENGTH = 3

    private const val MAX_RESULTS = 8

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Returns up to [MAX_RESULTS] candidate places for [query], or an empty
     * list if the query is too short, the lookup fails, or nothing matched --
     * callers should treat all three the same way (no results to show),
     * never surface a network error for what is a supplementary search-as-
     * you-type affordance, not a required step (a Member can still place the
     * pin manually or use their current location).
     */
    override suspend fun search(query: String): List<AddressSearchResult> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return@withContext emptyList()

        val url = "https://nominatim.openstreetmap.org/search" +
            "?format=jsonv2&q=${URLEncoder.encode(trimmed, "UTF-8")}" +
            "&addressdetails=0&limit=$MAX_RESULTS" +
            "&countrycodes=id"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TAMS-TrubusAlamiMonitoring/1.0")
            .build()

        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "Address search failed: ${e.message}")
                        continuation.resume(emptyList())
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!it.isSuccessful) {
                                Log.w(TAG, "Address search HTTP ${it.code}")
                                continuation.resume(emptyList())
                                return
                            }
                            val body = it.body?.string()
                            if (body == null) {
                                continuation.resume(emptyList())
                                return
                            }
                            val parsed = try {
                                parseResults(body)
                            } catch (_: org.json.JSONException) {
                                Log.w(TAG, "Address search: malformed JSON response")
                                emptyList()
                            }
                            continuation.resume(parsed)
                        }
                    }
                },
            )
        }
    }

    private fun parseResults(body: String): List<AddressSearchResult> {
        val array = JSONArray(body)
        val results = mutableListOf<AddressSearchResult>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val lat = obj.optString("lat", "").toDoubleOrNull() ?: continue
            val lon = obj.optString("lon", "").toDoubleOrNull() ?: continue
            val displayName = obj.optString("display_name", "")
            if (displayName.isBlank()) continue
            results.add(AddressSearchResult(label = displayName, latitude = lat, longitude = lon))
        }
        return results
    }
}
