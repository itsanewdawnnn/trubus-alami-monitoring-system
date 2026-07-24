package com.trubus.tams.data.geocoding

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Fallback forward-geocoding provider, only ever tried by
 * [AddressSearchService] when [NominatimSearchService] itself returned no
 * results -- Komoot's free public Photon demo server (photon.komoot.io), no
 * API key, also built on OpenStreetMap data but with a different search
 * engine (OpenSearch-based, typo-tolerant, "search-as-you-type" per its own
 * docs) that succeeds on some queries Nominatim's stricter token matching
 * misses (typos, partial/reordered words, informal phrasing). Same
 * underlying OSM coverage as Nominatim, though: an address that has simply
 * never been mapped in OpenStreetMap at all -- the single biggest cause of
 * "not found" for small/local addresses in Indonesia -- will still fail
 * here too. This only widens the "it's in OSM but phrased/typed differently"
 * class of miss; see [AddressSearchService]'s own doc comment for the full
 * picture and why a paid provider was deliberately not used instead.
 *
 * Same singleton-object-with-its-own-tiny-OkHttpClient shape as
 * [NominatimSearchService]/[ReverseGeocodingService], for the same reasons
 * (different host, no ApiService auth, short timeouts). Komoot's demo server
 * usage policy just asks for "reasonable" request volume (no published hard
 * number, unlike Nominatim's explicit ~1 req/s) -- this provider only ever
 * running when Nominatim already found nothing (never on every keystroke),
 * plus the shared cache [AddressSearchService] keeps for both providers'
 * results, keeps usage well within that. This class has no cache of its
 * own -- see [AddressSearchService]'s own doc comment for why.
 */
object PhotonSearchService : ForwardGeocodingProvider {

    private const val TAG = "PhotonSearch"

    // Mirrors NominatimSearchService's own constant -- same reasoning.
    private const val MIN_QUERY_LENGTH = 3
    private const val MAX_RESULTS = 8

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Same empty-list-for-everything contract as
     * [NominatimSearchService.search]: too short, lookup failed, or genuinely
     * no match are all indistinguishable to the caller -- see that function's
     * own doc comment for why. [AddressSearchService] is what decides whether
     * to try this provider at all.
     */
    override suspend fun search(query: String): List<AddressSearchResult> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return@withContext emptyList()

        val url = "https://photon.komoot.io/api/" +
            "?q=${URLEncoder.encode(trimmed, "UTF-8")}" +
            "&limit=$MAX_RESULTS" +
            "&countrycode=ID"
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
                        Log.w(TAG, "Address search (fallback) failed: ${e.message}")
                        continuation.resume(emptyList())
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!it.isSuccessful) {
                                Log.w(TAG, "Address search (fallback) HTTP ${it.code}")
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
                                Log.w(TAG, "Address search (fallback): malformed JSON response")
                                emptyList()
                            }
                            continuation.resume(parsed)
                        }
                    }
                },
            )
        }
    }

    /**
     * Photon returns GeoJSON (`features[].geometry.coordinates` as
     * [lon, lat], `features[].properties.*`), not Nominatim's
     * `[{lat, lon, display_name}]` shape -- this is the one real parsing
     * difference between the two providers.
     */
    private fun parseResults(body: String): List<AddressSearchResult> {
        val features = JSONObject(body).optJSONArray("features") ?: JSONArray()
        val results = mutableListOf<AddressSearchResult>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val properties = feature.optJSONObject("properties") ?: continue
            val label = formatLabel(properties)
            if (label.isBlank()) continue
            results.add(AddressSearchResult(label = label, latitude = lat, longitude = lon))
        }
        return results
    }

    /**
     * Photon has no single "display_name" field the way Nominatim does --
     * builds an equivalent short label from whichever of name/street+
     * housenumber/city/state/country properties are actually present,
     * de-duplicated (a place's own `name` sometimes repeats its `city`).
     */
    private fun formatLabel(properties: JSONObject): String {
        val name = properties.optString("name", "").trim()
        val housenumber = properties.optString("housenumber", "").trim()
        val street = properties.optString("street", "").trim()
        val streetLine = when {
            street.isNotEmpty() && housenumber.isNotEmpty() -> "$street $housenumber"
            street.isNotEmpty() -> street
            else -> ""
        }
        val city = properties.optString("city", "").trim()
        val state = properties.optString("state", "").trim()
        val country = properties.optString("country", "").trim()
        return listOf(name, streetLine, city, state, country)
            .asSequence()
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
    }
}
