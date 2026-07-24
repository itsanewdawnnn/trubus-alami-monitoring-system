package com.trubus.tams.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.trubus.tams.data.api.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Remote Management settings (GPS Update Interval, Sync Interval,
 * password length bounds, and any future safe-to-externalize setting) from
 * the server -- backed by web/database/schema.sql's tams_remote_management
 * table, managed through the Admin Panel's "Remote Management" page
 * (web/pages/remote_management.php). Lets an Administrator change these values
 * for every installed copy of the app without a new APK build. Also the
 * replacement for what used to be a hardcoded PASSWORD_MIN_LENGTH/
 * PASSWORD_MAX_LENGTH pair mirrored by hand from the now-removed
 * web/database/validation_rules.php.
 *
 * Cache-first, not fetch-on-every-read: [gpsIntervalSeconds],
 * [syncIntervalMinutes], [passwordMinLength], and [passwordMaxLength] are
 * synchronous SharedPreferences reads, safe to call from a hot path
 * (starting GPS tracking, scheduling the next sync pass, validating a
 * password as the user types) since they never touch the network.
 * [refreshIfStale] is the only method that ever does, and only actually
 * fetches at most once per [STALE_THRESHOLD_MS] -- callers
 * ([com.trubus.tams.service.MemberLocationService],
 * [com.trubus.tams.worker.LocationSyncWorker],
 * [com.trubus.tams.ui.viewmodel.MainViewModel]) call it opportunistically on
 * their own existing passes rather than this class polling on a timer of
 * its own, so most calls are a single cheap SharedPreferences read with no
 * network involved at all.
 *
 * Fails safe in every direction: a never-fetched cache, a stale cache the
 * network fetch failed to refresh, or a malformed/out-of-range server value
 * are all treated the same way -- fall back to this class's own `DEFAULT_*`
 * constants (which must stay numerically equal to database/schema.sql's
 * seed rows and helpers/functions.php's remote_management_definitions() for the
 * same keys). App behavior only ever changes on a successful, in-range
 * fetch, never on a failed or malformed one.
 */
