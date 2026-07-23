package com.trubus.tams.data.geocoding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM unit tests for [AddressSearchService]'s orchestration logic --
 * the fallback chain, the shared cache (including TTL expiry), and
 * exception handling -- using fake [ForwardGeocodingProvider]s instead of
 * the real Nominatim/Photon network endpoints.
 *
 * [AddressSearchService] is a singleton `object`, so its cache and
 * [AddressSearchService.providers]/[AddressSearchService.clockMillis] seams
 * persist for the whole test JVM process; every test resets them in
 * [setUp]/[tearDown] to avoid leaking state across test methods (in this
 * class or any other test class sharing the same process).
 */
class AddressSearchServiceTest {

    private var fakeNowMs = 1_000_000_000_000L

    private class FakeProvider(
        private val resultsProvider: () -> List<AddressSearchResult> = { emptyList() },
        private val throwable: Throwable? = null
    ) : ForwardGeocodingProvider {
        var callCount = 0
            private set

        override suspend fun search(query: String): List<AddressSearchResult> {
            callCount++
            throwable?.let { throw it }
            return resultsProvider()
        }
    }

    @Before
    fun setUp() {
        AddressSearchService.clearCacheForTest()
        fakeNowMs = 1_000_000_000_000L
        AddressSearchService.clockMillis = { fakeNowMs }
    }

    @After
    fun tearDown() {
        // Restore real behavior so this singleton never leaks fake state
        // into any other test that happens to run in the same process.
        AddressSearchService.providers = listOf(NominatimSearchService, PhotonSearchService)
        AddressSearchService.clockMillis = { System.currentTimeMillis() }
        AddressSearchService.clearCacheForTest()
    }

    @Test
    fun `search caches a successful result and does not call the provider again`() = runTest {
        val result = AddressSearchResult("Found Place", -6.2, 106.8)
        val provider = FakeProvider(resultsProvider = { listOf(result) })
        AddressSearchService.providers = listOf(provider)

        val first = AddressSearchService.search("Jakarta")
        val second = AddressSearchService.search("Jakarta")

        assertEquals(listOf(result), first)
        assertEquals(listOf(result), second)
        assertEquals(1, provider.callCount)
    }

    @Test
    fun `search re-queries after the cache entry expires past the TTL`() = runTest {
        val oldResult = AddressSearchResult("Old Result", -6.2, 106.8)
        val newResult = AddressSearchResult("New Result", -6.3, 106.9)
        var callCount = 0
        val provider = object : ForwardGeocodingProvider {
            override suspend fun search(query: String): List<AddressSearchResult> {
                callCount++
                return if (callCount == 1) listOf(oldResult) else listOf(newResult)
            }
        }
        AddressSearchService.providers = listOf(provider)

        val first = AddressSearchService.search("Bandung")
        assertEquals(listOf(oldResult), first)
        assertEquals(1, callCount)

        // Still within the 24h TTL -- cache hit, provider not called again.
        val stillCached = AddressSearchService.search("Bandung")
        assertEquals(listOf(oldResult), stillCached)
        assertEquals(1, callCount)

        // Advance the injected clock past the TTL.
        fakeNowMs += 24 * 60 * 60 * 1000L + 1

        val afterExpiry = AddressSearchService.search("Bandung")
        assertEquals(listOf(newResult), afterExpiry)
        assertEquals(2, callCount)
    }

    @Test
    fun `search does not expire a cache entry that is still within the TTL`() = runTest {
        val result = AddressSearchResult("Still Fresh", -6.2, 106.8)
        val provider = FakeProvider(resultsProvider = { listOf(result) })
        AddressSearchService.providers = listOf(provider)

        AddressSearchService.search("Surabaya")
        // Advance just short of the 24h TTL.
        fakeNowMs += 24 * 60 * 60 * 1000L - 1
        val stillCached = AddressSearchService.search("Surabaya")

        assertEquals(listOf(result), stillCached)
        assertEquals(1, provider.callCount)
    }

    @Test
    fun `search falls back to the second provider when the first finds nothing`() = runTest {
        val fallbackResult = AddressSearchResult("Fallback Place", -7.0, 110.0)
        val first = FakeProvider(resultsProvider = { emptyList() })
        val second = FakeProvider(resultsProvider = { listOf(fallbackResult) })
        AddressSearchService.providers = listOf(first, second)

        val results = AddressSearchService.search("Somewhere")

        assertEquals(listOf(fallbackResult), results)
        assertEquals(1, first.callCount)
        assertEquals(1, second.callCount)
    }

    @Test
    fun `search recovers when a provider throws and tries the next one`() = runTest {
        val fallbackResult = AddressSearchResult("Recovered", -7.0, 110.0)
        val throwing = FakeProvider(throwable = RuntimeException("boom"))
        val second = FakeProvider(resultsProvider = { listOf(fallbackResult) })
        AddressSearchService.providers = listOf(throwing, second)

        val results = AddressSearchService.search("Query")

        assertEquals(listOf(fallbackResult), results)
        assertEquals(1, second.callCount)
    }

    @Test
    fun `search propagates cancellation instead of trying the next provider`() = runTest {
        val cancelling = FakeProvider(throwable = CancellationException("cancelled"))
        val second = FakeProvider(resultsProvider = { listOf(AddressSearchResult("Should not be reached", 0.0, 0.0)) })
        AddressSearchService.providers = listOf(cancelling, second)

        var threw = false
        try {
            AddressSearchService.search("Query")
        } catch (e: CancellationException) {
            threw = true
        }

        assertTrue("expected search() to propagate CancellationException", threw)
        assertEquals(0, second.callCount)
    }

    @Test
    fun `search returns empty and caches nothing when every provider finds nothing`() = runTest {
        var callCount = 0
        val provider = object : ForwardGeocodingProvider {
            override suspend fun search(query: String): List<AddressSearchResult> {
                callCount++
                return emptyList()
            }
        }
        AddressSearchService.providers = listOf(provider)

        val first = AddressSearchService.search("Nowhere")
        assertTrue(first.isEmpty())
        assertEquals(1, callCount)

        // "Not found" must never be cached -- a repeated query must re-query
        // rather than silently keep returning empty forever.
        val second = AddressSearchService.search("Nowhere")
        assertTrue(second.isEmpty())
        assertEquals(2, callCount)
    }

    @Test
    fun `search treats queries as case-insensitive and trims whitespace for cache lookups`() = runTest {
        val result = AddressSearchResult("Found", -6.2, 106.8)
        val provider = FakeProvider(resultsProvider = { listOf(result) })
        AddressSearchService.providers = listOf(provider)

        AddressSearchService.search("Jakarta")
        val cached = AddressSearchService.search("  JAKARTA  ")

        assertEquals(listOf(result), cached)
        assertEquals(1, provider.callCount)
    }
}
