package com.trubus.tams.data.repository

import com.trubus.tams.data.api.ApiService
import com.trubus.tams.data.model.ApiResponse
import com.trubus.tams.data.model.HistoryDatesResponseDto
import com.trubus.tams.data.model.HistoryResponseDto
import com.trubus.tams.data.model.LocationStatusDto
import com.trubus.tams.data.model.LocationUpdateResponse
import com.trubus.tams.data.model.LoginData
import com.trubus.tams.data.model.MemberCurrentLocationDto
import com.trubus.tams.data.model.OutletDto
import com.trubus.tams.data.model.UserDto
import com.trubus.tams.data.model.VersionInfoDto
import retrofit2.Response

/**
 * Every [ApiService] route throws -- used only as [FakeApiService]'s
 * delegate so a test only has to override the 1-2 routes it actually
 * exercises (Kotlin's `by` delegation auto-forwards everything else here).
 * A test that reaches an un-stubbed route has a bug in its own setup and
 * should fail loudly, not silently get back a canned empty response.
 */
private class UnsupportedApiService : ApiService {
    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("ApiService.$name was not expected to be called in this test")

    override suspend fun login(body: Map<String, String>): Response<ApiResponse<LoginData>> = unsupported("login")
    override suspend fun logout(): Response<ApiResponse<Unit>> = unsupported("logout")
    override suspend fun getProfile(): Response<ApiResponse<UserDto>> = unsupported("getProfile")
    override suspend fun updateProfile(body: Map<String, String>): Response<ApiResponse<UserDto>> = unsupported("updateProfile")
    override suspend fun logActivity(body: Map<String, Any>): Response<ApiResponse<Unit>> = unsupported("logActivity")
    override suspend fun getLocationStatus(): Response<ApiResponse<LocationStatusDto>> = unsupported("getLocationStatus")
    override suspend fun updateLocation(body: Map<String, Any>): Response<ApiResponse<LocationUpdateResponse>> = unsupported("updateLocation")
    override suspend fun getCurrentLocations(): Response<ApiResponse<List<MemberCurrentLocationDto>>> = unsupported("getCurrentLocations")
    override suspend fun getLocationHistory(userId: Int, date: String): Response<ApiResponse<HistoryResponseDto>> = unsupported("getLocationHistory")
    override suspend fun getLocationHistoryDates(userId: Int, month: String): Response<ApiResponse<HistoryDatesResponseDto>> = unsupported("getLocationHistoryDates")
    override suspend fun getMemberList(): Response<ApiResponse<List<UserDto>>> = unsupported("getMemberList")
    override suspend fun getOutlets(): Response<ApiResponse<List<OutletDto>>> = unsupported("getOutlets")
    override suspend fun createOutlet(body: Map<String, Any>): Response<ApiResponse<Unit>> = unsupported("createOutlet")
    override suspend fun updateOutlet(body: Map<String, Any>): Response<ApiResponse<Unit>> = unsupported("updateOutlet")
    override suspend fun deleteOutlet(body: Map<String, Any>): Response<ApiResponse<Unit>> = unsupported("deleteOutlet")
    override suspend fun reorderOutlets(body: Map<String, Any>): Response<ApiResponse<Unit>> = unsupported("reorderOutlets")
    override suspend fun getAppVersion(): Response<ApiResponse<VersionInfoDto>> = unsupported("getAppVersion")
    override suspend fun getAppConfig(): Response<ApiResponse<Map<String, String>>> = unsupported("getAppConfig")
}

/**
 * Hand-written fake [ApiService] for [MemberRepositoryTest] -- avoids a real
 * network stack (MockWebServer, real OkHttp/Retrofit) entirely, since every
 * route MemberRepository calls is a plain suspend function returning a
 * [Response] this class can construct directly with
 * `retrofit2.Response.success`/`.error`. Delegates every method it doesn't
 * override to [UnsupportedApiService] so a test only wires up the routes it
 * actually exercises.
 */
class FakeApiService(
    private val delegate: ApiService = UnsupportedApiService()
) : ApiService by delegate {

    var loginResponse: Response<ApiResponse<LoginData>>? = null

    // A suspend lambda, not a plain canned Response, so a test can inspect
    // side effects (e.g. the write-ahead queue's current contents) at the
    // exact moment the network call would have happened -- the whole point
    // of proving the insert really does happen BEFORE this is ever called.
    var updateLocationHandler: (suspend (Map<String, Any>) -> Response<ApiResponse<LocationUpdateResponse>>)? = null

    override suspend fun login(body: Map<String, String>): Response<ApiResponse<LoginData>> =
        loginResponse ?: throw UnsupportedOperationException("FakeApiService.loginResponse was not set for this test")

    override suspend fun updateLocation(body: Map<String, Any>): Response<ApiResponse<LocationUpdateResponse>> =
        updateLocationHandler?.invoke(body)
            ?: throw UnsupportedOperationException("FakeApiService.updateLocationHandler was not set for this test")
}
