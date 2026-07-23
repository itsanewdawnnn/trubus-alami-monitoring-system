package com.trubus.tams.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Pure-JVM unit tests -- [TrackingHealth] has no Android framework
 * dependency, so no Robolectric/instrumentation is needed here.
 *
 * Covers the two behaviors this class exists to guarantee as a single
 * source of truth: the stale-threshold formula (with its floor), and
 * [TrackingHealth.elapsedMillisSince]'s clock-rollback guard (never
 * negative, never a false "still fresh" reading after the device's wall
 * clock jumps backward).
 */
class TrackingHealthTest {

    private val pattern = "yyyy-MM-dd HH:mm:ss"

    private fun format(epochMillis: Long): String =
        WibTime.formatter(pattern).format(Date(epochMillis))

    // --- staleThresholdMillis ---

    @Test
    fun `staleThresholdMillis scales with interval above the floor`() {
        // 15s * 4 = 60_000ms, comfortably above the 40_000ms floor.
        assertEquals(60_000L, TrackingHealth.staleThresholdMillis(15))
    }

    @Test
    fun `staleThresholdMillis applies the floor for a short interval`() {
        // 5s * 4 = 20_000ms, below the 40_000ms floor -- a short configured
        // GPS interval must never make this trigger on ordinary jitter.
        assertEquals(40_000L, TrackingHealth.staleThresholdMillis(5))
    }

    @Test
    fun `staleThresholdMillis at exactly the floor boundary`() {
        // 10s * 4 = 40_000ms, exactly equal to the floor.
        assertEquals(40_000L, TrackingHealth.staleThresholdMillis(10))
    }

    // --- parseFixMillis ---

    @Test
    fun `parseFixMillis parses a WIB timestamp to the correct epoch millis`() {
        // 07:00:00 WIB (UTC+7) on the epoch date is exactly UTC midnight --
        // a hard, non-circular assertion that both the WIB offset and the
        // parsing itself are correct, not just "does it call WibTime".
        assertEquals(0L, TrackingHealth.parseFixMillis("1970-01-01 07:00:00"))
    }

    @Test
    fun `parseFixMillis returns null for an unparseable string`() {
        assertNull(TrackingHealth.parseFixMillis("not-a-date"))
    }

    @Test
    fun `parseFixMillis returns null for an empty string`() {
        assertNull(TrackingHealth.parseFixMillis(""))
    }

    // --- elapsedMillisSince ---

    @Test
    fun `elapsedMillisSince returns a small non-negative age for a fix taken just now`() {
        val fixTime = format(System.currentTimeMillis())
        val age = TrackingHealth.elapsedMillisSince(fixTime)
        assertTrue("expected a non-null age, got null", age != null)
        // Formatting truncates to whole seconds, so up to ~1000ms of
        // apparent age is expected even for a fix taken this instant --
        // never negative, per the class's own clock-rollback guard.
        assertTrue("expected age in [0, 2000), got $age", age!! in 0 until 2000)
    }

    @Test
    fun `elapsedMillisSince returns null when the fix is dated in the future (clock rollback)`() {
        // Simulates the device's wall clock having moved backward since this
        // fix was recorded: relative to "now", the fix appears to be from
        // 10 minutes in the future. Must never read as a negative age.
        val futureFixTime = format(System.currentTimeMillis() + 10 * 60 * 1000L)
        assertNull(TrackingHealth.elapsedMillisSince(futureFixTime))
    }

    @Test
    fun `elapsedMillisSince returns a large positive age for a genuinely old fix (no forward-jump correction)`() {
        val twoHoursMs = 2 * 60 * 60 * 1000L
        val oldFixTime = format(System.currentTimeMillis() - twoHoursMs)
        val age = TrackingHealth.elapsedMillisSince(oldFixTime)
        assertTrue("expected a non-null age, got null", age != null)
        // Deliberately not clamped/corrected -- only bounded by a generous
        // tolerance for test execution time and second-level truncation.
        assertTrue("expected age close to ${twoHoursMs}ms, got $age", age!! in (twoHoursMs - 2000)..(twoHoursMs + 2000))
    }

    @Test
    fun `elapsedMillisSince returns null for an unparseable fix`() {
        assertNull(TrackingHealth.elapsedMillisSince("garbage"))
    }
}
