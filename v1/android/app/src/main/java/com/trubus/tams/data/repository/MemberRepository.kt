package com.trubus.tams.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.trubus.tams.BuildConfig
import com.trubus.tams.data.api.ApiService
import com.trubus.tams.data.local.AppDatabase
import com.trubus.tams.data.model.*
import com.trubus.tams.util.WibTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Thrown by [MemberRepository.validateSession] specifically when the server
 * was actually reached and explicitly rejected the token (HTTP 401/403) --
 * as opposed to any other failure (unreachable host, timeout, TLS error, a
 * 5xx, an ISP block page that isn't even valid JSON), where the token's
 * real validity is simply unknown because the server was never confirmed to
 * have looked at it. MainViewModel uses this distinction to decide whether
 * it's safe to clear the locally stored session -- see
 * MainViewModel.validateSessionOnStartup's doc comment.
 */
class SessionInvalidException(message: String) : Exception(message)

/**
 * Thrown by [MemberRepository.postLocation] specifically for a 401/403 whose
 * body carries `error_code: "outside_operational_hours"` (see
 * backend/api.php's /location/update Force Override gate) -- the session
 * itself is perfectly valid, but this particular fix was rejected because
 * the Member is outside the allowed tracking window and has no (or no
 * longer has an) Admin-granted Force override. Distinct from
 * [SessionInvalidException] on purpose: [MemberLocationService] reacts to
 * this by automatically stopping its own foreground service and GPS
 * subscription (Force Location's ON->OFF auto-revoke behavior -- see that
 * class's handleNewLocation() doc comment), but must NOT log the Member out
 * or tear down their session, since nothing about the token itself was
 * rejected. This is also what makes an Admin's Force Location toggle
 * self-enforcing with no separate "push a stop signal to the device"
 * mechanism: the very next fix after the toggle flips OFF is rejected here
 * if the Member is outside operational hours, and the service reacts
 * immediately.
 */
class TrackingNotAllowedException(message: String) : Exception(message)

class MemberRepository(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val offlineDao = db.offlineLocationDao()

    private val prefs: SharedPreferences = context.getSharedPreferences("member_monitor_prefs", Context.MODE_PRIVATE)

    // Lambdas read `baseUrl`/`authToken` live on every call (same pattern as
    // apiService's tokenProvider above), so this never needs its own
    // rebuild-on-change plumbing.
    private val activityLogRepository = ActivityLogRepository(
        baseUrlProvider = { baseUrl },
        tokenProvider = { authToken }
    )

    companion object {
        private const val PREF_TOKEN = "auth_token"
        private const val PREF_USER_ID = "user_id"
        private const val PREF_USER_NAME = "user_name"
        private const val PREF_USER_NOTE = "user_note"
        private const val PREF_USER_USERNAME = "user_username"
        private const val PREF_USER_ROLE = "user_role"
        private const val PREF_BASE_URL = "base_url"
        private const val PREF_LAST_SYNC = "last_sync_time"
        private const val PREF_TRACKING_ENABLED = "tracking_enabled"
        private const val PREF_LAST_LOC_LAT = "last_loc_lat"
        private const val PREF_LAST_LOC_LNG = "last_loc_lng"
        private const val PREF_LAST_LOC_ACC = "last_loc_acc"
        private const val PREF_LAST_LOC_SPEED = "last_loc_speed"
        private const val PREF_LAST_LOC_TIME = "last_loc_time"
        private const val PREF_BATTERY_OPT_CARD_DISMISSED = "battery_opt_card_dismissed"

        private const val DEFAULT_BASE_URL = "https://your-tams-domain.example/backend/"

        // Caps the local offline-location queue so a device left offline for
        // an extended period doesn't accumulate unbounded local storage.
        private const val MAX_OFFLINE_QUEUE_SIZE = 2000

        // Guards syncOfflineLocations()'s read-send-delete section against
        // running concurrently with itself. Must be a companion-object (process-
        // wide) Mutex, not an instance-level one: MemberLocationService,
        // LocationSyncWorker and MainViewModel each construct their own
        // MemberRepository, so an instance-level lock would miss the race
        // between postLocation()'s opportunistic flush and the watchdog's
        // periodic flush landing at the same time -- both could read the same
        // queued rows before either deletes them, double-sending them to the
        // server. backend/schema.sql's UNIQUE(user_id, recorded_at) is a
        // second line of defense, but avoiding the duplicate calls here is
        // the primary fix.
        private val offlineSyncMutex = Mutex()
    }

    /**
     * Persists the member's intent to be tracked, independent of whether the
     * OS-level Service process happens to be alive right now. Used by
     * [com.trubus.tams.service.MemberLocationService] to safely resume
     * tracking (and re-call startForeground()) if the system kills and
     * restarts the START_STICKY service, and to know whether a restart with
     * a null Intent should resume tracking or just stop.
     */
    var isTrackingEnabled: Boolean
        get() = prefs.getBoolean(PREF_TRACKING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(PREF_TRACKING_ENABLED, value).apply()

    /**
     * Last GPS fix captured on this device, persisted independently of
     * whether the network POST for it ever succeeded. Without this, reopening
     * the app (new process, new ViewModel) while tracking ran in the
     * background showed "No location recorded yet" until the next live
     * broadcast, since the dashboard previously only had an in-memory
     * StateFlow with no persisted fallback. MainViewModel seeds its StateFlow
     * from this at startup, same pattern as [isTrackingEnabled].
     */
    var lastKnownLocation: TrackedLocationSnapshot?
        get() {
            val lat = prefs.getString(PREF_LAST_LOC_LAT, null)?.toDoubleOrNull() ?: return null
            val lng = prefs.getString(PREF_LAST_LOC_LNG, null)?.toDoubleOrNull() ?: return null
            val time = prefs.getString(PREF_LAST_LOC_TIME, null) ?: return null
            return TrackedLocationSnapshot(
                latitude = lat,
                longitude = lng,
                accuracy = prefs.getFloat(PREF_LAST_LOC_ACC, 0f),
                speed = prefs.getFloat(PREF_LAST_LOC_SPEED, 0f),
                time = time
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit()
                    .remove(PREF_LAST_LOC_LAT)
                    .remove(PREF_LAST_LOC_LNG)
                    .remove(PREF_LAST_LOC_ACC)
                    .remove(PREF_LAST_LOC_SPEED)
                    .remove(PREF_LAST_LOC_TIME)
                    .apply()
            } else {
                prefs.edit()
                    .putString(PREF_LAST_LOC_LAT, value.latitude.toString())
                    .putString(PREF_LAST_LOC_LNG, value.longitude.toString())
                    .putFloat(PREF_LAST_LOC_ACC, value.accuracy)
                    .putFloat(PREF_LAST_LOC_SPEED, value.speed)
                    .putString(PREF_LAST_LOC_TIME, value.time)
                    .apply()
            }
        }

    /**
     * Manual escape hatch for the battery-optimization card on
     * MemberDashboard. `PowerManager.isIgnoringBatteryOptimizations()` is the
     * correct API, but some OEM ROMs (observed on iQOO/Vivo OriginOS) don't
     * reliably sync its return value with what the user actually granted.
     * Lets a member who's sure they granted it hide the card manually;
     * auto-cleared the moment the real check reports true, so it can never
     * mask a genuine regression.
     */
    var batteryOptimizationCardDismissed: Boolean
        get() = prefs.getBoolean(PREF_BATTERY_OPT_CARD_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(PREF_BATTERY_OPT_CARD_DISMISSED, value).apply()

    // --- Preferences & Configuration ---

    var baseUrl: String
        get() = prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            prefs.edit().putString(PREF_BASE_URL, value).apply()
            rebuildApiService()
        }

    var lastSyncTime: String
        get() = prefs.getString(PREF_LAST_SYNC, "-") ?: "-"
        set(value) = prefs.edit().putString(PREF_LAST_SYNC, value).apply()

    var authToken: String?
        get() = prefs.getString(PREF_TOKEN, null)
        private set(value) = prefs.edit().putString(PREF_TOKEN, value).apply()

    var currentUser: UserDto?
        get() {
            val id = prefs.getInt(PREF_USER_ID, -1)
            if (id == -1) return null
            return UserDto(
                id = id,
                name = prefs.getString(PREF_USER_NAME, "") ?: "",
                note = prefs.getString(PREF_USER_NOTE, "") ?: "",
                username = prefs.getString(PREF_USER_USERNAME, "") ?: "",
                role = prefs.getString(PREF_USER_ROLE, "member") ?: "member"
            )
        }
        private set(value) {
            if (value == null) {
                prefs.edit()
                    .remove(PREF_USER_ID)
                    .remove(PREF_USER_NAME)
                    .remove(PREF_USER_NOTE)
                    .remove(PREF_USER_USERNAME)
                    .remove(PREF_USER_ROLE)
                    .apply()
            } else {
                prefs.edit()
                    .putInt(PREF_USER_ID, value.id)
                    .putString(PREF_USER_NAME, value.name)
                    .putString(PREF_USER_NOTE, value.note)
                    .putString(PREF_USER_USERNAME, value.username)
                    .putString(PREF_USER_ROLE, value.role)
                    .apply()
            }
        }

    // ApiService.create()'s tokenProvider lambda reads `authToken` fresh on
    // every request, so a token change never requires rebuilding the
    // OkHttpClient/Retrofit instance. Rebuilding is only needed when
    // `baseUrl` changes, since that's baked in at construction time.
    // @Volatile: the settings screen can reassign this from the UI thread
    // while a background coroutine on Dispatchers.IO is mid-call -- without
    // it, that reader thread isn't guaranteed to observe the reassignment.
    @Volatile
    private var apiService: ApiService = ApiService.create(baseUrl) { authToken }

    private fun rebuildApiService() {
        apiService = ApiService.create(baseUrl) { authToken }
    }

    /**
     * Member Version Monitoring: reported at login and on every location
     * sync (including offline-queue flushes), matching backend/api.php's
     * /auth/login and /location/update device-info extraction. Both server
     * routes upsert with `COALESCE(VALUES(col), col)`, so sending this on
     * every call is safe and requires no "did this already change"
     * tracking here -- an omitted or unchanged value never overwrites a
     * previously-known one.
     */
    private fun deviceInfoFields(): Map<String, Any> = mapOf(
        "app_version_name" to BuildConfig.VERSION_NAME,
        "app_version_code" to BuildConfig.VERSION_CODE,
        "android_version" to Build.VERSION.RELEASE,
        "device_model" to Build.MODEL
    )

    // --- Authentication Actions ---

    /**
     * Retrofit only populates `response.body()` for 2xx responses -- for
     * every error response, it's always null even though this backend still
     * returns a well-formed `{"success":false,"message":"..."}` body (see
     * backend/api.php's catch blocks). Reads the real message out of
     * `errorBody()` instead, falling back to [default] only if that body is
     * missing or not the expected JSON shape.
     */
    private fun errorMessageOrDefault(response: retrofit2.Response<*>, default: String): String {
        val raw = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
        if (!raw.isNullOrBlank()) {
            val parsedMessage = try {
                org.json.JSONObject(raw).optString("message", "")
            } catch (e: Exception) {
                ""
            }
            if (parsedMessage.isNotBlank()) return parsedMessage
        }
        // No parseable JSON message -- still surface the HTTP status code
        // rather than a fully opaque string (helps tell 404 apart from 500/401).
        return "$default (HTTP ${response.code()})"
    }

    /**
     * Reads an error response body exactly once, returning both the
     * human-readable message (same fallback behavior as
     * [errorMessageOrDefault]) and the machine-readable `error_code` field
     * if the body happens to carry one -- e.g. backend/api.php's
     * /location/update sends `error_code: "outside_operational_hours"` on
     * its Force Override rejection specifically so this can be told
     * apart from a genuinely dead/revoked token, which is also a 403 (see
     * [SessionInvalidException]'s doc comment and [postLocation]'s own use
     * of this). An OkHttp response body can only be read once -- a caller
     * needing both the message and the error code must go through this
     * single read rather than calling [errorMessageOrDefault] and a second,
     * separate `errorBody()` read side by side (the second read would
     * always come back empty/closed).
     */
    private fun parseErrorBody(response: retrofit2.Response<*>, default: String): Pair<String, String?> {
        val raw = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
        val fallbackMessage = "$default (HTTP ${response.code()})"
        if (raw.isNullOrBlank()) {
            return fallbackMessage to null
        }
        return try {
            val json = org.json.JSONObject(raw)
            val message = json.optString("message", "").ifBlank { fallbackMessage }
            val errorCode = json.optString("error_code", "").ifBlank { null }
            message to errorCode
        } catch (e: Exception) {
            fallbackMessage to null
        }
    }

    /**
     * Shared shape for "call one endpoint, unwrap ApiResponse, turn failure
     * into a Result.failure with a real message" -- avoids repeating the same
     * try/isSuccessful+success/errorMessageOrDefault/catch skeleton in every
     * call site below.
     *
     * [default] supplies a fallback for the case where the server reports
     * success but `data` is null; when omitted, that case is treated as a
     * failure with [defaultErrorMessage] rather than crashing on `!!`.
     *
     * [treatAuthFailureAsSessionInvalid] defaults to true because every
     * route this helper backs except one (see [login]) is only ever called
     * with an existing token, where a 401/403 can only mean that token is
     * dead. [login] is the one call site that passes false: a login attempt
     * has no session to begin with, so its own 401 ("Invalid username or
     * password") and 403 ("Account is inactive") are ordinary credential/
     * account-status failures, never a session dying -- wrapping them in
     * [SessionInvalidException] would be a false label with no session to
     * actually invalidate. Callers already only branch on message text for
     * this route today, so this was previously a latent mislabel rather than
     * an observed bug, but a future caller that generically reacts to any
     * [SessionInvalidException] (e.g. redirecting to the login screen) would
     * otherwise mishandle a failed login attempt made from that very screen.
     */
    private suspend fun <T : Any> apiCall(
        defaultErrorMessage: String,
        default: (() -> T)? = null,
        treatAuthFailureAsSessionInvalid: Boolean = true,
        call: suspend () -> Response<ApiResponse<T>>
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val response = call()
            val body = response.body()
            if (response.isSuccessful && body?.success == true) {
                val data = body.data
                when {
                    data != null -> Result.success(data)
                    default != null -> Result.success(default())
                    else -> Result.failure(Exception(errorMessageOrDefault(response, defaultErrorMessage)))
                }
            } else if (treatAuthFailureAsSessionInvalid && (response.code() == 401 || response.code() == 403)) {
                // Server-confirmed dead/revoked token, not a generic failure --
                // same distinction validateSession() already makes (see
                // SessionInvalidException's doc comment). Previously every
                // route going through this shared helper (getCurrentLocations,
                // getMemberList, getLocationHistory, etc.) treated a 401
                // identically to any other rejection, so nothing but the
                // one-time startup check ever detected a token dying mid-
                // session -- e.g. Admin's 5s live-map poll would keep hitting
                // a dead token forever with no path back to the login screen.
                Result.failure(SessionInvalidException(errorMessageOrDefault(response, "Session is no longer valid.")))
            } else {
                Result.failure(Exception(body?.message ?: errorMessageOrDefault(response, defaultErrorMessage)))
            }
        } catch (e: CancellationException) {
            // Must propagate, never be reported as a failed API call -- this
            // means the calling coroutine (e.g. a screen the user navigated
            // away from) was cancelled, not that the request itself failed.
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(friendlyNetworkErrorMessage(e), e))
        }
    }

    /**
     * Maps a raw network/parsing exception to a short, user-appropriate
     * message. Used only in [apiCall]'s catch-all branch, where the request
     * never reached the server (or the response couldn't be parsed at all) --
     * unlike every other failure branch above, there's no server-provided
     * `message` to fall back on here, so without this the exception's own
     * `.message` (e.g. `UnknownHostException: Unable to resolve host
     * "your-tams-domain.example"...`) would reach the login screen, member list, or
     * trip history UI verbatim. That's technically accurate but not
     * something a Member or Admin should have to read, and was previously
     * inconsistent with [validateSessionOnStartup]'s own connectivity-error
     * branch in MainViewModel, which already used a hand-written fallback
     * string instead of the raw exception message.
     *
     * The original exception is kept as the returned exception's `cause` (see
     * [apiCall]'s catch block), so anywhere a caller logs the [Result]'s
     * exception via the `Log.e(tag, msg, throwable)` overload, the real
     * cause chain and stack trace still reach Logcat -- this mapping only
     * changes what a Composable `Text()` can render, never what a developer
     * can debug.
     */
    private fun friendlyNetworkErrorMessage(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "Could not connect to the server. Check your internet connection."
        is java.net.SocketTimeoutException ->
            "The connection timed out. Please try again."
        is javax.net.ssl.SSLException ->
            "A secure connection to the server could not be established."
        is java.io.IOException ->
            "Network error. Please check your internet connection and try again."
        else ->
            "Something went wrong. Please try again."
    }

    suspend fun login(username: String, password: String): Result<UserDto> {
        // See apiCall()'s own doc comment for why this is the one call site
        // that opts out of its default 401/403 -> SessionInvalidException
        // mapping: there is no session yet for a login attempt to invalidate.
        val result = apiCall("Login failed.", treatAuthFailureAsSessionInvalid = false) {
            // login()'s ApiService signature is Map<String, String> (unlike
            // updateLocation's Map<String, Any>), so device info fields are
            // stringified here -- backend/api.php's filter_var(FILTER_VALIDATE_INT)
            // parses a numeric string exactly the same as a JSON number.
            apiService.login(
                mapOf("username" to username, "password" to password) +
                    deviceInfoFields().mapValues { it.value.toString() }
            )
        }
        return result.map { loginData ->
            authToken = loginData.token
            currentUser = loginData.user
            loginData.user
        }
    }

    /**
     * Cold-start-only check, called once per process before MainViewModel
     * lets [isLoggedIn][com.trubus.tams.ui.viewmodel.MainViewModel] become
     * true -- confirms with the server that the locally stored [authToken]
     * is still valid AND that the server is actually reachable, rather than
     * trusting a cached token unconditionally. That used to mean a device
     * cut off from the server (e.g. an ISP-level block returning HTTP 403)
     * still showed the dashboard and let the user believe location sync was
     * working when every request was silently failing underneath. Reuses
     * GET /profile -- the cheapest authenticated route, since its response
     * is fully replaceable by data already cached locally; refreshing
     * [currentUser] from it on success is a free side benefit, not the point.
     *
     * Returns [Result.failure] with a [SessionInvalidException] specifically
     * for a server-confirmed 401/403 (the token itself is provably bad).
     * Every other failure (network exception, timeout, 5xx, malformed body)
     * is returned as a plain [Exception], meaning the server was never
     * actually confirmed to have rejected anything -- see that class's doc
     * comment for why callers must treat the two differently.
     */
    suspend fun validateSession(): Result<UserDto> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getProfile()
            val body = response.body()
            if (response.isSuccessful && body?.success == true && body.data != null) {
                currentUser = body.data
                Result.success(body.data)
            } else if (response.code() == 401 || response.code() == 403) {
                Result.failure(SessionInvalidException(errorMessageOrDefault(response, "Session is no longer valid.")))
            } else {
                Result.failure(Exception(errorMessageOrDefault(response, "Session validation failed.")))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never reached the server (or got back something that isn't a
            // parseable response) -- explicitly NOT a SessionInvalidException,
            // since nothing actually confirmed the token is bad.
            Result.failure(e)
        }
    }

    /**
     * [notifyServer] is false only when the caller already knows (via a
     * server-confirmed [SessionInvalidException] from [validateSession])
     * that the current token is dead -- calling POST /auth/logout with a
     * token the server already rejected would just fail identically and
     * waste a round trip during the startup path, which needs to stay fast.
     */
    suspend fun logout(notifyServer: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        // Captured before currentUser is cleared below.
        val loggedOutUserId = currentUser?.id
        val result = if (!notifyServer) {
            Result.success(Unit)
        } else {
            try {
                apiService.logout()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        authToken = null
        currentUser = null
        isTrackingEnabled = false
        lastKnownLocation = null
        lastSyncTime = "-"
        // Anything still stuck in the offline queue at logout time can never
        // be safely sent later -- a future sync runs under whichever account
        // logs in next on this device, and sending this user's location under
        // someone else's session would misattribute it server-side. Dropping
        // a few unsent points here is a better tradeoff than that (see also
        // syncOfflineLocations()'s own defensive filter for the same reason).
        if (loggedOutUserId != null) {
            offlineDao.deleteByUserId(loggedOutUserId)
        }
        result
    }

    // --- Profile Actions (Member Role) ---

    /**
     * Updates the authenticated member's own profile. [password] passed as
     * null/blank leaves the current password unchanged. [name] is accepted
     * for API-payload symmetry, but the UI no longer lets the member edit it
     * (see MainAppScreen's read-only Name field). On success, [currentUser]
     * updates immediately -- no re-login needed since the token is tied to
     * the user's id, not their password.
     */
    suspend fun updateProfile(
        name: String,
        username: String,
        note: String,
        password: String?
    ): Result<UserDto> {
        val payload = mutableMapOf(
            "name" to name,
            "username" to username,
            "note" to note
        )
        if (!password.isNullOrBlank()) {
            payload["password"] = password
        }
        return apiCall("Failed to update profile.") { apiService.updateProfile(payload) }
            .onSuccess { currentUser = it }
    }

    // --- Location Logging (Member Role) ---

    suspend fun postOfflineStatus(): Result<Unit> = withContext(Dispatchers.IO) {
        if (currentUser == null) return@withContext Result.failure(Exception("User not authenticated."))
        try {
            val payload = mapOf("status" to "offline")
            val response = apiService.updateLocation(payload)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(Unit)
            } else {
                val errMsg = response.body()?.message ?: errorMessageOrDefault(response, "API returned failure")
                Result.failure(Exception(errMsg))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Posts one continuous-tracking GPS fix to POST /location/update.
     * [lastKnownLocation] is persisted unconditionally before the network
     * call, regardless of whether the POST itself succeeds, so the Member's
     * own dashboard always reflects the latest fix captured on-device (see
     * that property's own doc comment).
     */
    suspend fun postLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        speed: Float,
        recordedAt: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val user = currentUser ?: return@withContext Result.failure(Exception("User not authenticated."))

        // Persisted unconditionally -- captured on-device regardless of
        // whether the network POST below succeeds (see property doc).
        lastKnownLocation = TrackedLocationSnapshot(latitude, longitude, accuracy, speed, recordedAt)

        try {
            val payload = mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "accuracy" to accuracy,
                "speed" to speed,
                "recorded_at" to recordedAt
            ) + deviceInfoFields()
            val response = apiService.updateLocation(payload)
            if (response.isSuccessful && response.body()?.success == true) {
                lastSyncTime = WibTime.nowShort()
                // Network just proved reachable -- opportunistically flush the
                // offline queue now instead of waiting for the next 15-minute
                // WorkManager pass. Cheap no-op (one Room COUNT query) when empty.
                syncOfflineLocations()
                Result.success(Unit)
            } else {
                // Only queue for retry on a transient (5xx) failure -- retrying
                // a 4xx (revoked token, invalid coordinates, outside allowed
                // hours) would just fail forever with the same payload,
                // growing the queue for nothing.
                val httpCode = response.code()
                if (httpCode in 500..599) {
                    queueOfflineLocation(user.id, latitude, longitude, accuracy, speed, recordedAt)
                }
                val (errMsg, errorCode) = parseErrorBody(response, "API returned failure")
                // Force Override's own 403 (see backend/api.php's
                // /location/update doc comment) reuses the same HTTP status
                // code a genuinely dead/revoked token uses, but is a
                // completely different, expected, recoverable condition --
                // "not allowed to track right now", not "this session is
                // gone". A tracking session that starts within the allowed
                // window and keeps running past its end, OR an Admin turning
                // Force Location OFF mid-session, both hit this branch under
                // completely normal daily use, not as a rare edge case.
                // error_code is exactly the discriminator backend/api.php
                // sends for this reason -- three distinct outcomes read it:
                //  - 401/403 WITHOUT "outside_operational_hours": the token
                //    itself was rejected -- [SessionInvalidException], which
                //    MemberLocationService reacts to by stopping tracking AND
                //    tearing down the session (see that class's doc comment).
                //  - 401/403 WITH "outside_operational_hours":
                //    [TrackingNotAllowedException] -- the session is fine,
                //    but this fix isn't allowed right now (natural window
                //    end, or an Admin-revoked Force override).
                //    MemberLocationService reacts by stopping tracking
                //    WITHOUT touching the session -- see that exception
                //    class's own doc comment.
                //  - anything else: a plain [Exception], left running (the
                //    caller doesn't react by stopping tracking).
                if (httpCode == 401 || httpCode == 403) {
                    if (errorCode == "outside_operational_hours") {
                        Result.failure(TrackingNotAllowedException(errMsg))
                    } else {
                        Result.failure(SessionInvalidException(errMsg))
                    }
                } else {
                    Result.failure(Exception(errMsg))
                }
            }
        } catch (e: CancellationException) {
            // Not a network failure -- the caller's coroutine scope was
            // cancelled (e.g. MemberLocationService tearing down mid-upload).
            // Must propagate uncaught, and must NOT be queued for offline
            // retry: postLocation() will simply be called again with a fresh
            // fix once tracking resumes.
            throw e
        } catch (e: Exception) {
            queueOfflineLocation(user.id, latitude, longitude, accuracy, speed, recordedAt)
            Result.failure(e)
        }
    }

    private suspend fun queueOfflineLocation(
        userId: Int,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        speed: Float,
        recordedAt: String
    ) {
        val offlineLoc = OfflineLocation(
            userId = userId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            speed = speed,
            recordedAt = recordedAt
        )
        offlineDao.insert(offlineLoc)
        offlineDao.trimToMostRecent(MAX_OFFLINE_QUEUE_SIZE)
        Log.d("MemberRepository", "Saved failed location update to offline queue: $offlineLoc")
    }

    /**
     * Force Override pre-flight -- see
     * [com.trubus.tams.data.model.LocationStatusDto]'s doc comment. Never
     * the actual enforcement boundary (POST /location/update is), so callers
     * must treat a failure here as "unknown", not "denied" -- see
     * [com.trubus.tams.ui.viewmodel.MainViewModel.requestStartTracking]'s
     * fail-open handling.
     */
    suspend fun getLocationStatus(): Result<LocationStatusDto> =
        apiCall("Failed to check tracking status.") { apiService.getLocationStatus() }

    // --- Offline Synchronisation & WorkManager ---
    // Entirely automatic -- no manual "Sync" affordance in the UI. Flushed
    // opportunistically after a successful postLocation(), by the periodic
    // WorkManager job (LocationSyncWorker, ~15 min), and by its watchdog pass.

    // withLock suspends (doesn't block a thread) a caller that arrives mid-sync,
    // then proceeds once free -- it just sees an already-drained queue and
    // no-ops instead of re-sending rows. See offlineSyncMutex's doc comment
    // for why this lock must be process-wide.
    suspend fun syncOfflineLocations(): Result<Int> = withContext(Dispatchers.IO) {
        offlineSyncMutex.withLock {
            // Whole body wrapped: offlineDao.getAll()/deleteByIds() below are
            // real disk I/O (Room/SQLite) with no try/catch of their own --
            // unlike the per-item network loop, which already handles its
            // own failures. A disk-level fault (full storage, corruption, a
            // rare threading assertion) would otherwise propagate uncaught
            // out of this function and, from LocationSyncWorker's ~3-minute
            // watchdog pass, crash the app repeatedly for as long as a
            // Member stays logged in. Converting it to Result.failure keeps
            // this function's contract honest (it already returns a Result)
            // and lets every caller's existing failure handling take over,
            // instead of a new, previously-impossible throw path.
            try {
                val user = currentUser ?: return@withLock Result.success(0)
                val items = offlineDao.getAll()
                if (items.isEmpty()) return@withLock Result.success(0)

                // Rows queued under a different account can slip in if logout()
                // didn't get to run its own cleanup (process killed, etc.).
                // Sending them now would attribute a previous user's location to
                // whoever is logged in today -- drop them instead of sending or
                // leaving them to squat on MAX_OFFLINE_QUEUE_SIZE's budget forever.
                val (ownItems, staleItems) = items.partition { it.userId == user.id }
                if (staleItems.isNotEmpty()) {
                    offlineDao.deleteByIds(staleItems.map { it.id })
                    Log.w("MemberRepository", "Dropped ${staleItems.size} offline location(s) queued under a different account")
                }
                if (ownItems.isEmpty()) return@withLock Result.success(0)

                var successfulCount = 0
                val itemsToDelete = mutableListOf<Int>()

                for (item in ownItems) {
                    try {
                        val payload = mapOf(
                            "latitude" to item.latitude,
                            "longitude" to item.longitude,
                            "accuracy" to item.accuracy,
                            "speed" to item.speed,
                            "recorded_at" to item.recordedAt
                        ) + deviceInfoFields()
                        val res = apiService.updateLocation(payload)
                        if (res.isSuccessful && res.body()?.success == true) {
                            successfulCount++
                            itemsToDelete.add(item.id)
                        } else {
                            // A failure here (e.g. revoked token) will fail identically
                            // for every remaining item -- stop instead of hammering the
                            // server, leaving the queue intact to retry next pass.
                            val errMsg = errorMessageOrDefault(res, "HTTP ${res.code()}")
                            Log.w("MemberRepository", "Sync item rejected: $errMsg")
                            activityLogRepository.log("sync_failed", success = false, message = "Offline sync rejected by server: $errMsg")
                            break
                        }
                    } catch (e: CancellationException) {
                        // Propagate -- e.g. the WorkManager job running this sync
                        // was itself cancelled/timed out. Whatever was already
                        // deleted below stays deleted; the rest retry next pass.
                        throw e
                    } catch (e: Exception) {
                        Log.e("MemberRepository", "Failed sync iteration: ${e.message}")
                        activityLogRepository.log("sync_failed", success = false, message = "Offline sync error: ${e.message}")
                        break
                    }
                }

                if (itemsToDelete.isNotEmpty()) {
                    offlineDao.deleteByIds(itemsToDelete)
                    lastSyncTime = WibTime.nowShort()
                }

                Result.success(successfulCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MemberRepository", "syncOfflineLocations failed (storage error): ${e.message}")
                Result.failure(e)
            }
        }
    }

    // --- Admin Screen Actions ---

    suspend fun getMemberList(): Result<List<UserDto>> =
        apiCall("Failed to load member list.", default = { emptyList() }) { apiService.getMemberList() }

    suspend fun getCurrentLocations(): Result<List<MemberCurrentLocationDto>> =
        apiCall("Failed to load member locations.", default = { emptyList() }) { apiService.getCurrentLocations() }

    suspend fun getLocationHistory(userId: Int, date: String): Result<HistoryResponseDto> =
        apiCall("Failed to load trip history.") { apiService.getLocationHistory(userId, date) }

    /**
     * Which dates within [month] ("yyyy-MM") have recorded GPS history for
     * [userId] -- backs the date picker's hint. Just date strings, not full
     * history, so it stays cheap on every calendar month change.
     */
    suspend fun getLocationHistoryDates(userId: Int, month: String): Result<List<String>> =
        apiCall(
            "Failed to load history calendar.",
            default = { HistoryDatesResponseDto(userId, month, emptyList()) }
        ) { apiService.getLocationHistoryDates(userId, month) }.map { it.dates }
}
