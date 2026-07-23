# TAMS Android

Native Android app for **T**rubus **A**lami **M**onitoring **S**ystem — GPS
location tracking for field members, with a separate admin role for live
monitoring and history review. Talks to the [Backend API](../web/README.md#backend-api)
in `web/backend/`. If you're new to this project, read this file before
opening anything else.

## Folder structure

```
android/
├── README.md                         <- you are here
├── build.gradle.kts                  Top-level: plugin declarations only
├── settings.gradle.kts                rootProject.name = "TAMS", single :app module
├── gradle.properties                  Build performance/style flags
├── gradle/
│   ├── libs.versions.toml             Version catalog -- every dependency version lives here
│   └── wrapper/gradle-wrapper.properties
│
└── app/
    ├── build.gradle.kts               Module config: SDK versions, signing, dependencies
    ├── proguard-rules.pro             R8 keep rules (release builds only)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml    Permissions, activity/service/receiver declarations
        │   ├── java/com/trubus/tams/  All app code, see "Package layout" below
        │   └── res/                   Strings, theme colors, launcher icons, XML config
        └── test/                      JVM unit tests -- see "Testing" below (no androidTest/; nothing needs a device/emulator)
```

### Package layout (`app/src/main/java/com/trubus/tams/`)

| Package | Responsibility |
|---|---|
| `MainActivity.kt` | Single activity, hosts the whole Compose UI tree. No navigation library -- the app has one screen surface that swaps content based on login/role state. |
| `data/model/` | Plain data classes (`Models.kt`), Moshi-annotated for JSON (de)serialization. |
| `data/api/` | `ApiService.kt` -- Retrofit interface describing every backend route (`?route=` query params match `backend/api.php`'s router), including the `/outlet/*` routes backing Outlet Management (see below). |
| `data/repository/` | `MemberRepository.kt` -- the single data-access layer. Owns the Retrofit client, SharedPreferences (session token, saved base URL, tracking state), and the offline location queue (via `data/local/`). Everything else in the app goes through this instead of touching Retrofit or SharedPreferences directly, including Outlet Management's `getOutlets()`/`createOutlet()`/`updateOutlet()`/`deleteOutlet()` -- there is no separate Outlet repository. `RemoteConfigRepository.kt` -- caches Remote Management settings (GPS Update Interval, Sync Interval) from `/app/config`, refreshed opportunistically at most once every 30 minutes; see "Remote Management" below. `ActivityLogRepository.kt` -- fire-and-forget writes to `/activity/log` for the Member Log feature; see "Member Log" below. `UpdateRepository.kt` -- the OTA update feature's own unauthenticated server call (`GET /app/version`), kept separate from `MemberRepository` since it must work before login; see "OTA Update" below. |
| `data/local/` | `AppDatabase.kt` -- Room database with one table, an offline queue of GPS points, written ahead of every send attempt (not only after one fails) and flushed once connectivity returns -- see "Background Tracking Reliability" below. |
| `data/geocoding/` | `ReverseGeocodingService.kt` -- turns lat/lng into a human-readable "near X" landmark hint via Nominatim (OpenStreetMap), called directly from the client with an in-memory cache to avoid repeat lookups for the same coordinates. Used on the Admin's live map/history point-detail cards. Independent of the Admin Panel's own separate, server-side reverse-geocoding proxy (`web/ajax/reverse_geocode.php`) -- see [`web/README.md`](../web/README.md)'s "Live Tracking, Member History & Reverse Geocoding" section. `NominatimSearchService.kt` -- the forward-geocoding counterpart, turning a typed address query into candidate places for Outlet Management's "Search Address" field; same Nominatim host, same singleton-with-its-own-OkHttpClient/cache shape as `ReverseGeocodingService.kt`, see "Outlet Management" below. |
| `data/update/` | OTA (in-app) update: `ApkDownloadManager.kt` streams the update APK to app-private storage with retry + progress; `UpdateManager.kt` owns the whole check → download → install state machine (`UpdateFlowState`), built from `data/repository/UpdateRepository.kt`'s server call. See "OTA Update" below. |
| `service/` | `MemberLocationService.kt` -- foreground `Service` doing continuous GPS tracking (FusedLocationProviderClient), holds a wake lock, and watches for stale/stuck location updates. `BootCompletedReceiver.kt` restarts tracking after the device reboots, if it was active before. |
| `worker/` | `LocationSyncWorker.kt` -- a self-rescheduling `OneTimeWorkRequest` (not `PeriodicWorkRequest`, which has a 15-minute floor) acting as a ~3-minute watchdog: makes sure the foreground service is actually still alive, flushes the offline queue, and doubles as a session-death probe (see "Force Location (Force Override)" below). |
| `ui/screens/` | `MainAppScreen.kt` -- all screen composables (login, member dashboard, admin dashboard with its 3 tabs: active members, live map, history); also hosts `MemberRootScreen`, the tab container that splits the Member role into Dashboard/Outlet. `SplashScreen.kt` -- the cold-start branding screen shown only for as long as startup session validation takes (no artificial minimum duration, and skipped entirely with no stored token); app initialization runs concurrently behind it either way. `OsmMap.kt` -- wraps osmdroid (not Google Maps -- no API key needed) in a Compose `AndroidView`, used by the Admin's live map/history. `OutletScreen.kt` -- Outlet Management's own screens (list, add/edit form, map picker); see "Outlet Management" below. `UpdateDialog.kt` -- the OTA update dialog (optional and force-update variants), rendered on top of whichever screen is active. |
| `ui/viewmodel/` | `MainViewModel.kt` -- single `AndroidViewModel` exposing all app state as `StateFlow`, backed by `MemberRepository`. |
| `ui/theme/` | Material3 theme, color palette, typography. |
| `util/` | `WibTime.kt` -- centralizes all Asia/Jakarta (WIB, UTC+7) timestamp formatting so the app never mixes time zones between screens. |

## Architecture

MVVM, single-activity, no navigation library:

- **UI** (`ui/screens/`) is 100% Jetpack Compose + Material3, driven by one `MainViewModel`.
- **`MainViewModel`** (`ui/viewmodel/`) exposes state via `StateFlow` and is the only thing the UI layer talks to.
- **`MemberRepository`** (`data/repository/`) is the sole data-access layer: Retrofit for network calls, SharedPreferences for session/settings persistence, Room for the offline location queue. The ViewModel never calls Retrofit or SharedPreferences directly.
- **`MemberLocationService`** runs independently of the UI (foreground `Service`), so GPS tracking survives the app being backgrounded or the activity being destroyed.
- **`LocationSyncWorker`** runs independently of both, as a periodic safety net.

There are two roles, `member` and `admin`, sharing one `tams_users` table on the backend -- which screen set `MainAppScreen.kt` shows is decided by the logged-in user's `role` field, not by separate app builds.

`UpdateManager` (`data/update/`) follows the same composition pattern as `MemberRepository`: it's owned privately by `MainViewModel` and never touched directly by `ui/screens/`, which only ever sees `MainViewModel.updateState` (a `StateFlow<UpdateFlowState>`) and calls functions like `MainViewModel.startUpdateDownload()` -- the same "MainViewModel is the only thing the UI talks to" rule applies to it as to every other feature.

## App workflow

1. **Login** -- credentials POST to the backend, which returns a bearer token; `MemberRepository` stores it in SharedPreferences and attaches it to every subsequent request.
2. **Cold-start session validation** -- a stored token is never trusted by itself. Every process launch, `MainViewModel.validateSessionOnStartup()` calls `GET /profile` (the cheapest authenticated route) before `isLoggedIn` is allowed to become true, overlapped with the splash screen so a fast/valid check adds no visible delay. A server-confirmed HTTP 401/403 clears the local session and sends the user to the login screen with an explanatory message; any other failure (unreachable server, timeout, 5xx) leaves the token in place -- since it was never actually confirmed bad -- but still blocks entry, showing a "Try Again" action instead of forcing a full re-login. This exists specifically so a device cut off from the backend (e.g. an ISP-level block) can't silently reach the dashboard on a cached token while every sync fails underneath it.
3. **Member role** -- on login, `MemberLocationService` starts as a foreground service and begins sending location updates to the backend on an interval. Every fix is written to the local Room queue before the network attempt, not only if it fails, so a process death mid-upload never silently loses it; queued points get flushed once connectivity returns (checked both opportunistically and by `LocationSyncWorker`) -- see "Background Tracking Reliability" below for this and how tracking survives being swiped from Recent Apps. Pressing Start is also subject to Force Location's 07:00-16:00 WIB window unless an Admin has granted that Member an override -- see "Force Location (Force Override)" below; the same window is re-checked on every single fix for as long as tracking runs, and the service stops itself automatically if a fix is ever rejected for it. The Member Dashboard also shows a Trip Summary card (today's distance/duration, sourced from the same `GET /location/history` data the Admin sees) and, on any device where the OS hasn't exempted the app from battery optimization, a dismissible card prompting the Member to grant that exemption via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` -- it auto-hides the instant the exemption is detected, and a manual dismissal is auto-cleared the same way so it can never mask a real regression. A second tab, Outlet, sits alongside Dashboard for the Member role (`MemberRootScreen`'s `TabRow`) -- see "Outlet Management" below; switching to it does not pause GPS tracking, only the Dashboard's own on-screen polling.
4. **Admin role** -- three tabs: active members list, a live map (osmdroid) of everyone currently tracking, and a location history browser per member/date.
5. **Reboot** -- `BootCompletedReceiver` restarts the location service automatically if tracking was active when the device powered off.
6. **Logout** -- clears the stored token and stops the foreground service.
7. **OTA update check** -- runs once per process launch, independent of login state (see "OTA Update" below).

