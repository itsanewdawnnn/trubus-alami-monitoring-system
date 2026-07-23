package com.trubus.tams.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

// --- REST API Response Models ---

@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T?
)

@JsonClass(generateAdapter = true)
data class LoginData(
    val token: String,
    val user: UserDto
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: Int,
    val name: String,
    val note: String,
    val username: String,
    val role: String // 'member' or 'admin'
)

@JsonClass(generateAdapter = true)
data class LocationUpdateResponse(
    val user_id: Int,
    val latitude: Double?,
    val longitude: Double?,
    val is_moving: Boolean = false,
    val updated_at: String
)

@JsonClass(generateAdapter = true)
data class MemberCurrentLocationDto(
    val user_id: Int,
    val name: String,
    val note: String,
    val username: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val is_moving: Boolean = false,
    val updated_at: String?,
    val status: String // 'active' or 'offline'
)

@JsonClass(generateAdapter = true)
data class HistoryResponseDto(
    val user_id: Int,
    val date: String,
    val total_points: Int,
    val total_distance_km: Double,
    val start_time: String?,
    val end_time: String?,
    val duration_seconds: Long,
    val duration_formatted: String,
    val points: List<HistoryPointDto>
)

@JsonClass(generateAdapter = true)
data class HistoryPointDto(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val is_moving: Boolean = false,
    val recorded_at: String
)

// Backs the date picker's "which dates have data" hint -- just a list of
// date strings, not full history points, so checking a month stays cheap.
@JsonClass(generateAdapter = true)
data class HistoryDatesResponseDto(
    val user_id: Int,
    val month: String,
    val dates: List<String>
)

// Backs the OTA update feature (see data/repository/UpdateRepository.kt).
// Field names are snake_case to match every other DTO in this file (all
// JSON keys are consumed as-is, no @Json(name=) remapping anywhere in this
// codebase) -- deliberately NOT the camelCase shown in the original OTA
// feature spec, to keep this the only endpoint with different casing.
// Source of truth: web/database/schema.sql's tams_ota_update table,
// edited exclusively through the Admin Panel's "OTA Update" page.
@JsonClass(generateAdapter = true)
data class VersionInfoDto(
    val version_code: Int,
    val version_name: String,
    val force_update: Boolean,
    val apk_url: String,
    val release_notes: List<String> = emptyList()
)

// Backs GET /location/status -- see backend/api.php's route doc comment for
// what this serves (Force Override pre-flight). tracking_allowed already
// folds force_override together with the current server-clock window, so
// callers that only care about "can I press Start right now" don't need to
// re-derive it themselves.
@JsonClass(generateAdapter = true)
data class LocationStatusDto(
    val tracking_allowed: Boolean,
    val force_override: Boolean,
    val operational_hours_start: String,
    val operational_hours_end: String
)

// --- UI State Models ---

/**
 * Typed snapshot of the last GPS fix shown on the Member dashboard.
 * Replaces an earlier `Map<String, Any>` with unchecked casts that risked
 * a ClassCastException crash at render time on any mismatched key/type.
 */
data class TrackedLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val time: String
)

// --- Local Room Entities ---

// Queues a location update that failed to send due to connectivity loss.
@Entity(tableName = "tams_offline_locations")
data class OfflineLocation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val recordedAt: String,
    val timestamp: Long = System.currentTimeMillis()
)
