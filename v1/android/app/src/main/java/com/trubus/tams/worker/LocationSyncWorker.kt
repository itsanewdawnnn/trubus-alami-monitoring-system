package com.trubus.tams.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trubus.tams.data.repository.MemberRepository
import com.trubus.tams.data.repository.RemoteConfigRepository
import com.trubus.tams.data.repository.SessionInvalidException
import com.trubus.tams.service.MemberLocationService
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
            Log.d("LocationSyncWorker", "Tracking should be active; re-asserting foreground service state.")
            val intent = Intent(applicationContext, MemberLocationService::class.java).apply {
                action = MemberLocationService.ACTION_START
            }
            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                Log.e("LocationSyncWorker", "Failed to re-assert tracking service: ${e.message}")
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
