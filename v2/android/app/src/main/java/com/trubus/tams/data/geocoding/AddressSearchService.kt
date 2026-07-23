package com.trubus.tams.data.geocoding

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates forward-geocoding across free providers, in priority order,
 * so the "Search Address" field (ui/screens/OutletScreen.kt's
 * OutletFormScreen) has exactly one suspend call to make -- it doesn't know
 * or care how many providers exist, what order they run in, or how results
 * are cached. This is the only class with that knowledge; adding a future
 * third free provider is "implement [ForwardGeocodingProvider] in one new
 * object, add it to [providers] below" -- nothing else in the app changes.
 *
 * [NominatimSearchService] is tried first -- same provider, same behavior,
 * same ~1 req/s rate-limit profile this app already had.
 * [PhotonSearchService] only runs at all when Nominatim comes back with zero
 * results, so on the common "Nominatim already found it" path this is
 * exactly as fast and exactly as network-light as before this class
 * existed. A deliberately free-only, two-provider chain -- no paid API
 * (Google Places, Geoapify, etc.) was considered as an alternative and
 * rejected: both providers here are community-run/free-forever with no key
 * to manage, matching this project's existing geocoding stack, and Photon
 * specifically closes the gap Nominatim alone has (typo/phrasing
 * sensitivity) without introducing a second data source's licensing/terms
 * to track. Neither provider can find an address that was never mapped in
 * OpenStreetMap at all, since both are built on that same dataset -- that
 * class of miss has no free fix and is a known, accepted limitation.
 *
 * The cache lives here, shared across both providers, rather than one
 * separate cache per provider (the original shape). A per-provider cache
 * cannot record a "not found by Nominatim, found by Photon" outcome against
 * the query itself: repeating that same query would still re-hit
 * Nominatim's network first (its own cache never held this query, since
 * Nominatim never found anything for it) before ever reaching Photon's
 * cache -- one avoidable network call every single repeat, indefinitely.
 * A single cache keyed by the query, storing whichever provider's result
 * ultimately answered it, is checked before EITHER provider runs, so a
 * repeated query -- regardless of which provider originally resolved it --
 * costs zero network calls the second time. Only non-empty results are
 * cached (matching each provider's own prior behavior): caching "not found"
 * would risk permanently hiding a transient failure or a since-added OSM
 * entry behind one bad first attempt, which was never requested here and
 * is deliberately not done.
 *
 * A provider is not expected to throw (both existing implementations already
 * catch their own network/parsing failures internally and return an empty
 * list -- see their own doc comments for why a supplementary search
 * affordance should never surface a raw error), but is caught here too as a
 * last line of defense so one misbehaving provider can never break the
 * fallback chain for the caller.
 *
 * Entries expire after [CACHE_TTL_MS] (24h), checked lazily -- only when
 * that exact key is looked up again, never by a background sweep/scheduler.
 * Without this, an entry could otherwise sit valid indefinitely (bounded
 * only by [MAX_CACHE_ENTRIES]'s occasional full-clear, which is about entry
 * *count*, not entry *age*) across a process that stays alive for many
 * hours -- this app's own tracking foreground service is exactly the kind
 * of long-lived process where that could happen.
 */
object AddressSearchService : ForwardGeocodingProvider {

    private const val TAG = "AddressSearchService"

    // `internal var`, not `private val`: test-only seam so
    // AddressSearchServiceTest can substitute fake providers instead of
    // hitting real Nominatim/Photon network endpoints. Production behavior
    // is unchanged -- always this same two-provider list unless test code
    // (same Gradle module, `internal` visibility) reassigns it.
    internal var providers: List<ForwardGeocodingProvider> = listOf(
        NominatimSearchService,
        PhotonSearchService
    )

    // Full clear on overflow, not real LRU -- same tradeoff every geocoding
    // cache in this app already makes (occasionally losing entries just
    // costs one extra network call, never correctness).
    private const val MAX_CACHE_ENTRIES = 100

    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    // Wall-clock timestamp of when each entry was cached, alongside its
    // results -- a private wrapper, not a change to AddressSearchResult
    // itself, since nothing outside this class needs to know an entry's age.
    private data class CacheEntry(val results: List<AddressSearchResult>, val cachedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // `internal var`, not a direct `System.currentTimeMillis()` call: test-only
    // seam so AddressSearchServiceTest can simulate TTL expiry (24h) without
    // either sleeping in a test or reaching into private state via
    // reflection. Production behavior is unchanged -- always the real wall
    // clock unless test code overrides it.
    internal var clockMillis: () -> Long = { System.currentTimeMillis() }

    // Test-only seam: AddressSearchServiceTest needs to reset this
    // singleton's cache between test methods (this being an `object`, its
    // cache would otherwise persist for the whole test JVM process,
    // leaking state across unrelated test methods). Exposed as a function
    // returning Unit, not the cache field itself, since [CacheEntry] is
    // private and Kotlin doesn't allow a wider-than-private member to
    // expose it directly.
    internal fun clearCacheForTest() {
        cache.clear()
    }

    override suspend fun search(query: String): List<AddressSearchResult> {
        val key = query.trim().lowercase(Locale.US)
        cache[key]?.let { entry ->
            if (clockMillis() - entry.cachedAtMs < CACHE_TTL_MS) {
                return entry.results
            }
            // Expired -- evict now rather than serve a stale answer. The
            // 2-arg remove only evicts if this exact (still-expired) entry
            // is still the one mapped to this key, so a fresh entry written
            // concurrently by another call in the moment between this read
            // and this remove is never accidentally evicted.
            cache.remove(key, entry)
        }

        for (provider in providers) {
            val results = try {
                provider.search(query)
            } catch (e: CancellationException) {
                // Propagates immediately -- the loop deliberately does NOT
                // move on to the next provider on cancellation (e.g. the
                // Member kept typing and Compose cancelled this debounced
                // search): a cancelled attempt means "abandoned", not "try
                // the fallback", so this must exit here, not continue.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${provider::class.simpleName} threw unexpectedly: ${e.message}")
                emptyList()
            }
            if (results.isNotEmpty()) {
                if (cache.size >= MAX_CACHE_ENTRIES) {
                    cache.clear()
                }
                cache[key] = CacheEntry(results, clockMillis())
                return results
            }
        }
        return emptyList()
    }
}
