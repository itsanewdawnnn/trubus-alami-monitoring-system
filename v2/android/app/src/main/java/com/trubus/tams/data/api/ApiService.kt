package com.trubus.tams.data.api

import com.trubus.tams.BuildConfig
import com.trubus.tams.data.model.*
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/**
 * Moshi has no built-in adapter for kotlin.Unit -- it isn't a data class
 * Moshi's reflection/codegen can introspect, so any route whose response
 * data is `ApiResponse<Unit>` (logActivity, logout: both server routes that
 * return `"data": null` with nothing meaningful to deserialize) fails to
 * even build a converter, throwing "Unable to create converter for
 * ApiResponse<kotlin.Unit>" the first time either is called.
 *
 * Extends JsonAdapter<Unit> directly rather than the usual @FromJson/@ToJson
 * annotated-method style: Kotlin compiles a plain `fun f(): Unit` to a JVM
 * `void` return, which Moshi's reflective method-signature matcher
 * (AdapterMethodsFactory) rejects outright ("Unexpected signature") the
 * moment Moshi.Builder.add() inspects it -- a startup crash, not just a
 * failed request. Overriding JsonAdapter<Unit>'s abstract fromJson(...)
 * instead forces the correct generic-erasure bytecode signature (returning
 * the boxed Unit object, not void), since Kotlin must satisfy the
 * superclass's type-erased `Object fromJson(JsonReader)` contract.
 */
private class UnitJsonAdapter : JsonAdapter<Unit>() {
    override fun fromJson(reader: JsonReader) {
        reader.skipValue()
    }

    override fun toJson(writer: JsonWriter, value: Unit?) {
        writer.nullValue()
    }
}

interface ApiService {
    @POST("api.php?route=/auth/login")
    suspend fun login(
        @Body body: Map<String, String>
    ): Response<ApiResponse<LoginData>>

    @POST("api.php?route=/auth/logout")
    suspend fun logout(): Response<ApiResponse<Unit>>

    // Cheapest authenticated route (returns only what's already cached
    // locally, nothing computed) -- reused by MemberRepository.validateSession()
    // as the cold-start "is this token still valid AND is the server
    // reachable" check, see that function's doc comment.
    @GET("api.php?route=/profile")
    suspend fun getProfile(): Response<ApiResponse<UserDto>>

    // "password" key is only included by the caller when the member
    // actually wants to change it -- see MemberRepository.updateProfile.
    @POST("api.php?route=/profile/update")
    suspend fun updateProfile(
        @Body body: Map<String, String>
    ): Response<ApiResponse<UserDto>>

