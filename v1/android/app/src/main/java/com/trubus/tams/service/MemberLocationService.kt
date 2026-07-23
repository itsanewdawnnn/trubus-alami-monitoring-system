package com.trubus.tams.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.trubus.tams.R
import com.trubus.tams.data.repository.ActivityLogRepository
import com.trubus.tams.data.repository.MemberRepository
import com.trubus.tams.data.repository.RemoteConfigRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*

class MemberLocationService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var repository: MemberRepository
    private lateinit var remoteConfigRepository: RemoteConfigRepository
    private lateinit var activityLogRepository: ActivityLogRepository
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    // Mutated from both the main thread (onStartCommand) and serviceScope's
    // IO-dispatched coroutines (the staleness watchdog and Stop path). A
    // plain var has no cross-thread visibility guarantee, and the
    // "if (locationCallback != null) return" guard is a check-then-act that
    // isn't atomic across threads -- both could pass the null check before
    // either finishes registering, resulting in TWO LocationCallback
    // instances (every fix then fires handleNewLocation() twice). @Volatile
    // plus synchronized(this) around every mutation site closes both gaps.
    // Nothing in those blocks does blocking I/O, so no ANR/deadlock risk.
    @Volatile
    private var locationCallback: LocationCallback? = null

    // Reused across every fix instead of constructing a new SimpleDateFormat
    // per callback -- fixes arrive every 10-15s for hours at a stretch, so
    // that allocation adds up on low-end devices. Safe to share because
    // onLocationResult always runs on the main looper (see
    // Looper.getMainLooper() below) -- only one thread ever touches it.
    private val recordedAtFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }

    // SystemClock.elapsedRealtime() (survives deep sleep, unaffected by clock
    // changes) of the last real GPS fix delivered to onLocationResult. Used
    // by the staleness watchdog below to detect a case LocationSyncWorker's
    // watchdog can't: the service stays alive and isTrackingEnabled stays
    // true, but the FusedLocationProviderClient subscription itself has
    // silently gone quiet (observed on some devices after long background runs).
    @Volatile
    private var lastFixElapsedRealtimeMs: Long = 0L

    private var stalenessWatchdogJob: Job? = null

    // Held only for the few seconds it takes to hand a fresh fix to the
    // network. Play services already wakes the CPU briefly to deliver the
    // callback, but that window isn't guaranteed to cover the async POST --
    // an aggressive OEM power manager could suspend mid-upload. A short,
    // explicit PARTIAL_WAKE_LOCK closes that gap without holding it for the
    // whole session (which would defeat Doze/App-Standby savings).
    private var wakeLock: PowerManager.WakeLock? = null

    // Manual reference count for the wake lock above: with
    // setReferenceCounted(false), any in-flight upload's finally block
    // calling release() would fully clear the lock even while a different,
    // more recent upload is still mid-network-call. setReferenceCounted(true)
    // wasn't used instead because Android's built-in counting throws if
    // release() is called more than acquire() -- this counter avoids that
    // crash risk; release() here is always guarded by count-reaches-zero AND isHeld.
    private val inFlightUploadCount = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        private const val TAG = "MemberLocationService"
        private const val CHANNEL_ID = "member_location_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "TAMS:locationUploadLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

        // Floor for the staleness watchdog's re-registration threshold -- 4x
        // the default 10s GPS interval, matching MainAppScreen's static
        // MAX_NORMAL_FIX_GAP_SECONDS heuristic at Remote Management's
        // default settings. When an Administrator raises the GPS Update
        // Interval above default via Remote Management, the actual threshold
        // used at runtime (see startStalenessWatchdog) scales up alongside
        // it (still 4x, never below this floor) so a longer configured
        // interval is never mistaken for a stalled subscription. Deliberately
        // NOT wired back into MainAppScreen.MAX_NORMAL_FIX_GAP_SECONDS, which
        // is a display-only heuristic over already-recorded history spanning
        // whatever interval was active at the time each point was captured --
        // keeping it a static constant is simpler and correct enough for a
        // UI hint, unlike this watchdog's live GPS-recovery decision.
        private const val GPS_STALE_THRESHOLD_MS = 40_000L
        private const val STALENESS_CHECK_INTERVAL_MS = 30_000L

        // A kilometer of uncertainty is a broken/cold-start reading --
        // discarded here, before any wake lock/network is involved, since the
        // server enforces the same bound (api.php's GPS_MAX_USABLE_ACCURACY_M)
        // as the real authority; this is just a cheap client-side head start.
        private const val GPS_MAX_USABLE_ACCURACY_M = 1000f

        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"

        // Broadcast action to notify UI
        const val ACTION_LOCATION_BROADCAST = "com.trubus.tams.service.LOCATION_BROADCAST"
        const val EXTRA_LATITUDE = "EXTRA_LATITUDE"
        const val EXTRA_LONGITUDE = "EXTRA_LONGITUDE"
        const val EXTRA_ACCURACY = "EXTRA_ACCURACY"
        const val EXTRA_SPEED = "EXTRA_SPEED"
        const val EXTRA_TIME = "EXTRA_TIME"
        const val EXTRA_SYNC_STATUS = "EXTRA_SYNC_STATUS"
        // Set true on the broadcast that accompanies a server-confirmed
        // dead/revoked token (see SessionInvalidException) -- lets the UI,
        // if currently alive, tear its own logged-in state down immediately
        // instead of only noticing on the next cold start's
        // validateSessionOnStartup() check. See handleNewLocation().
        const val EXTRA_SESSION_INVALID = "EXTRA_SESSION_INVALID"

        // Set true on the broadcast that accompanies this service
        // automatically stopping itself because a fix was rejected with
        // TrackingNotAllowedException (outside the operational-hours window,
        // with no -- or no longer any -- Force override). Distinct from
        // EXTRA_SESSION_INVALID: the Member's session stays fully valid, only
        // tracking itself stopped. Lets the UI, if currently alive, flip its
        // Start/Stop toggle back to "Start" and inform the Member immediately
        // instead of the toggle silently drifting out of sync with reality
        // until the next app foreground. See handleNewLocation().
        const val EXTRA_TRACKING_NOT_ALLOWED = "EXTRA_TRACKING_NOT_ALLOWED"
    }

    override fun onCreate() {
        super.onCreate()
        repository = MemberRepository(applicationContext)
        remoteConfigRepository = RemoteConfigRepository(applicationContext) { repository.baseUrl }
        activityLogRepository = ActivityLogRepository(
            baseUrlProvider = { repository.baseUrl },
            tokenProvider = { repository.authToken }
        )
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        // Fire-and-forget, non-blocking -- startForegroundServiceCompat() and
        // startLocationTracking() below never wait on this. Most calls are a
        // no-op (see RemoteConfigRepository.refreshIfStale's staleness gate);
        // when it does fetch, an updated GPS Update Interval takes effect the
        // next time tracking (re)starts, not necessarily this instant --
        // acceptable per Remote Management's own "no APK update needed"
        // requirement, which doesn't demand instant mid-session effect.
        serviceScope.launch {
            try {
                remoteConfigRepository.refreshIfStale()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Remote config refresh on service start failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START -> {
                // Persisted so the service can safely resume itself if the OS
                // kills and restarts it (see the `null` branch below).
                repository.isTrackingEnabled = true
                beginForegroundTracking()
            }
            ACTION_STOP -> {
                repository.isTrackingEnabled = false
                // Defers stopSelf() until the offline notification completes
                // (or times out) -- firing it in a detached scope and calling
                // stopSelf() immediately risks the process being torn down
                // before the "offline" ping reaches the server.
                serviceScope.launch {
                    stopLocationTrackingAndNotifyServer()
                    stopSelf()
                }
            }
            null -> {
                // The OS restarted this START_STICKY service after killing
                // it. If tracking was enabled, startForeground() MUST be
                // called again immediately or Android crashes the app
                // (ForegroundServiceDidNotStartInTimeException).
                if (repository.isTrackingEnabled) {
                    Log.d(TAG, "Service restarted by OS; resuming tracking.")
                    beginForegroundTracking()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Shared entry point for both the ACTION_START and OS-restart (`null`)
     * branches above -- checks location permission BEFORE calling
     * startForegroundServiceCompat(), not after. On Android 14+ (API 34),
     * calling Service.startForeground() with FOREGROUND_SERVICE_TYPE_LOCATION
     * while the app does NOT currently hold ACCESS_FINE_LOCATION/
     * ACCESS_COARSE_LOCATION throws a SecurityException synchronously, from
     * inside startForeground() itself -- before startLocationTracking()'s own
     * permission check (further downstream) ever runs. A member revoking
     * location permission from system Settings while `isTrackingEnabled`
     * stays persisted true (nothing else in this class observes that
     * revocation happening) is a completely reachable real-world path into
     * exactly that state on the next restart -- OS process restart, this
     * service's own staleness-driven re-registration, LocationSyncWorker's
     * watchdog re-asserting the service, or BootCompletedReceiver after a
     * reboot. Checking first, and wrapping the whole start attempt in
     * try/catch as a second line of defense, turns a hard crash into the
     * same graceful "permission missing, stop tracking" outcome
     * startLocationTracking() already produces for the same condition.
     */
    private fun beginForegroundTracking() {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission missing; cannot start foreground tracking.")
            repository.isTrackingEnabled = false
            stopSelf()
            return
        }
        try {
            startForegroundServiceCompat()
            startLocationTracking()
        } catch (e: Exception) {
            // Covers SecurityException (permission revoked in the narrow
            // window between the check above and this call) and any other
            // unexpected failure from startForeground() itself (e.g.
            // ForegroundServiceStartNotAllowedException on API 31+ if this
            // is somehow reached outside a valid background-start
            // exemption) -- never let a foreground-service start failure
            // crash the process.
            Log.e(TAG, "Failed to start foreground tracking: ${e.message}")
            repository.isTrackingEnabled = false
            stopSelf()
        }
    }

    private fun startForegroundServiceCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // Deliberately static text -- updating it on every fix (10-15s) would
    // mean a NotificationManager.notify() call purely to change text the
    // member rarely reads, for a real battery cost.
    private fun createNotification(): Notification {
        val intent = Intent(this, com.trubus.tams.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Just a state statement, not a status dashboard -- exists only
        // because Android requires a foreground service to show one.
        // Per-fix details are shown on the member's own dashboard instead.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAMS - Trubus Alami Monitoring System")
            .setContentText("System runtime and dependencies are operating normally.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    // kotlin.synchronized is an inline function, so a bare `return` inside
    // this block is a non-local return out of startLocationTracking() itself
    // -- see locationCallback's doc comment for why this must be atomic.
    private fun startLocationTracking() = synchronized(this) {
        if (locationCallback != null) return@synchronized // Already tracking

        // Balanced-power interval, tunable via Remote Management (default
        // 15s target / 10s fastest-if-available) without a new APK build --
        // see RemoteConfigRepository.gpsIntervalSeconds. A tighter interval
        // than the default was evaluated and reverted -- it triples the GPS
        // chip's duty cycle and upload count, a real battery cost this app's
        // low-end-device priority doesn't justify; an Administrator who still
        // wants tighter tracking can lower this from the Admin Panel.
        val intervalMs = remoteConfigRepository.gpsIntervalSeconds * 1000L
        // Fastest-if-available stays 5s ahead of the target, same absolute
        // gap as the original 15s/10s default, floored at 1s so a very short
        // configured interval never produces a zero/negative value.
        val fastestIntervalMs = (intervalMs - 5000L).coerceAtLeast(1000L)
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        ).apply {
            setMinUpdateIntervalMillis(fastestIntervalMs)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation = locationResult.lastLocation ?: return
                handleNewLocation(lastLocation)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                super.onLocationAvailability(availability)
                if (!availability.isLocationAvailable) {
                    // Fires when GPS is turned off or signal is lost while the
                    // app is still running -- tell the server immediately
                    // instead of waiting for the staleness fallback.
                    Log.w(TAG, "Location is not available (GPS disabled or signal lost).")
                    serviceScope.launch {
                        try {
                            repository.postOfflineStatus()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to notify offline status: ${e.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "Location is available.")
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions missing. Stopping service.")
            locationCallback = null
            repository.isTrackingEnabled = false
            serviceScope.launch {
                activityLogRepository.log("error", success = false, message = "Tracking stopped: location permission missing")
                stopLocationTrackingAndNotifyServer()
                stopSelf()
            }
            return@synchronized
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Requested location updates successfully.")
            // Start the staleness clock now, not only when a fix arrives, so
            // a subscription that never produces a single fix is still caught.
            lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
            startStalenessWatchdog()
        } catch (unlikely: SecurityException) {
            Log.e(TAG, "Lost location permission: $unlikely")
            locationCallback = null
            repository.isTrackingEnabled = false
            serviceScope.launch {
                activityLogRepository.log("error", success = false, message = "Tracking stopped: location permission revoked")
                stopLocationTrackingAndNotifyServer()
                stopSelf()
            }
        }
    }

    /**
     * Detects a narrower failure mode than "the service got killed" (already
     * handled by LocationSyncWorker's watchdog + START_STICKY +
     * onTaskRemoved): the process is alive, but the FusedLocationProviderClient
     * subscription itself has gone silent, observed in the field on some
     * devices after long background execution. Since [locationCallback] is
     * still non-null, [startLocationTracking]'s own guard would otherwise
     * mean nothing ever notices. Runs until [stalenessWatchdogJob] is
     * cancelled (a child of [serviceJob], cancelled by
     * [stopLocationTrackingAndNotifyServer]/[onDestroy]).
     */
    private fun startStalenessWatchdog(): Unit = synchronized(this) {
        if (stalenessWatchdogJob != null) return@synchronized
        stalenessWatchdogJob = serviceScope.launch {
            while (true) {
                delay(STALENESS_CHECK_INTERVAL_MS)
                // This loop runs for as long as tracking stays active --
                // potentially many hours in one continuous stretch -- and
                // nothing in this app installs a CoroutineExceptionHandler,
                // so an uncaught exception from any statement below (an
                // unexpected RuntimeException from Play Services, a
                // SharedPreferences fault reading gpsIntervalSeconds, etc.)
                // would propagate to the thread's default uncaught-exception
                // handler and kill the whole process -- ending GPS tracking
                // silently until the next external recovery (WorkManager's
                // watchdog, reboot, or reopening the app). Caught broadly
                // and logged instead, exactly like every network-facing call
                // elsewhere in this class; the loop itself keeps running
                // either way since `delay` at the top starts the next
                // iteration regardless of what happened in this one.
                try {
                    val elapsedSinceLastFix = SystemClock.elapsedRealtime() - lastFixElapsedRealtimeMs
                    // 4x the currently configured GPS Update Interval, never
                    // below GPS_STALE_THRESHOLD_MS's floor -- see that constant's
                    // doc comment for why this must scale with Remote Management
                    // rather than staying fixed at the 15s-interval default.
                    val staleThresholdMs = (remoteConfigRepository.gpsIntervalSeconds * 4_000L)
                        .coerceAtLeast(GPS_STALE_THRESHOLD_MS)
                    if (elapsedSinceLastFix > staleThresholdMs) {
                        Log.w(
                            TAG,
                            "No GPS fix in ${elapsedSinceLastFix}ms while tracking is active -- " +
                                "forcing location-update re-registration."
                        )
                        // Re-subscribing is the actual recovery action -- a fresh
                        // requestLocationUpdates call has been observed to
                        // un-stick a silently-dead subscription.
                        //
                        // Wrapped in one outer synchronized(this): removeLocationUpdates()
                        // and startLocationTracking() each independently synchronize
                        // on `this`, but as two SEPARATE lock acquisitions a Stop
                        // request (stopLocationTrackingAndNotifyServer, also
                        // synchronized) could interleave between them on another
                        // thread -- landing right after removeLocationUpdates() sets
                        // locationCallback = null, nulling out stalenessWatchdogJob
                        // too. startLocationTracking()'s trailing
                        // startStalenessWatchdog() call would then see a null job
                        // and spawn a SECOND watchdog loop that's never tracked
                        // anywhere, running forever alongside this one. Java monitors
                        // are reentrant for the same thread, so nesting the two
                        // already-synchronized calls inside this outer block keeps
                        // the whole recovery sequence atomic against that race.
                        synchronized(this@MemberLocationService) {
                            removeLocationUpdates()
                            startLocationTracking()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Staleness watchdog pass failed: ${e.message}")
                }
            }
        }
    }

    private fun handleNewLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy
        val speed = location.speed

        // "Stale" means the GPS subscription stopped producing callbacks, not
        // "the last send failed" -- reaching this function at all proves it's alive.
        lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()

        // See GPS_MAX_USABLE_ACCURACY_M's doc comment. Common right after
        // Start while the GPS chip is still acquiring satellites; the next
        // fixes typically improve on their own.
        if (accuracy > GPS_MAX_USABLE_ACCURACY_M) {
            Log.d(TAG, "Discarding fix with unusable accuracy: ${accuracy}m (Lat $lat, Lng $lng)")
            return
        }

        val timeStr = recordedAtFormat.format(Date(location.time))

        Log.d(TAG, "New coordinates: Lat $lat, Lng $lng, Acc $accuracy, Time $timeStr")

        // Paired with release() in the finally block, plus a hard timeout as
        // a backstop. Counted via inFlightUploadCount (see its doc comment)
        // so an overlapping upload isn't released early by an older one.
        inFlightUploadCount.incrementAndGet()
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)

        serviceScope.launch {
            try {
                val result = repository.postLocation(lat, lng, accuracy, speed, timeStr)

                val isSuccess = result.isSuccess
                val rejection = result.exceptionOrNull()
                val sessionInvalid = rejection is com.trubus.tams.data.repository.SessionInvalidException
                val trackingNotAllowed = rejection is com.trubus.tams.data.repository.TrackingNotAllowedException
                if (!isSuccess) {
                    Log.w(TAG, "Failed to sync location to server: ${rejection?.message}")
                }

                // Broadcast to update UI screen in real time
                val intent = Intent(ACTION_LOCATION_BROADCAST).apply {
                    putExtra(EXTRA_LATITUDE, lat)
                    putExtra(EXTRA_LONGITUDE, lng)
                    putExtra(EXTRA_ACCURACY, accuracy)
                    putExtra(EXTRA_SPEED, speed)
                    putExtra(EXTRA_TIME, timeStr)
                    putExtra(EXTRA_SYNC_STATUS, isSuccess)
                    putExtra(EXTRA_SESSION_INVALID, sessionInvalid)
                    putExtra(EXTRA_TRACKING_NOT_ALLOWED, trackingNotAllowed)
                }
                sendBroadcast(intent)

                // A server-confirmed dead/revoked token (see
                // SessionInvalidException's doc comment) is unrecoverable --
                // retrying will fail identically for every future fix, and
                // postLocation() deliberately never queues a 401 for offline
                // retry either (same reasoning). Without this, tracking
                // stayed marked "active" while every subsequent GPS fix was
                // silently dropped for the rest of the session -- real
                // location data loss with no visible indication. Stopping
                // here surfaces it the same way a revoked/missing permission
                // already does above in startLocationTracking().
                //
                // TrackingNotAllowedException (see its own doc comment) is
                // the Force Location auto-revoke fix: this Member's session
                // is still perfectly valid, but this fix landed outside the
                // allowed operational-hours window with no (or no longer
                // any) Admin-granted Force override -- most commonly because
                // an Admin just flipped Force Location OFF while this Member
                // was still tracking outside hours. Stopping here, the same
                // way a dead session does above, is the ENTIRE client-side
                // enforcement: there is no separate "Force was revoked" push
                // signal from the server. Every fix is already checked
                // against the Force/hours state fresh from the database on
                // every single call (see backend/api.php's /location/update
                // Force Override gate), so reacting correctly to its
                // rejection here is sufficient -- no additional polling loop
                // needed, no additional race to introduce. Deliberately does
                // NOT touch the session/login state (unlike the
                // sessionInvalid branch below) -- the Member stays logged in
                // and can press Start again once back within the allowed
                // window or once an Admin re-enables Force.
                if (sessionInvalid) {
                    Log.e(TAG, "Session no longer valid; stopping tracking.")
                    repository.isTrackingEnabled = false
                    stopLocationTrackingAndNotifyServer()
                    stopSelf()
                } else if (trackingNotAllowed) {
                    Log.w(TAG, "Tracking no longer allowed (outside operational hours / Force revoked); stopping automatically.")
                    repository.isTrackingEnabled = false
                    activityLogRepository.log(
                        "stop_location",
                        success = true,
                        message = "Tracking stopped automatically: outside operational hours (Force Location may have been turned off)"
                    )
                    stopLocationTrackingAndNotifyServer()
                    stopSelf()
                }
            } finally {
                // Only the upload that brings the count to zero releases the
                // lock -- see inFlightUploadCount's doc comment.
                if (inFlightUploadCount.decrementAndGet() <= 0 && wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        }
    }

    private fun removeLocationUpdates(): Unit = synchronized(this) {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            Log.d(TAG, "Removed location updates.")
        }
    }

    /**
     * Removes location updates and WAITS (bounded timeout) for the "offline"
     * status ping to reach the server before returning -- guarantees the
     * admin panel flips to OFFLINE as soon as Stop is pressed, instead of
     * racing the process teardown.
     */
    private suspend fun stopLocationTrackingAndNotifyServer() {
        // One atomic block, not two separate synchronized calls: the
        // staleness watchdog's own recovery sequence (removeLocationUpdates
        // + startLocationTracking, see startStalenessWatchdog) is also
        // synchronized(this), but as two independent lock acquisitions the
        // watchdog could interleave between "remove" and "cancel the
        // watchdog job" here -- re-registering a fresh location subscription
        // (and finding stalenessWatchdogJob still non-null, so skipping its
        // own re-creation) a moment before this code cancels that same job
        // out from under it, leaving an active GPS subscription with no
        // watchdog supervising it for the remainder of this service's short
        // remaining lifetime. Not cancelling the watchdog loop from inside
        // removeLocationUpdates() itself: that helper is also called BY the
        // watchdog to force re-registration mid-session, and killing the
        // loop from within itself there would end it prematurely. Only a
        // genuine Stop or onDestroy should end the watchdog.
        synchronized(this) {
            removeLocationUpdates()
            stalenessWatchdogJob?.cancel()
            stalenessWatchdogJob = null
        }

        try {
            val result = withTimeoutOrNull(8000L) {
                repository.postOfflineStatus()
            }
            if (result == null) {
                Log.w(TAG, "Timed out waiting for offline status confirmation.")
            } else {
                Log.d(TAG, "Sent offline status to server.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send offline status: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TAMS - Trubus Alami Monitoring System",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "System runtime and dependencies are operating normally."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Fires when the member swipes TAMS away from Recents. Several OEM power
     * managers (MIUI, ColorOS, FuntouchOS, EMUI) treat "task removed" as a
     * stronger signal than an ordinary low-memory kill and tear down the
     * whole process shortly after this callback returns, bypassing the
     * restart-after-kill path in onStartCommand's `null` branch. This likely
     * caused the ~10-minute tracking freeze seen in testing: once the
     * process is gone, nothing resumes GPS fixes until the member reopens
     * the app or WorkManager's watchdog happens to run (up to 15 minutes
     * away). Missed fixes in that window are never generated at all, which
     * is why they show as real gaps in Trip History.
     *
     * Scheduling an alarm-triggered restart here closes most of that gap.
     * `setAndAllowWhileIdle` (not `...Exact...`) still fires promptly right
     * after an interactive task-removal, but doesn't require the user to
     * separately grant SCHEDULE_EXACT_ALARM on Android 12+.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (repository.isTrackingEnabled) {
            Log.w(TAG, "Task removed from Recents while tracking is active; scheduling fast restart.")
            try {
                val restartIntent = Intent(applicationContext, MemberLocationService::class.java).apply {
                    action = ACTION_START
                }
                // getForegroundService() (API 26+) tells the platform up
                // front this is a foreground-service start; minSdk 24 still
                // needs the plain getService() fallback below API 26.
                val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        applicationContext,
                        0,
                        restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getService(
                        applicationContext,
                        0,
                        restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 1_000L,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule fast restart after task removal: ${e.message}")
            }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onDestroy() {
        super.onDestroy()
        removeLocationUpdates()
        // Best-effort only: unlike ACTION_STOP, onDestroy() offers no
        // guarantee of extra run time, so this fires without awaiting it.
        // Deliberately NOT serviceScope: serviceJob.cancel() below would
        // cancel it immediately since it's a child job, killing this ping
        // before it could ever be sent. GlobalScope (explicit, not a plain
        // throwaway CoroutineScope(...)) documents that this is intentionally
        // unscoped -- bounded by its own 8s timeout, not by the service's
        // lifecycle, so it can outlive onDestroy() without running wild.
        // Only matters for a teardown other than ACTION_STOP (which already
        // awaits this same call, see stopLocationTrackingAndNotifyServer);
        // the 90s server-side staleness fallback covers the rare case where
        // even this doesn't make it out in time.
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(8000L) { repository.postOfflineStatus() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "onDestroy best-effort offline notify failed: ${e.message}")
            }
        }
        serviceJob.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        Log.d(TAG, "Service destroyed.")
    }
}
