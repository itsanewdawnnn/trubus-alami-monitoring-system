package com.trubus.tams.data.repository

import android.util.Log
import com.trubus.tams.data.api.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fire-and-forget writer for the Log (Member activity audit trail) feature
 * -- backed by web/database/schema.sql's tams_member_log table, POSTed to
 * backend/api.php's /activity/log route, and displayed on the Admin Panel's
 * "Member Log" page (web/pages/member_log.php).
 *
 * Every call is best-effort: a failed write here must never surface to the
 * caller as an app-visible error, retry, or offline queue -- the activity
 * being logged (a profile change, a Start/Stop press, a sync failure) has
 * already happened (or already failed) on its own terms by the time this is
 * called, and audit-trail delivery isn't important enough to justify any
 * complexity (offline queueing, retry backoff) beyond "try once, log
 * locally on failure, move on". [log] therefore never throws (except
 * [CancellationException], which is always propagated) and returns nothing
 * callers need to check.
 *
 * [actionType] MUST be one of backend/api.php's POST /activity/log
 * `$allowedActionTypes` ('profile_update', 'start_location', 'stop_location',
 * 'sync_failed', 'error') -- an unrecognized value is rejected server-side
 * with a 400, which this class simply logs locally and discards like any
 * other failure.
 *
 * NEVER pass a password (or any other sensitive value) as [fieldBefore]/
 * [fieldAfter]/[message] -- per the Log feature's security requirement,
 * passwords must never be stored anywhere, even in an audit trail. A
 * password change is logged as the bare fact "password was changed", never
 * the value (see MainViewModel.updateProfile's call site).
 */
class ActivityLogRepository(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?
) {
    companion object {
        private const val TAG = "ActivityLogRepository"
    }

    suspend fun log(
        actionType: String,
        success: Boolean,
        fieldBefore: String? = null,
        fieldAfter: String? = null,
        message: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        try {
            // Built fresh per call, same rationale as UpdateRepository/
            // RemoteConfigRepository's own ApiService.create() calls -- log
            // writes are discrete, infrequent events (a profile save, a
            // Start/Stop tap), not a hot path, so the minor cost of a new
            // OkHttpClient here isn't worth extra caching plumbing.
            val service = ApiService.create(baseUrlProvider()) { tokenProvider() }
            val payload = mutableMapOf<String, Any>(
                "action_type" to actionType,
                "status" to if (success) "success" else "failed"
            )
            fieldBefore?.let { payload["field_before"] = it }
            fieldAfter?.let { payload["field_after"] = it }
            message?.let { payload["message"] = it }

            val response = service.logActivity(payload)
            if (!(response.isSuccessful && response.body()?.success == true)) {
                Log.w(TAG, "Activity log write rejected by server (HTTP ${response.code()}): $actionType")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Activity log write failed for '$actionType': ${e.message}")
        }
    }
}
