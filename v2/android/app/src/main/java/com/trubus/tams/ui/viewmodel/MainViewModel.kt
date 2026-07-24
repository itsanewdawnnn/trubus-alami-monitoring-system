package com.trubus.tams.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trubus.tams.R
import androidx.work.*
import com.trubus.tams.BuildConfig
import com.trubus.tams.data.model.HistoryResponseDto
import com.trubus.tams.data.model.MemberCurrentLocationDto
import com.trubus.tams.data.model.OutletDto
import com.trubus.tams.data.model.TrackedLocationSnapshot
import com.trubus.tams.data.model.UserDto
import com.trubus.tams.data.repository.ActivityLogRepository
import com.trubus.tams.data.repository.MemberRepository
import com.trubus.tams.data.repository.RemoteConfigRepository
import com.trubus.tams.data.repository.SessionInvalidException
import com.trubus.tams.data.repository.UpdateRepository
import com.trubus.tams.data.update.UpdateFlowState
import com.trubus.tams.data.update.UpdateManager
import com.trubus.tams.service.MemberLocationService
import com.trubus.tams.util.WibTime
import com.trubus.tams.worker.LocationSyncWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.Date

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = MemberRepository(application)
    private val workManager = WorkManager.getInstance(application)

    // Password length bounds (used by updateProfile()'s client-side check
    // below) now live in Remote Management (tams_remote_management), not a
    // hardcoded constant here -- see RemoteConfigRepository's doc comment.
    // Same instance-per-repository pattern as `activityLogRepository` below.
    private val remoteConfigRepository = RemoteConfigRepository(application) { repository.baseUrl }

    // Log (Member activity audit trail) -- see ActivityLogRepository's doc
    // comment. Every call from this ViewModel is fire-and-forget; failures
    // never surface to the UI.
    private val activityLogRepository = ActivityLogRepository(
        baseUrlProvider = { repository.baseUrl },
        tokenProvider = { repository.authToken },
    )

    // --- OTA Update ---
    // updateManager is intentionally private -- ui/screens/ only ever talks
    // to this ViewModel (see CLAUDE.md's architecture note), so its state is
    // forwarded below as this ViewModel's own StateFlow, same composition
    // pattern already used for `repository`.
    private val updateManager = UpdateManager(application, UpdateRepository { repository.baseUrl })
    val updateState: StateFlow<UpdateFlowState> = updateManager.state
    private var updateDownloadJob: Job? = null

    // --- Authentication States ---
    // Never seeded true from a locally stored token alone -- only
    // validateSessionOnStartup() (below) is allowed to flip this to true,
    // and only after the server has actually confirmed the token is still
    // valid. See that function's doc comment for why.
    private val _isLoggedIn = MutableStateFlow(value = false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _user = MutableStateFlow(repository.currentUser)
    val user: StateFlow<UserDto?> = _user.asStateFlow()

    // True from process start until validateSessionOnStartup()'s check
    // resolves -- MainAppScreen's splash gate stays up for as long as this
    // is true, so the dashboard/login screen is never shown before the
    // server has had a chance to confirm (or reject) a locally stored
    // token. Seeded false (nothing to validate) when there's no token to
    // check in the first place -- see validateSessionOnStartup().
    private val _isValidatingSession = MutableStateFlow(repository.authToken != null)
    val isValidatingSession: StateFlow<Boolean> = _isValidatingSession.asStateFlow()

    // --- Language Selection ---
    // Reads the current application-wide locale set via AppCompatDelegate
    // (persisted automatically by AndroidX). Defaults to English if none set.
    private val _currentLanguage = MutableStateFlow(
        AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "en" }
    )
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    /**
     * Updates the app's language across all activities and components.
     * Persisted automatically by AndroidX/AppCompat.
     */
    fun setLanguage(languageCode: String) {
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
        _currentLanguage.value = languageCode
    }

    // Shown on the login screen after validateSessionOnStartup() (or a
    // manual retrySessionValidation()) fails -- distinct from [loginError],
    // which only ever comes from a user-initiated login attempt.
    private val _sessionValidationError = MutableStateFlow<String?>(null)
    val sessionValidationError: StateFlow<String?> = _sessionValidationError.asStateFlow()

    // True only when the last validation failure was a connectivity/server
    // problem (the stored token was left in place, since it was never
    // actually confirmed bad) -- offers "Try Again" instead of forcing the
    // user to retype credentials for what might be a transient blip. False
    // after a server-confirmed-invalid token, since the token is gone and a
    // retry would have nothing to check.
    private val _canRetrySessionValidation = MutableStateFlow(value = false)
    val canRetrySessionValidation: StateFlow<Boolean> = _canRetrySessionValidation.asStateFlow()

    private val _isLoading = MutableStateFlow(value = false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    // --- Member Tracking States ---
    // Seed from the persisted flag so the UI reflects reality immediately on
    // app relaunch instead of assuming "stopped" until the next broadcast.
    private val _isTrackingActive = MutableStateFlow(repository.isTrackingEnabled)
    val isTrackingActive: StateFlow<Boolean> = _isTrackingActive.asStateFlow()

    // One-shot notification for MemberDashboard to show as a Toast when
    // MemberLocationService auto-stops tracking on its own (Force Location's
    // ON->OFF auto-revoke behavior -- see handleTrackingNotAllowedByService's
    // doc comment). Null most of the time; MemberDashboard clears it back to
    // null immediately after showing it (consumeTrackingAutoStoppedMessage),
    // same one-shot-event shape as a Snackbar host, so it never re-shows on
    // an unrelated recomposition.
    private val _trackingAutoStoppedMessage = MutableStateFlow<String?>(null)
    val trackingAutoStoppedMessage: StateFlow<String?> = _trackingAutoStoppedMessage.asStateFlow()

    fun consumeTrackingAutoStoppedMessage() {
        _trackingAutoStoppedMessage.value = null
    }

    // Seed from the persisted last fix so reopening the app while tracking
    // runs in the background shows the real location immediately instead of
    // "No location recorded yet" until the next broadcast arrives.
    private val _lastTrackedLocation = MutableStateFlow(repository.lastKnownLocation)
    val lastTrackedLocation: StateFlow<TrackedLocationSnapshot?> = _lastTrackedLocation.asStateFlow()

    // Passive "Last Synced" detail on the Member dashboard -- no
    // manual sync control exists; the offline queue flushes automatically
    // (see MemberRepository.postLocation and the periodic WorkManager job below).
    private val _lastSyncTime = MutableStateFlow(repository.lastSyncTime)
    val lastSyncTime: StateFlow<String> = _lastSyncTime.asStateFlow()

    // Backing state for the "Trip Detail" card. Deliberately fetched
    // from the same /location/history endpoint Admin's Trip History
    // uses (for today's GMT+7 date), not computed client-side -- single source
    // of truth, so a Member always sees the same figures an Admin would.
    // Keeps showing the last trip's numbers after Stop until the next fix.
    private val _memberTripSummary = MutableStateFlow<HistoryResponseDto?>(null)
    val memberTripSummary: StateFlow<HistoryResponseDto?> = _memberTripSummary.asStateFlow()

    fun refreshMemberTripSummary() {
        val currentUserId = _user.value?.id ?: return
        viewModelScope.launch {
            val result = repository.getLocationHistory(currentUserId, WibTime.today())
            // Only update on success -- a network hiccup shouldn't blank out
            // the last successfully fetched summary.
            result.onSuccess { _memberTripSummary.value = it }
        }
    }

    // Manual escape hatch for the battery-optimization card: on some OEM ROMs
    // (observed: iQOO/vivo OriginOS 6), PowerManager#isIgnoringBatteryOptimizations()
    // can keep reporting "not exempt" even after it's genuinely granted. Not
    // the primary path -- only matters when the automatic check + retry is still wrong.
    private val _batteryOptimizationCardDismissed = MutableStateFlow(repository.batteryOptimizationCardDismissed)
    val batteryOptimizationCardDismissed: StateFlow<Boolean> = _batteryOptimizationCardDismissed.asStateFlow()

    fun setBatteryOptimizationCardDismissed(dismissed: Boolean) {
        repository.batteryOptimizationCardDismissed = dismissed
        _batteryOptimizationCardDismissed.value = dismissed
    }

    // --- Profile Editing (Member) ---
    private val _isEditingProfile = MutableStateFlow(false)
    val isEditingProfile: StateFlow<Boolean> = _isEditingProfile.asStateFlow()

    private val _profileUpdateLoading = MutableStateFlow(false)
    val profileUpdateLoading: StateFlow<Boolean> = _profileUpdateLoading.asStateFlow()

    private val _profileUpdateError = MutableStateFlow<String?>(null)
    val profileUpdateError: StateFlow<String?> = _profileUpdateError.asStateFlow()

    // --- Outlet (Member Role) ---
    // Backs ui/screens/OutletScreen.kt (MemberOutletScreen + its list/form
    // sub-screens), reached from MemberRootScreen's "Outlet" tab
    // (MainAppScreen.kt) -- entirely additive, MemberDashboard's own
    // composable and state above are untouched by this section.
    private val _outlets = MutableStateFlow<List<OutletDto>>(emptyList())
    val outlets: StateFlow<List<OutletDto>> = _outlets.asStateFlow()

    private val _outletsLoading = MutableStateFlow(false)
    val outletsLoading: StateFlow<Boolean> = _outletsLoading.asStateFlow()

    // Distinguishes "failed to load" from "genuinely no outlets yet", same
    // rationale as _memberListError below.
    private val _outletsError = MutableStateFlow<String?>(null)
    val outletsError: StateFlow<String?> = _outletsError.asStateFlow()

    // Guards against a slow first fetch landing after a newer one (e.g. the
    // Member bounces Outlet tab -> Dashboard -> Outlet quickly) -- same
    // out-of-order guard as historyFetchJob below.
    private var outletsFetchJob: Job? = null

    // Cancelled on logout() alongside outletsFetchJob above -- without this,
    // a reorder saved right before logging out could still complete (or
    // fail and re-fetch) after _outlets has already been cleared for the
    // next account on a shared device, the same class of bug that section
    // of logout() already guards every other outlet StateFlow against.
    private var outletReorderJob: Job? = null

    fun fetchOutlets() {
        outletsFetchJob?.cancel()
        outletsFetchJob = viewModelScope.launch {
            _outletsLoading.value = true
            _outletsError.value = null
            val result = repository.getOutlets()
            if (result.isSuccess) {
                _outlets.value = result.getOrDefault(emptyList())
            } else {
                _outletsError.value = result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.failed_load_outlets)
            }
            _outletsLoading.value = false
        }
    }

    private val _outletFormLoading = MutableStateFlow(false)
    val outletFormLoading: StateFlow<Boolean> = _outletFormLoading.asStateFlow()

    private val _outletFormError = MutableStateFlow<String?>(null)
    val outletFormError: StateFlow<String?> = _outletFormError.asStateFlow()

    /** Clears a stale error so it never lingers into the next Add/Edit session. */
    fun clearOutletFormError() {
        _outletFormError.value = null
    }

    /**
     * Submits a brand-new outlet. [latitude]/[longitude] are the map picker's
     * current center (see OutletMapPicker -- panning always keeps a real
     * center, there is no "unset" pin state to guard against here). Only a
     * non-blank name is checked client-side -- Address is populated by
     * reverse geocoding and read-only in the Add Outlet form (see
     * OutletFormScreen), so the Member has no direct way to fix an empty
     * Address here even if this checked it; an Address left blank because
     * the geocoding lookup failed/hadn't resolved yet is instead caught by
     * the server's own validation (MemberRepository.createOutlet's doc
     * comment), the same round trip every other field-level rule already
     * goes through. Everything else (length limits, coordinate range) is
     * likewise the server's job; this client-side check is purely to avoid a
     * round trip for an obviously incomplete Name. On success, refetches
     * [outlets] so the new PENDING row appears immediately without the
     * Member needing to pull-to-refresh.
     */
    fun submitNewOutlet(
        name: String,
        address: String,
        latitude: Double,
        longitude: Double,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val trimmedName = name.trim()
        val trimmedAddress = address.trim()
        if (trimmedName.isEmpty()) {
            _outletFormError.value = getApplication<Application>().getString(R.string.outlet_name_required)
            return
        }

        viewModelScope.launch {
            _outletFormLoading.value = true
            _outletFormError.value = null
            val result = repository.createOutlet(trimmedName, trimmedAddress, latitude, longitude)
            if (result.isSuccess) {
                fetchOutlets()
                onResult(true, getApplication<Application>().getString(R.string.outlet_submitted))
            } else {
                val message = result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.outlet_submit_failed)
                _outletFormError.value = message
                onResult(false, message)
            }
            _outletFormLoading.value = false
        }
    }

    /**
     * Proposes changes to an existing outlet. [currentStatus] is the outlet's
     * own status as already known from [outlets] (not re-derived from the
     * server's response -- see MemberRepository.updateOutlet's doc comment)
     * purely to pick the right success message: an APPROVED outlet's edit is
     * queued for Admin review with its live data unchanged in the meantime,
     * while a PENDING/REJECTED outlet is edited in place immediately --
     * mirroring backend/api.php's own /outlet/update branching exactly.
     */
    fun submitOutletEdit(
        id: Int,
        currentStatus: String,
        name: String,
        address: String,
        latitude: Double,
        longitude: Double,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val trimmedName = name.trim()
        val trimmedAddress = address.trim()
        if (trimmedName.isEmpty() || trimmedAddress.isEmpty()) {
            _outletFormError.value = getApplication<Application>().getString(R.string.outlet_name_address_required)
            return
        }

        viewModelScope.launch {
            _outletFormLoading.value = true
            _outletFormError.value = null
            val result = repository.updateOutlet(id, trimmedName, trimmedAddress, latitude, longitude)
            if (result.isSuccess) {
                fetchOutlets()
                val message = if (currentStatus == "APPROVED") {
                    getApplication<Application>().getString(R.string.outlet_changes_submitted)
                } else {
                    getApplication<Application>().getString(R.string.outlet_updated)
                }
                onResult(true, message)
            } else {
                val message = result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.outlet_edit_failed)
                _outletFormError.value = message
                onResult(false, message)
            }
            _outletFormLoading.value = false
        }
    }

    /**
     * Deletes a PENDING/REJECTED outlet this Member created -- the server
     * independently re-checks both conditions (ownership, status) and
     * rejects otherwise (see MemberRepository.deleteOutlet's doc comment);
     * this call is never assumed to succeed just because the button was
     * reachable in the UI.
     */
    fun deleteOutlet(id: Int, onResult: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            val result = repository.deleteOutlet(id)
            if (result.isSuccess) {
                fetchOutlets()
                onResult(true, getApplication<Application>().getString(R.string.outlet_deleted))
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.outlet_delete_failed))
            }
        }
    }

    /**
     * Called once a drag-to-reorder gesture ends (OutletScreen.kt's
     * MemberOutletScreen list). [orderedIds] is every outlet currently shown,
     * in the Member's newly chosen order. Updates [outlets] immediately
     * (optimistic -- the caller already reflects this exact order locally
     * mid-drag, so this just makes it the new source of truth instead of a
     * separate fetchOutlets() round trip flashing the list), then persists
     * in the background; only re-fetches from the server to correct course
     * if that save actually fails, rather than leaving a locally-guessed
     * order that silently never made it to the server.
     */
    fun reorderOutlets(orderedIds: List<Int>, onResult: ((success: Boolean, message: String) -> Unit)? = null) {
        val currentById = _outlets.value.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { currentById[it] }
        if (reordered.size == _outlets.value.size) {
            _outlets.value = reordered
        }
        // Cancel any in-flight fetch too, not just a previous reorder: a
        // slower fetchOutlets() call still pending from before this drag
        // could otherwise land AFTER the optimistic update above and
        // silently overwrite it back to the pre-reorder order. Cancelling
        // it skips fetchOutlets()'s own `_outletsLoading.value = false` at
        // its tail, so reset the flag here too -- otherwise a fetch
        // cancelled mid-flight would leave the loading spinner (and
        // reorderEnabled, which is gated on it) stuck permanently, since
        // nothing else would ever clear it.
        outletsFetchJob?.cancel()
        _outletsLoading.value = false
        outletReorderJob?.cancel()
        outletReorderJob = viewModelScope.launch {
            val result = repository.reorderOutlets(orderedIds)
            if (result.isSuccess) {
                onResult?.invoke(true, getApplication<Application>().getString(R.string.outlet_order_saved))
            } else {
                // The optimistic order above never actually saved -- resync
                // with the server's real order rather than let the Member
                // keep looking at an arrangement that silently didn't stick.
                fetchOutlets()
                onResult?.invoke(false, result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.outlet_order_failed))
            }
        }
    }

    // --- Admin Dashboard States ---
    private val _memberList = MutableStateFlow<List<UserDto>>(emptyList())
    val memberList: StateFlow<List<UserDto>> = _memberList.asStateFlow()

    private val _memberListLoading = MutableStateFlow(false)
    val memberListLoading: StateFlow<Boolean> = _memberListLoading.asStateFlow()

    // Distinguishes "failed to load" from "genuinely no active members" so
    // the dropdown doesn't show a misleading empty-state on a network error.
    private val _memberListError = MutableStateFlow<String?>(null)
    val memberListError: StateFlow<String?> = _memberListError.asStateFlow()

    // Change-detection for the Admin live map's 5s poll happens right here,
    // not by comparing responses before assigning them: MutableStateFlow's
    // `.value` setter already checks the new value against the current one
    // with structural equality (List.equals -- element-wise, and
    // MemberCurrentLocationDto is a plain data class with no callback or
    // other always-different field) and silently skips emitting to
    // collectors when they're equal. So a poll tick that returns a
    // byte-for-byte identical list -- the common case for a stationary or
    // offline member between two 5s ticks -- never reaches
    // collectAsState() in MainAppScreen, never triggers a recomposition of
    // AdminMapScreen, and therefore never reaches OsmMap's own update() pass
    // at all. This is the first of three independent layers that keep an
    // unchanged poll from causing any redraw -- the other two are
    // MapMarkerData's diff-friendly equals() (see OsmMap.kt) for the case
    // where the underlying DTO DID change but not in a way that affects
    // what's drawn, and OsmMap's own applied-vs-incoming comparison that
    // skips mapView.invalidate() entirely when nothing visual changed.
    private val _currentLocations = MutableStateFlow<List<MemberCurrentLocationDto>>(emptyList())
    val currentLocations: StateFlow<List<MemberCurrentLocationDto>> = _currentLocations.asStateFlow()

    private val _selectedMemberForHistory = MutableStateFlow<UserDto?>(null)
    val selectedMemberForHistory: StateFlow<UserDto?> = _selectedMemberForHistory.asStateFlow()

    // Defaults to "today" in GMT+7 specifically -- the backend filters by GMT+7
    // calendar date, so the picker must agree with that zone, not the device's.
    private val _historyDate = MutableStateFlow(WibTime.today())
    val historyDate: StateFlow<String> = _historyDate.asStateFlow()

    private val _historyResponse = MutableStateFlow<HistoryResponseDto?>(null)
    val historyResponse: StateFlow<HistoryResponseDto?> = _historyResponse.asStateFlow()

    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    private val _historyError = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = _historyError.asStateFlow()

    private var pollingJob: Job? = null

    init {
        validateSessionOnStartup()
        // Member-only background work (LocationSyncWorker's watchdog chain,
        // and the GPS/Sync-Interval Remote Management setting it depends on) is gated on
        // the persisted role from the LAST session, not unconditionally --
        // an Admin account (or a fresh process with nobody logged in yet)
        // has no use for either: Admin never tracks, so the watchdog chain
        // would just wake the device every ~3 minutes to check a repository
        // it immediately determines has nothing to do (see
        // LocationSyncWorker.doWork()'s own role check), and Remote Management's
        // cached values (GPS Update Interval, Sync Interval, password
        // bounds) are only ever read by Member-tracking code and this
        // ViewModel's own (Member-only) updateProfile() validation. Gated
        // here for the "already logged in, process just restarted" case;
        // see login()'s success branch for the "fresh login this session"
        // case -- logout() already cancels the chain either way.
        if (repository.currentUser?.role == "member") {
            enqueuePeriodicSync()
            refreshRemoteManagement()
        }
        checkForUpdate()
    }

    // --- OTA Update Actions ---

    /**
     * Runs once per process lifetime (called from init, matching
     * enqueuePeriodicSync's pattern) -- independent of [isLoggedIn], since a
     * force update must be able to block the app before login. Failure is
     * silent by design; see [UpdateManager.checkForUpdate]'s doc comment.
     */
    private fun checkForUpdate() {
        viewModelScope.launch {
            updateManager.checkForUpdate(BuildConfig.VERSION_CODE)
        }
    }

    /**
     * One opportunistic refresh per process lifetime, same rationale as
     * [checkForUpdate] -- so [remoteConfigRepository]'s cached password
     * bounds (used by [updateProfile]'s validation) are reasonably fresh
     * even for a Member who edits their profile before ever starting
     * tracking (the only other place this cache gets refreshed --
     * MemberLocationService/LocationSyncWorker -- requires tracking to have
     * started at least once). A no-op most of the time either way, per
     * RemoteConfigRepository.refreshIfStale's own staleness gate.
     */
    private fun refreshRemoteManagement() {
        viewModelScope.launch {
            try {
                remoteConfigRepository.refreshIfStale()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "Remote config refresh failed: ${e.message}")
            }
        }
    }

    /** "Later" -- cancels an in-flight download (if any) before dismissing, so a declined update doesn't keep downloading in the background. */
    fun dismissUpdate() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        updateManager.dismiss()
    }

    /** Starts the download for whichever update [updateState] currently reports as [UpdateFlowState.Available]. No-op if that's no longer the current state (e.g. a stale recomposition). */
    fun startUpdateDownload() {
        val info = (updateState.value as? UpdateFlowState.Available)?.info ?: return
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            updateManager.startDownload(info)
        }
    }

    /** Retries the download for whichever update [updateState] currently reports as [UpdateFlowState.DownloadFailed]. */
    fun retryUpdateDownload() {
        val info = (updateState.value as? UpdateFlowState.DownloadFailed)?.info ?: return
        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch {
            updateManager.startDownload(info)
        }
    }

    /** Intent to the OS "install unknown apps" settings screen for this app -- see [UpdateManager.installPermissionSettingsIntent]. */
    fun installPermissionSettingsIntent(): Intent = updateManager.installPermissionSettingsIntent()

    /** Re-checks install permission after the user may have granted it in Settings -- see [UpdateManager.recheckInstallPermission]. */
    fun recheckInstallPermission() = updateManager.recheckInstallPermission()

    /**
     * Called every time the app returns to the foreground (MainAppScreen's
     * LifecycleResumeEffect) -- covers reopening from Recent Apps, returning
     * after Force Stop or a device restart, and returning from the Package
     * Installer or the "install unknown apps" Settings screen, none of
     * which re-run this ViewModel's init{} block (that only happens once
     * per process). Two responsibilities:
     *
     * 1. If the last known state was a FORCED [UpdateFlowState.ReadyToInstall],
     *    the user just came back from the Package Installer -- whether they
     *    cancelled it, it failed, or they backed out some other way, the
     *    mandatory install prompt must re-engage immediately rather than
     *    silently sit there, since MainAppScreen's LaunchedEffect(updateState)
     *    only fires on a genuine state *change*, not on simply revisiting
     *    the same one.
     * 2. Otherwise -- except while a download is actively in flight, or an
     *    already-downloaded APK is just waiting on the install-permission
     *    Settings screen, where a blind re-check would only force a
     *    wasteful re-download -- re-verifies against the server. Cheap and
     *    silent-on-failure like every other [UpdateManager.checkForUpdate]
     *    call, this closes the gap where a persisted/blocked state is never
     *    reconfirmed, or a brand-new mandatory update published while this
     *    app sat backgrounded/in Recents is never noticed until the next
     *    full process restart.
     */
    fun onAppResumed() {
        val current = updateState.value
        if (current is UpdateFlowState.ReadyToInstall && (current.info.force_update)) {
            updateManager.launchInstall(current.apkFile)
            return
        }
        if (current is UpdateFlowState.Downloading || current is UpdateFlowState.NeedsInstallPermission) {
            return
        }
        viewModelScope.launch {
            updateManager.checkForUpdate(BuildConfig.VERSION_CODE)
        }
    }

    /** Launches the system Package Installer for [apkFile] -- see [UpdateManager.launchInstall]. */
    fun launchInstall(apkFile: File): Boolean = updateManager.launchInstall(apkFile)

    // --- Authentication Actions ---

    /**
     * Cold-start session verification -- runs once per process from init{}.
     * A locally stored token is never trusted by itself: [isLoggedIn] is
     * only ever set true here (or left false), and only after the server
     * has confirmed both that it's reachable and that the token is still
     * valid. This closes the gap where a device cut off from the server
     * (e.g. an ISP-level block returning HTTP 403) could still reach the
     * dashboard on a cached token and silently fail every sync afterward.
     *
     * Deliberately overlaps with MainAppScreen's 2-second splash screen
     * (that screen's gate stays up for as long as [isValidatingSession] is
     * true) rather than adding a second, separate loading screen -- in the
     * common case (server reachable, token valid) this resolves well within
     * that window, so nothing extra is ever visibly waited on; only a slow
     * or failed check extends it.
     *
     * No token stored at all is not a failure -- there is nothing to
     * validate, so this returns immediately and the login screen shows
     * exactly as it always did.
     */
    private fun validateSessionOnStartup() {
        if (repository.authToken == null) {
            _isValidatingSession.value = false
            return
        }
        viewModelScope.launch {
            val result = repository.validateSession()
            if (result.isSuccess) {
                _user.value = result.getOrNull()
                _isLoggedIn.value = true
                _sessionValidationError.value = null
                _canRetrySessionValidation.value = false
            } else {
                when (result.exceptionOrNull()) {
                    is SessionInvalidException -> {
                        // Server-confirmed dead token -- tear the local
                        // session down the same way a normal logout does
                        // (stop tracking, drop the offline queue, cancel the
                        // sync worker), just without the doomed extra call
                        // to /auth/logout using a token the server already
                        // rejected.
                        stopTracking()
                        stopLocationPolling()
                        workManager.cancelUniqueWork(LocationSyncWorker.UNIQUE_WORK_NAME)
                        repository.logout(notifyServer = false)
                        _user.value = null
                        _canRetrySessionValidation.value = false
                        _sessionValidationError.value =
                            getApplication<Application>().getString(R.string.session_expired)
                    }
                    else -> {
                        // Unconfirmed -- likely a connectivity or server
                        // problem, not a proven-bad token. The stored
                        // session is deliberately left alone so a transient
                        // blip (or an ISP block, the whole motivation for
                        // this check) doesn't force a needless full
                        // re-login the moment it clears; "Try Again" just
                        // re-runs this same check.
                        _canRetrySessionValidation.value = true
                        _sessionValidationError.value =
                            getApplication<Application>().getString(R.string.network_error)
                    }
                }
                _isLoggedIn.value = false
            }
            _isValidatingSession.value = false
        }
    }

    /**
     * "Try Again" on the login screen after a connectivity-type session
     * validation failure -- re-runs the exact same check as
     * [validateSessionOnStartup] without forcing the user to retype
     * credentials for what might just be a transient network blip. No-op if
     * a check is already in flight, or if there's no token left to validate
     * (a server-confirmed-invalid session already cleared it -- that case
     * has no retry, only a normal login).
     */
    fun retrySessionValidation() {
        if (_isValidatingSession.value || repository.authToken == null) return
        _isValidatingSession.value = true
        _sessionValidationError.value = null
        validateSessionOnStartup()
    }

    fun login(username: String, password: String) {
        val trimmedUsername = username.trim()
        // Client-side check before touching the network -- avoids a wasted
        // round trip when the server would reject the same blank fields anyway.
        if (trimmedUsername.isEmpty() || password.isEmpty()) {
            _loginError.value = getApplication<Application>().getString(R.string.login_required)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _loginError.value = null
            // A manual login attempt supersedes any stale session-validation
            // error left over from startup.
            _sessionValidationError.value = null
            _canRetrySessionValidation.value = false

            val result = repository.login(trimmedUsername, password)
            if (result.isSuccess) {
                _user.value = result.getOrNull()
                _isLoggedIn.value = true
                _lastSyncTime.value = repository.lastSyncTime
                // Fresh login this process -- init{}'s role check above ran
                // before this was known (no user was logged in yet at
                // process start). Mirrors that same "member" gate for the
                // one-time kickoff of the watchdog chain + Remote Management
                // refresh; a no-op for an Admin login.
                if (result.getOrNull()?.role == "member") {
                    enqueuePeriodicSync()
                    refreshRemoteManagement()
                }
            } else {
                _loginError.value = result.exceptionOrNull()?.message ?: "Authentication failed."
            }
            _isLoading.value = false
        }
    }

    // This ViewModel is a single long-lived instance for the whole process,
    // so on a shared device where a different account logs in without the
    // app being killed, every StateFlow below would otherwise still hold the
    // PREVIOUS account's data (e.g. a Member briefly seeing a stranger's last
    // GPS fix). Explicitly resetting each account-scoped StateFlow closes
    // that leak. batteryOptimizationCardDismissed is deliberately NOT reset --
    // it describes this device's OS-level state, not the logged-in account.
    fun logout() {
        viewModelScope.launch {
            // Best-effort, and must run BEFORE repository.logout() clears the
            // auth token below -- MemberLocationService's own offline ping
            // (fired async via the ACTION_STOP intent in stopTracking()) races
            // that token clear and reliably loses, since posting the intent
            // returns immediately while the service takes an IPC round trip
            // to actually act on it. Sending it here first, synchronously
            // ahead of the token clear, avoids depending on winning that race.
            // (The server would self-correct within OFFLINE_STALE_SECONDS
            // regardless, but there's no reason to wait on that.)
            if (repository.isTrackingEnabled) {
                try {
                    repository.postOfflineStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Failed to notify offline status before logout: ${e.message}")
                }
            }
            stopTracking()
            stopLocationPolling()
            // Stops the LocationSyncWorker watchdog chain immediately rather
            // than waiting for it to notice on its own next ~3-minute pass
            // (see LocationSyncWorker.doWork()'s own currentUser check, which
            // is what actually makes the chain stop rescheduling -- this just
            // saves one lingering cycle).
            workManager.cancelUniqueWork(LocationSyncWorker.UNIQUE_WORK_NAME)
            repository.logout()
            _user.value = null
            _isLoggedIn.value = false
            _lastTrackedLocation.value = null
            _lastSyncTime.value = repository.lastSyncTime
            _memberTripSummary.value = null
            outletsFetchJob?.cancel()
            outletReorderJob?.cancel()
            _outlets.value = emptyList()
            _outletsError.value = null
            _outletFormError.value = null
            _memberList.value = emptyList()
            _memberListError.value = null
            _currentLocations.value = emptyList()
            _selectedMemberForHistory.value = null
            _historyResponse.value = null
            _historyError.value = null
            _historyDate.value = WibTime.today()
            _historyAvailableDates.value = emptySet()
        }
    }

    // --- Member Tracking Controls ---

    fun updateLastLocation(lat: Double, lng: Double, accuracy: Float, speed: Float, time: String) {
        _lastTrackedLocation.value = TrackedLocationSnapshot(
            latitude = lat,
            longitude = lng,
            accuracy = accuracy,
            speed = speed,
            time = time
        )
    }

    /**
     * Called from MainAppScreen's LOCATION_BROADCAST receiver when
     * MemberLocationService.EXTRA_SESSION_INVALID comes back true -- the
     * service already confirmed the token dead (401/403), stopped GPS
     * updates, and stopped itself, but had no way to reach into this
     * ViewModel directly. Without this, _isLoggedIn/_isTrackingActive stayed
     * stuck showing a normal active dashboard -- state visibly contradicting
     * what the service just did -- until the member force-closed and
     * reopened the app (a fresh process re-runs validateSessionOnStartup()).
     * Mirrors validateSessionOnStartup()'s own SessionInvalidException
     * branch, minus stopTracking(): the service already tore its own GPS
     * subscription and offline-status ping down, so re-sending ACTION_STOP
     * here would just restart the service a second time for nothing.
     */
    fun handleSessionInvalidatedByService() {
        if (!_isLoggedIn.value) return
        _isTrackingActive.value = false
        stopLocationPolling()
        workManager.cancelUniqueWork(LocationSyncWorker.UNIQUE_WORK_NAME)
        viewModelScope.launch {
            repository.logout(notifyServer = false)
            _user.value = null
            _isLoggedIn.value = false
            _canRetrySessionValidation.value = false
            _sessionValidationError.value =
                getApplication<Application>().getString(R.string.session_expired)
        }
    }

    /**
     * Called from MainAppScreen's LOCATION_BROADCAST receiver when
     * MemberLocationService.EXTRA_TRACKING_NOT_ALLOWED comes back true --
     * the service already stopped its own foreground service and GPS
     * subscription because a fix was rejected with
     * TrackingNotAllowedException (outside operational hours, with no or no
     * longer any Force override -- most commonly an Admin just turned Force
     * Location OFF while this Member was still tracking past the allowed
     * window). This is Force Location's entire ON->OFF auto-revoke
     * enforcement path on the client side: no separate polling loop, no
     * separate "Force was revoked" push signal -- reacting correctly to the
     * next fix's rejection is sufficient. See TrackingNotAllowedException's
     * and MemberLocationService.handleNewLocation's doc comments for the
     * full contract this implements.
     *
     * Deliberately does NOT touch login/session state (unlike
     * [handleSessionInvalidatedByService] above) -- the Member's token is
     * perfectly valid, only tracking itself stopped. Also does NOT cancel
     * the periodic WorkManager sync job -- it should keep running normally
     * (flushing any still-queued offline points, watching for the Member
     * logging in again), it just has nothing to restart since
     * [MemberRepository.isTrackingEnabled] is already false by the time this
     * broadcast arrives (the service cleared it before stopping itself).
     */
    fun handleTrackingNotAllowedByService() {
        if (!_isLoggedIn.value) return
        _isTrackingActive.value = false
        _trackingAutoStoppedMessage.value =
            getApplication<Application>().getString(R.string.tracking_auto_stopped)
    }

    // Belt-and-suspenders refresh: re-reads the persisted fix from disk
    // rather than relying solely on MemberLocationService's LOCATION_BROADCAST.
    // On aggressive OEM battery managers (MIUI/ColorOS/FuntouchOS/EMUI) a
    // foreground service's broadcasts can be silently dropped even within the
    // same app while its network calls keep working -- so Admin sees fresh
    // coordinates while the Member's own dashboard shows nothing. Polling
    // this cheap local read while tracking is active closes that gap.
    fun refreshLastKnownLocation() {
        val persisted = repository.lastKnownLocation
        if (persisted != null && persisted != _lastTrackedLocation.value) {
            _lastTrackedLocation.value = persisted
        }
    }

    // --- Profile Editing (Member) ---

    fun setEditingProfile(editing: Boolean) {
        _isEditingProfile.value = editing
        // Clear any stale error so it never lingers into the next edit session.
        _profileUpdateError.value = null
    }

    /**
     * Validates then persists profile changes. [password] should be null or
     * blank to leave it unchanged. On success, [user] updates immediately and
     * edit mode closes; [onResult] fires either way so the UI can show a
     * one-off Snackbar without its own duplicate state.
     */
    fun updateProfile(
        name: String,
        username: String,
        note: String,
        password: String?,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val trimmedName = name.trim()
        val trimmedUsername = username.trim()
        val trimmedNote = note.trim()

        if (trimmedName.isEmpty() || trimmedUsername.isEmpty()) {
            _profileUpdateError.value = getApplication<Application>().getString(R.string.profile_name_user_required)
            return
        }
        // Bounds come from Remote Management (tams_remote_management), same
        // source ajax/members_save.php, ajax/profile_update.php, and
        // backend/api.php's /profile/update read server-side -- one table,
        // one rule, everywhere a password is set. Cached, so this is a
        // synchronous read (see RemoteConfigRepository's doc comment).
        val passwordMinLength = remoteConfigRepository.passwordMinLength
        val passwordMaxLength = remoteConfigRepository.passwordMaxLength
        if (!password.isNullOrBlank() && password.trim().length < passwordMinLength) {
            _profileUpdateError.value = getApplication<Application>().getString(R.string.password_min_length, passwordMinLength)
            return
        }
        // tams_users.password is VARCHAR(255) on the server at most --
        // without this check, a too-long password would only be caught
        // after a round trip to the server (or worse, silently truncated there).
        if (!password.isNullOrBlank() && password.length > passwordMaxLength) {
            _profileUpdateError.value = getApplication<Application>().getString(R.string.password_max_length, passwordMaxLength)
            return
        }

        // Captured before the API call so the Log entries below describe what
        // actually changed, regardless of whether the call itself succeeds.
        val oldUser = _user.value

        viewModelScope.launch {
            _profileUpdateLoading.value = true
            _profileUpdateError.value = null

            val result = repository.updateProfile(trimmedName, trimmedUsername, trimmedNote, password)
            val succeeded = result.isSuccess
            logProfileChanges(oldUser, trimmedUsername, trimmedNote, password, succeeded)

            if (result.isSuccess) {
                _user.value = result.getOrNull()
                _isEditingProfile.value = false
                onResult(true, getApplication<Application>().getString(R.string.profile_updated))
            } else {
                val message = result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.profile_update_failed)
                _profileUpdateError.value = message
                onResult(false, message)
            }
            _profileUpdateLoading.value = false
        }
    }

    /**
     * Writes one Log entry per changed field (Username, Note), matching the
     * spec's example format ("`[Date]` member changed Username from old
     * to new"), plus one entry for a password change --
     * NEVER the password value itself, only the bare fact that it changed.
     * Logged with [succeeded]'s actual outcome (not assumed success) for
     * both the field-level entries and the password entry, since a
     * server-side validation failure means none of these changes actually
     * took effect even though they were attempted.
     */
    private suspend fun logProfileChanges(
        oldUser: UserDto?,
        newUsername: String,
        newNote: String,
        newPassword: String?,
        succeeded: Boolean
    ) {
        if (oldUser == null) return

        if (newUsername != oldUser.username) {
            activityLogRepository.log(
                actionType = "profile_update",
                success = succeeded,
                fieldBefore = oldUser.username,
                fieldAfter = newUsername,
                message = "Changed Username from '${oldUser.username}' to '$newUsername'"
            )
        }

        val oldNoteDisplay = oldUser.note.ifBlank { "(none)" }
        val newNoteDisplay = newNote.ifBlank { "(none)" }
        if (newNote != oldUser.note) {
            activityLogRepository.log(
                actionType = "profile_update",
                success = succeeded,
                fieldBefore = oldNoteDisplay,
                fieldAfter = newNoteDisplay,
                message = "Changed Note from '$oldNoteDisplay' to '$newNoteDisplay'"
            )
        }

        if (!newPassword.isNullOrBlank()) {
            activityLogRepository.log(
                actionType = "profile_update",
                success = succeeded,
                message = "Password changed"
            )
        }
    }

    /**
     * Returns true if the foreground service start request was actually
     * issued. On Android 12+, Context.startForegroundService() can throw
     * ForegroundServiceStartNotAllowedException if the app lacks a valid
     * background-start exemption at that moment -- this call is reached from
     * a direct Start-button tap so it's exempt in the vast majority of cases,
     * but is still guarded like LocationSyncWorker/BootCompletedReceiver's
     * calls to avoid an uncaught crash. isTrackingActive only flips to true
     * on the success path.
     */
    fun startTracking(): Boolean {
        val intent = Intent(getApplication(), MemberLocationService::class.java).apply {
            action = MemberLocationService.ACTION_START
        }
        return try {
            androidx.core.content.ContextCompat.startForegroundService(getApplication(), intent)
            _isTrackingActive.value = true
            viewModelScope.launch { activityLogRepository.log("start_location", success = true) }
            true
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to start foreground tracking service: ${e.message}")
            viewModelScope.launch {
                activityLogRepository.log("start_location", success = false, message = e.message)
            }
            false
        }
    }

    /**
     * Force Override pre-flight, wrapping [startTracking]. Calls GET
     * /location/status first so a Member outside the allowed window (and
     * without an Admin-granted Force override) sees an immediate, specific
     * message instead of a foreground service that silently never sends
     * anything -- POST /location/update rejects every fix with HTTP 403
     * either way (see that route's doc comment in backend/api.php), so this
     * pre-flight is pure UX, never the actual enforcement.
     *
     * Fails OPEN on any inconclusive answer (network failure, timeout,
     * malformed response -- i.e. [MemberRepository.getLocationStatus]
     * returning [Result.failure]): [startTracking] is still attempted, since
     * the real security boundary is server-side on every write, and blocking
     * Start over a transient network blip would be a pure UX regression with
     * no security benefit. A modified app build or spoofed device clock
     * cannot widen the window this way -- skipping/spoofing this check only
     * ever reaches the same server-side gate [startTracking] would have hit
     * anyway.
     *
     * [onResult] receives a single non-null, user-facing message on either
     * failure path (denied by the operational-hours window, or
     * [startTracking] itself failing) and null on success -- callers can use
     * this directly in place of the old `if (!startTracking())` Toast check.
     */
    fun requestStartTracking(onResult: (errorMessage: String?) -> Unit) {
        viewModelScope.launch {
            val status = repository.getLocationStatus().getOrNull()
            if (status != null && !status.tracking_allowed) {
                onResult(
                    getApplication<Application>().getString(R.string.tracking_allowed_between, status.operational_hours_start, status.operational_hours_end)
                )
                return@launch
            }
            if (startTracking()) {
                onResult(null)
            } else {
                onResult(getApplication<Application>().getString(R.string.failed_start_tracking))
            }
        }
    }

    /**
     * Same defensive guard as startTracking() -- plain startService() is also
     * subject to the "not allowed to start in background" IllegalStateException
     * on API 26+. If this ever failed silently unguarded, the service would
     * keep running/draining battery while the UI claims tracking stopped.
     */
    fun stopTracking(): Boolean {
        val intent = Intent(getApplication(), MemberLocationService::class.java).apply {
            action = MemberLocationService.ACTION_STOP
        }
        return try {
            getApplication<Application>().startService(intent)
            _isTrackingActive.value = false
            viewModelScope.launch { activityLogRepository.log("stop_location", success = true) }
            true
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to send stop-tracking intent: ${e.message}")
            viewModelScope.launch {
                activityLogRepository.log("stop_location", success = false, message = e.message)
            }
            false
        }
    }

    /**
     * Kicks off LocationSyncWorker's self-rescheduling chain (see that
     * class's doc comment for why it's a chained OneTimeWorkRequest, not a
     * PeriodicWorkRequest). `ExistingWorkPolicy.KEEP` (unlike the worker's own
     * REPLACE) avoids resetting an already-running chain's timer on every app
     * open. No network constraint, matching the worker's own rescheduling --
     * the watchdog's restart duty must run even offline.
     */
    private fun enqueuePeriodicSync() {
        val initialRequest = OneTimeWorkRequestBuilder<LocationSyncWorker>().build()

        workManager.enqueueUniqueWork(
            LocationSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            initialRequest
        )
    }

    // --- Admin Dashboard Polling ---

    fun startLocationPolling() {
        if (pollingJob != null) return
        // fetchCurrentLocations() is awaited each iteration so polls run
        // strictly sequentially -- otherwise a slow network could let an
        // older response arrive after a newer one and stomp it with stale data.
        pollingJob = viewModelScope.launch {
            while (true) {
                fetchCurrentLocations()
                delay(kotlin.time.Duration.parse("5s"))
            }
        }
    }

    fun stopLocationPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun fetchCurrentLocations() {
        val result = repository.getCurrentLocations()
        if (result.isSuccess) {
            _currentLocations.value = result.getOrDefault(emptyList())
        } else {
            val error = result.exceptionOrNull()
            // Passed as the Throwable overload (not interpolated into the
            // message string) so Logcat still gets the full cause chain and
            // stack trace -- getCurrentLocations() now wraps raw network
            // exceptions in a user-friendly message (see MemberRepository's
            // friendlyNetworkErrorMessage()), and that wrapped exception's own
            // .message alone would be less useful here than before. The
            // original exception is preserved as .cause, which this overload
            // prints as "Caused by: ...".
            Log.e("MainViewModel", "Failed polling current locations", error)
            if (error is SessionInvalidException) {
                // Server-confirmed dead/revoked token -- without this, the
                // 5s live-map poll would keep hitting a dead token forever
                // (indefinite battery/data waste) with no path back to the
                // login screen. Same session-teardown as a startup
                // validation failure -- see validateSessionOnStartup's
                // SessionInvalidException branch.
                Log.w("MainViewModel", "Session invalid during live polling; logging out.")
                stopLocationPolling()
                logout()
            }
        }
    }

    fun fetchMemberList() {
        viewModelScope.launch {
            _memberListLoading.value = true
            _memberListError.value = null
            val result = repository.getMemberList()
            if (result.isSuccess) {
                _memberList.value = result.getOrDefault(emptyList())
            } else {
                // Keep the existing list on failure; surface the error separately
                // so the UI can tell "load failed" apart from "genuinely empty".
                _memberListError.value = result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.failed_load_members)
                Log.e("MainViewModel", "Failed to load member list: ${_memberListError.value}")
            }
            _memberListLoading.value = false
        }
    }

    fun setHistoryMember(memberUser: UserDto?) {
        _selectedMemberForHistory.value = memberUser
        _historyResponse.value = null
        if (memberUser != null) {
            fetchLocationHistory()
        }
    }

    fun setHistoryDate(date: String) {
        _historyDate.value = date
        _historyResponse.value = null
        if (_selectedMemberForHistory.value != null) {
            fetchLocationHistory()
        }
    }

    // Tracks the in-flight fetchLocationHistory call so a newer call can
    // cancel it -- otherwise rapidly switching member/date could let an
    // older, slower response land after a newer one and overwrite it.
    private var historyFetchJob: Job? = null

    fun fetchLocationHistory() {
        val member = _selectedMemberForHistory.value ?: return
        val date = _historyDate.value

        historyFetchJob?.cancel()
        historyFetchJob = viewModelScope.launch {
            _historyLoading.value = true
            _historyError.value = null

            val result = repository.getLocationHistory(member.id, date)
            if (result.isSuccess) {
                _historyResponse.value = result.getOrNull()
            } else {
                _historyError.value = result.exceptionOrNull()?.message ?: getApplication<Application>().getString(R.string.failed_load_history)
            }
            _historyLoading.value = false
        }
    }

    // Backs the date picker's enabled/disabled day cells (SimpleMonthCalendar
    // in MainAppScreen.kt). Keyed by the displayed month; switching
    // members/months just overwrites this with a fresh, cheap-to-refetch result.
    private val _historyAvailableDates = MutableStateFlow<Set<String>>(emptySet())
    val historyAvailableDates: StateFlow<Set<String>> = _historyAvailableDates.asStateFlow()

    // True while a fetch for the currently-displayed month is in flight.
    // SimpleMonthCalendar disables every cell while true, instead of showing
    // a stale set left over from a previous month that could flash as valid.
    private val _historyAvailableDatesLoading = MutableStateFlow(false)
    val historyAvailableDatesLoading: StateFlow<Boolean> = _historyAvailableDatesLoading.asStateFlow()

    // Same race-condition class as historyFetchJob above: rapidly tapping
    // "next month" could let an older month's response land last and show
    // the wrong month's enabled/disabled cells.
    private var historyAvailableDatesJob: Job? = null

    fun fetchHistoryAvailableDates(month: String) {
        val member = _selectedMemberForHistory.value ?: return
        historyAvailableDatesJob?.cancel()
        historyAvailableDatesJob = viewModelScope.launch {
            _historyAvailableDatesLoading.value = true
            _historyAvailableDates.value = emptySet()
            val result = repository.getLocationHistoryDates(member.id, month)
            // On failure, stays at the empty set from the reset above --
            // failing "safe" (nothing enabled) beats failing "open".
            result.onSuccess { _historyAvailableDates.value = it.toSet() }
            _historyAvailableDatesLoading.value = false
        }
    }
}
