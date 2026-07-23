package com.trubus.tams.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trubus.tams.data.geocoding.AddressSearchResult
import com.trubus.tams.data.geocoding.AddressSearchService
import com.trubus.tams.data.geocoding.ReverseGeocodingService
import com.trubus.tams.data.model.OutletDto
import com.trubus.tams.ui.viewmodel.MainViewModel
import com.trubus.tams.util.OneShotLocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File

/**
 * Outlet feature (Member role): the Outlet List screen plus an Add/Edit form
 * with a Gojek/Shopee-style map picker and Nominatim address search.
 * Reached from MainAppScreen.kt's MemberRootScreen ("Outlet" tab) --
 * entirely additive to the existing Member Dashboard, see that file's own
 * comments for why the two are isolated from each other.
 *
 * All server-side validation (name/address length, coordinate range,
 * status/ownership rules) lives in backend/api.php -- everything here is
 * genuinely client-only convenience (non-blank checks, a soft maxlength
 * while typing), never the actual authority. See
 * MemberRepository's Outlet Actions section for the same principle stated
 * on the data-layer side.
 */

// --- Constants --------------------------------------------------------------

private const val OUTLET_VIEW_LIST = 0
private const val OUTLET_VIEW_FORM = 1

// Jakarta -- matches the Web Admin's own default map center
// (web/assets/js/map.js's DEFAULT_CENTER), so a fresh Add Outlet without a
// current-location fix yet starts the picker somewhere familiar instead of
// the middle of the ocean (0,0).
private val DEFAULT_MAP_CENTER = GeoPoint(-6.2, 106.816666)
private const val DEFAULT_MAP_ZOOM = 15.0
private const val PICKER_MAP_ZOOM = 17.0

// Same brand semantic colors as web/assets/css/style.css's --color-warning/
// --color-success/--color-danger, so PENDING/APPROVED/REJECTED read as the
// same color on the Web Admin and this app.
private val OutletStatusPendingColor = Color(0xFFD97706)
private val OutletStatusApprovedColor = Color(0xFF16A34A)
private val OutletStatusRejectedColor = Color(0xFFDC2626)

private fun outletStatusColor(status: String): Color = when (status) {
    "APPROVED" -> OutletStatusApprovedColor
    "REJECTED" -> OutletStatusRejectedColor
    else -> OutletStatusPendingColor
}

private fun outletStatusLabel(status: String): String = when (status) {
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    else -> "Pending"
}

// --- Root screen --------------------------------------------------------------

/**
 * Entry point for the Member "Outlet" tab. Owns simple list/form navigation
 * as local Compose state (`remember`, not a MainViewModel StateFlow) -- the
 * same pattern AdminDashboard/AdminHistoryScreen already use for their own
 * internal tab/view navigation in this file, since it's pure UI-navigation
 * state nothing else needs to observe.
 */
