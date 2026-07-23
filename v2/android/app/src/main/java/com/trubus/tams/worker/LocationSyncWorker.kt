package com.trubus.tams.worker

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trubus.tams.data.repository.MemberRepository
import com.trubus.tams.data.repository.RemoteConfigRepository
import com.trubus.tams.data.repository.SessionInvalidException
import com.trubus.tams.data.repository.TrackingNotAllowedException
import com.trubus.tams.service.MemberLocationService
import com.trubus.tams.util.OneShotLocationProvider
import com.trubus.tams.util.TrackingHealth
import com.trubus.tams.util.WibTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Watchdog + offline-queue-flush job for member tracking. Two independent
 * jobs each run:
 *
 * 1. Flushes the local offline-location queue (original purpose).
 * 2. Watchdog for the tracking foreground service: some OEM Android builds
 *    (MIUI, ColorOS, FuntouchOS, EMUI) kill foreground services outright
 *    even when battery-optimization-whitelisted, ignoring stock Android's
 *    Doze/App-Standby exemptions. If the tracking flag says it should be on
 *    but the service isn't running, this restarts it (idempotent if already
 *    alive). Note this never fights Force Location's auto-stop behavior: by
 *    the time MemberLocationService stops itself for a rejected fix (see
 *    that class's handleNewLocation() doc comment), it has already flipped
 *    [MemberRepository.isTrackingEnabled] to false, so this watchdog simply
 *    sees nothing to restart on its next pass.
 *
 * Safety net, not the primary mechanism -- the foreground service itself
 * (plus [MemberLocationService.onTaskRemoved]'s fast alarm restart) keeps
 * coordinates flowing; this worker recovers the slower case where the
 * service was killed while still in Recents (onTaskRemoved never fired).
 *
 * SELF-RESCHEDULING CHAIN, NOT A PeriodicWorkRequest: WorkManager's
 * `PeriodicWorkRequest` has a hard-coded 15-minute floor, far too slow here
 * -- a service killed at minute 2 could go unrestarted for 15 minutes,
 * silently losing GPS fixes (not just delaying them). A `OneTimeWorkRequest`
 * has no such floor, so this worker re-enqueues ITSELF with a delay at the
 * end of every run, behaving like a periodic job -- by default every 3
 * minutes (a 5x tighter recovery window than PeriodicWorkRequest's floor,
 * no extra permissions required), adjustable by an Administrator via Remote
 * Management (see [RemoteConfigRepository.syncIntervalMinutes] and
 * [rescheduleNextPass]) without shipping a new APK. The chain survives
 * process death and reboot because WorkManager persists enqueued work to its
 * own on-disk database.
 *
 * Deliberately a SINGLE fixed interval regardless of whether Start Location
 * is currently on or off -- an adaptive (slower-while-idle) cadence was
 * evaluated and reverted after an architecture review concluded the request
 * volume this poll produces (well under 1 request/second on average across
 * 100 Members) was never an actual server-side bottleneck at this project's
 * scale, so the extra branching/untested code path wasn't worth carrying
 * long-term. An Administrator who genuinely wants a slower background
 * cadence can already raise Sync Interval via Remote Management -- no code
 * change needed for that either way.
 *
 * This same pass is also the opportunistic point where Remote Management's
 * cache gets refreshed (see [RemoteConfigRepository.refreshIfStale]) -- no
 * separate polling loop exists just for that.
 */
class LocationSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "location_watchdog_and_sync_work"

        // Bounds the one-shot stale-fix attempt in doWork() below so a slow
        // or never-resolving GPS request can't hold this worker open
        // indefinitely -- long enough for a real (if slow) fix, short enough
        // to still leave comfortable headroom before WorkManager's own
        // execution ceiling.
        private const val ONE_SHOT_FIX_TIMEOUT_MS = 20_000L

        // How old MemberRepository.serviceHeartbeatAt may be before this
        // worker stops treating the service as "still alive" and falls back
        // to its own one-shot fix. Deliberately the same order of magnitude
        // as this project's other "is this still live" cutoffs
        // (backend/api.php's OFFLINE_STALE_SECONDS, MainAppScreen's
        // MEMBER_LOCATION_STALE_THRESHOLD_MS) rather than a fresh, arbitrary
        // number -- comfortably above the service's own ~30s heartbeat tick
        // (see startStalenessWatchdog()) so ordinary scheduling jitter is
        // never mistaken for the service being down.
        private const val SERVICE_HEARTBEAT_FRESHNESS_MS = 90_000L
    }

    override suspend fun doWork(): Result {
        Log.d("LocationSyncWorker", "Watchdog/sync pass triggered.")
        val repository = MemberRepository(applicationContext)
        val remoteConfigRepository = RemoteConfigRepository(applicationContext) { repository.baseUrl }

        // This worker's own ~3-minute cadence is the opportunistic refresh
        // point documented on RemoteConfigRepository -- most passes are a
        // no-op (staleness gate), so this adds no real network overhead to
        // the watchdog's existing frequency.
        try {
            remoteConfigRepository.refreshIfStale()
        } catch (e: Exception) {
            Log.w("LocationSyncWorker", "Remote config refresh failed this pass: ${e.message}")
        }

        if (repository.isTrackingEnabled) {
            // Checked BEFORE touching anything below -- this is what tells
            // apart a genuine fallback pass (service not alive recently)
            // from the service simply being alive and mid-GPS-acquisition
            // (see the `if (wasServiceAlreadyRunning)` branch further down).
            // Reading it only AFTER startForegroundService() below would
            // muddy that signal for no benefit, so it's read first.
            val wasServiceAlreadyRunning = isServiceLikelyAlive(repository)

            Log.d("LocationSyncWorker", "Tracking should be active; re-asserting foreground service state.")
            val intent = Intent(applicationContext, MemberLocationService::class.java).apply {
                action = MemberLocationService.ACTION_START
            }
            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                Log.e("LocationSyncWorker", "Failed to re-assert tracking service: ${e.message}")
            }

            if (wasServiceAlreadyRunning) {
                // The foreground service was already alive coming into this
                // pass -- whatever is keeping `lastKnownLocation` from
                // updating (mid-acquisition right after a (re)start, a slow
                // fix, momentarily poor signal) is exactly what
                // MemberLocationService's OWN in-session staleness watchdog
                // already exists to detect and recover from (see that
                // class's startStalenessWatchdog()). This worker is a
                // fallback for when the service isn't running at all, never
                // a second, independent location-getter racing an
                // already-live subscription -- so no one-shot fix is
                // attempted in this branch, regardless of staleness.
                Log.d("LocationSyncWorker", "Foreground service already running; skipping one-shot stale-fix (not a fallback scenario).")
            } else {
                // Stale-fix stopgap: the service was confirmed NOT running
                // coming into this pass -- the re-assert call just above
                // only helps once its own restart actually completes,
                // however long that takes, plus a fresh GPS acquisition
                // afterward. If the last fix this device actually captured
                // is already older than [TrackingHealth]'s staleness rule
                // allows, take one single-fix reading right now via
                // [OneShotLocationProvider] (the same helper OutletScreen's
                // "Use Current Location" button uses) and send it through
                // the exact same postLocation() every other fix uses -- no
                // new upload path, no new server contract.
                //
                // In the common, healthy case (service alive, this whole
                // branch is skipped above) this staleness check is never
                // even reached, so the added battery cost stays proportional
                // to how broken things already are, not a fixed per-pass cost.
                val staleThresholdMs = TrackingHealth.staleThresholdMillis(remoteConfigRepository.gpsIntervalSeconds)
                val lastFixAgeMs = repository.lastKnownLocation?.time?.let { TrackingHealth.elapsedMillisSince(it) }
                // Null covers "no fix has ever been captured yet this
                // session", "the persisted timestamp couldn't be parsed", AND
                // "the device's wall clock moved backward since that fix was
                // captured" (see TrackingHealth.elapsedMillisSince's own doc
                // comment for that last case) -- all three fail toward "try
                // now" rather than silently doing nothing or trusting a
                // clock reading that just proved itself unreliable.
                val isStale = lastFixAgeMs == null || lastFixAgeMs > staleThresholdMs
                if (isStale) {
                    Log.w("LocationSyncWorker", "Service was not running and last known fix is stale (age=${lastFixAgeMs}ms, threshold=${staleThresholdMs}ms); attempting one-shot location fix.")
                    try {
                        val fix = withTimeoutOrNull(ONE_SHOT_FIX_TIMEOUT_MS) {
                            OneShotLocationProvider.getCurrentLocation(applicationContext)
                        }
                        if (fix == null) {
                            Log.w("LocationSyncWorker", "One-shot stale-fix attempt produced no location (permission missing, no signal, or timed out).")
                        } else if (fix.accuracy > TrackingHealth.MAX_USABLE_ACCURACY_M) {
                            // Same client-side sanity discard
                            // MemberLocationService.handleNewLocation applies
                            // -- the server enforces the identical bound
                            // anyway, so this only saves a doomed network
                            // round trip.
                            Log.d("LocationSyncWorker", "One-shot stale-fix discarded: accuracy ${fix.accuracy}m exceeds usable bound.")
                        } else {
                            val recordedAt = WibTime.formatter("yyyy-MM-dd HH:mm:ss").format(Date(fix.time))
                            // gnssSatellitesUsed is deliberately null here -- this is a
                            // single one-shot fix with no live GnssStatus.Callback
                            // subscription behind it (see MemberLocationService's own
                            // continuous-tracking path for where that signal comes
                            // from), so there is nothing genuine to report.
                            val result = repository.postLocation(
                                fix.latitude, fix.longitude, fix.accuracy, fix.speed, recordedAt,
                                isMock = LocationCompat.isMock(fix),
                                gnssSatellitesUsed = null
                            )
                            val rejection = result.exceptionOrNull()
                            when {
                                rejection is SessionInvalidException -> {
                                    // Same contract every other postLocation()
                                    // caller honors (see
                                    // MemberLocationService.handleNewLocation
                                    // and this function's own
                                    // getLocationStatus() check below): a
                                    // server-confirmed dead/revoked token is
                                    // unrecoverable, so tracking must stop
                                    // and the local session must be torn
                                    // down -- regardless of which call site
                                    // happens to be the one that discovers it.
                                    Log.e("LocationSyncWorker", "Stale-fix stopgap found session invalid; stopping tracking.")
                                    repository.isTrackingEnabled = false
                                    repository.logout(notifyServer = false)
                                }
                                rejection is TrackingNotAllowedException -> {
                                    // Same Force Location auto-revoke
                                    // contract MemberLocationService.
                                    // handleNewLocation implements -- session
                                    // stays valid, only tracking itself stops.
                                    Log.w("LocationSyncWorker", "Stale-fix stopgap found tracking not allowed right now; stopping tracking.")
                                    repository.isTrackingEnabled = false
                                }
                                result.isSuccess -> {
                                    Log.d("LocationSyncWorker", "Stale-fix stopgap sent one location update.")
                                }
                                else -> {
                                    Log.w("LocationSyncWorker", "Stale-fix stopgap upload failed (left queued for the offline-sync pass below): ${rejection?.message}")
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("LocationSyncWorker", "One-shot stale-fix attempt failed: ${e.message}")
                    }
                }
            }
        }

        // Session-death detection, independent of whether Start Location is
        // active: GET /location/status also doubles as this pass's only
        // network probe when the offline queue is empty and nothing else in
        // a given pass would otherwise touch the network -- without it, a
        // session that died purely while the app is backgrounded (never
        // reopened) could go undetected indefinitely.
        if (repository.currentUser?.role == "member") {
            val statusResult = repository.getLocationStatus()
            if (statusResult.exceptionOrNull() is SessionInvalidException) {
                // Server-confirmed dead/revoked token, detected here
                // independently of whether any UI is alive to react.
                // MainViewModel.handleSessionInvalidatedByService() (the
                // foreground path) only runs while MemberDashboard's
                // broadcast receiver is composed -- a session that dies
                // purely while the app is backgrounded (never reopened)
                // left currentUser/authToken never cleared, so the check
                // below (`repository.currentUser?.role == "member"`) kept
                // reading true forever and this chain rescheduled itself
                // every syncIntervalMinutes indefinitely for a pass that
                // could never succeed again -- waking the device for
                // nothing. Clearing the local session right here (same
                // notifyServer = false as MainViewModel's own handling,
                // since the server already rejected this token) makes that
                // same check correctly see no member logged in a few lines
                // down, so the chain ends itself this pass instead of
                // rescheduling.
                Log.w("LocationSyncWorker", "Session no longer valid; clearing local session so the watchdog chain stops.")
                repository.logout(notifyServer = false)
            }
        }

        val result = repository.syncOfflineLocations()

        // Only reschedule while a member is actually logged in. MainViewModel
        // now only enqueues this chain's first pass for a "member" role in
        // the first place (see its init{}/login() doc comments), but this
        // check stays as defense in depth: it's what actually stops the
        // chain from persisting once already scheduled -- e.g. a Member
        // logging out between one pass and the next (logout() also cancels
        // the chain directly, but that's a separate belt-and-suspenders
        // path, not a substitute for this check). Without this, a chain that
        // was legitimately enqueued for a Member would keep rescheduling
        // itself forever even after logout/role change, waking the device
        // every ~3 minutes for a pass that does nothing. Rescheduling
        // regardless of *outcome* (success/failure) is still correct and
        // preserved below; this only adds a check for whether the chain
        // should keep existing at all.
        if (repository.currentUser?.role == "member") {
            rescheduleNextPass(remoteConfigRepository)
        } else {
            Log.d("LocationSyncWorker", "No member logged in; letting the watchdog chain end here.")
        }

        return if (result.isSuccess) {
            val syncedCount = result.getOrDefault(0)
            Log.d("LocationSyncWorker", "Successfully synced $syncedCount locations from offline queue.")
            Result.success()
        } else {
            Log.e("LocationSyncWorker", "Failed to sync offline locations this pass; next watchdog pass already scheduled.")
            Result.success()
        }
    }

    // True only if MemberLocationService has recorded a sign of life
    // ([MemberRepository.serviceHeartbeatAt]) within the last
    // [SERVICE_HEARTBEAT_FRESHNESS_MS]. Deliberately NOT
    // `ActivityManager.getRunningServices()` -- that API is deprecated for
    // third-party introspection since API 26, and even the "still works for
    // your own app" carve-out means trusting the OS/OEM's own service
    // bookkeeping, exactly the kind of dependency this whole feature exists
    // to avoid (this project already treats several OEM ROMs' process/
    // service handling as unreliable elsewhere -- see
    // MemberLocationService.onTaskRemoved's own doc comment). A heartbeat
    // this app writes and reads itself needs no deprecated API and, unlike a
    // simple onCreate()/onDestroy()-toggled flag, self-corrects even on an
    // abrupt kill: onDestroy() is not guaranteed to run in that case, so a
    // flag flipped true in onCreate() and never flipped back would wrongly
    // read "alive" forever. A timestamp that must be recently refreshed to
    // count as fresh has no such failure mode -- it simply stops being
    // fresh once nothing is left alive to refresh it.
    //
    // Compared using SystemClock.elapsedRealtime(), not
    // System.currentTimeMillis() -- see
    // [MemberRepository.serviceHeartbeatAt]'s doc comment for why (immune to
    // wall-clock/NTP/timezone changes, keeps ticking through deep sleep). The
    // one thing this requires handling explicitly here: elapsedRealtime()
    // resets to (near) zero on every reboot, so a `heartbeatAt` persisted
    // before a reboot can end up LARGER than the current reading taken after
    // one -- there is no in-between value it could otherwise take, since the
    // service can't run before the OS finishes booting. That specific shape
    // (persisted value ahead of "now") is only possible across a reboot, so
    // it's treated the same as any other stale reading -- correct here even
    // though it fails toward "not alive" for what's typically only the first
    // watchdog pass after boot, until BootCompletedReceiver's restart (or
    // this same worker's own re-assert call below) lets the service write a
    // fresh, post-reboot heartbeat.
    //
    // Used to gate the stale-fix stopgap above: this worker must only ever
    // act as a fallback for a service that is genuinely not alive, never a
    // second fetcher racing an already-live subscription. A heartbeat of 0
    // (never written -- e.g. tracking enabled but the service has never
    // actually managed to subscribe even once) correctly reads as "not
    // alive" here, same as one that's simply aged out.
    private fun isServiceLikelyAlive(repository: MemberRepository): Boolean {
        val heartbeatAt = repository.serviceHeartbeatAt
        if (heartbeatAt <= 0L) return false
        val now = SystemClock.elapsedRealtime()
        if (heartbeatAt > now) return false // reboot occurred since this heartbeat was written
        return (now - heartbeatAt) <= SERVICE_HEARTBEAT_FRESHNESS_MS
    }

    // Deliberately unconstrained (no setRequiredNetworkType): the
    // service-restart watchdog needs zero network, and gating the whole
    // worker behind connectivity would mean it never runs during exactly the
    // scenario that matters most -- a connectivity dead zone, also a
    // plausible moment for an OEM power manager to kill the service. The
    // offline-queue flush already degrades gracefully without network on its own.
    private fun rescheduleNextPass(remoteConfigRepository: RemoteConfigRepository) {
        // Sync Interval, tunable via Remote Management (default 3 minutes)
        // without a new APK build -- see RemoteConfigRepository.syncIntervalMinutes.
        val nextRequest = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setInitialDelay(remoteConfigRepository.syncIntervalMinutes, TimeUnit.MINUTES)
            .build()

        // REPLACE, not KEEP/APPEND: this worker instance is always the sole
        // owner of the next scheduled run.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            nextRequest
        )
    }
}
