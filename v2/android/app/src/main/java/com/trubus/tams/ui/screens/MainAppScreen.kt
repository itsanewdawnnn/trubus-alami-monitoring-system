package com.trubus.tams.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.net.toUri
import com.trubus.tams.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.trubus.tams.data.geocoding.ReverseGeocodingService
import com.trubus.tams.data.model.HistoryPointDto
import com.trubus.tams.data.model.HistoryResponseDto
import com.trubus.tams.data.model.MemberCurrentLocationDto
import com.trubus.tams.data.model.TrackedLocationSnapshot
import com.trubus.tams.data.model.UserDto
import com.trubus.tams.data.update.UpdateFlowState
import com.trubus.tams.service.MemberLocationService
import com.trubus.tams.ui.viewmodel.MainViewModel
import com.trubus.tams.util.TrackingHealth
import com.trubus.tams.util.WibTime
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.*

// --- Shared Design Tokens ---
// One consistent corner-radius scale for the whole app instead of several
// close-but-different values that had drifted in screen by screen.
// [ContentCardShape] is used by ordinary in-flow content cards. Not private:
// ui/screens/OutletScreen.kt reuses it too, so its cards match every other
// content card in the app instead of a second, easy-to-drift 16.dp literal.
// [OverlayCardShape] is for cards that float above other content (the
// live-marker and route-point detail panels, plus UpdateDialog.kt's dialogs)
// given extra rounding plus real elevation at their call sites to convey
// that floating role. Not private: UpdateDialog.kt reuses it so the OTA
// update dialogs match every other dialog/floating card in the app instead
// of falling back to Material3's default shape.
val ContentCardShape = RoundedCornerShape(16.dp)
val OverlayCardShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    // Shown once per process launch, before the login screen or dashboard --
    // an early return so none of the state below (update checks, login
    // state) needs to know the splash exists. No artificial minimum timer
    // anymore -- stays up for exactly as long as
    // MainViewModel.validateSessionOnStartup() takes to resolve
    // (isValidatingSession), so a stored token is never trusted enough to
    // show the dashboard before the server has confirmed it's still valid
    // (see that function's doc comment), but nothing pads the wait beyond
    // that real check. No token stored at all resolves this to false
    // immediately (see isValidatingSession's own seed value), so a fresh
    // install goes straight to the login screen with no splash at all.
    val isValidatingSession by viewModel.isValidatingSession.collectAsState()
    if (isValidatingSession) {
        SplashScreen()
        return
    }

    val context = LocalContext.current
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentUser by viewModel.user.collectAsState()

    // --- OTA Update ---
    // Deliberately outside Scaffold's content and independent of
    // isLoggedIn: a force update must be able to block the app whether the
    // login screen or a dashboard is what's currently showing underneath.
    val updateState by viewModel.updateState.collectAsState()

    // ReadyToInstall has no visual representation of its own (see
    // UpdateDialog's doc comment) -- launching the system installer is a
    // side effect, so it belongs here, not inside that composable's render
    // body. Keyed on updateState itself (a data class, compared by value) so
    // this only re-fires when the state actually changes to a new
    // ReadyToInstall, not on every unrelated recomposition.
    LaunchedEffect(updateState) {
        (updateState as? UpdateFlowState.ReadyToInstall)?.let { ready ->
            viewModel.launchInstall(ready.apkFile)
        }
    }

    // Re-checks install permission when the app resumes -- covers the user
    // returning from the "install unknown apps" settings screen
    // (UpdateDialog's "Open Settings" button) without needing an
    // ActivityResultLauncher just to notice they came back. Also
    // re-verifies/re-engages the update flow itself on every foreground
    // return (Recents, Force Stop + reopen, device restart, returning from
    // the Package Installer) -- see MainViewModel.onAppResumed()'s doc
    // comment for why this can't just rely on the once-per-process init{}
    // check.
    LifecycleResumeEffect(Unit) {
        viewModel.recheckInstallPermission()
        viewModel.onAppResumed()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "REAL-TIME LOCATION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            // Full expanded name, distinct from the short
                            // "TAMS" launcher label (R.string.app_name) --
                            // lengthening this one must never affect the
                            // home-screen icon label. Ellipsis safety net
                            // since it must share the bar with the nav icon
                            // and logout action on narrow screens.
                            text = stringResource(R.string.app_full_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                navigationIcon = {
                    // Same MyLocation glyph as LoginScreen's brand badge --
                    // one consistent mark for the app's identity.
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp).size(20.dp)
                    )
                },
                actions = {
                    if (isLoggedIn) {
                        IconButton(
                            onClick = { viewModel.logout() },
                            modifier = Modifier.testTag("logout_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Log Out",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (updateState.isForced) {
                // Structural gate, not just an overlay dialog: the real
                // login/dashboard content is never composed at all while a
                // mandatory update is pending, in ANY sub-state including
                // ReadyToInstall. UpdateDialog (below) supplies the actual
                // title/progress/buttons on top of this -- this block exists
                // so that even a dialog-rendering gap (the original bug:
                // ReadyToInstall used to render no dialog UI at all) can
                // never leave the real screen reachable underneath.
                ForceUpdateBlockingScreen()
            } else if (!isLoggedIn) {
                LoginScreen(viewModel = viewModel)
            } else {
                when (currentUser?.role) {
                    "member" -> MemberRootScreen(viewModel = viewModel)
                    "admin" -> AdminDashboard(viewModel = viewModel)
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Invalid role: ${currentUser?.role}")
                        }
                    }
                }
            }
        }
    }

    UpdateDialog(
        state = updateState,
        onLaterClick = viewModel::dismissUpdate,
        onUpdateClick = viewModel::startUpdateDownload,
        onRetryClick = viewModel::retryUpdateDownload,
        onOpenSettingsClick = { context.startActivity(viewModel.installPermissionSettingsIntent()) },
        onInstallClick = {
            (updateState as? UpdateFlowState.ReadyToInstall)?.let { viewModel.launchInstall(it.apkFile) }
        }
    )
}

/**
 * Rendered in place of the login screen / dashboard whenever
 * [UpdateFlowState.isForced] is true, for as long as this device is on a
 * version the server has marked mandatory to replace -- see this file's
 * MainAppScreen doc comment on why this is a structural gate rather than
 * relying solely on [UpdateDialog]'s overlay. Deliberately inert (no
 * buttons, no text worth reading twice) -- [UpdateDialog] is what actually
 * communicates with the user; this composable's only job is to guarantee
 * nothing real is ever left visible/interactive underneath it.
 */
@Composable
private fun ForceUpdateBlockingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.MyLocation,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            modifier = Modifier.size(64.dp)
        )
    }
}

// --- LOGIN SCREEN ---

