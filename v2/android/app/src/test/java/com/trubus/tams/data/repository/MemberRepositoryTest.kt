package com.trubus.tams.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trubus.tams.data.model.ApiResponse
import com.trubus.tams.data.model.LocationUpdateResponse
import com.trubus.tams.data.model.LoginData
import com.trubus.tams.data.model.UserDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * [MemberRepository] needs a real (shadowed) [Context]/`SharedPreferences`
 * (backing `lastKnownLocation`/`currentUser`/etc.), hence Robolectric --
 * see that class's own constructor doc comment for what's faked instead
 * ([FakeOfflineLocationDao] in place of Room, [FakeApiService] in place of
 * real network).
 *
 * Pinned to a specific Robolectric-supported SDK level, independent of this
 * app's own compileSdk/targetSdk -- Robolectric's shadow support for the
 * newest Android versions typically lags behind the current SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowWorkManagerInitializer::class])
class MemberRepositoryTest {

    private lateinit var fakeDao: FakeOfflineLocationDao
    private lateinit var fakeApi: FakeApiService
    private lateinit var repository: MemberRepository

    private val testUser = UserDto(id = 42, name = "Test User", note = "", username = "testuser", role = "member")

    @Before
    fun setUp() = runTest {
        fakeDao = FakeOfflineLocationDao()
        fakeApi = FakeApiService()
        repository = MemberRepository(
            context = ApplicationProvider.getApplicationContext<Context>(),
            offlineDaoOverride = fakeDao,
            apiServiceOverride = fakeApi
        )
        // Establishes currentUser/authToken through the real login() path
        // (a fake login response), rather than reaching for a private
        // setter -- postLocation() requires a logged-in currentUser.
        fakeApi.loginResponse = Response.success(
            ApiResponse(success = true, message = "OK", data = LoginData(token = "test-token", user = testUser))
        )
        val result = repository.login("testuser", "password")
        assertTrue("test setup: login() must succeed", result.isSuccess)
    }

    private fun jsonError(message: String, errorCode: String? = null): okhttp3.ResponseBody {
        val body = buildString {
            append("{\"success\":false,\"message\":\"$message\"")
            if (errorCode != null) append(",\"error_code\":\"$errorCode\"")
            append("}")
        }
        return body.toResponseBody("application/json".toMediaType())
    }

    private fun successResponse(): Response<ApiResponse<LocationUpdateResponse>> =
        Response.success(
            ApiResponse(
                success = true,
                message = "OK",
                data = LocationUpdateResponse(user_id = testUser.id, latitude = 0.0, longitude = 0.0, is_moving = false, updated_at = "2024-01-01 10:00:00")
            )
        )

    // --- lastKnownLocation recency guard ---

    @Test
    fun `postLocation saves the first fix unconditionally`() = runTest {
        fakeApi.updateLocationHandler = { successResponse() }

        repository.postLocation(1.0, 2.0, 5f, 0f, "2024-01-01 10:00:00")

        assertEquals("2024-01-01 10:00:00", repository.lastKnownLocation?.time)
    }

    @Test
    fun `postLocation ignores an older fix that arrives after a newer one`() = runTest {
        fakeApi.updateLocationHandler = { successResponse() }

        repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:05:00")
        // Simulates LocationSyncWorker's one-shot stopgap fix landing after
        // MemberLocationService's own newer live fix already won the race.
        repository.postLocation(2.0, 2.0, 5f, 0f, "2024-01-01 10:00:00")

        val stored = repository.lastKnownLocation
        assertEquals("2024-01-01 10:05:00", stored?.time)
        assertEquals(1.0, stored?.latitude)
    }

    @Test
    fun `postLocation accepts a fix with the same timestamp as a corrected reading`() = runTest {
        fakeApi.updateLocationHandler = { successResponse() }

        repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")
        repository.postLocation(2.0, 2.0, 5f, 0f, "2024-01-01 10:00:00")

        // newFixMillis >= existingFixMillis (equal) still wins -- a same-
        // recorded_at correction is not blocked.
        assertEquals(2.0, repository.lastKnownLocation?.latitude)
    }