## OTA Update

`MainViewModel` checks `backend/api.php`'s `/app/version` route once per app launch (before login, since a mandatory update must be able to block the app either way) and compares the response's `version_code` against this build's own `BuildConfig.VERSION_CODE`. The server-side version info is entirely configured through the Admin Panel's "OTA Update" page (`web/pages/ota_update.php`) -- see [`web/README.md`](../web/README.md#ota-update) for that side.

- **Optional update** (`force_update: false`): a dismissible dialog offers LATER/UPDATE; the app stays fully usable either way.
- **Force update** (`force_update: true`): the same dialog has no Later button and no dismiss path (back press/backdrop tap are both no-ops) -- the user must download and install before continuing.
- Tapping **UPDATE** streams the APK via `ApkDownloadManager` to `filesDir/updates/` (app-private storage, no storage permission needed) with live progress, retrying transient failures automatically. Once downloaded, it's handed to the system Package Installer through a `FileProvider` content URI (`res/xml/file_paths.xml`, the `<provider>` entry in `AndroidManifest.xml`) -- never a raw `file://` path, which Android blocks outright (`FileUriExposedException`).
- On API 26+, if the OS hasn't yet granted this app permission to install packages (`REQUEST_INSTALL_PACKAGES`), the dialog offers a one-tap shortcut to the relevant Settings screen and re-checks automatically when the app resumes.
- A failed version *check* (server unreachable, etc.) is always silent -- it runs on every launch, so surfacing an error for it would false-alarm on every offline launch. A failed *download* (after the user already tapped UPDATE) shows an error with a retry button, since that's a response to an explicit user action.

## Remote Management

`RemoteConfigRepository` reads the GPS Update Interval, Sync Interval, and
minimum/maximum password length from `backend/api.php`'s public
`GET /app/config` route, letting an Administrator retune all of them from
the Admin Panel without a new APK build. Password length bounds used to be
a hardcoded `PASSWORD_MIN_LENGTH`/`PASSWORD_MAX_LENGTH` pair mirrored by
hand from `web/database/validation_rules.php` (now removed); both apps
read the same `tams_remote_management` table instead.

- **Cache-first, not fetch-on-every-read**: `gpsIntervalSeconds`,
  `syncIntervalMinutes`, `passwordMinLength`, and `passwordMaxLength` are
  synchronous SharedPreferences reads, safe to call from a hot path
  (starting GPS tracking, scheduling the next sync pass, validating a
  password as the user types). `refreshIfStale()` is the only method that
  touches the network, and only actually fetches at most once every 30
  minutes -- callers
  (`MemberLocationService.onCreate`, `LocationSyncWorker.doWork`,
  `MainViewModel.init`) invoke it opportunistically on their own existing
  passes rather than this class polling on a timer of its own.
- **Fails safe**: a never-fetched cache, a stale cache the network fetch
  failed to refresh, or an out-of-range server value are all treated the
  same way -- fall back to the built-in defaults (10s / 3 minutes / password
  4-255 characters, matching `database/schema.sql`'s seed rows and
  `helpers/functions.php`'s `remote_management_definitions()`). App behavior
  only ever changes on a successful, in-range fetch.
- `MemberLocationService.startLocationTracking()` reads the cached GPS
  interval when building its `LocationRequest` (default 10s target / 5s
  fastest-if-available, same ratio preserved at other configured values).
  The staleness watchdog's re-registration threshold scales with the
  configured interval too (4x, floored at 40s), so a longer configured
  interval is never mistaken for a stalled GPS subscription.
- `LocationSyncWorker.rescheduleNextPass()` reads the cached Sync Interval
  instead of a hardcoded 3-minute delay when re-enqueueing itself. This is a
  single fixed interval regardless of whether Start Location is on or off --
  see "Server Load & Scalability" below for why an adaptive (slower-while-idle)
  version of this was evaluated and deliberately not kept.

## Server Load & Scalability

Audited for concurrent use by 50-100 Members. Per-Member recurring request
sources, at default Remote Management values:

- **`POST /location/update`** (continuous tracking): one request every GPS
  Update Interval (10s default) for as long as Start Location is on. This is
  the dominant traffic source but is inherently bounded by real tracking
  hours, and each request is a single small indexed transaction
  (`tams_live_tracking_current`'s `user_id` is its primary key, so the row lock
  never contends across different Members) -- at 100 Members tracking
  simultaneously this is at most ~10 requests/second system-wide, a light
  load for any PHP+MySQL host.
- **`GET /location/status`** (`LocationSyncWorker`'s watchdog pass): a fixed
  background cost that runs for every logged-in Member every ~3 minutes
  regardless of whether Start Location is on, since it also doubles as this
  pass's session-death probe -- up to ~480 requests/Member/day in the worst
  case (logged in around the clock, never tracking). An adaptive version of
  `rescheduleNextPass()` (slower cadence while idle) was built and evaluated
  during this audit, but reverted after a follow-up architecture review: at
  50-100 Members this works out to well under 1 request/second on average
  system-wide -- never an actual server-side bottleneck -- so the extra
  branching and untested code path it would add wasn't worth carrying
  long-term for a problem that doesn't exist at this scale. An Administrator
  who wants a slower cadence for a specific deployment can already raise
  Sync Interval via Remote Management, with no code change either way.
- **`GET /app/config`**: staleness-gated to at most once every 30 minutes
  per device regardless of how often `refreshIfStale()` is called -- already
  efficient, left unchanged.
- **`GET /app/version`**: once per app launch only -- not a recurring cost.

Admin-side polling (`GET /location/current`, 5s interval on both the Web
Admin Panel's Live Tracking page and this app's own Admin dashboard) is
bounded by the number of concurrently open Admin sessions, not by Member
count, and the Web Admin Panel already pauses its poll via the
`visibilitychange` event while its tab isn't visible.

**Considered and deliberately not done:** batching multiple GPS fixes into
one upload. At the request rates above, the server side is not the
bottleneck at 100 Members, and batching would require reworking
`backend/api.php`'s single-point movement/out-of-order/history logic to
accept arrays, a materially larger change for negligible real benefit at
this scale -- see root `README.md`'s own note on this.

## Member Log

`ActivityLogRepository` fire-and-forget-POSTs to `backend/api.php`'s
`/activity/log` route for the Member Log (Member activity audit trail) feature --
viewed on the Admin Panel's "Member Log" page. Every call is best-effort: a failed
write never surfaces to the UI, retries, or gets queued -- the activity
being logged has already happened either way, and the audit trail isn't
important enough to justify offline-queueing complexity.

Wired into:

- `MainViewModel.updateProfile()` -- one entry per changed field (Username,
  Note) with before/after values, plus a separate entry for a password
  change that logs only the bare fact "Password changed", **never** the
  password value itself.
- `MainViewModel.startTracking()` / `stopTracking()` -- `start_location` /
  `stop_location`, with success/failure status.
- `MemberRepository.syncOfflineLocations()` -- `sync_failed`, only on a
  genuine failure (never on routine successful syncs).
- `MemberLocationService.startLocationTracking()` -- `error`, for the
  critical case where location permission is missing or gets revoked
  mid-session, stopping tracking outright.

## Force Location (Force Override)

Driven by an Admin from the Web Admin Members page's Actions column -- see
[`web/README.md`](../web/README.md#force-location-force-override) for the
server/Admin Panel side. Doesn't change the Start/Stop button's appearance
and needs no re-login to take effect.

**Pre-flight (UX only)**: `MainViewModel.requestStartTracking()` wraps
`startTracking()` with a `GET /location/status` pre-flight check, so a
Member outside the allowed window (and without an Admin-granted override)
sees an immediate, specific Toast message instead of a foreground service
that silently never sends anything. This pre-flight is pure UX, never the
actual enforcement -- `backend/api.php`'s `POST /location/update` rejects
every fix with HTTP 403 (`error_code: outside_operational_hours`)
regardless of whether this check ran, so neither a modified app build nor a
spoofed device clock can widen the window. The pre-flight fails **open** on
an inconclusive answer (network error, timeout): `startTracking()` is still
attempted, since blocking Start over a transient connectivity blip would be
a pure UX regression with no security benefit -- the server-side gate is
what actually matters.

**Distinguishing "not allowed right now" from "session is dead"**: this 403
shares its HTTP status code with a genuinely dead/revoked token (also 403
-- see `SessionInvalidException`). `MemberRepository.postLocation()` tells
the two apart by reading the response's `error_code` field
(`parseErrorBody()`): a 401/403 tagged `outside_operational_hours` raises
`TrackingNotAllowedException` instead of `SessionInvalidException` -- see
that exception class's own doc comment.

**Auto-stop on revocation**: `MemberLocationService.handleNewLocation()`
reacts to `TrackingNotAllowedException` the same way it already reacted to
`SessionInvalidException` -- by stopping its own foreground service and GPS
subscription immediately, with no user interaction -- except it deliberately
does **not** tear down the session (`MainViewModel.handleTrackingNotAllowedByService()`
only flips `isTrackingActive` back to false and shows a one-shot Toast, it
never logs the Member out). This is the fix for a real bug: previously, a
Member who started tracking while an Admin-granted Force override was
active, and then had that override turned off while still outside the
07:00-16:00 WIB window, kept sending (rejected) location updates forever
until manually pressing Stop -- every fix failed with this same 403, but
nothing reacted to it. Because `POST /location/update` re-reads
`force_tracking_hours` fresh from the database on every single call (not
just at Start), and an Admin's toggle
(`web/ajax/members_force_toggle.php`) is a single atomic UPDATE with no
separate "notify the device" step, reacting correctly to this one
rejection is the *entire* fix -- no additional polling loop, no additional
server-side state, no new race condition introduced. `LocationSyncWorker`'s
watchdog never fights this: by the time the service stops itself,
`MemberRepository.isTrackingEnabled` is already false, so the watchdog's
next pass sees nothing to restart.

## Background Tracking Reliability

Continuous GPS tracking has to survive far more than "the app is in the
foreground": the device swiping TAMS away from Recent Apps, the OS or an
OEM's own power manager killing the foreground service outright, and any
transient failure when the service tries to restart itself. Three
mechanisms cooperate, each covering a gap the others don't:

- **`START_STICKY`** -- if the OS kills the process outright, Android
  restarts `MemberLocationService` with a null `Intent`; `onStartCommand`
  resumes tracking if `MemberRepository.isTrackingEnabled` is still true.
- **`onTaskRemoved()`'s fast restart alarm** -- fires the moment the app is
  swiped from Recents. Several OEM power managers (MIUI, ColorOS,
  FuntouchOS, EMUI) treat this as a stronger signal than an ordinary kill
  and tear the process down shortly after, bypassing `START_STICKY`'s own
  restart path -- a one-shot `AlarmManager` alarm (~1s later) closes that
  gap. `scheduleRestartAlarm()` uses `setExactAndAllowWhileIdle()` whenever
  `SCHEDULE_EXACT_ALARM` is granted (declared in the manifest; checked via
  `canScheduleExactAlarms()`), since Android's own documentation states
  exact alarms are exempt from the Android 12+ foreground-service
  background-start restriction that an inexact `setAndAllowWhileIdle` alarm
  is not -- falling back to `setAndAllowWhileIdle` when the permission isn't
  granted (the common case for a fresh install targeting API 33+, per
  Android's docs), which is strictly the same behavior this mechanism had
  before that permission was added.
- **`LocationSyncWorker`'s ~3-minute watchdog** -- the slower safety net for
  when even `onTaskRemoved` doesn't get to run (a harder OEM freeze):
  re-asserts the foreground service if `isTrackingEnabled` is still true.

**A restart attempt that fails is treated as recoverable, not permanent.**
`MemberLocationService.beginForegroundTracking()` only sets
`isTrackingEnabled = false` for a *confirmed* unrecoverable condition --
location permission actually missing, checked synchronously right before
starting -- never for a generic exception from the start attempt itself
(e.g. a background-start-exemption race on a very fast restart). Every
restart path above gates on this same flag, so an earlier version of this
method that disabled it on any exception turned one transient hiccup into
tracking silently, permanently stopping for the rest of the session, with
no server-side trace. Leaving the flag untouched lets the watchdog above
retry automatically -- no separate retry mechanism needed.

**Write-ahead offline queue.** `MemberRepository.postLocation()` persists
every GPS fix to the local Room queue *before* attempting the network
POST, not only after a failed attempt -- an earlier design queued only in
the failure branch, leaving a fix with no record anywhere (not delivered,
not queued) if the process died while the network call was in flight. The
row is removed once the outcome is known: confirmed delivered, or a
permanent (non-5xx) rejection where retrying would fail forever; a
transient (5xx/network) failure leaves it queued for
`syncOfflineLocations()` to retry.

## Mock-Location Detection

`MemberLocationService.handleNewLocation()` computes two advisory trust
signals for every fix and forwards them to the server alongside the fix
itself, surviving an offline retry via `OfflineLocation`'s own
`isMock`/`gnssSatellitesUsed` columns:

- **`is_mock_location`** -- `androidx.core.location.LocationCompat.isMock(location)`,
  AndroidX's compat wrapper unifying `Location.isMock()` (API 31+) and the
  deprecated `isFromMockProvider()` (below it) without touching the
  deprecated method directly.
- **`gnss_satellites_used`** -- a `GnssStatus.Callback` (registered in
  `registerGnssStatusCallback()` alongside the FusedLocationProviderClient
  subscription) counts satellites where `GnssStatus.usedInFix()` is true;
  the most recent count is attached only while still fresh (within
  `GNSS_STATUS_FRESHNESS_MS`), otherwise `null`.

**Both signals are advisory-only and never gate, filter, or reject a fix --
on the device, in transit, or on the server.** A modified APK or root hook
could trivially fake either client-side value, so neither is trustworthy as
a security boundary; instead, `backend/api.php` stores them as-is on both
`tams_live_tracking_current` and `tams_member_history_locations` purely for
an Admin to review. The Web Admin's Live Tracking detail card and Trip History's
per-point detail card show a passive "Possible Mock GPS" badge when
`is_mock_location === true` or `gnss_satellites_used === 0` -- informational
only, never blocking. `NULL`/absent means "unknown" (an older app version,
or a one-shot fallback fix with no live GNSS subscription behind it), not
"confirmed genuine".

Deliberately out of scope for this design: fake-GPS-app package-name
blocklists and Developer Options detection (both evaluated and rejected --
high maintenance burden, easy to bypass, weak/noisy signal) and any
RF/SDR-level GNSS spoofing countermeasure (unaddressable in software, and
disproportionate to this app's actual threat model).

## Member Version Monitoring

`MemberRepository.deviceInfoFields()` attaches `app_version_name`
(`BuildConfig.VERSION_NAME`), `app_version_code` (`BuildConfig.VERSION_CODE`),
`android_version` (`Build.VERSION.RELEASE`), and `device_model`
(`Build.MODEL`) to every `/auth/login` and `/location/update` request
(including offline-queue flushes) -- no separate endpoint or extra network
call. The Admin Panel's Members page displays these with a computed
🟢/🟡/🔴 status; see [`web/README.md`](../web/README.md#member-version-monitoring)
for the server side.

## Outlet Management

Lets a Member register outlets -- physical locations they're expected to
visit -- and see their own approval status. Visit detection itself
(geofencing) is entirely server-side; the app's role here is registration
and review only. See [`web/README.md`](../web/README.md#outlet-management)
for the full server-side picture (tables, approval workflow, geofencing).

- **Navigation**: `MemberRootScreen` (`ui/screens/MainAppScreen.kt`) wraps
  the existing, untouched `MemberDashboard` in a `TabRow` (Dashboard /
  Outlet), local `remember`-scoped state, mirroring `AdminDashboard`'s own
  tab pattern. `MemberOutletScreen` (`OutletScreen.kt`) is the Outlet tab's
  root, switching between a list view and an add/edit form view.
- **Outlet List (list view)**: `MainViewModel.fetchOutlets()` calls
  `MemberRepository.getOutlets()` (`GET /outlet/list`), refetched every time
  the list view becomes visible (including right after a create/edit/delete
  succeeds). Each row shows name, address, a PENDING/APPROVED/REJECTED
  status badge, a rejection reason when present, and a "changes awaiting
  approval" note when `has_pending_edit` is true. Edit/Delete are shown only
  when `is_own_outlet` is true (the Member created it themselves) -- an
  outlet merely assigned to them by an Admin is view-only, matching the
  principle that the UI should never offer an action the server is
  guaranteed to refuse. Delete is further restricted to
  PENDING/REJECTED outlets, matching `/outlet/delete`'s own server-side rule.
- **Add/Edit Outlet (form)**: a name text field (soft client-side length cap
  only, for UX -- see "Validation stays server-side" below); Address is
  read-only and populated only by reverse geocoding when adding a NEW
  outlet (the Member can move the pin -- drag, Search Address, Use Current
  Location -- but never type into Address directly), while Edit Outlet keeps
  Address freely editable exactly as before. A "Use Current Location" button
  (one-shot `FusedLocationProviderClient.getCurrentLocation`, manually
  wrapped in `suspendCancellableCoroutine` -- no
  `kotlinx-coroutines-play-services` dependency added, consistent with this
  project's existing anti-bloat stance, see "Main dependencies" below) --
  for Add Outlet only, this same fetch also runs automatically the moment
  the form opens (`LaunchedEffect(Unit)` in `OutletFormScreen`, reusing the
  button's own permission-check/fetch path, not a second copy) and lands
  zoomed in on the result, so a Member registering a new outlet never has
  to find the button or pinch-zoom in themselves; Edit Outlet does not
  auto-trigger this, since its map picker already opens on the outlet's own
  saved coordinate. Also a debounced Search Address field, and an osmdroid
  map picker.
- **Map picker (`OutletMapPicker`)**: a Gojek/Shopee-style fixed-center pin
  -- a plain Compose `Icon` overlay (not an osmdroid `Marker`, which would
  move with the map on pan), with the actual picked coordinate reported by
  `MapListener.onScroll`/`onZoom` reading `mapView.mapCenter` on every pan/
  zoom. Panning always defines a real center coordinate, so there is no
  "nothing picked yet" state to validate against. Lifecycle mirrors
  `OsmMap.kt`'s already-proven pattern exactly: a `DisposableEffect` tied to
  `ON_RESUME`/`ON_PAUSE`, and `onDetach()` on `AndroidView`'s `onRelease` so
  navigating away from the form (back to the list, or switching to the
  Dashboard tab) tears down the `MapView` and its tile-download threads with
  no leak.
- **Search Address**: `AddressSearchService.search()` (`data/geocoding/`),
  called from a `LaunchedEffect(searchQuery) { delay(500); ... }` -- Compose's
  automatic cancel-and-relaunch-on-key-change *is* the debounce, and the
  cancellation also aborts the in-flight HTTP call. `AddressSearchService`
  is a small orchestrator, not a geocoder itself: it tries
  `NominatimSearchService` (OpenStreetMap Nominatim, forward geocoding)
  first, and only falls back to `PhotonSearchService` (Komoot's free public
  Photon instance, also OpenStreetMap-based but with a typo-tolerant search
  engine) if Nominatim comes back with zero results -- both free, no API
  key. Each provider implements the same `ForwardGeocodingProvider`
  interface and does its own caching (lowercased query string, 100-entry cap,
  full clear on overflow, same tradeoff `ReverseGeocodingService` already
  makes) -- adding a further free provider later is implementing that
  interface once and adding it to `AddressSearchService`'s provider list,
  nothing else. The UI only ever sees "Address not found" once both
  providers have found nothing. Tapping a result recenters the map picker to
  that coordinate.
- **Address stays in sync with the pin (reverse geocoding).** Found during
  Final Validation: dragging/zooming the map, or using "Use Current
  Location", changed `pickedLat`/`pickedLng` with no effect on the Address
  field at all, leaving a Search-Address-era (or stale) label attached to a
  since-moved pin. Fixed by a second `LaunchedEffect(pickedLat, pickedLng) {
  delay(500); ... }` in `OutletFormScreen` -- the same debounce idiom as
  Search Address above, reusing `ReverseGeocodingService.getNearbyLabel()`
  as-is (no new geocoding service, no change to that shared object, which
  Live Tracking's nearby-label display also depends on). A failed/timed-out
  lookup (`null`) leaves the current Address text untouched rather than
  blanking it. Selecting a Search Address result is the one coordinate
  change deliberately exempted -- its own label is already authoritative
  for that exact point -- tracked by comparing the current coordinate
  against the last coordinate a search result was actually applied to
  (`lastAuthoritativeLat`/`Lng`), not a one-shot boolean flag, since a
  boolean has no coordinate of its own to compare against and could be
  consumed by the wrong triggering (e.g. a very fast drag immediately after
  tapping a search result, before that tap's own effect instance runs).
  `OutletMapPicker` itself is untouched -- it still only ever reports a
  coordinate, never anything address-related, keeping it a reusable, dumb
  picker. A manual edit to Address is also never clobbered: `addressManuallyEdited`
  is set from the field's own `onValueChange` and checked right before the
  reverse-geocode result would otherwise overwrite it (both right after the
  debounce wait, to skip the network call entirely, and again after the
  lookup returns, in case the Member typed while it was in flight).
  Deliberately reset to `false` every time the pin moves again, rather than
  a permanent "never auto-update again this session" latch -- the whole
  point of this feature is staying in sync with the pin, so the Member's
  next drag/zoom/Use Current Location/Search Address pick is the signal
  that they want a fresh lookup, overriding whatever they typed in the
  meantime. An earlier, considered alternative (disable auto-update
  permanently for the rest of the form session after the first manual
  edit) was rejected specifically because it would silently break this same
  sync promise the very next time the Member moved the pin, with no
  indication why. The `getNearbyLabel()` call is wrapped in `try { ... }
  finally { resolvingAddress = false }` -- found during audit: without it,
  cancelling this effect (the pin moved again before a request returned)
  threw `CancellationException` right at that suspension point, which
  skipped straight past a bare post-call `resolvingAddress = false` and
  could leave the spinner stuck showing for a request that had already been
  aborted. `finally` is what Kotlin guarantees runs on every exit path --
  success, exception, or cancellation -- so the spinner can no longer get
  stuck regardless of how the request ends.
- **Status and edit semantics**: creating an outlet always starts it at
  PENDING. Editing a PENDING/REJECTED outlet re-submits it for review, in
  place. Editing an APPROVED outlet does not change its live data or
  status -- the Member sees "Changes submitted. This outlet's current data
  stays active until an Admin approves your changes." (computed client-side
  from the outlet's already-known status, not parsed from the server
  response) -- and the outlet keeps showing its current APPROVED data plus a
  pending-edit note until an Admin reviews it (`web/README.md`'s "Outlet
  Management" section). If that edit is instead rejected, `OutletCard`
  shows "Last edit rejected: ..." using `OutletDto.last_edit_rejection_reason`
  (`GET /outlet/list`) -- added during Final Validation after finding a
  rejected edit otherwise left no visible trace once `has_pending_edit`
  flipped back to `false`; see `web/README.md`'s own note on this field.
- **Validation stays server-side.** `MemberRepository`'s outlet methods do
  no client-side length/range validation, only pass-through to
  `backend/api.php`. `MainViewModel.submitNewOutlet()` checks only that name
  is non-blank ("Outlet name is required.") -- Address is no longer checked
  here, since Add Outlet's Address field is read-only (see above) and the
  Member has no direct way to fix an empty one by editing that field; an
  Address left blank because reverse geocoding failed or hadn't resolved yet
  is instead caught by the server's own validation, the same round trip
  every other field-level rule already goes through. `submitOutletEdit()`
  still checks both name and address are non-blank ("Outlet name and
  address are required."), unchanged, since Edit Outlet keeps Address
  manually editable and a Member could clear it there. Both avoid an
  obviously-incomplete round trip only; the text fields' soft character caps
  are a UX affordance only, mirroring the Web Admin's own non-authoritative
  `maxlength` attributes -- the server independently re-validates length,
  coordinate range, and ownership on every request regardless of what the
  client sends.
- **Account-scoped reset**: `MainViewModel.logout()` cancels any in-flight
  outlet fetch and clears outlet state, the same pattern already applied to
  every other account-scoped `StateFlow`, so a second account on a shared
  device never briefly sees a previous Member's outlets.
- **Scope note**: Delete (`/outlet/delete`) is included even though it
  reuses a backend route built primarily for the Web Admin's own Outlet
  Management flow -- a Member can only ever delete their own PENDING/REJECTED
  submissions, never an APPROVED or Admin-assigned outlet.

## Important configuration locations

| What | Where |
|---|---|
| Backend API base URL | Defaults to `https://your-tams-domain.example/backend/` (`DEFAULT_BASE_URL` in `MemberRepository.kt`). `MemberRepository.baseUrl` supports being changed at runtime and persisted to SharedPreferences, but no screen currently calls that setter -- there's no in-app UI for it yet. To point at a different backend, edit `DEFAULT_BASE_URL` and rebuild. |
| Application ID / namespace | `com.trubus.tams`, set in `app/build.gradle.kts`. |
| Permissions & components | `AndroidManifest.xml` -- location (fine/coarse/background), foreground service, boot receiver, notifications, wake lock, `REQUEST_INSTALL_PACKAGES` + the `FileProvider` entry for OTA updates. |
| OTA update FileProvider paths | `res/xml/file_paths.xml` -- exposes only `filesDir/updates/` (where the downloaded APK lands) to the system installer; nothing else in app storage is shared. |
| Release signing | `app/build.gradle.kts`'s `signingConfigs`, reads `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_PASSWORD` env vars, falls back to `<android>/my-upload-key.jks` if `KEYSTORE_PATH` isn't set. Neither the keystore nor the passwords are checked into the repo. |
| Debug signing | Uses `<android>/debug.keystore` if present, otherwise the standard `~/.android/debug.keystore` (default alias/password `androiddebugkey` / `android`). |
| R8 / shrinking rules | `app/proguard-rules.pro` -- applied only to release builds (`isMinifyEnabled = true`, `isShrinkResources = true`). |
| Dependency versions | `gradle/libs.versions.toml` (single source of truth -- don't hardcode a version directly in `app/build.gradle.kts`). |
| Remote Management cache | `RemoteConfigRepository`'s own SharedPreferences file (`remote_config_prefs`) -- GPS Update Interval, Sync Interval, and last-fetch timestamp. Cleared only by uninstalling the app; not tied to login/logout. |

## Main dependencies

- **UI**: Jetpack Compose (BOM `2024.09.00`) + Material3, `activity-compose`, lifecycle `2.8.7`, `material-icons-core`/`material-icons-extended` (icon set used across every screen)
- **Networking**: Retrofit `2.12.0` + `converter-moshi`, Moshi `1.15.2` (via `moshi-kotlin-codegen`, KSP-generated adapters -- see `ApiService.kt`'s doc comment on why no reflection-based Moshi factory is used), OkHttp `4.10.0` + logging interceptor
- **Persistence**: Room `2.7.0` (offline location queue)
- **Background work**: WorkManager `2.9.1`
- **Location**: Play Services Location `21.3.0` (`FusedLocationProviderClient`)
- **Maps**: osmdroid `6.1.18` (OpenStreetMap, no API key)
- **Async**: Kotlin Coroutines `1.10.2`
- **Testing** (`testImplementation`, `src/test` only): JUnit4 `4.13.2`, Robolectric `4.16.1` (`MemberRepositoryTest`'s real Context/SharedPreferences), kotlinx-coroutines-test `1.10.2`, AndroidX Test Core `1.6.1` (`ApplicationProvider`) -- see "Testing" below
- **Build tooling**: AGP `9.2.1`, Kotlin `2.2.10`, KSP `2.3.5` (for Room + Moshi codegen), Gradle `9.6.1` (`gradle/wrapper/gradle-wrapper.properties`)

Deliberately **not** used: Google Maps SDK, Firebase, Google Services plugin, navigation-compose, Coil, CameraX, Accompanist, DataStore -- each was either leftover template wiring or would add APK size/startup overhead with no feature depending on it. See the comments at the top of `app/build.gradle.kts`'s `dependencies` block before re-adding any of these.

## Testing

`app/src/test/` holds plain JVM unit tests, run via `./gradlew test`. There
is still no `androidTest/` (instrumented) source set -- nothing in this
suite needs a device/emulator.

- **`util/TrackingHealthTest.kt`** -- pure JVM, no Robolectric (`TrackingHealth`
  has no Android framework dependency). Covers `staleThresholdMillis`'s floor,
  `parseFixMillis`'s WIB-offset correctness, and `elapsedMillisSince`'s
  clock-rollback guard (a future-dated fix must return `null`, never a
  negative number).
- **`data/geocoding/AddressSearchServiceTest.kt`** -- pure JVM. Uses fake
  `ForwardGeocodingProvider`s (never hits real Nominatim/Photon) to cover the
  fallback chain, provider-exception recovery, cancellation propagation, the
  "not-found is never cached" rule, and the 24h cache TTL -- the last of
  which is only testable without a real 24h sleep because of a test-only
  `internal var clockMillis` seam on `AddressSearchService` (production
  always uses the real wall clock).
- **`data/repository/MemberRepositoryTest.kt`** -- runs under Robolectric
  (`@RunWith(RobolectricTestRunner::class)`), since `MemberRepository` needs
  a real `Context`/`SharedPreferences` for `lastKnownLocation`/`currentUser`/
  etc. Room and the network are faked instead, via `FakeOfflineLocationDao`
  and `FakeApiService` (both in the same package, constructor-injected
  through `MemberRepository`'s `offlineDaoOverride`/`apiServiceOverride`
  parameters -- `null` by default, so every production call site is
  unaffected). Covers the write-ahead offline queue (insert happens before
  the network attempt; the row survives a 5xx but is dropped on a
  non-retryable 4xx or a thrown network exception) and the
  `lastKnownLocation` recency guard (an older fix arriving after a newer one
  must never overwrite it, but an equal-or-newer or unparseable timestamp
  still does).

  Also declares `@Config(shadows = [ShadowWorkManagerInitializer::class])`.
  Robolectric recreates the Application -- and re-attaches every
  manifest-merged ContentProvider -- fresh for every `@Test` method by
  design (test isolation); left un-shadowed, `work-runtime-ktx`'s own
  default-init component (`androidx.work.WorkManagerInitializer`,
  an App Startup component merged into the manifest by that library)
  rebuilds WorkManager's internal Room `WorkDatabase` from scratch on
  every single test method -- the dominant cost behind an earlier
  version of this suite taking about 2 minutes for these 12 tests alone.
  `ShadowWorkManagerInitializer.kt` (same package, `src/test`-only, never
  compiled into the production APK) no-ops that component's `create()` so
  Robolectric never does that work in the first place -- safe here
  specifically because nothing `MemberRepositoryTest` exercises ever
  calls `WorkManager.getInstance(...)` (only `LocationSyncWorker` does,
  which this test class never constructs).

`testOptions.unitTests.isReturnDefaultValues = true` (in
`app/build.gradle.kts`) lets these tests call incidental, unmocked Android
framework methods (e.g. `android.util.Log.*` on an error path) without
throwing -- they just return that type's default. Robolectric tests are
unaffected by this setting; Robolectric fully shadows the Android runtime
instead of using the plain stub jar.

Not covered here and out of scope for this pass: `AddressSearchService`'s
100-entry cache-overflow eviction, and any test that would require actually
exercising Room/SQLite or a real HTTP stack -- both were judged not worth
the added test-only production surface for what they'd additionally prove
beyond what's already covered above.

## Building the app

Requires a signing keystore for release builds only -- debug builds work out of the box. Requires JDK 17 and Gradle `9.4.1`+ to run the build itself (AGP `9.2.1`'s own minimums -- see its [release notes](https://developer.android.com/build/releases/gradle-plugin) for the current compatibility table); this is independent of `app/build.gradle.kts`'s `sourceCompatibility`/`targetCompatibility = JavaVersion.VERSION_11`, which only controls the Java language level the app's own Kotlin/Java source compiles against, not the JDK Gradle itself runs on. `gradle/wrapper/gradle-wrapper.properties` currently pins Gradle `9.6.1`.

> `gradlew`/`gradlew.bat` and the wrapper jar aren't currently checked into
> this repo (only `gradle/wrapper/gradle-wrapper.properties` is) -- open the
> project in Android Studio first, which provisions the wrapper
> automatically, before running `./gradlew` from a terminal.

```bash
# Debug build (uses debug.keystore, no env vars needed)
./gradlew assembleDebug

# Release build (requires KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD env vars,
# or a my-upload-key.jks file at the android/ project root)
./gradlew assembleRelease
```

Output APKs land in `app/build/outputs/apk/{debug,release}/`.

## Running the project

1. Open the `android/` folder in a recent Android Studio release compatible with AGP `9.2.1` (requires JDK 17 and Gradle `9.4.1`+ to run the build itself -- see the [AGP release notes](https://developer.android.com/build/releases/gradle-plugin) for the exact minimum Studio version, since that mapping changes with every AGP release) / compileSdk `36`.
2. Let Gradle sync -- no `local.properties` edits needed beyond the SDK path Android Studio sets automatically.
3. Run on a device or emulator with **API 24+**. Background location tracking (the app's core feature) needs a real device or an emulator image with Google Play services for realistic GPS behavior.
4. On first launch, log in with a `tams_users` account (ask a project maintainer for backend access, or edit `DEFAULT_BASE_URL` in `MemberRepository.kt` and rebuild to point at a local backend instance -- there's no in-app setting for this).

## Troubleshooting

- **`gradlew`/`gradlew.bat: command not found`, or Gradle can't locate the wrapper jar** -- expected on a fresh checkout; only `gradle/wrapper/gradle-wrapper.properties` is committed. Open `android/` in Android Studio first (it provisions the wrapper automatically), then run `./gradlew` from a terminal afterwards.
- **Gradle sync fails on JDK version, or a cryptic class-loading error during sync/build** -- AGP `9.2.1` requires JDK 17 and Gradle `9.4.1`+ to run the build itself (see "Building the app" above); a JDK 11 toolchain (which is only this app's own *source/target* compatibility, not the JDK Gradle runs on) is not enough to invoke the build.
- **`org.xml.sax.SAXParseException: The string "--" is not permitted within comments`** -- an XML `<!-- -->` comment (typically in `res/values/*.xml`) contains a literal `--` somewhere in its body, which the XML spec forbids anywhere inside a comment, not just at the boundaries. This is easy to introduce by habit from this codebase's Kotlin `//` comments, which use `--` freely as a dash -- that convention does not carry over into XML comments. Search the changed XML file for `--` and replace it with a different separator (e.g. a single `-`).
- **Start Location silently does nothing, or the foreground service appears to stop shortly after being backgrounded** -- check, in order: (1) `ACCESS_BACKGROUND_LOCATION` was granted -- the app blocks Start entirely via a dialog until this is set to "Allow all the time", so this is usually not the cause once Start has ever succeeded once; (2) the OS hasn't killed the app despite the foreground service, common on MIUI/ColorOS/FuntouchOS/EMUI even when battery-optimization-whitelisted -- see the Battery Optimization card in "App workflow" above, and note `LocationSyncWorker`'s ~3-minute watchdog is a recovery mechanism, not a guarantee of zero gap; (3) whether the rejection is actually server-side and expected -- see the next item.
- **`POST /location/update` returns HTTP 403 with `error_code: outside_operational_hours`** -- not a bug: this is Force Location's server-side gate rejecting a tracking fix sent outside the 07:00-16:00 WIB window for a Member without an Admin-granted override. Expect the app to automatically stop tracking right after, if it was still running -- see "Force Location (Force Override)" above and [`web/README.md`](../web/README.md#force-location-force-override) for how an Admin grants the override.

## Before you start developing

- There is **no local backend** included here -- the app talks to `web/backend/` (PHP REST API). See [`web/README.md`](../web/README.md) to run that, or use the production URL.
- `web/backend/api.php`'s route path is hardcoded as this app's `DEFAULT_BASE_URL`. If you rename or move that file, existing installs break until manually reconfigured -- don't, without a very good reason and a migration plan.
- All timestamps are Asia/Jakarta (WIB, UTC+7); always go through `util/WibTime.kt` rather than formatting dates ad hoc.
- `MemberRepository` is the only class allowed to touch Retrofit, SharedPreferences, or the Room database directly -- new features should extend it rather than reaching around it.
