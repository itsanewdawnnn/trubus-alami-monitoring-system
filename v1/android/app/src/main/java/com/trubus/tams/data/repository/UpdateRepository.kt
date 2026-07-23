package com.trubus.tams.data.repository

import com.trubus.tams.data.api.ApiService
import com.trubus.tams.data.model.VersionInfoDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the currently-published app version from the server -- backed by
 * web/database/schema.sql's tams_ota_update table, managed entirely
 * through the Admin Panel's "OTA Update" page (web/pages/ota_update.php).
 *
 * Kept separate from [MemberRepository] on purpose: a version check must
 * work even before the user has ever logged in (no auth token yet, and a
 * force-update needs to be able to block the app pre-login), and folding an
 * unauthenticated, update-only concern into the auth-heavy MemberRepository
 * would blur what that class is responsible for. [MainViewModel] owns one
 * instance of each side by side, the same way it already owns [MemberRepository].
 */
class UpdateRepository(private val baseUrlProvider: () -> String) {

    /**
     * Fetches the server's current version info. Returns [Result.failure]
     * for any network/parse/server error -- callers should treat that as
     * "couldn't check right now", not as "no update available": staying
     * silent and letting the app continue is the correct behavior for a
     * background version check (see MainViewModel.checkForUpdate's doc for
     * why this must never block normal app usage on its own).
     */
    suspend fun fetchLatestVersion(): Result<VersionInfoDto> = withContext(Dispatchers.IO) {
        try {
            // Built fresh per call instead of cached like MemberRepository's
            // apiService -- version checks happen at most once per app
            // launch, so the minor cost of constructing a new OkHttpClient
            // here is not worth the extra rebuild-on-baseUrl-change plumbing
            // MemberRepository needs for its much hotter call paths.
            val service = ApiService.create(baseUrlProvider()) { null }
            val response = service.getAppVersion()
            val body = response.body()
            if (response.isSuccessful && body?.success == true && body.data != null) {
                Result.success(body.data)
            } else {
                Result.failure(Exception(body?.message ?: "Failed to check app version (HTTP ${response.code()})"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