class RemoteConfigRepository(
    context: Context,
    private val baseUrlProvider: () -> String,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("remote_config_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "RemoteConfigRepository"

        private const val KEY_GPS_INTERVAL_SECONDS = "gps_interval_seconds"
        private const val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
        private const val KEY_PASSWORD_MIN_LENGTH = "password_min_length"
        private const val KEY_PASSWORD_MAX_LENGTH = "password_max_length"
        private const val KEY_LAST_FETCH_TIME = "last_fetch_time_ms"

        const val DEFAULT_GPS_INTERVAL_SECONDS = 10
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 3L

        // Formerly hardcoded PASSWORD_MIN_LENGTH/PASSWORD_MAX_LENGTH constants
        // here, manually mirroring web/database/validation_rules.php (now
        // removed). Both sides now read the same tams_remote_management table
        // instead -- this is just the fail-safe fallback, matching
        // helpers/functions.php's remote_management_definitions() defaults.
        const val DEFAULT_PASSWORD_MIN_LENGTH = 4
        const val DEFAULT_PASSWORD_MAX_LENGTH = 255

        // How often refreshIfStale() is actually allowed to hit the network.
        // Callers invoke it far more often than this (LocationSyncWorker's
        // watchdog runs every ~3 minutes) purely opportunistically -- the
        // vast majority of calls return immediately without a network round trip.
        private const val STALE_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes

        // Mirrors helpers/functions.php's remote_management_definitions() bounds.
        // A malformed or tampered response is clamped into this range rather
        // than applied as-is, so a bad value can never make GPS tracking
        // effectively unusable (e.g. a 0-second interval or an hours-long
        // sync gap) even if it somehow bypassed the Admin Panel's own
        // server-side validation.
        private const val MIN_GPS_INTERVAL_SECONDS = 5
        private const val MAX_GPS_INTERVAL_SECONDS = 300
        private const val MIN_SYNC_INTERVAL_MINUTES = 1L
        private const val MAX_SYNC_INTERVAL_MINUTES = 60L
        private const val MIN_PASSWORD_MIN_LENGTH = 4
        private const val MAX_PASSWORD_MIN_LENGTH = 50
        private const val MIN_PASSWORD_MAX_LENGTH = 8
        private const val MAX_PASSWORD_MAX_LENGTH = 255
    }

    /** Cached GPS Update Interval, in seconds. Synchronous, network-free. */
    val gpsIntervalSeconds: Int
        get() = prefs.getInt(KEY_GPS_INTERVAL_SECONDS, DEFAULT_GPS_INTERVAL_SECONDS)
            .coerceIn(MIN_GPS_INTERVAL_SECONDS, MAX_GPS_INTERVAL_SECONDS)

    /** Cached Sync Interval, in minutes. Synchronous, network-free. */
    val syncIntervalMinutes: Long
        get() = prefs.getLong(KEY_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
            .coerceIn(MIN_SYNC_INTERVAL_MINUTES, MAX_SYNC_INTERVAL_MINUTES)

    /** Cached minimum password length. Synchronous, network-free. */
    val passwordMinLength: Int
        get() = prefs.getInt(KEY_PASSWORD_MIN_LENGTH, DEFAULT_PASSWORD_MIN_LENGTH)
            .coerceIn(MIN_PASSWORD_MIN_LENGTH, MAX_PASSWORD_MIN_LENGTH)

    /** Cached maximum password length. Synchronous, network-free. */
    val passwordMaxLength: Int
        get() = prefs.getInt(KEY_PASSWORD_MAX_LENGTH, DEFAULT_PASSWORD_MAX_LENGTH)
            .coerceIn(MIN_PASSWORD_MAX_LENGTH, MAX_PASSWORD_MAX_LENGTH)

    /**
     * Refreshes the cache from GET /app/config if it's older than
     * [STALE_THRESHOLD_MS] (or has never successfully been fetched). Safe to
     * call on every pass of a frequent background job -- most calls return
     * immediately without touching the network. Never throws; any failure
     * (network, parse, missing/malformed keys) silently leaves the existing
     * cache (or defaults, if nothing has ever been cached) in place.
     */
    suspend fun refreshIfStale() = withContext(Dispatchers.IO) {
        val lastFetch = prefs.getLong(KEY_LAST_FETCH_TIME, 0L)
        val now = System.currentTimeMillis()
        if (lastFetch != 0L && ((now - lastFetch) < STALE_THRESHOLD_MS)) {
            return@withContext
        }

        try {
            // Built fresh per call, same rationale as UpdateRepository's own
            // ApiService.create() call -- this runs at most once every 30
            // minutes, so the minor cost of constructing a new OkHttpClient
            // here isn't worth extra rebuild-on-baseUrl-change plumbing.
            // Token provider returns null: /app/config is deliberately
            // unauthenticated (see ApiService.getAppConfig's doc comment).
            val service = ApiService.create(baseUrlProvider()) { null }
            val response = service.getAppConfig()
            val body = response.body()
            if (response.isSuccessful && (body?.success == true) && (body.data != null)) {
                val config = body.data
                prefs.edit {
                    config[KEY_GPS_INTERVAL_SECONDS]?.toIntOrNull()?.let {
                        putInt(KEY_GPS_INTERVAL_SECONDS, it)
                    }
                    config[KEY_SYNC_INTERVAL_MINUTES]?.toLongOrNull()?.let {
                        putLong(KEY_SYNC_INTERVAL_MINUTES, it)
                    }
                    config[KEY_PASSWORD_MIN_LENGTH]?.toIntOrNull()?.let {
                        putInt(KEY_PASSWORD_MIN_LENGTH, it)
                    }
                    config[KEY_PASSWORD_MAX_LENGTH]?.toIntOrNull()?.let {
                        putInt(KEY_PASSWORD_MAX_LENGTH, it)
                    }
                    putLong(KEY_LAST_FETCH_TIME, now)
                }
                Log.d(TAG, "Remote config refreshed successfully.")
            } else {
                Log.w(TAG, "Remote config fetch returned failure; keeping existing cache.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Remote config fetch failed; keeping existing cache: ${e.message}")
        }
    }
}