@Composable
fun LoginScreen(viewModel: MainViewModel) {
    val isLoading by viewModel.isLoading.collectAsState()
    val loginError by viewModel.loginError.collectAsState()
    val sessionValidationError by viewModel.sessionValidationError.collectAsState()
    val canRetrySessionValidation by viewModel.canRetrySessionValidation.collectAsState()
    val isValidatingSession by viewModel.isValidatingSession.collectAsState()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        var passwordVisible by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        fun submitLogin() {
            keyboardController?.hide()
            focusManager.clearFocus()
            viewModel.login(username, password)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 450.dp),
            shape = ContentCardShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            // No elevation -- every card in this app is flat/bordered, no drop shadow.
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Flat brand-color fill, not a gradient -- matches the app's
                // plain/bordered visual language used elsewhere.
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Log in to your account.",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Please enter your credentials to access your account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Shown after MainViewModel.validateSessionOnStartup() (or a
                // manual "Try Again") fails -- explains WHY the user landed
                // back on this screen despite already having a saved
                // session, distinct from [loginError] below which only ever
                // comes from submitting this form. See that function's doc
                // comment for the 401/403-vs-connectivity distinction behind
                // canRetrySessionValidation.
                if (sessionValidationError != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = sessionValidationError ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                            if (canRetrySessionValidation) {
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(
                                    onClick = { viewModel.retrySessionValidation() },
                                    enabled = !isValidatingSession
                                ) {
                                    Text(if (isValidatingSession) "CHECKING..." else "TRY AGAIN")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                val passwordFocusRequester = remember { FocusRequester() }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("username_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // IME "Done" submits directly from the keyboard.
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!isLoading) submitLogin() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input")
                        .focusRequester(passwordFocusRequester)
                )

                if (loginError != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = loginError ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { submitLogin() },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("login_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text("SIGN IN", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// --- MEMBER TRACKING DASHBOARD ---

/**
 * Devices before Android M have no battery-optimization whitelist concept,
 * so they're treated as "already fine" rather than showing a card whose
 * button couldn't do anything on that OS version.
 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private const val MEMBER_TAB_DASHBOARD = 0
private const val MEMBER_TAB_OUTLET = 1

/**
 * Member-role root: a thin TabRow wrapper added on top of the existing
 * [MemberDashboard] (Tracking/Profile/Trip Detail) to add the Outlet
 * feature's "Outlet" tab alongside it -- mirrors [AdminDashboard]'s own
 * TabRow + local `remember` tab-selection pattern in this same file (see
 * that composable's own `selectedTab` for the precedent).
 *
 * Deliberately does NOT touch [MemberDashboard]'s body at all -- that
 * composable (permission launchers, the LOCATION_BROADCAST receiver,
 * tracking/profile state) is reused completely unmodified, just nested one
 * level deeper, specifically so none of its existing, already-relied-upon
 * behavior is at any risk of regressing. Switching to the Outlet tab
 * unmounts MemberDashboard from composition (same as AdminDashboard's own
 * tabs already do for Active Members/Map/History), which pauses its
 * LOCATION_BROADCAST listening and 3s local-state polling -- this does NOT
 * pause actual GPS tracking (MemberLocationService is a foreground Service
 * independent of any Composable's lifecycle, and isTrackingActive lives in
 * MainViewModel, not in MemberDashboard); switching back simply
 * recomposes it fresh and it immediately re-reads the latest persisted fix
 * (see MemberRepository.lastKnownLocation's own doc comment).
 */
@Composable
fun MemberRootScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(MEMBER_TAB_DASHBOARD) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            Tab(
                selected = selectedTab == MEMBER_TAB_DASHBOARD,
                onClick = { selectedTab = MEMBER_TAB_DASHBOARD },
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                text = { Text("Dashboard", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == MEMBER_TAB_OUTLET,
                onClick = { selectedTab = MEMBER_TAB_OUTLET },
                icon = { Icon(Icons.Default.Storefront, contentDescription = null) },
                text = { Text("Outlet", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                MEMBER_TAB_DASHBOARD -> MemberDashboard(viewModel = viewModel)
                MEMBER_TAB_OUTLET -> MemberOutletScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MemberDashboard(viewModel: MainViewModel) {
    val context = LocalContext.current
    val currentUser by viewModel.user.collectAsState()
    val isTrackingActive by viewModel.isTrackingActive.collectAsState()
    val lastLocation by viewModel.lastTrackedLocation.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val memberTripSummary by viewModel.memberTripSummary.collectAsState()
    val trackingAutoStoppedMessage by viewModel.trackingAutoStoppedMessage.collectAsState()

    var showBackgroundPermissionDialog by remember { mutableStateOf(false) }

    // Force Location's ON->OFF auto-revoke: MemberLocationService can stop
    // tracking on its own (see MainViewModel.handleTrackingNotAllowedByService's
    // doc comment) with no button press involved -- this is the one place
    // that surfaces WHY to the Member, once, as a Toast. Consumed
    // immediately so it never re-shows on an unrelated recomposition.
    LaunchedEffect(trackingAutoStoppedMessage) {
        if (trackingAutoStoppedMessage != null) {
            Toast.makeText(context, trackingAutoStoppedMessage, Toast.LENGTH_LONG).show()
            viewModel.consumeTrackingAutoStoppedMessage()
        }
    }

    // Wall-clock reference that forces periodic re-evaluation of staleness
    // below (see `isLocationStale`) -- if tracking stalls, `lastLocation`
    // never changes, so nothing else would prompt Compose to re-check
    // elapsed time on its own. Updated on the same 3s tick as the freshness
    // poll below.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Re-reads the persisted last fix from disk every few seconds while
    // tracking is active, instead of relying solely on MemberLocationService's
    // LOCATION_BROADCAST -- on some OEM battery managers that broadcast can
    // be throttled even while the service's own network calls keep working,
    // which previously left this card stuck on "No location yet". A local
    // SharedPreferences read only (no network/GPS), so it's cheap.
    LaunchedEffect(isTrackingActive) {
        if (isTrackingActive) {
            while (true) {
                viewModel.refreshLastKnownLocation()
                nowMillis = System.currentTimeMillis()
                delay(3000L)
            }
        }
    }

    // "Trip Detail" is sourced from the same /location/history call
    // Admin's Trip History uses, not computed locally, so it doesn't
    // share the 3s poll loop above -- refreshed immediately on start/stop
    // (so Stop shows final numbers right away) and every 30s while tracking.
    LaunchedEffect(isTrackingActive) {
        if (isTrackingActive) {
            while (true) {
                viewModel.refreshMemberTripSummary()
                delay(30_000)
            }
        } else {
            viewModel.refreshMemberTripSummary()
        }
    }

    // Surfaces tracking-stalled to the Member, not just the Admin's live map
    // (which already had a staleness fallback). 90s matches the server's own
    // OFFLINE_STALE_SECONDS threshold (backend/api.php) so both views of
    // "is this still live" agree -- deliberately a fixed constant, NOT
    // TrackingHealth.staleThresholdMillis() (which scales with the
    // Remote-Management-configurable GPS interval, a different "has the
    // watchdog kicked in yet" concept used by MemberLocationService/
    // LocationSyncWorker) -- only the age CALCULATION below is shared with
    // those two, not this threshold.
    //
    // `nowMillis` is listed as a remember() key purely to force this block
    // to re-run every ~3s (see that variable's own doc comment) -- the age
    // itself now comes from TrackingHealth.elapsedMillisSince's own fresh
    // System.currentTimeMillis() read, not from reading `nowMillis` directly,
    // so this is the same single age-calculation (and the same clock-rollback
    // guard, see that function's doc comment) LocationSyncWorker's stale-fix
    // stopgap already uses -- previously duplicated here with its own
    // parse-and-subtract, including the same clock-rollback exposure that
    // function used to have before it was hardened.
    val isLocationStale = remember(lastLocation, nowMillis, isTrackingActive) {
        if (!isTrackingActive || (lastLocation == null)) {
            false
        } else {
            val ageMs = TrackingHealth.elapsedMillisSince(lastLocation!!.time)
            ageMs != null && ageMs > MEMBER_LOCATION_STALE_THRESHOLD_MS
        }
    }

    // Once true, the battery optimization card below is hidden. Re-checked
    // on every resume since the member grants this from a system Settings
    // screen outside the app, so it can change while this composable is
    // merely paused, not recreated.
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    LifecycleResumeEffect(Unit) {
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose {}
    }

    val batteryOptimizationCardDismissed by viewModel.batteryOptimizationCardDismissed.collectAsState()

    // Auto-clear the manual dismiss the instant the real check succeeds --
    // it only exists to unstick a lagging/misreporting device.
    LaunchedEffect(isIgnoringBatteryOptimizations) {
        if (isIgnoringBatteryOptimizations && batteryOptimizationCardDismissed) {
            viewModel.setBatteryOptimizationCardDismissed(false)
        }
    }

    // Bumped when the member returns from the battery-optimization Settings
    // screen; drives one extra delayed re-check since some OEM ROMs
    // (observed: iQOO/vivo OriginOS 6) take a moment to propagate a
    // just-granted exemption. Bounded, one-shot -- not a polling loop.
    var batteryOptimizationRecheckTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(batteryOptimizationRecheckTrigger) {
        if (batteryOptimizationRecheckTrigger > 0) {
            delay(1500)
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        }
    }

    // StartActivityForResult gives a definitive callback the instant the
    // Settings screen closes, rather than relying solely on LifecycleResumeEffect.
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        batteryOptimizationRecheckTrigger++
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val lat = intent.getDoubleExtra(MemberLocationService.EXTRA_LATITUDE, 0.0)
                val lng = intent.getDoubleExtra(MemberLocationService.EXTRA_LONGITUDE, 0.0)
                val acc = intent.getFloatExtra(MemberLocationService.EXTRA_ACCURACY, 0.0f)
                val speed = intent.getFloatExtra(MemberLocationService.EXTRA_SPEED, 0.0f)
                val time = intent.getStringExtra(MemberLocationService.EXTRA_TIME) ?: ""

                viewModel.updateLastLocation(lat, lng, acc, speed, time)

                if (intent.getBooleanExtra(MemberLocationService.EXTRA_SESSION_INVALID, false)) {
                    viewModel.handleSessionInvalidatedByService()
                } else if (intent.getBooleanExtra(MemberLocationService.EXTRA_TRACKING_NOT_ALLOWED, false)) {
                    viewModel.handleTrackingNotAllowedByService()
                }
            }
        }
        val filter = IntentFilter(MemberLocationService.ACTION_LOCATION_BROADCAST)
        
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        
        if (fineGranted || coarseGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bgGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!bgGranted) {
                    showBackgroundPermissionDialog = true
                } else {
                    viewModel.requestStartTracking { errorMessage ->
                        errorMessage?.let {
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                viewModel.requestStartTracking { errorMessage ->
                    if (errorMessage != null) {
                        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, "Location permission is required for the app to work properly.", Toast.LENGTH_LONG).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Tracking must not be blocked on notification permission -- proceed
        // to location permissions either way.
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Entry point for the Start button: on API 33+, a foreground service's
    // notification requires POST_NOTIFICATIONS at runtime, asked for first.
    // Falls through to location permissions either way -- tracking must
    // still work even if notifications are denied.
    fun beginStartTrackingFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!notifGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Member Profile Header Card -- tap to edit inline, no navigation.
        item {
            MemberProfileCard(viewModel = viewModel, currentUser = currentUser)
        }

        // Live Tracking Status & Controller. The offline queue syncs
        // automatically (opportunistically, via WorkManager, and via a
        // watchdog pass), so there's nothing left for the member to trigger.
        item {
            TrackingStatusCard(
                isTrackingActive = isTrackingActive,
                onToggleClick = {
                    if (!isTrackingActive) {
                        beginStartTrackingFlow()
                    } else if (!viewModel.stopTracking()) {
                        Toast.makeText(context, "Failed to stop sending location. Try again.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        item {
            LastCoordinateCard(
                lastLocation = lastLocation,
                isLocationStale = isLocationStale,
                lastSyncTime = lastSyncTime
            )
        }

        // Trip Detail: sourced from viewModel.memberTripSummary, the
        // same /location/history data Admin sees for this member + today.
        // Deliberately not hidden by isTrackingActive -- reflects today's
        // history so far, so it keeps showing the last trip's numbers after
        // Stop. "GPS Point Count" is omitted -- only useful for Admin's
        // data-quality sanity check, not for a Member.
        item {
            TripSummaryCard(summary = memberTripSummary)
        }

        // Only shown while there's still something for the member to do.
        if (!isIgnoringBatteryOptimizations && !batteryOptimizationCardDismissed) {
            item {
                BatteryOptimizationCard(
                            onRequestExemption = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                            batteryOptimizationLauncher.launch(intent)
                        } catch (e: Exception) {
                            Log.e("MemberDashboard", "Battery optimization intent failed: ${e.message}")
                        }
                    },
                    onDismiss = { viewModel.setBatteryOptimizationCardDismissed(true) }
                )
            }
        }
    }

    if (showBackgroundPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundPermissionDialog = false },
            // Matches OverlayCardShape, used by every other floating surface
            // in the app, instead of Material3's default 28dp dialog shape.
            shape = OverlayCardShape,
            title = { Text("Allow Background Location") },
            text = {
                Text(
                    "The app needs 'Allow all the time' location access so location data keeps sending when the app is minimized or the screen is locked.\n\n" +
                    "Please tap 'Settings', select 'Permissions', then set location to 'Allow all the time'."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackgroundPermissionDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Text("SETTINGS")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackgroundPermissionDialog = false
                        // Makes the requirement explicit instead of a silent
                        // dead end where START appears to do nothing.
                        Toast.makeText(
                            context,
                            "'Allow all the time' location access is required for location sending to keep running.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                ) {
                    Text("CANCEL")
                }
            }
        )
    }
}

/**
 * Live tracking status + Start/Stop toggle. Pure UI: all side-effecting
 * logic (permission flow, start/stop calls) stays owned by the caller and
 * is passed in as [onToggleClick], so this has no dependency on Context,
 * ViewModel, or ActivityResult launchers.
 */
@Composable
private fun TrackingStatusCard(isTrackingActive: Boolean, onToggleClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (isTrackingActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "MEMBER LOCATION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTrackingActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            // Idle is a normal, expected state, not a problem -- uses a
                            // neutral tone, reserving error/red for things actually wrong.
                            .background(if (isTrackingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    )
                    Text(
                        text = if (isTrackingActive) "Active" else "Inactive",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 48dp tall to meet the minimum recommended touch target size.
            Button(
                onClick = onToggleClick,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTrackingActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag(if (isTrackingActive) "stop_tracking_button" else "start_tracking_button")
            ) {
                Icon(
                    imageVector = if (isTrackingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isTrackingActive) "STOP" else "START",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

/** Last recorded GPS fix + staleness warning -- see TrackingStatusCard's doc comment for why this was extracted. */
@Composable
private fun LastCoordinateCard(
    lastLocation: TrackedLocationSnapshot?,
    isLocationStale: Boolean,
    lastSyncTime: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "LAST COORDINATE STATUS",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (lastLocation == null) {
                Text(
                    text = "No location recorded yet. Tap 'START' to begin sending GPS coordinates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Tells the Member directly when tracking has stalled,
                // instead of silently keeping the last known fix on screen
                // with no indication anything is wrong -- see
                // isLocationStale's doc comment (MemberDashboard).
                if (isLocationStale) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SyncProblem,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "No recent GPS update. Check signal/connection -- the app will try to recover automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                DetailRow("Time Recorded", lastLocation.time)
                DetailRow("Latitude", "%.7f".format(lastLocation.latitude))
                DetailRow("Longitude", "%.7f".format(lastLocation.longitude))
                DetailRow("Accuracy (GPS)", "%.1f meter".format(lastLocation.accuracy))
                DetailRow("Travel Speed", "%.2f m/s".format(lastLocation.speed))
                if (lastSyncTime != "-") {
                    DetailRow("Server Sync", lastSyncTime)
                }
            }
        }
    }
}

/** Today's trip distance/duration/time-range summary -- see TrackingStatusCard's doc comment for why this was extracted. */
@Composable
private fun TripSummaryCard(summary: HistoryResponseDto?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "TRIP DETAIL",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (summary == null || summary.total_points == 0) {
                Text(
                    text = "No trip recorded today yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Same formatting as Admin's HistoryStatTile, for consistency.
                val rangeText = "${timeOfDay(summary.start_time)}–${timeOfDay(summary.end_time)}"

                DetailRow("Total Distance", "${summary.total_distance_km} km")
                DetailRow("Duration", summary.duration_formatted)
                DetailRow("Time Range", "$rangeText WIB")
            }
        }
    }
}

/**
 * Battery-optimization exemption prompt. [onRequestExemption] owns the
 * actual Settings intent + ActivityResult launcher (stays in
 * MemberDashboard); this composable is pure UI.
 */
@Composable
private fun BatteryOptimizationCard(onRequestExemption: () -> Unit, onDismiss: () -> Unit) {
    Card(
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Background Optimization",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Battery optimization must be disabled for the app to work properly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRequestExemption,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BatteryAlert, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ignore Battery Optimization")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "For Xiaomi/Redmi/POCO (MIUI/HyperOS), OPPO/realme (ColorOS), " +
                        "vivo (FuntouchOS/OriginOS), or Huawei/Honor (EMUI) devices, enable " +
                        "\"Autostart\" and set battery usage to " +
                        "\"No restrictions\" so the system doesn't stop " +
                        "the app while it runs in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Escape hatch for the rare case where the automatic check keeps
            // disagreeing. Low-emphasis text action -- most members never need it.
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I've already granted it, hide this reminder")
            }
        }
    }
}

/**
 * Member profile card: shows Name/Username/Note in view mode; tapping it
 * morphs the same card in place into an inline edit form -- no navigation,
 * no new screen. Uses [AnimatedContent]'s default transition, already
 * smooth enough for this app's lightweight requirement on low-end devices.
 */
@Composable
private fun MemberProfileCard(viewModel: MainViewModel, currentUser: UserDto?) {
    val context = LocalContext.current
    val isEditing by viewModel.isEditingProfile.collectAsState()
    val isSaving by viewModel.profileUpdateLoading.collectAsState()
    val updateError by viewModel.profileUpdateError.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!isEditing) {
                    Modifier.clickable { viewModel.setEditingProfile(true) }
                } else {
                    Modifier
                }
            ),
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        AnimatedContent(targetState = isEditing, label = "profile_card") { editing ->
            if (!editing) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Badge,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(48.dp)
                            .padding(end = 12.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentUser?.name ?: "Member Roster",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Username: ${currentUser?.username}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val note = currentUser?.note
                        if (!note.isNullOrBlank()) {
                            Text(
                                text = "Note: $note",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit profile",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // Name is not mutable state: the Member can no longer edit
                // it, so it's read straight from currentUser and shown read-only.
                val name = currentUser?.name ?: ""
                var username by remember(currentUser) { mutableStateOf(currentUser?.username ?: "") }
                var note by remember(currentUser) { mutableStateOf(currentUser?.note ?: "") }
                var password by remember(currentUser) { mutableStateOf("") }
                var passwordVisible by remember { mutableStateOf(false) }

                val noteFocus = remember { FocusRequester() }
                val passwordFocus = remember { FocusRequester() }
                val focusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current

                fun trySave() {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    val passwordArg = password.ifBlank { null }
                    viewModel.updateProfile(name, username, note, passwordArg) { success, message ->
                        Toast.makeText(context, message, if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                        if (success) password = ""
                    }
                }

                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Edit Profile",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // `enabled = false` gives Material3's built-in muted
                    // "disabled" styling, so this reads as view-only, not broken.
                    OutlinedTextField(
                        value = name,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text("Name") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { noteFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (Visible to Admin)") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null) },
                    singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Text),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(noteFocus)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("New Password (optional)") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        supportingText = { Text("Leave blank if you don't want to change the password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!isSaving) trySave() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocus)
                    )

                    if (updateError != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = updateError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.setEditingProfile(false) },
                            enabled = !isSaving,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("CANCEL")
                        }
                        Button(
                            onClick = { trySave() },
                            enabled = !isSaving,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("SAVE")
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- ADMIN DASHBOARD ---

// Tab indices for AdminDashboard's TabRow -- named constants instead of
// bare 0/1/2 so every LaunchedEffect/LifecycleResumeEffect/when branch below
// stays correct and self-explanatory if the tab order ever changes again.
private const val ADMIN_TAB_ACTIVE_MEMBER = 0
private const val ADMIN_TAB_MAP = 1
private const val ADMIN_TAB_HISTORY = 2

/**
 * Shared compact "icon + label + value" stat card used for both the Active
 * Member and Currently Moving summaries on AdminDashboard -- guarantees they
 * stay visually identical instead of two copies drifting out of sync.
 */
@Composable
private fun BentoStatCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    subValue: String
) {
    Card(
        modifier = modifier,
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = value,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subValue,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun AdminDashboard(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(ADMIN_TAB_ACTIVE_MEMBER) }
    val currentLocations by viewModel.currentLocations.collectAsState()
    val activeMemberCount = currentLocations.count { it.status == "active" }
    val totalMemberCount = currentLocations.size
    // How many currently-active members are actually moving -- an actionable,
    // at-a-glance signal for an admin overseeing field workers.
    val movingMemberCount = currentLocations.count { it.status == "active" && it.is_moving }

    // Lifted to this level (not inside AdminMapScreen) so it survives
    // switching away from and back to the Map tab, and so the Active Members
    // tab can set it before switching tabs. Looked up live against
    // currentLocations below rather than a snapshot, so the detail card
    // never shows a stale copy from the moment it was tapped.
    var focusedMemberId by remember { mutableStateOf<Int?>(null) }

    // Refetches on every open (not just first composition) so the dropdown
    // self-heals if the first load failed transiently, instead of staying
    // permanently empty for the rest of the session.
    LaunchedEffect(selectedTab) {
        if (selectedTab == ADMIN_TAB_HISTORY) {
            viewModel.fetchMemberList()
        }
    }

    // LifecycleResumeEffect (not plain DisposableEffect) pauses polling the
    // instant the app is backgrounded, not just on tab switch -- otherwise
    // the 5s poll kept hitting the API indefinitely in the background.
    // Active Members and Real-Time Map share this one poll/stream.
    LifecycleResumeEffect(selectedTab) {
        if (selectedTab == ADMIN_TAB_ACTIVE_MEMBER || selectedTab == ADMIN_TAB_MAP) {
            viewModel.startLocationPolling()
        }
        onPauseOrDispose {
            viewModel.stopLocationPolling()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Same layout order on all three tabs: Tab Row, then summary cards,
        // then tab content -- previously this jumped position between tabs.
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            Tab(
                selected = selectedTab == ADMIN_TAB_ACTIVE_MEMBER,
                onClick = { selectedTab = ADMIN_TAB_ACTIVE_MEMBER },
                icon = { Icon(Icons.Default.People, contentDescription = null) },
                text = { Text("Active Members", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == ADMIN_TAB_MAP,
                onClick = { selectedTab = ADMIN_TAB_MAP },
                icon = { Icon(Icons.Default.Map, contentDescription = null) },
                text = { Text("Real-Time Map", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == ADMIN_TAB_HISTORY,
                onClick = { selectedTab = ADMIN_TAB_HISTORY },
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                text = { Text("Member History", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
        }

        // Hidden on Member History so that screen's filter/stats/map get the
        // full available height instead of competing with an unrelated summary.
        if (selectedTab != ADMIN_TAB_HISTORY) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BentoStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.People,
                    label = "Active Members",
                    value = activeMemberCount.toString(),
                    subValue = "/$totalMemberCount"
                )
                BentoStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Outlined.DirectionsWalk,
                    label = "Currently Moving",
                    value = movingMemberCount.toString(),
                    subValue = "/$activeMemberCount"
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                ADMIN_TAB_ACTIVE_MEMBER -> ActiveMemberScreen(
                    currentLocations = currentLocations,
                    onMemberSelected = { loc ->
                        focusedMemberId = loc.user_id
                        selectedTab = ADMIN_TAB_MAP
                    }
                )
                ADMIN_TAB_MAP -> AdminMapScreen(
                    currentLocations = currentLocations,
                    focusedMemberId = focusedMemberId,
                    onFocusedMemberIdChange = { focusedMemberId = it }
                )
                ADMIN_TAB_HISTORY -> AdminHistoryScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * Lists every currently-active (online) member so an Admin can find someone
 * without hunting for their marker on the map first. Tapping a row jumps to
 * the Real-Time Map centered on that member, reusing the same currentLocations
 * data already in memory -- no reload on tab switch.
 */
@Composable
fun ActiveMemberScreen(
    currentLocations: List<MemberCurrentLocationDto>,
    onMemberSelected: (MemberCurrentLocationDto) -> Unit
) {
    // Only "active" (online) members -- an offline member has no position
    // worth jumping to. Already alphabetically sorted by the backend
    // (/location/current's ORDER BY u.name ASC), so no client-side sort needed.
    val activeMembers = remember(currentLocations) {
        currentLocations.filter { it.status == "active" }
    }

    if (activeMembers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.PeopleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No members are currently active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    // 12dp, matching AdminHistoryScreen's padding, so switching tabs doesn't
    // show a visible margin jump.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(activeMembers, key = { it.user_id }) { loc ->
            ActiveMemberListItem(loc = loc, onClick = { onMemberSelected(loc) })
        }
    }
}

/**
 * One row in [ActiveMemberScreen]: name, live movement status, freshness.
 * Reuses [movementSnippet] rather than re-deriving the wording, so it stays
 * one source of truth shared with the map's marker/detail card.
 */
@Composable
private fun ActiveMemberListItem(loc: MemberCurrentLocationDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Matches the app's other compacted cards' 12/10dp scale --
                // this list can hold every active member, so tighter rows
                // mean more visible without scrolling.
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = loc.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (loc.is_moving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = movementSnippet(loc),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Last update: ${formatToWIB(loc.updated_at)} WIB",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (loc.note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Note: ${loc.note}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View on map",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * @param focusedMemberId Which member (by id) should be shown as
 * selected/centered, or null for none. Owned by the caller (AdminDashboard)
 * so the selection survives switching tabs and back.
 */
@Composable
fun AdminMapScreen(
    currentLocations: List<MemberCurrentLocationDto>,
    focusedMemberId: Int?,
    onFocusedMemberIdChange: (Int?) -> Unit
) {
    // Looked up live from currentLocations rather than storing the tapped
    // object, so the detail card reflects the latest poll, not a stale copy.
    val selectedMemberLoc = remember(focusedMemberId, currentLocations) {
        currentLocations.find { it.user_id == focusedMemberId }
    }

    // `remember`'d against currentLocations (not recomputed on every
    // recomposition, e.g. one triggered only by focusedMemberId changing) --
    // MapMarkerData no longer carries a callback (see its doc comment in
    // OsmMap.kt), so this list's content now genuinely reflects "did the
    // renderable data change", which is exactly what OsmMap's own diffing
    // relies on to skip untouched markers.
    val mapMarkers = remember(currentLocations) {
        currentLocations.filter { it.latitude != null && it.longitude != null }.map { loc ->
            MapMarkerData(
                id = loc.user_id,
                position = GeoPoint(loc.latitude!!, loc.longitude!!),
                title = loc.name,
                snippet = movementSnippet(loc),
                isActive = loc.status == "active"
            )
        }
    }

    // 12dp margin, matching AdminHistoryScreen's outer padding, to give the
    // map a bit more width.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(ContentCardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ContentCardShape)
            .background(MaterialTheme.colorScheme.surface)
    ) {
            if (mapMarkers.isEmpty()) {
                OsmMap(
                    modifier = Modifier.fillMaxSize(),
                    center = GeoPoint(-6.200000, 106.816666), // Jakarta, as a default
                    zoomLevel = 11.0,
                    markers = emptyList()
                )
                
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Waiting for member coordinates. Scanning for data...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                OsmMap(
                    modifier = Modifier.fillMaxSize(),
                    // selectedMemberLoc comes from ActiveMemberScreen's tap-to-focus
                    // flow, which only filters by status == "active" -- unlike
                    // mapMarkers' own lat/lng != null filter above. A member can be
                    // "active" with not-yet-populated coordinates, so guard both
                    // fields here too instead of asserting them non-null.
                    center = selectedMemberLoc?.let { loc ->
                        val lat = loc.latitude
                        val lng = loc.longitude
                        if (lat != null && lng != null) GeoPoint(lat, lng) else null
                    } ?: mapMarkers.first().position,
                    zoomLevel = 14.0,
                    markers = mapMarkers,
                    onMarkerClick = onFocusedMemberIdChange
                )
            }

            // Plain (non-modal) card, not a full ModalBottomSheet -- a modal
            // scrim would fight the map for focus while this panel is open.
            // The drag-handle bar below is a purely visual dismissible-panel cue.
            AnimatedVisibility(
                visible = selectedMemberLoc != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                selectedMemberLoc?.let { loc ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            // Consumes taps so they don't fall through to the
                            // map/markers underneath this overlay.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            ),
                        shape = OverlayCardShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        // Elevation is meaningful here (unlike most cards in
                        // this app): this card floats above the map, and a
                        // shadow correctly conveys that z-order/layering.
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            // Drag-handle affordance bar
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .width(32.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = loc.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    if (loc.note.isNotBlank()) {
                                        Text(
                                            text = "Note: ${loc.note}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                IconButton(onClick = { onFocusedMemberIdChange(null) }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close detail"
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Status + movement shown together in one compact
                            // row instead of two stacked rows.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (loc.status == "active") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (loc.status == "active") "ONLINE" else "OFFLINE",
                                        fontWeight = FontWeight.Bold,
                                        color = if (loc.status == "active") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp
                                    )
                                }

                                if (loc.status == "active") {
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (loc.is_moving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (loc.is_moving) "MOVING" else "STATIONARY",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (loc.is_moving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Reverse-geocoded landmark hint alongside the raw
                            // coordinates -- easier for an Admin to recognize
                            // at a glance than a lat/lng pair. Keyed on
                            // coordinates rounded to 4 decimals (~11m), backed
                            // by ReverseGeocodingService's cache, so this
                            // doesn't fire a fresh request on every 5s poll --
                            // only when the rounded position actually changes.
                            var nearbyLabel by remember(loc.user_id) { mutableStateOf<String?>(null) }
                            val roundedLat = loc.latitude?.let { kotlin.math.round(it * 10000.0) }
                            val roundedLon = loc.longitude?.let { kotlin.math.round(it * 10000.0) }
                            LaunchedEffect(loc.user_id, roundedLat, roundedLon) {
                                nearbyLabel = if (loc.latitude != null && loc.longitude != null) {
                                    ReverseGeocodingService.getNearbyLabel(loc.latitude, loc.longitude)
                                } else {
                                    null
                                }
                            }
                            nearbyLabel?.let { label ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Place,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                            DetailRow("Latitude", loc.latitude?.toString() ?: "-")
                            DetailRow("Longitude", loc.longitude?.toString() ?: "-")
                            DetailRow("GPS Accuracy", if (loc.accuracy != null) "±%.1f m".format(loc.accuracy) else "-")
                            DetailRow("Last Updated", "${formatToWIB(loc.updated_at)} WIB")
                        }
                    }
                }
            }
        }
}

/**
 * Detail overlay for a tapped point on the Trip History polyline (see
 * OsmMap's onRoutePointSelected). Mirrors AdminMapScreen's live-marker
 * detail card in structure/styling for consistency.
 */
@Composable
private fun RoutePointDetailCard(point: HistoryPointDto, onClose: () -> Unit) {
    var nearbyLabel by remember(point.recorded_at) { mutableStateOf<String?>(null) }
    LaunchedEffect(point.latitude, point.longitude) {
        nearbyLabel = ReverseGeocodingService.getNearbyLabel(point.latitude, point.longitude)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            // Consumes taps so they don't fall through to the map/polyline
            // underneath this overlay.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        shape = OverlayCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // Elevation kept here (unlike the flat list cards) since this floats
        // above the map, and a shadow correctly conveys that z-order.
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatToWIB(point.recorded_at)} WIB",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close point detail",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            nearbyLabel?.let { label ->
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Place,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            DetailRow("Latitude", point.latitude.toString())
            DetailRow("Longitude", point.longitude.toString())
            DetailRow("GPS Accuracy", "±%.1f m".format(point.accuracy))
            DetailRow("Status", if (point.is_moving) "Moving" else "Stationary")
        }
    }
}

/**
 * Compact "icon + centered message" hint used by AdminHistoryScreen for its
 * two empty-ish states -- one shared composable so they can't drift apart.
 */
@Composable
private fun HistoryEmptyHint(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminHistoryScreen(viewModel: MainViewModel) {
    val memberList by viewModel.memberList.collectAsState()
    val memberListLoading by viewModel.memberListLoading.collectAsState()
    val memberListError by viewModel.memberListError.collectAsState()
    val selectedMember by viewModel.selectedMemberForHistory.collectAsState()
    val historyDate by viewModel.historyDate.collectAsState()

    val historyResponse by viewModel.historyResponse.collectAsState()
    val historyLoading by viewModel.historyLoading.collectAsState()
    val historyError by viewModel.historyError.collectAsState()

    var showMemberDropdown by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Reset whenever a new history result loads so a tapped point from a
    // previous route never lingers after the map changes.
    var selectedPointIndex by remember(historyResponse) { mutableStateOf<Int?>(null) }

    // A non-scrolling Column (not LazyColumn) so the map below can use
    // `Modifier.weight(1f)` to fill remaining vertical space -- LazyColumn
    // has no equivalent mechanism. Only the map stretches; filter/stats stay
    // their natural compact size.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Selector card: member + date, in a single clean, breathable card.
        Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ContentCardShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Route,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Trip History",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // 40dp rather than the default 48dp for this
                        // secondary action -- still at the ~40dp minimum
                        // comfortable touch-target size.
                        IconButton(
                            onClick = { viewModel.fetchMemberList() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (memberListLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload member list",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Member + Date share one row instead of stacking
                    // full-width, saving horizontal space and height.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Member Selection Dropdown
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = selectedMember?.name ?: "Select Member",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Member") },
                                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Transparent)
                                    .clickable { showMemberDropdown = true }
                            )
                            DropdownMenu(
                                expanded = showMemberDropdown,
                                onDismissRequest = { showMemberDropdown = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                when {
                                    memberListLoading && memberList.isEmpty() -> {
                                        DropdownMenuItem(
                                            text = { Text("Loading member list...") },
                                            onClick = {},
                                            enabled = false
                                        )
                                    }
                                    memberListError != null && memberList.isEmpty() -> {
                                        DropdownMenuItem(
                                            text = { Text("Failed to load members. Tap to try again.") },
                                            onClick = {
                                                viewModel.fetchMemberList()
                                            }
                                        )
                                    }
                                    memberList.isEmpty() -> {
                                        DropdownMenuItem(
                                            text = { Text("No active members") },
                                            onClick = { showMemberDropdown = false }
                                        )
                                    }
                                    else -> {
                                        memberList.forEach { member ->
                                            DropdownMenuItem(
                                                text = { Text(member.name) },
                                                onClick = {
                                                    viewModel.setHistoryMember(member)
                                                    showMemberDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Date selector -- opens a calendar dialog instead of asking
                        // the admin to hand-type a "YYYY-MM-DD" string, which was
                        // error-prone on a touch keyboard and easy to submit malformed.
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = formatDateDisplay(historyDate),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Date") },
                                leadingIcon = { Icon(Icons.Default.CalendarToday, null) },
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Transparent)
                                    .clickable { showDatePicker = true }
                            )
                        }
                    }

                    if (memberListError != null) {
                        Text(
                            text = "Failed to load member list: $memberListError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

        // Snapshot once so the branches below smart-cast to non-null instead
        // of each re-reading the State and asserting `!!` separately.
        val historyResponseSnapshot = historyResponse

        // Content: loading / error / empty / stats+map, one state at a time.
        when {
                historyLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
                historyError != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = ContentCardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Error: $historyError",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                historyResponseSnapshot == null -> {
                    HistoryEmptyHint(
                        icon = Icons.Outlined.Route,
                        message = "Please select a member & date to show trip history."
                    )
                }
                historyResponseSnapshot.total_points == 0 -> {
                    HistoryEmptyHint(
                        icon = Icons.Outlined.LocationOff,
                        message = "No GPS trip logs found for ${selectedMember?.name} on ${formatDateDisplay(historyDate)}."
                    )
                }
                else -> {
                    HistoryStatsCard(
                        stats = historyResponseSnapshot,
                        dateLabel = formatDateDisplay(historyDate)
                    )
                }
            }

        // `Modifier.weight(1f)` lets the map fill remaining vertical space
        // instead of being capped at a fixed dp height. `heightIn(min = ...)`
        // is only a safety floor for unusually tall filter/error content.
        if (historyResponseSnapshot != null && historyResponseSnapshot.total_points > 0) {
            val historyPoints = historyResponseSnapshot.points
            val routePoints = historyPoints.map { GeoPoint(it.latitude, it.longitude) }
            val gapIndices = remember(historyPoints) { computeHistoryGapIndices(historyPoints) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 200.dp),
                shape = ContentCardShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    OsmMap(
                        modifier = Modifier.fillMaxSize(),
                        routePoints = routePoints,
                        routeGapAfterIndices = gapIndices,
                        // Resolves to the nearest recorded point and shows its
                        // detail below, same idea as the marker detail on
                        // the Real-Time Map.
                        onRoutePointSelected = { index -> selectedPointIndex = index }
                    )

                    val tappedPoint = selectedPointIndex?.let { historyPoints.getOrNull(it) }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = tappedPoint != null,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        tappedPoint?.let { point ->
                            RoutePointDetailCard(point = point, onClose = { selectedPointIndex = null })
                        }
                    }
                }
            }

            if (gapIndices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 20.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            // Same constant OsmMap.kt's gapRoutePolyline uses,
                            // so this legend swatch can't drift from the map.
                            .background(Color(HISTORY_GAP_COLOR_ARGB))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Dashed red line: ${gapIndices.size} segment(s) with missing location data (not the actual route)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        HistoryDatePickerDialog(
            viewModel = viewModel,
            selectedMemberId = selectedMember?.id,
            initialDateKey = historyDate,
            onDismiss = { showDatePicker = false }
        )
    }
}

/**
 * The "Select Date" dialog for Member History, extracted out of
 * [AdminHistoryScreen] since this dialog's state (displayed month, pending
 * date) has no reason to live any higher than the dialog itself.
 */
@Composable
private fun HistoryDatePickerDialog(
    viewModel: MainViewModel,
    selectedMemberId: Int?,
    initialDateKey: String,
    onDismiss: () -> Unit
) {
    // Plain local state, not derived from Material3 DatePicker internals
    // (see SimpleMonthCalendar's doc comment). The "yyyy-MM" slice of
    // initialDateKey is safe without parsing -- every date key in this app
    // is always produced in that exact "yyyy-MM-dd" shape.
    var viewedMonth by remember { mutableStateOf(initialDateKey.substring(0, 7)) }
    var pendingDateKey by remember { mutableStateOf(initialDateKey) }

    // Refetched whenever the admin navigates to a different month or member,
    // keyed on the displayed month -- one small request per month view.
    LaunchedEffect(selectedMemberId, viewedMonth) {
        viewModel.fetchHistoryAvailableDates(viewedMonth)
    }
    val availableDates by viewModel.historyAvailableDates.collectAsState()
    val availableDatesLoading by viewModel.historyAvailableDatesLoading.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OverlayCardShape,
        title = { Text("Select Date") },
        text = {
            Column {
                SimpleMonthCalendar(
                    yearMonth = viewedMonth,
                    onYearMonthChange = { viewedMonth = it },
                    selectedDateKey = pendingDateKey,
                    onDateSelected = { pendingDateKey = it },
                    availableDates = availableDates,
                    availableDatesLoading = availableDatesLoading
                )
                if (!availableDatesLoading && availableDates.isEmpty()) {
                    Text(
                        text = "No trip history for this month.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            // Second guard on top of the grid's per-cell disabled state, so a
            // malformed or stale `pendingDateKey` can never be confirmed.
            TextButton(
                onClick = {
                    viewModel.setHistoryDate(pendingDateKey)
                    onDismiss()
                },
                enabled = availableDates.contains(pendingDateKey)
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Minimal, self-contained month-grid date picker used in place of
 * Material3's `DatePicker`. That component's `SelectableDates` hook was
 * tried first, but "which dates have data" is only known after an async
 * fetch completes, and whether Material3's internal day cells actually
 * re-run `isSelectableDate` once app state changes underneath them turned
 * out not to be reliable in testing (iQOO Neo 10, OriginOS 6): every date
 * stayed tappable regardless. A plain grid of ordinary Compose primitives
 * sidesteps that -- every cell's enabled state is driven directly by this
 * composable's own state reads, and it's also lighter to render on the
 * low-end devices this app targets.
 */
@Composable
private fun SimpleMonthCalendar(
    yearMonth: String, // "yyyy-MM"
    onYearMonthChange: (String) -> Unit,
    selectedDateKey: String?, // "yyyy-MM-dd"
    onDateSelected: (String) -> Unit,
    availableDates: Set<String>,
    availableDatesLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val year = remember(yearMonth) { yearMonth.substring(0, 4).toInt() }
    val month = remember(yearMonth) { yearMonth.substring(5, 7).toInt() }
    val daysInMonth = remember(yearMonth) {
        Calendar.getInstance(WibTime.ZONE).apply {
            clear()
            set(year, month - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    // Calendar.DAY_OF_WEEK is Sunday=1..Saturday=7; convert to a Monday-first
    // 0..6 offset so the grid's header (Sen..Min) lines up correctly.
    val firstDayOffset = remember(yearMonth) {
        val dow = Calendar.getInstance(WibTime.ZONE).apply {
            clear()
            set(year, month - 1, 1)
        }[Calendar.DAY_OF_WEEK]
        (dow + 5) % 7
    }
    val todayKey = remember { WibTime.today() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onYearMonthChange(shiftYearMonth(yearMonth, -1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = monthTitleLabel(yearMonth),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { onYearMonthChange(shiftYearMonth(yearMonth, 1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        if (availableDatesLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val totalCells = firstDayOffset + daysInMonth
        val rowCount = (totalCells + 6) / 7
        for (row in 0 until rowCount) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val day = row * 7 + col - firstDayOffset + 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (day in 1..daysInMonth) {
                            val dateKey = "%04d-%02d-%02d".format(year, month, day)
                            val hasData = availableDates.contains(dateKey)
                            val isFuture = dateKey > todayKey
                            val enabled = hasData && !isFuture && !availableDatesLoading
                            // Only shown as "selected" when actually valid --
                            // highlighting a disabled day would visually
                            // contradict the disabled confirm button below.
                            val showAsSelected = enabled && dateKey == selectedDateKey
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(if (showAsSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .then(
                                        if (enabled) {
                                            Modifier.clickable { onDateSelected(dateKey) }
                                        } else {
                                            Modifier
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = day.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = if (hasData) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        showAsSelected -> MaterialTheme.colorScheme.onPrimary
                                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shiftYearMonth(yearMonth: String, delta: Int): String {
    val year = yearMonth.substring(0, 4).toInt()
    val month = yearMonth.substring(5, 7).toInt()
    val cal = Calendar.getInstance(WibTime.ZONE).apply {
        clear()
        set(year, month - 1, 1)
        add(Calendar.MONTH, delta)
    }
    return "%04d-%02d".format(cal[Calendar.YEAR], cal[Calendar.MONTH] + 1)
}

private fun monthTitleLabel(yearMonth: String): String {
    val year = yearMonth.substring(0, 4).toInt()
    val month = yearMonth.substring(5, 7).toInt()
    val cal = Calendar.getInstance(WibTime.ZONE).apply {
        clear()
        set(year, month - 1, 1)
    }
    val indonesian = Locale.Builder().setLanguage("id").setRegion("ID").build()
    return SimpleDateFormat("MMMM yyyy", indonesian).apply { timeZone = WibTime.ZONE }.format(cal.time)
}

/**
 * Formats a "yyyy-MM-dd" date key (as stored/queried) into a compact,
 * human-friendly Indonesian label for display, e.g. "07 Jul 2026". Falls
 * back to the raw string if it doesn't parse, so a malformed value never
 * disappears from the UI.
 */
private fun formatDateDisplay(dateKey: String): String {
    return try {
        val date = WibTime.formatter("yyyy-MM-dd").parse(dateKey) ?: return dateKey
        val indonesian = Locale.Builder().setLanguage("id").setRegion("ID").build()
        SimpleDateFormat("dd MMM yyyy", indonesian).apply { timeZone = WibTime.ZONE }.format(date)
    } catch (e: Exception) {
        dateKey
    }
}

/**
 * Extracts the "HH:mm" portion out of a "yyyy-MM-dd HH:mm:ss" timestamp.
 * Defensive on length even though the backend validates recorded_at before
 * storing it -- a raw `substring(11, 16)` on a malformed string would
 * otherwise crash the trip-summary UI.
 */
private fun timeOfDay(timestamp: String?): String {
    if (timestamp == null || timestamp.length < 16) return "-"
    return timestamp.substring(11, 16)
}

/**
 * Travel statistics summary: a compact hero row (total distance + date)
 * followed by three supporting tiles (duration, point count, time range).
 */
@Composable
private fun HistoryStatsCard(stats: HistoryResponseDto, dateLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContentCardShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${stats.total_distance_km} km",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Total Distance · $dateLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HistoryStatTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Schedule,
                    value = stats.duration_formatted,
                    label = "Duration"
                )
                HistoryStatTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.MyLocation,
                    value = "${stats.total_points}",
                    label = "GPS Points"
                )
                HistoryStatTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.AccessTime,
                    value = "${timeOfDay(stats.start_time)}–${timeOfDay(stats.end_time)}",
                    label = "Time Range"
                )
            }
        }
    }
}

@Composable
private fun HistoryStatTile(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Shared "label ... value" row used for coordinate/detail listings --
 * centralized so the styling can't drift between call sites.
 */
@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

/**
 * Builds the map marker snippet text for a member's current status.
 * `is_moving` is a real, server-computed signal (backend/api.php), so a
 * stationary-but-online member correctly shows as "ACTIVE - Stationary".
 */
fun movementSnippet(loc: MemberCurrentLocationDto): String {
    return when {
        loc.status != "active" -> "OFFLINE (last update ${formatToWIB(loc.updated_at)})"
        loc.is_moving -> "ACTIVE - Moving"
        else -> "ACTIVE - Stationary"
    }
}

// Every timestamp the backend stores/returns is already WIB wall-clock time
// (backend/config.php sets Asia/Jakarta as the default timezone), and the
// Member app formats its own GPS timestamps in Asia/Jakarta too -- nothing
// in this system ever transmits UTC. Both parser and formatter must use the
// same zone, or a value would get a spurious +7 hours applied on top.

// The Member app requests a fresh GPS fix every 10-15s while tracking is
// active. A delayed/queued fix still carries its original recorded_at when
// it eventually syncs, so a real network hiccup alone can't produce a large
// gap between stored recorded_at values -- a gap this size means one or more
// fixes were never recorded (e.g. the tracking service was killed by the OS).
// 60s (4x the slowest normal interval) gives margin above ordinary jitter.
private const val MAX_NORMAL_FIX_GAP_SECONDS = 60L

// Matches backend/api.php's OFFLINE_STALE_SECONDS (90s) so the Member's own
// "is my tracking actually still live" signal agrees with the Admin's.
private const val MEMBER_LOCATION_STALE_THRESHOLD_MS = 90_000L

/**
 * Returns the set of indices i such that the segment between
 * history points i and i+1 bridges a real gap in recorded data (see
 * [MAX_NORMAL_FIX_GAP_SECONDS]), for the red/dashed rendering in [OsmMap].
 */
private fun computeHistoryGapIndices(points: List<HistoryPointDto>): Set<Int> {
    if (points.size < 2) return emptySet()
    val parser = WibTime.formatter("yyyy-MM-dd HH:mm:ss")

    val gaps = mutableSetOf<Int>()
    for (i in 0 until points.size - 1) {
        val curTime = try { parser.parse(points[i].recorded_at)?.time } catch (_: Exception) { null }
        val nextTime = try { parser.parse(points[i + 1].recorded_at)?.time } catch (_: Exception) { null }
        if (curTime != null && nextTime != null) {
            val gapSeconds = (nextTime - curTime) / 1000
            if (gapSeconds > MAX_NORMAL_FIX_GAP_SECONDS) {
                gaps.add(i)
            }
        }
    }
    return gaps
}

/**
 * Validates a "yyyy-MM-dd HH:mm:ss" WIB timestamp for display, falling back
 * to the raw string if it doesn't parse, or "-" if there's nothing to show.
 * Parses once purely to validate rather than parsing and reformatting with
 * an identical pattern, which would just be a wasted round trip.
 */
fun formatToWIB(wibTimeString: String?): String {
    if (wibTimeString == null) return "-"
    return try {
        WibTime.formatter("yyyy-MM-dd HH:mm:ss").parse(wibTimeString)
        wibTimeString
    } catch (_: Exception) {
        wibTimeString
    }
}