    @Test
    fun `postLocation always saves when the new fix timestamp is unparseable`() = runTest {
        fakeApi.updateLocationHandler = { successResponse() }

        repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")
        repository.postLocation(2.0, 2.0, 5f, 0f, "not-a-real-timestamp")

        assertEquals("not-a-real-timestamp", repository.lastKnownLocation?.time)
    }

    // --- write-ahead offline queue ---

    @Test
    fun `postLocation writes the fix to the queue before attempting the network call`() = runTest {
        var queueSizeDuringNetworkCall = -1
        fakeApi.updateLocationHandler = { _ ->
            queueSizeDuringNetworkCall = fakeDao.getAll().size
            successResponse()
        }

        repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")

        assertEquals("row must already be queued before the network attempt", 1, queueSizeDuringNetworkCall)
    }

    @Test
    fun `postLocation removes the queued row once the upload succeeds`() = runTest {
        fakeApi.updateLocationHandler = { successResponse() }

        repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")

        assertTrue("queue must be empty after a confirmed delivery", fakeDao.snapshotForTest().isEmpty())
    }

    @Test
    fun `postLocation keeps the queued row on a transient 5xx failure`() = runTest {
        fakeApi.updateLocationHandler = {
            Response.error(500, jsonError("Server error"))
        }

        val result = repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")

        assertTrue(result.isFailure)
        assertEquals(1, fakeDao.snapshotForTest().size)
    }

    @Test
    fun `postLocation drops the queued row on a non-retryable 4xx failure`() = runTest {
        fakeApi.updateLocationHandler = {
            Response.error(400, jsonError("Bad request"))
        }

        val result = repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")

        assertTrue(result.isFailure)
        assertTrue("a non-retryable failure must not grow the queue forever", fakeDao.snapshotForTest().isEmpty())
    }

    @Test
    fun `postLocation surfaces TrackingNotAllowedException for a Force Location rejection`() = runTest {
        fakeApi.updateLocationHandler = {
            Response.error(403, jsonError("Outside operational hours", errorCode = "outside_operational_hours"))
        }

        val result = repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")

        assertTrue(result.exceptionOrNull() is TrackingNotAllowedException)
        assertTrue(fakeDao.snapshotForTest().isEmpty())
    }

    @Test
    fun `postLocation surfaces SessionInvalidException for a dead token`() = runTest {
        fakeApi.updateLocationHandler = {
            Response.error(401, jsonError("Invalid token"))
        }

        val result = repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")

        assertTrue(result.exceptionOrNull() is SessionInvalidException)
        assertTrue(fakeDao.snapshotForTest().isEmpty())
    }

    @Test
    fun `postLocation keeps the queued row when the network call throws`() = runTest {
        fakeApi.updateLocationHandler = { throw java.io.IOException("network unreachable") }

        val result = repository.postLocation(1.0, 1.0, 5f, 0f, "2024-01-01 10:00:00")

        assertTrue(result.isFailure)
        assertEquals(1, fakeDao.snapshotForTest().size)
    }

    @Test
    fun `syncOfflineLocations drains queued rows for the current user on success`() = runTest {
        fakeDao.insert(
            com.trubus.tams.data.model.OfflineLocation(
                userId = testUser.id, latitude = 1.0, longitude = 1.0,
                accuracy = 5f, speed = 0f, recordedAt = "2024-01-01 09:00:00"
            )
        )
        fakeDao.insert(
            com.trubus.tams.data.model.OfflineLocation(
                userId = testUser.id, latitude = 2.0, longitude = 2.0,
                accuracy = 5f, speed = 0f, recordedAt = "2024-01-01 09:01:00"
            )
        )
        fakeApi.updateLocationHandler = { successResponse() }

        val result = repository.syncOfflineLocations()

        assertEquals(2, result.getOrNull())
        assertTrue(fakeDao.snapshotForTest().isEmpty())
    }
}