    // Backs the Log feature's audit trail (see data/repository/ActivityLogRepository.kt).
    // Authenticated like every other route below -- user_id/user_name are
    // always derived server-side from the bearer token, never trusted from
    // this body (see backend/api.php's /activity/log doc comment).
    @POST("api.php?route=/activity/log")
    suspend fun logActivity(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ApiResponse<Unit>>

    // Force Override pre-flight -- see backend/api.php's /location/status
    // route doc comment. Purely a UX convenience: POST /location/update
    // enforces the operational-hours gate unconditionally on every call
    // regardless of whether this was ever called, so this route can never
    // be the actual security boundary.
    @GET("api.php?route=/location/status")
    suspend fun getLocationStatus(): Response<ApiResponse<LocationStatusDto>>

    @POST("api.php?route=/location/update")
    suspend fun updateLocation(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ApiResponse<LocationUpdateResponse>>

    @GET("api.php?route=/location/current")
    suspend fun getCurrentLocations(): Response<ApiResponse<List<MemberCurrentLocationDto>>>

    @GET("api.php?route=/location/history")
    suspend fun getLocationHistory(
        @Query("user_id") userId: Int,
        @Query("date") date: String
    ): Response<ApiResponse<HistoryResponseDto>>

    @GET("api.php?route=/location/history/dates")
    suspend fun getLocationHistoryDates(
        @Query("user_id") userId: Int,
        @Query("month") month: String
    ): Response<ApiResponse<HistoryDatesResponseDto>>

    @GET("api.php?route=/member/list")
    suspend fun getMemberList(): Response<ApiResponse<List<UserDto>>>

    // --- Outlet Management (Member Role) ---
    // See backend/api.php's own "Outlet Management" route comment: Approve/
    // Reject/Merge are deliberately absent here -- those are Admin-only
    // actions the Web Admin performs by writing directly to the database
    // (root CLAUDE.md), never through this API. Everything below is what a
    // Member is allowed to do for their own (or Admin-assigned) outlets.

    @GET("api.php?route=/outlet/list")
    suspend fun getOutlets(): Response<ApiResponse<List<OutletDto>>>

    // ApiResponse<Unit> for create/update/delete -- same idiom as login()/
    // logActivity() above: the response's `data` (an outlet id/status
    // snapshot, or null for delete) is never read back client-side, since
    // MemberRepository always re-fetches getOutlets() after a successful
    // mutation instead of stitching a partial response back into a full
    // OutletDto (see MainViewModel's fetchOutlets() calls after each
    // mutation). UnitJsonAdapter above skips whatever value.data JSON is
    // just fine either way.
    @POST("api.php?route=/outlet/create")
    suspend fun createOutlet(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ApiResponse<Unit>>

    @POST("api.php?route=/outlet/update")
    suspend fun updateOutlet(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ApiResponse<Unit>>

    @POST("api.php?route=/outlet/delete")
    suspend fun deleteOutlet(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<ApiResponse<Unit>>

    // Deliberately excluded from the auth interceptor's concern -- this
    // route needs no Authorization header (see backend/api.php's /app/version,
    // which is unauthenticated on purpose) since it must be checkable before
    // login for a force-update block to work at all. No @Headers exclusion
    // needed here: the interceptor already only attaches the header when
    // tokenProvider() returns a non-null/non-empty token, so a caller that
    // passes a provider returning null (see UpdateRepository) simply sends
    // no Authorization header, same effect.
    @GET("api.php?route=/app/version")
    suspend fun getAppVersion(): Response<ApiResponse<VersionInfoDto>>

    // Deliberately unauthenticated, same rationale as getAppVersion() above --
    // Remote Management values must be readable independent of login state
    // (see backend/api.php's /app/config route doc comment). Map<String,String>
    // rather than a fixed @JsonClass DTO: tams_remote_management is an open-ended
    // key/value table (helpers/functions.php's remote_management_definitions()),
    // so a new setting added server-side is picked up here with no client
    // release required, matching the whole point of Remote Management.
    @GET("api.php?route=/app/config")
    suspend fun getAppConfig(): Response<ApiResponse<Map<String, String>>>

    companion object {
        // Shared by every OkHttpClient built below. MemberRepository,
        // RemoteConfigRepository, ActivityLogRepository, and UpdateRepository
        // each call create() with their own tokenProvider (so each still gets
        // its own OkHttpClient/Retrofit/ApiService instance -- that part is
        // unchanged), but without this, each of those clients also got its
        // own ConnectionPool and Dispatcher (thread pool) by default: every
        // one of those call sites talks to the same host, so separate pools
        // meant no warm keep-alive connection was ever reused between them --
        // an activity-log write right after a location upload paid for a
        // fresh TCP+TLS handshake instead of reusing the connection that
        // upload just opened. Real cost on the unstable/cellular connections
        // this app targets. Sharing just the pool + dispatcher (not the
        // client itself) keeps per-instance behavior (timeouts, interceptors,
        // auth) exactly as before.
        private val sharedConnectionPool = okhttp3.ConnectionPool()
        private val sharedDispatcher = okhttp3.Dispatcher()

        // Stateless -- has no dependency on baseUrl/tokenProvider, unlike the
        // OkHttpClient/Retrofit built below (which legitimately differ per
        // caller). Building a fresh Moshi.Builder()/adapter graph on every
        // create() call was pure waste: MemberRepository, RemoteConfigRepository,
        // ActivityLogRepository, and UpdateRepository each call create() (and
        // LocationSyncWorker's watchdog re-constructs a MemberRepository, and
        // so a fresh Moshi, every ~3-minute pass while a Member is tracking).
        // Shared by reference across all of them; Moshi adapters are
        // immutable/thread-safe once built.
        private val sharedMoshi: Moshi by lazy {
            Moshi.Builder()
                .add(Unit::class.java, UnitJsonAdapter())
                .build()
        }

        fun create(baseUrl: String, tokenProvider: () -> String?): ApiService {
            // Clean up base URL
            val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            // BODY-level logging prints the Authorization header and raw GPS
            // coordinates -- must never ship in release (logcat token leak
            // plus needless overhead on the low-end devices this app targets).
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            }

            val client = OkHttpClient.Builder()
                .connectionPool(sharedConnectionPool)
                .dispatcher(sharedDispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val requestBuilder = chain.request().newBuilder()
                    tokenProvider()?.let { token ->
                        if (token.isNotEmpty()) {
                            requestBuilder.addHeader("Authorization", "Bearer $token")
                        }
                    }
                    chain.proceed(requestBuilder.build())
                }
                .addInterceptor(logging)
                .build()

            // No reflection-based adapter factory here on purpose: every model
            // is @JsonClass(generateAdapter = true), so moshi-kotlin-codegen
            // (KSP) already generates real adapters at compile time. Adding
            // KotlinJsonAdapterFactory would pull in kotlin-reflect as dead
            // weight, inflating APK size on the low-end devices this app targets.
            // UnitJsonAdapter is the one exception -- a single hand-written
            // adapter (not a reflective factory) for the one type codegen
            // can never cover, see its own doc comment. See sharedMoshi above
            // for why this is reused rather than rebuilt per call.
            return Retrofit.Builder()
                .baseUrl(cleanUrl)
                .addConverterFactory(MoshiConverterFactory.create(sharedMoshi))
                .client(client)
                .build()
                .create(ApiService::class.java)
        }
    }
}
