package com.trubus.tams.data.geocoding

/**
 * Common contract for a forward-geocoding source: turns a typed address
 * query into a short list of candidate places. [NominatimSearchService] and
 * [PhotonSearchService] each implement this independently -- neither knows
 * about the other -- and [AddressSearchService] is the only class that
 * orchestrates them together. Adding a future third free provider is
 * "implement this interface in one new object, add it to
 * AddressSearchService's provider list" -- nothing else in the app (the
 * Search Address UI included) needs to change.
 */
interface ForwardGeocodingProvider {
    suspend fun search(query: String): List<AddressSearchResult>
}
