package com.trubus.tams.util

/**
 * Shared "is a GPS fix believable / is it overdue" rules. Used by both
 * [com.trubus.tams.service.MemberLocationService]'s own in-session staleness
 * watchdog (which re-subscribes the live LocationCallback when it goes
 * quiet, checked against an in-memory elapsedRealtime clock) and
 * [com.trubus.tams.worker.LocationSyncWorker]'s much coarser, cross-process
 * check (which can only see the last fix's persisted wall-clock timestamp,
 * not that Service instance's own in-memory clock) -- one formula, not two
 * copies that could silently drift apart.
 */
object TrackingHealth {
    // A kilometer of uncertainty is a broken/cold-start reading -- the
    // server enforces the same bound (backend/api.php's
    // GPS_MAX_USABLE_ACCURACY_M) as the real authority; this is just a cheap
    // client-side head start before a fix is even sent.
    const val MAX_USABLE_ACCURACY_M = 1000f

    // Floor for "how long since the last fix is too long", regardless of how
    // short the configured GPS interval is.
    private const val STALE_THRESHOLD_FLOOR_MS = 30_000L

    /**
     * 3x the currently configured GPS Update Interval, never below
     * [STALE_THRESHOLD_FLOOR_MS]. An interval below the floor would make
     * this trigger on completely normal jitter; a slower Administrator-
     * configured interval must still be given proportionally more slack
     * before being treated as stalled.
     */
    fun staleThresholdMillis(gpsIntervalSeconds: Int): Long =
        (gpsIntervalSeconds * 3_000L).coerceAtLeast(STALE_THRESHOLD_FLOOR_MS)

    /**
     * Milliseconds between [fixTime] ("yyyy-MM-dd HH:mm:ss", WIB -- the same
     * format every recorded_at/lastKnownLocation timestamp in this app uses,
     * see [WibTime]) and now, or null if [fixTime] can't be parsed. Never
     * throws -- an unparseable/legacy value fails safe (treated by callers as
     * "unknown age", not a crash).
     *
     * Necessarily compares against [System.currentTimeMillis] rather than
     * [android.os.SystemClock.elapsedRealtime] -- unlike
     * [com.trubus.tams.data.repository.MemberRepository.serviceHeartbeatAt]
     * (an arbitrary liveness marker with no inherent wall-clock meaning),
     * [fixTime] IS a wall-clock quantity by construction: it's the same
     * `recorded_at` string sent to and stored by the server, compared
     * against other members' `recorded_at` values there, and shown to
     * Admins in Trip History -- there is no elapsedRealtime equivalent to
     * anchor it to. This does reopen the class of problem the heartbeat
     * rework closed elsewhere: a device whose wall clock moves between when
     * [fixTime] was captured and now (manual date/time change, or an NTP
     * correction -- common right after boot on a device with a dead/weak
     * RTC battery, which this project's low-end-device user base skews
     * toward) throws the raw delta off. Guarded against here for the one
     * direction that actually matters:
     *
     * - A clock moving BACKWARD makes the delta land at or below zero (a fix
     *   can never legitimately be dated in the future relative to "now").
     *   Trusting that value would silently understate -- or invert the sign
     *   of -- how stale the fix actually is, which is exactly the false
     *   negative [com.trubus.tams.worker.LocationSyncWorker.doWork]'s own
     *   `isStale` check must never see: a wrongly-small/negative age reads
     *   as "still fresh" and skips the one-shot stale-fix recovery attempt
     *   precisely when it's needed most. Returning null here instead means
     *   every current caller sees exactly what it already does for "no fix
     *   captured yet" -- null already fails toward attempting a recovery
     *   fix, never toward silently trusting a clock reading that just
     *   proved itself unreliable.
     * - A clock moving FORWARD makes the delta read implausibly LARGE
     *   instead. Deliberately NOT special-cased the same way: an oversized
     *   age can only ever push a caller toward the MORE conservative
     *   "treat as stale, attempt a recovery fix" outcome -- the same
     *   direction a genuinely old (but real) last fix already takes it. There
     *   is no false-negative risk on this side to guard against, only a
     *   possible unnecessary extra recovery fix, so no correction is applied.
     */
    fun elapsedMillisSince(fixTime: String): Long? {
        val fixMillis = parseFixMillis(fixTime) ?: return null
        val elapsed = System.currentTimeMillis() - fixMillis
        return if (elapsed < 0) null else elapsed
    }

    /**
     * Parses a `recorded_at`-shaped timestamp ("yyyy-MM-dd HH:mm:ss", WIB --
     * see [elapsedMillisSince]'s own doc comment) to epoch millis, or null if
     * it can't be parsed. Extracted out of [elapsedMillisSince] so any other
     * caller needing the raw parsed instant -- not an elapsed duration --
     * shares this one parsing implementation instead of keeping its own
     * try/catch around the same formatter (e.g.
     * [com.trubus.tams.data.repository.MemberRepository.postLocation]'s
     * newer-fix-wins guard on [com.trubus.tams.data.repository.MemberRepository.lastKnownLocation]).
     */
    fun parseFixMillis(fixTime: String): Long? = try {
        WibTime.formatter("yyyy-MM-dd HH:mm:ss").parse(fixTime)?.time
    } catch (_: Exception) {
        null
    }
}