@Composable
fun MemberOutletScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var currentView by remember { mutableIntStateOf(OUTLET_VIEW_LIST) }
    var editingOutlet by remember { mutableStateOf<OutletDto?>(null) }

    val outlets by viewModel.outlets.collectAsState()
    val outletsLoading by viewModel.outletsLoading.collectAsState()
    val outletsError by viewModel.outletsError.collectAsState()

    // Refetches every time this screen (re)enters composition -- covers both
    // the very first visit and returning here after Save/Cancel from the
    // form, so an Admin's meanwhile approval/rejection is picked up without
    // a manual pull-to-refresh.
    LaunchedEffect(currentView) {
        if (currentView == OUTLET_VIEW_LIST) {
            viewModel.fetchOutlets()
        }
    }

    when (currentView) {
        OUTLET_VIEW_LIST -> OutletListScreen(
            outlets = outlets,
            loading = outletsLoading,
            error = outletsError,
            onRefresh = { viewModel.fetchOutlets() },
            onAddClick = {
                editingOutlet = null
                viewModel.clearOutletFormError()
                currentView = OUTLET_VIEW_FORM
            },
            onEditClick = { outlet ->
                editingOutlet = outlet
                viewModel.clearOutletFormError()
                currentView = OUTLET_VIEW_FORM
            },
            onDeleteConfirmed = { outlet ->
                viewModel.deleteOutlet(outlet.id) { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        )
        OUTLET_VIEW_FORM -> OutletFormScreen(
            viewModel = viewModel,
            editingOutlet = editingOutlet,
            onDone = { currentView = OUTLET_VIEW_LIST }
        )
    }
}

// --- List screen --------------------------------------------------------------

@Composable
private fun OutletListScreen(
    outlets: List<OutletDto>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (OutletDto) -> Unit,
    onDeleteConfirmed: (OutletDto) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<OutletDto?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My Outlets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload outlets")
                    }
                }
                Button(onClick = onAddClick, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        when {
            error != null && outlets.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = onRefresh) { Text("Try Again") }
                    }
                }
            }
            outlets.isEmpty() && !loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You haven't registered any outlets yet. Tap 'Add' to register one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(outlets, key = { it.id }) { outlet ->
                        OutletCard(
                            outlet = outlet,
                            onEditClick = { onEditClick(outlet) },
                            onDeleteClick = { pendingDelete = outlet }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { outlet ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = OverlayCardShape,
            title = { Text("Delete Outlet") },
            text = { Text("Delete \"${outlet.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConfirmed(outlet)
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun OutletCard(outlet: OutletDto, onEditClick: () -> Unit, onDeleteClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = outlet.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = outlet.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutletStatusBadge(status = outlet.status)
            }

            if (outlet.status == "APPROVED" && outlet.has_pending_edit) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.HourglassEmpty,
                        contentDescription = null,
                        tint = OutletStatusPendingColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Changes pending Admin approval",
                        style = MaterialTheme.typography.bodySmall,
                        color = OutletStatusPendingColor
                    )
                }
            }

            if (outlet.status == "REJECTED" && !outlet.rejection_reason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reason: ${outlet.rejection_reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Distinct from the block above -- this outlet is APPROVED (its
            // own status was never REJECTED), but the Member's last proposed
            // EDIT to it was. Gated on !has_pending_edit so a fresh
            // resubmission's "Changes pending Admin approval" note (above)
            // takes priority instead of showing both at once.
            if (outlet.status == "APPROVED" && !outlet.has_pending_edit &&
                !outlet.last_edit_rejection_reason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last edit rejected: ${outlet.last_edit_rejection_reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Registered ${formatToWIB(outlet.created_at)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))
            if (!outlet.is_own_outlet) {
                // Assigned by Admin -- edit/delete rights follow
                // created_by_user_id server-side (see backend/api.php's
                // /outlet/update and /outlet/delete own ownership checks),
                // so a Member merely assigned to this outlet can only view
                // it, never edit/delete it. No buttons shown at all, rather
                // than shown-then-rejected -- the UI should never offer an
                // action the server is guaranteed to refuse.
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Assigned by Admin", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.AdminPanelSettings,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onEditClick,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", style = MaterialTheme.typography.labelMedium)
                    }
                    // Only a PENDING/REJECTED outlet is deletable server-side
                    // (see MemberRepository.deleteOutlet's doc comment) -- an
                    // APPROVED outlet may already have visit history, so
                    // Delete is hidden entirely rather than offered and
                    // rejected.
                    if (outlet.status == "PENDING" || outlet.status == "REJECTED") {
                        OutlinedButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutletStatusBadge(status: String) {
    val color = outletStatusColor(status)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = outletStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// --- Add / Edit form screen ----------------------------------------------------

@Composable
private fun OutletFormScreen(
    viewModel: MainViewModel,
    editingOutlet: OutletDto?,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEditing = editingOutlet != null

    var name by remember(editingOutlet) { mutableStateOf(editingOutlet?.name ?: "") }
    var address by remember(editingOutlet) { mutableStateOf(editingOutlet?.address ?: "") }

    val initialCenter = remember(editingOutlet) {
        val lat = editingOutlet?.latitude
        val lng = editingOutlet?.longitude
        if (lat != null && lng != null) GeoPoint(lat, lng) else DEFAULT_MAP_CENTER
    }
    var pickedLat by remember(editingOutlet) { mutableDoubleStateOf(initialCenter.latitude) }
    var pickedLng by remember(editingOutlet) { mutableDoubleStateOf(initialCenter.longitude) }

    // See OutletMapPicker's own doc comment for why a (point, zoom, nonce)
    // triple, not just a point, is needed to force a recenter.
    var recenterRequest by remember { mutableStateOf<MapRecenterRequest?>(null) }
    var recenterNonce by remember { mutableIntStateOf(0) }

    val formLoading by viewModel.outletFormLoading.collectAsState()
    val formError by viewModel.outletFormError.collectAsState()

    var locatingCurrentPosition by remember { mutableStateOf(false) }

    // [zoom] is null for Search Address's own selection below -- that call
    // site's behavior is unchanged, recenter only. Non-null for
    // fetchAndApplyCurrentLocation() below, so both the manual "Use Current
    // Location" button and the Add Outlet auto-trigger (see the
    // LaunchedEffect(Unit) further down) land on a genuinely useful close-up
    // view instead of requiring the Member to pinch-zoom in themselves
    // afterward.
    fun applyPickedPoint(point: GeoPoint, zoom: Double? = null) {
        recenterNonce++
        recenterRequest = MapRecenterRequest(point, zoom, recenterNonce)
        pickedLat = point.latitude
        pickedLng = point.longitude
    }

    fun fetchAndApplyCurrentLocation() {
        locatingCurrentPosition = true
        scope.launch {
            val location = OneShotLocationProvider.getCurrentLocation(context)
            locatingCurrentPosition = false
            if (location != null) {
                applyPickedPoint(GeoPoint(location.latitude, location.longitude), zoom = PICKER_MAP_ZOOM)
            } else {
                Toast.makeText(
                    context,
                    "Could not get your current location. Check GPS/network signal and try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchAndApplyCurrentLocation()
        } else {
            Toast.makeText(
                context,
                "Location permission is required to use your current location.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun onUseCurrentLocationClick() {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            fetchAndApplyCurrentLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // Add Outlet only: the moment this form first opens for a brand-new
    // outlet, run the exact same "Use Current Location" path the button
    // itself triggers -- same permission check/request, same fetch, same
    // recenter+zoom -- so a Member registering a NEW outlet never has to
    // find and tap that button themselves. Deliberately excluded for Edit
    // Outlet: [initialCenter] there is already the outlet's own saved
    // coordinate, and auto-jumping to the Member's current physical position
    // the instant Edit opens would silently discard/override that saved
    // location before the Member ever sees it. Fires once per form instance
    // (LaunchedEffect(Unit) restarts only if this composable itself leaves
    // and re-enters composition, never on an ordinary recomposition).
    LaunchedEffect(Unit) {
        if (!isEditing) {
            onUseCurrentLocationClick()
        }
    }

    // --- Address search (debounced + cached, see AddressSearchService) ---
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AddressSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // Re-launches (cancelling any in-flight search, which itself aborts the
    // underlying HTTP call -- see NominatimSearchService.search's own doc
    // comment) every time searchQuery changes, so typing quickly never
    // fires a request per keystroke -- this delay() IS the debounce.
    //
    // AddressSearchService.search() tries Nominatim first and only falls
    // back to Photon if that comes back empty -- this call site doesn't
    // change either way; see AddressSearchService's own doc comment.
    LaunchedEffect(searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.length < 3) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(500)
        searchResults = AddressSearchService.search(trimmed)
        searching = false
    }

    // --- Reverse geocoding: keep Address in sync with the pin ---------------
    // Fixes the gap found during Final Validation: dragging/zooming the map
    // (or using "Use Current Location") used to change pickedLat/pickedLng
    // with no effect on `address` at all, silently leaving a Search-Address-
    // era or stale label attached to a since-moved pin.
    //
    // resolvingAddress drives a small trailing spinner on the Address field
    // below -- purely a "something is about to change" affordance, mirroring
    // the Search Address field's own `searching` indicator above.
    var resolvingAddress by remember { mutableStateOf(false) }

    // The coordinate a Search Address result was last actually applied to --
    // or, initially, the form's own starting point (DEFAULT_MAP_CENTER for a
    // fresh Add Outlet, the outlet's own saved coordinate for Edit Outlet).
    // The reverse-geocode effect below skips exactly when the CURRENT
    // (pickedLat, pickedLng) still equals this pair.
    //
    // Deliberately a coordinate to compare against, not a one-shot "skip
    // next" boolean: a boolean has no coordinate of its own, only "was I
    // just armed", so a drag landing before its own LaunchedEffect instance
    // even started running (cancelled by the drag's newer key before
    // reaching the check) would still see a stale "yes, skip" left over
    // from the search selection and wrongly swallow the drag's own,
    // legitimate trigger. Comparing actual coordinates instead can't be
    // raced this way -- a drag to any different coordinate simply fails
    // the equality check regardless of timing, while landing back on this
    // exact same point (which already has a known-good label) correctly
    // still skips.
    var lastAuthoritativeLat by remember(editingOutlet) { mutableDoubleStateOf(pickedLat) }
    var lastAuthoritativeLng by remember(editingOutlet) { mutableDoubleStateOf(pickedLng) }

    // True once the Member has manually retyped Address since the pin last
    // moved -- checked right before the reverse-geocode result would
    // otherwise overwrite it, so a manual correction (e.g. fixing a wrong
    // Nominatim label) is never clobbered by a request that was already in
    // flight or still debouncing. Reset to `false` every time the pin moves
    // again (top of the LaunchedEffect below, before anything else) --
    // deliberately NOT a permanent "never auto-update again this session"
    // latch: this feature's whole point is staying in sync with the pin, so
    // the Member's next drag/zoom/Use Current Location/Search Address pick
    // is exactly the signal that they want a fresh lookup again, overriding
    // whatever they typed in the meantime. Set from the Address field's own
    // onValueChange below -- never from a programmatic `address = ...`
    // write (search selection, reverse geocoding itself), since
    // onValueChange only ever fires for genuine user input, not for a
    // recomposition showing a new `value`.
    var addressManuallyEdited by remember { mutableStateOf(false) }

    // Debounced exactly like Search Address above -- same delay(500) inside
    // a LaunchedEffect keyed on the values that change, per
    // NominatimSearchService's own doc comment that debouncing is always the
    // caller's job, never the geocoding service's. osmdroid's MapListener
    // (see OutletMapPicker below) only exposes onScroll/onZoom, fired
    // continuously while a drag/fling is in progress -- it has no distinct
    // "gesture ended" callback to hook instead. Keying on the two raw
    // Double values (not a GeoPoint object) means a zoom that doesn't
    // actually move the center is a no-op write and never re-triggers this
    // effect at all, on top of the debounce.
    //
    // Compose cancels the previous instance of this effect's coroutine the
    // moment pickedLat/pickedLng change again before delay(500) elapses --
    // and that cancellation propagates into ReverseGeocodingService's own
    // suspendCancellableCoroutine + invokeOnCancellation, which aborts the
    // in-flight OkHttp call too. So a rapid drag -> drag -> drag never
    // queues multiple requests, and a stale response can never land after a
    // newer one: there is no "newer one" racing it, the older coroutine is
    // actually cancelled, not merely superseded by a sequence number.
    LaunchedEffect(pickedLat, pickedLng) {
        // The pin just moved -- re-arm. Any manual edit made before THIS
        // move no longer applies; a manual edit made after this point (while
        // this same effect instance is still debouncing/in flight) will
        // re-set it below and is checked twice further down.
        addressManuallyEdited = false

        if (pickedLat == lastAuthoritativeLat && pickedLng == lastAuthoritativeLng) {
            return@LaunchedEffect
        }
        delay(500)
        // Retyped during the debounce wait -- skip the network call
        // entirely rather than fetch a label only to discard it; saves a
        // request and avoids flashing the resolving spinner for nothing.
        if (addressManuallyEdited) {
            return@LaunchedEffect
        }
        resolvingAddress = true
        // try/finally, not a plain statement sequence: getNearbyLabel below
        // is a suspension point, and cancelling this effect (the pin moved
        // again before the network call returned) throws
        // CancellationException right there, which would otherwise skip
        // straight past a bare `resolvingAddress = false` written after the
        // call -- leaving the spinner stuck showing for a request that was
        // already aborted. `finally` is the one construct Kotlin guarantees
        // runs on every exit path out of the block above it -- normal
        // return, any exception, or cancellation -- so resolvingAddress is
        // always brought back to `false` no matter how this ends. (Cheap
        // and safe to run unconditionally here: the block being guarded is
        // a single synchronous assignment, not another suspend call, so
        // there's nothing that would itself need `NonCancellable`.)
        try {
            val label = ReverseGeocodingService.getNearbyLabel(pickedLat, pickedLng)
            // Checked again: the Member could have retyped Address while this
            // network call was in flight. Null (lookup failed/timed out) also
            // leaves the current address text untouched rather than blanking it
            // or showing an error -- same "never surface a network error for a
            // supplementary lookup" philosophy ReverseGeocodingService's and
            // NominatimSearchService's own doc comments already describe.
            if (label != null && !addressManuallyEdited) {
                address = label
            }
        } finally {
            resolvingAddress = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDone, enabled = !formLoading) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (isEditing) "Edit Outlet" else "Add Outlet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isEditing && editingOutlet?.status == "APPROVED") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ContentCardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "This outlet is already approved. Your changes will be submitted for Admin " +
                                "review and won't take effect until approved.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 150) name = it },
                label = { Text("Outlet Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            OutlinedTextField(
                value = address,
                onValueChange = {
                    if (it.length <= 255) {
                        address = it
                        // Only ever reached by genuine user input (Compose
                        // never re-invokes onValueChange for a programmatic
                        // `address = ...` write elsewhere) -- see
                        // addressManuallyEdited's own declaration above for
                        // why this is checked-then-cleared per pin move
                        // rather than a permanent one-way latch. Unreachable
                        // in Add Outlet (readOnly below), still exercised by
                        // Edit Outlet, which keeps Address manually editable
                        // exactly as before.
                        addressManuallyEdited = true
                    }
                },
                label = { Text("Address") },
                // Add Outlet only: the Member sees the reverse-geocoded
                // result but cannot type into it -- moving the pin (drag,
                // Search Address, Use Current Location) is the only way to
                // change it, matching the field now always reflecting a
                // real, geocoded location rather than free text. Edit
                // Outlet keeps Address freely editable, unchanged.
                readOnly = !isEditing,
                supportingText = if (!isEditing) {
                    { Text("Automatically filled based on the pin location.") }
                } else null,
                trailingIcon = {
                    if (resolvingAddress) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 3,
                shape = RoundedCornerShape(16.dp)
            )

            Text(
                text = "LOCATION",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Address") },
                placeholder = { Text("e.g. Jalan Sudirman, Jakarta") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        searching -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        searchQuery.isNotEmpty() -> IconButton(onClick = {
                            searchQuery = ""
                            searchResults = emptyList()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            // Shown only once a real search has actually completed (query
            // long enough, not still debouncing/in flight) and came back
            // empty -- AddressSearchService.search() only returns empty once
            // BOTH Nominatim and its Photon fallback found nothing (or
            // failed), and each provider itself deliberately collapses "too
            // short", "lookup failed", and "genuinely no match" into the same
            // empty list (see NominatimSearchService.search's own doc
            // comment: this is a supplementary affordance, never worth
            // surfacing a raw network error for), so this message reads the
            // same to the Member either way: nothing came back for what they
            // typed, try a different term or place the pin manually.
            // `searching` being false here is what rules out the debounce
            // window and the in-flight fetch itself, so this can never flash
            // between keystrokes.
            val showNoSearchResultsMessage = !searching &&
                searchQuery.trim().length >= 3 &&
                searchResults.isEmpty()

            if (searchResults.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ContentCardShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column {
                        searchResults.forEachIndexed { index, result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Recorded before applyPickedPoint (which
                                        // changes pickedLat/pickedLng) so the
                                        // reverse-geocode LaunchedEffect above sees
                                        // a matching "authoritative" coordinate on
                                        // the relaunch this triggers, and skips --
                                        // this search result's own label is already
                                        // authoritative for this exact coordinate.
                                        lastAuthoritativeLat = result.latitude
                                        lastAuthoritativeLng = result.longitude
                                        applyPickedPoint(GeoPoint(result.latitude, result.longitude))
                                        address = result.label.take(255)
                                        searchQuery = ""
                                        searchResults = emptyList()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = result.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (index != searchResults.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            } else if (showNoSearchResultsMessage) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ContentCardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        // Title + caption, same combo already used for
                        // outlet.name/outlet.address above (bodySmall, second
                        // line in onSurfaceVariant) -- reused here rather than
                        // a new text style.
                        Column {
                            Text(
                                text = "Address not found.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Try another keyword, use a more specific address, or move the map manually.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onUseCurrentLocationClick() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !locatingCurrentPosition,
                shape = RoundedCornerShape(16.dp)
            ) {
                if (locatingCurrentPosition) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Use Current Location")
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(ContentCardShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ContentCardShape)
            ) {
                OutletMapPicker(
                    modifier = Modifier.fillMaxSize(),
                    initialCenter = initialCenter,
                    initialZoom = if (isEditing) PICKER_MAP_ZOOM else DEFAULT_MAP_ZOOM,
                    recenterRequest = recenterRequest,
                    onCenterChanged = { point ->
                        pickedLat = point.latitude
                        pickedLng = point.longitude
                    }
                )
            }

            Text(
                text = "Pin: %.6f, %.6f".format(pickedLat, pickedLng),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            formError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    if (isEditing && editingOutlet != null) {
                        viewModel.submitOutletEdit(
                            id = editingOutlet.id,
                            currentStatus = editingOutlet.status,
                            name = name,
                            address = address,
                            latitude = pickedLat,
                            longitude = pickedLng
                        ) { success, message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            if (success) onDone()
                        }
                    } else {
                        viewModel.submitNewOutlet(
                            name = name,
                            address = address,
                            latitude = pickedLat,
                            longitude = pickedLng
                        ) { success, message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            if (success) onDone()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !formLoading,
                shape = RoundedCornerShape(16.dp)
            ) {
                if (formLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (isEditing) "Submit Changes" else "Submit Outlet")
                }
            }

            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                enabled = !formLoading,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}

// --- Map picker ------------------------------------------------------------

/**
 * A requested recenter for [OutletMapPicker]. [zoom] is null to just pan
 * (Search Address's own selection, unchanged), or a target zoom level to
 * also jump to (the current-location fetch, so the Member lands on a
 * genuinely useful close-up view instead of having to pinch-zoom in
 * afterward). [nonce] lets the exact same [point] be requested twice in a
 * row (e.g. tapping "current location" again after wandering off, then
 * back) and still force a recenter, since a plain point-only comparison
 * could compare equal to the already-applied one and silently no-op the
 * second request.
 */
private data class MapRecenterRequest(val point: GeoPoint, val zoom: Double?, val nonce: Int)

/**
 * Gojek/Shopee-style location picker: the map pans freely under a pin icon
 * fixed at the screen's visual center (a plain Compose [Icon] overlay, not
 * an osmdroid [org.osmdroid.views.overlay.Marker] tied to a lat/lng -- a
 * Marker would move WITH the map on pan, defeating the whole point). The
 * "picked" coordinate is simply wherever the map is currently centered,
 * reported via [onCenterChanged] on every scroll/zoom.
 *
 * [recenterRequest] is the only way the caller can move the camera itself
 * (Use Current Location, tapping a search result) -- see [MapRecenterRequest]
 * for why it carries a zoom and a nonce alongside the point.
 *
 * Reuses the same osmdroid setup/lifecycle boilerplate as OsmMap.kt
 * (Configuration, tile source, ON_RESUME/ON_PAUSE observer, onDetach() on
 * release -- see that composable's own doc comments for why each line is
 * there) rather than being built on top of OsmMap itself: that composable's
 * API (marker list, route polyline) has no "read the current camera center"
 * concept, and forcing one in would complicate a composable three other
 * screens already depend on, for something only this feature needs.
 */
@Composable
private fun OutletMapPicker(
    modifier: Modifier = Modifier,
    initialCenter: GeoPoint,
    initialZoom: Double,
    recenterRequest: MapRecenterRequest?,
    onCenterChanged: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.filesDir
            osmdroidTileCache = File(context.cacheDir, "osmdroid")
        }
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // Refreshed on every recomposition (cheap field write, no allocation) so
    // the MapListener registered once in `factory` below never closes over a
    // stale onCenterChanged lambda -- the identical "unconditionally
    // refreshed callback reference" pattern OsmMap.kt already uses for its
    // own onMarkerClick/onRoutePointSelected, for the same reason (a fresh
    // lambda instance on every recomposition would otherwise go stale the
    // moment it's captured once in factory{}).
    val onCenterChangedRef = remember { mutableStateOf(onCenterChanged) }
    onCenterChangedRef.value = onCenterChanged

    val appliedRecenterNonce = remember { mutableIntStateOf(-1) }

    // osmdroid's MapView runs its own tile-download thread pool and keeps a
    // bitmap tile cache; not lifecycle-aware on its own -- same rationale as
    // OsmMap.kt's identical observer.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef.value?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().apply {
                    userAgentValue = ctx.packageName
                    osmdroidBasePath = ctx.filesDir
                    osmdroidTileCache = File(ctx.cacheDir, "osmdroid")
                    load(ctx, ctx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE))
                }
                val map = MapView(ctx)
                map.apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                    controller.setZoom(initialZoom)
                    controller.setCenter(initialCenter)
                    // `object : MapListener` is a genuine anonymous class body,
                    // not a lambda -- MapListener has two abstract methods, so
                    // it can't be a SAM conversion the way OsmMap.kt's single-
                    // method marker/polyline click listeners are. That means it
                    // does NOT implicitly see `apply`'s MapView receiver the
                    // way a lambda would; `map` (the local val captured by
                    // closure) is used explicitly instead of a bare `mapCenter`.
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            onCenterChangedRef.value(map.mapCenter as GeoPoint)
                            return true
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            onCenterChangedRef.value(map.mapCenter as GeoPoint)
                            return true
                        }
                    })
                    mapViewRef.value = this
                }
                map
            },
            onRelease = { mapView ->
                // Same cleanup OsmMap.kt performs on release -- stops
                // osmdroid's tile-download threads and releases its bitmap
                // cache the moment this composable permanently leaves
                // composition (Cancel/Submit navigating back to the list).
                mapView.onDetach()
                mapViewRef.value = null
            },
            update = { mapView ->
                recenterRequest?.let { request ->
                    if (request.nonce != appliedRecenterNonce.intValue) {
                        mapView.controller.animateTo(request.point)
                        // setZoom is instant (no animation of its own), same
                        // method this composable's own `factory` already uses
                        // for the initial zoom above -- paired here with the
                        // animated pan so both land together.
                        request.zoom?.let { mapView.controller.setZoom(it) }
                        appliedRecenterNonce.intValue = request.nonce
                        onCenterChangedRef.value(request.point)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Fixed at the visual center regardless of pan/zoom -- offset
        // upward so the pin's bottom TIP (not its visual center) marks the
        // actual picked coordinate, matching the Gojek/Shopee convention
        // this picker is modeled on.
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = "Selected location",
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-20).dp)
                .size(40.dp),
            tint = OutletStatusRejectedColor
        )
    }
}

// One-shot current-location fetch for the "Use Current Location" button
// above now lives in [com.trubus.tams.util.OneShotLocationProvider] --
// extracted unchanged so [com.trubus.tams.worker.LocationSyncWorker] can
// reuse the exact same implementation for its stale-fix stopgap instead of
// a second, duplicated copy. See that object's own doc comment.
