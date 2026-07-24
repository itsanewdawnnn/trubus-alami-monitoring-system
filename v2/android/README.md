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
| `data/model/` | Plain data classes (`Models.kt`), Moshi-annotated for JSON (de)serialization. Includes `OfflineLocation` entity with a unique constraint on `(userId, recordedAt)` to prevent queue bloat. |
| `data/api/` | `ApiService.kt` -- Retrofit interface describing every backend route (`?route=` query params match `backend/api.php`'s router), including the `/outlet/*` routes backing Outlet Management. |
| `data/repository/` | `MemberRepository.kt` -- the single data-access layer. Owns the Retrofit client, SharedPreferences, and the offline location queue. `RemoteConfigRepository.kt` -- caches Remote Management settings. `ActivityLogRepository.kt` -- fire-and-forget audit logs. `UpdateRepository.kt` -- OTA update server calls. |
| `data/local/` | `AppDatabase.kt` -- Room database for the offline location queue. Uses **Write-Ahead Logging (WAL)** style: points are queued before the network attempt to survive process death. |
| `data/geocoding/` | `ReverseGeocodingService.kt` -- turns lat/lng into "near X" landmark hints via Nominatim. `AddressSearchService.kt` -- orchestrates forward-geocoding (Nominatim + Photon fallback) for Outlet Management. |
| `data/update/` | OTA update logic: `ApkDownloadManager.kt` and `UpdateManager.kt`. |
| `service/` | `MemberLocationService.kt` -- **Reliability-First** foreground `Service`. Uses a dedicated `HandlerThread` for GPS callbacks, processes batched locations, and holds a `WakeLock` for health checks. `BootCompletedReceiver.kt` restarts tracking after reboot. |
| `worker/` | `LocationSyncWorker.kt` -- ~3-minute watchdog that re-asserts the service and flushes the offline queue in small batches (max 50) to avoid blocking live fixes. |
| `ui/screens/` | `MainAppScreen.kt` -- root composables. `OsmMap.kt` -- wraps osmdroid. `OutletScreen.kt` -- **Compact/Minimalist** outlet management UI optimized for field use. `UpdateDialog.kt` -- OTA update UI. |
| `ui/viewmodel/` | `MainViewModel.kt` -- single `AndroidViewModel` exposing all app state as `StateFlow`. |
| `util/` | `WibTime.kt` -- thread-safe WIB timestamp formatting. `TrackingHealth.kt` -- shared GPS staleness formulas. `OneShotLocationProvider.kt` -- helper for manual location fetches. |

## Architecture & Reliability Principles

The app is built with a **Reliability-First** approach, specifically optimized for **low-end devices (RAM 2–3 GB)** and aggressive OEM power management (MIUI, ColorOS, etc.):

- **Data Continuity**: Processes *all* locations in a `LocationResult` batch, not just the last one, to prevent gaps when the OS batches fixes during Doze mode.
- **Process Survival**: Uses `START_STICKY`, `onTaskRemoved()` alarm-restarts, and a WorkManager watchdog to ensure tracking resumes as quickly as possible after a kill.
- **Disk-First Persistence**: Every GPS fix is written to Room *before* the network call. If the process is killed mid-upload, the point remains in the queue for the next sync.
- **Resource Efficiency**: Caches expensive objects (like `SimpleDateFormat` via `ThreadLocal`), uses `Sequence` for list processing, and limits database batch sizes to keep the UI responsive on limited RAM.

## App workflow

1. **Login & Session Validation** -- a stored token is verified against `GET /profile` on every cold start before entry is allowed.
2. **Member Tracking** -- starts `MemberLocationService` (Foreground Service). Priority is set to `DEFAULT` to reduce the risk of Low Memory Kills.
3. **Offline Queueing** -- if a fix fails to upload (network/5xx), it stays in Room. A successful upload triggers a background flush of the queue.
4. **Outlet Management** -- members register and reorder outlets. The UI is designed to be **highly compact** to minimize scrolling and render overhead.
5. **OTA Updates** -- checks for updates on launch. Mandatory updates structurally block the app until installed.

## Remote Management

Administrators can tune the app's behavior via `GET /app/config` without an APK rebuild:
- **GPS Update Interval**: How often a fix is requested (default 10s).
- **Sync Interval**: How often the watchdog/sync worker runs (default 3 min).
- **Password Bounds**: Min/max length for profile updates.

## Background Tracking Reliability

Continuous tracking uses three cooperating layers:
- **Foreground Service**: The primary tracker. Runs on a high-priority background thread.
- **AlarmManager**: Schedules a near-instant (~1s) restart if the user swipes the app away from Recents.
- **WorkManager**: A ~3-minute watchdog that catches harder kills where the process couldn't react.

## Testing

Run `./gradlew test` for JVM unit tests.
- `TrackingHealthTest.kt`: Time and staleness logic.
- `AddressSearchServiceTest.kt`: Geocoding fallback/caching.
- `MemberRepositoryTest.kt`: Robolectric-based repository/persistence tests.

## Building & Running

- **SDK**: API 24+ required.
- **JDK**: Requires JDK 17 (Gradle 9.4.1+ requirement).
- **Wrapper**: Open in Android Studio first to provision the Gradle wrapper.

```bash
./gradlew assembleDebug   # For testing
./gradlew assembleRelease # For deployment (requires signing config)
```

## Troubleshooting

- **Gaps in rute (missing segments)**: Check if "Battery Optimization" is disabled and "Autostart" is enabled (especially on MIUI/ColorOS). The app includes a dashboard card to guide users through this.
- **Service stops in background**: Ensure `ACCESS_BACKGROUND_LOCATION` is set to "Allow all the time" in system settings.
- **HTTP 403 / outside_operational_hours**: Expected behavior when tracking outside the 07:00-16:00 WIB window without an Admin override.
