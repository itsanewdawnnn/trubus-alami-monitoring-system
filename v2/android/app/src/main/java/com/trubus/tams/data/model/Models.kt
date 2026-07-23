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

// Backs GET /outlet/list (Member-scoped: outlets this Member created or was
// assigned to, see backend/api.php's own route comment). Deliberately has no
// creator_name/creator_role field the way the Web Admin's ajax/outlet_list.php
// response does -- that endpoint needs them to build a "NAMA: OUTLET" label
// distinguishing many members' outlets in one shared table; here, every
// outlet already belongs to (or is assigned to) the one Member viewing this
// list, so the raw `name` is shown as-is (see ui/screens/OutletScreen.kt).
// `is_own_outlet` is what actually gates showing Edit/Delete -- an
// Admin-assigned outlet this Member didn't create is view-only, matching
// backend/api.php's own created_by_user_id scoping on /outlet/update and
// /outlet/delete (editing/deleting it would just 404).
@JsonClass(generateAdapter = true)
data class OutletDto(
    val id: Int,
    val name: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val status: String, // PENDING | APPROVED | REJECTED
    val rejection_reason: String?,
    val has_pending_edit: Boolean,
    // Distinct from `rejection_reason` above (which only ever describes the
    // OUTLET's own PENDING->REJECTED transition): this is the reason an
    // Admin gave when rejecting a proposed EDIT to an already-APPROVED
    // outlet (tams_outlet_edit_requests' own rejection_reason). Non-null
    // only when the most recent edit request for this outlet was rejected
    // and nothing newer has been approved since -- see backend/api.php's
    // /outlet/list doc comment for the exact query semantics. Nullable with
    // no default, same idiom as `rejection_reason` -- Moshi codegen assigns
    // null automatically when the key is absent.
    val last_edit_rejection_reason: String?,
    val is_own_outlet: Boolean,
    val created_at: String
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
// isMock/gnssSatellitesUsed are carried through the offline queue so a fix's
// mock-location trust signals (see MemberLocationService.handleNewLocation's
// doc comment) survive an offline retry instead of being lost the moment a
// fix couldn't be sent immediately -- MemberRepository.syncOfflineLocations
// reads them back when finally flushing this row.
@Entity(tableName = "tams_offline_locations")
data class OfflineLocation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val recordedAt: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMock: Boolean = false,
    val gnssSatellitesUsed: Int? = null
)
