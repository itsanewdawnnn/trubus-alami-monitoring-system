# TAMS — Trubus Alami Monitoring System

GPS location monitoring for field members: an Android app that tracks a
member's location and reports it to a PHP backend, plus a web admin panel
for staff to manage members and watch live/historical locations. If this is
the first time you're opening this project, start here.

This repository root holds versioned snapshots of the project: `v1/` and
`v2/`. Neither folder has its own root-level `README.md` anymore -- this
file is the single place for the project overview, architecture, setup, and
module documentation. Everything below describes `v2/`, the current active
snapshot, except "Versioned snapshots: v1/ and v2/" immediately below, which
is the one section that also covers the archived snapshot.

## Versioned snapshots: v1/ and v2/

The active folder is always the highest-numbered `vN/` present -- today
that's `v2/`. If a `v3/` (or later) folder is ever added, it becomes the new
active version, and this section should be updated to describe what changed
against the version before it.

- **`v2/`** -- the current, actively developed version. Default for any
  task: development, bug fixes, features, analysis, documentation.
  Everything else in this file (architecture, stack, setup, module docs)
  describes this folder.
- **`v1/`** -- read-only archive. Only touch it if a task explicitly asks to
  compare current behavior against this earlier snapshot. It's a snapshot of
  the project from before the Outlet Management feature existed. Confirmed
  by direct comparison against `v2/`, it lacks:

  - **Outlet Management** entirely -- no
    `tams_outlets`/`tams_outlet_edit_requests`/`tams_outlet_visits`/
    `tams_outlet_dwell_state` tables, no `web/pages/outlet.php` Admin Panel
    page, no Android Outlet screens/repository methods. This was the
    headline feature added after this snapshot was taken.
  - **Mock-location / GNSS trust signals** -- `is_mock_location`/
    `gnss_satellites_used` don't exist anywhere in `v1` (not on the Android
    side, not on the two location tables, not on the Web Admin's detail
    cards).
  - **`LocationSyncWorker`'s one-shot stale-fix watchdog** -- `v1`'s
    `LocationSyncWorker` has no fallback one-shot GPS fix attempt when the
    foreground service isn't running; that logic (and the
    `OneShotLocationProvider` helper it uses) doesn't exist in `v1` at all.
  - **`MemberRepository.postLocation()`'s `lastKnownLocation` recency
    guard** -- `v1` always overwrites `lastKnownLocation` unconditionally
    (last-write-wins, no timestamp comparison). This specific gap only
    became an exploitable race once the one-shot stale-fix watchdog above
    was introduced, which is why the guard itself is `v2`-only.
  - **Any automated test suite** -- `v1/android/app/src/` has no `test/`
    source set at all (no JUnit/Robolectric, no test dependencies in
    `build.gradle.kts`/`libs.versions.toml`).

  Everything else present in `v1` at the time of the snapshot -- Force
  Location, Remote Management, OTA Update, Member Log, the write-ahead
  offline queue's earlier failure-only variant, background tracking
  reliability (`onTaskRemoved` restart alarm, exact alarms) -- already
  existed there and carried forward into `v2` unchanged in kind, only
  refined further since.

## Overview

TAMS is two separate applications sharing one database:

- **Android app** (`v2/android/`) -- what field members and admins use day
  to day. Members get continuous background GPS tracking; admins get a
  live map, active-member list, and location history, all inside the same
  app (role-based, not separate builds).
- **Web** (`v2/web/`) -- a small PHP admin panel (browser-based, mirrors the
  Android app's admin role) *and* the REST API the Android app talks to,
  deployed together as one PHP codebase.

Both talk to the same MySQL/MariaDB database. There's no message queue, no
separate services layer -- it's intentionally simple: two clients, one
database, one source of truth for member data and location history.

## Project structure

```
tams/
├── README.md                <- you are here
├── v1/                       Archived snapshot (read-only) -- see "Versioned snapshots" above
│   ├── .gitignore
│   ├── TAMS-Panduan-Konfigurasi.docx   Indonesian quick-reference configuration guide
│   ├── android/               Native Android app (Kotlin, Jetpack Compose)
│   │   └── README.md          Android-specific documentation
│   └── web/                   PHP admin panel + REST API for the Android app
│       └── README.md          Web-specific documentation
└── v2/                       Current, actively developed version (default working folder)
    ├── .gitignore
    ├── TAMS-Panduan-Konfigurasi.docx   Indonesian quick-reference configuration guide
    ├── android/               Native Android app (Kotlin, Jetpack Compose)
    │   └── README.md          Android-specific documentation
    └── web/                   PHP admin panel + REST API for the Android app
        └── README.md          Web-specific documentation
```

## How Android and Web relate

```
┌─────────────┐        HTTPS / JSON        ┌──────────────────────┐
│  Android app │ ────────────────────────> │ v2/web/backend/       │
│  (members +  │ <──────────────────────── │ api.php (REST API)   │
│   admins)    │                            └──────────┬───────────┘
└─────────────┘                                        │
                                                         │ same DB
┌─────────────┐                                        │
│  Admin Panel │ ───────────────────────────────────────┘
│ (v2/web/     │  reads/writes the DB directly (PHP on
│  pages, ajax)│  the same server -- no HTTP hop needed)
└─────────────┘
```

- The **Android app** only ever talks to `v2/web/backend/api.php` over
  HTTPS, authenticated with a bearer token issued at login.
- The **Admin Panel** (the rest of `v2/web/`) is a separate, browser-facing
  PHP app that reads/writes the same database tables directly -- it does
  not go through the backend API, since both live on the same server.
- Both sides read/write the same `tams_users`, `tams_auth_tokens`,
  `tams_live_tracking_current`, `tams_member_history_locations`,
  `tams_ota_update`, `tams_remote_management`, `tams_member_log`,
  `tams_outlets`, `tams_outlet_edit_requests`, `tams_outlet_visits`, and
  `tams_outlet_dwell_state` tables. The one schema for all of them lives at
  `v2/web/database/schema.sql`.
- **OTA updates**: the Android app checks `v2/web/backend/api.php`'s
  `/app/version` route on every launch and can prompt (or, if the app is
  below the configured minimum supported version, require) an in-app
  update -- entirely configured through the Admin Panel's OTA Update page,
  including uploading the APK itself, no manual file editing or FTP step on
  the server. See `v2/web/README.md`'s "OTA Update" section and
  `v2/android/README.md`'s.
- **Remote Management**: an Administrator can change safe Android app
  behavior (GPS Update Interval, Sync Interval) from the Admin Panel without
  shipping a new APK -- the app reads these from `v2/web/backend/api.php`'s
  public `/app/config` route and caches them locally, falling back to
  built-in defaults if the server is unreachable. See `v2/web/README.md`'s
  and `v2/android/README.md`'s "Remote Management" sections.
- **Member Log**: a central audit trail of Member activity (profile changes,
  Start/Stop location, sync failures, and other important events),
  written by the Android app via `/activity/log` and viewed on the Admin
  Panel's "Member Log" page. See `v2/web/README.md`'s "Member Log" section.
- **Member Version Monitoring**: the Android app reports its version and
  device info at login and on every location sync; the Admin Panel's
  Members page shows each member's status (latest / outdated-but-supported
  / unsupported) computed against the OTA Update page's configured
  thresholds. See `v2/web/README.md`'s "Member Version Monitoring" section.
- **Force Location (Force Override)**: on the Members page's Actions
  column, an Admin can exempt a Member from the default 07:00-16:00 WIB
  Start Location window. Enforced server-side, on the server's own clock,
  fresh on every single `/location/update` call (not just at Start) --
  never bypassable from the app or a spoofed device clock. Turning Force
  OFF takes effect immediately with no separate "push a stop signal"
  step: the toggle is a single database write, and the Member's very next
  location fix is checked against it -- if they're still tracking outside
  the allowed window at that point, the server rejects the fix and the
  Android app automatically stops sending location, with no action needed
  from the Member. See both module READMEs' "Force Location" sections.
- **Outlet Management**: Members (via the Android app) can register outlets
  -- physical locations they're expected to visit -- which go through an
  Admin approval queue (PENDING / APPROVED / REJECTED); Admins can also
  create outlets directly (auto-approved) and assign each one to a Member.
  Outlet-Member is one-to-one from the outlet's side (`tams_outlets.member_id`
  -- exactly one Member per outlet, though a Member may own many outlets);
  reassigning an outlet to a different Member is a single-column update that
  automatically releases the previous owner. Visit detection is geofencing
  computed entirely server-side, inside `backend/api.php`'s existing
  `/location/update` transaction -- there's no separate "check in" call. An
  Admin-configurable radius and minimum dwell time (Remote Management)
  decide when a Member's incoming GPS fix counts as a confirmed visit for
  that day. Four tables back this feature (`tams_outlets` and friends, see
  above); visits are an append-only ledger, never rewritten even when an
  Admin merges two nearby outlets. The Web Admin's Outlet page has a Visit
  Report tab: for a chosen day, active Members who visited at least one
  outlet, each's distinct-outlet visit count against the Minimum Outlet
  Visits target; clicking a row opens a read-only popup with the visited
  outlets and each visit's time. See both module READMEs' "Outlet
  Management" sections.
- **Mock-location trust signals**: every GPS fix carries two advisory
  signals computed on-device -- whether Android flags the fix as coming
  from a mock provider, and how many GNSS satellites were actually used in
  the fix -- stored alongside the fix on both `tams_live_tracking_current`
  and `tams_member_history_locations`. These are informational only: they
  are never used anywhere to reject, filter, or hide a fix (a modified APK
  could fake either signal, and this project's location tables must only
  ever contain genuine, unfiltered continuous-tracking fixes). The Admin
  Panel's Live Tracking and Trip History detail
  cards show a passive "Possible Mock GPS" badge for manual review when
  either signal looks suspicious. See `v2/android/README.md`'s
  "Mock-Location Detection" section and `v2/web/README.md`'s "Live
  Tracking, Member History & Reverse Geocoding" section.
- **`v2/web/backend/` must never be renamed or moved.** The Android app has
  `https://tams.sbstrans.net/backend/` hardcoded as its default API URL
  (`MemberRepository.kt`). Changing that path breaks every already-installed
  copy of the app until manually reconfigured.

## Technology stack

| Layer | Technology |
|---|---|
| Android app | Kotlin, Jetpack Compose + Material3, MVVM, Retrofit + Moshi, Room, WorkManager, osmdroid (maps), Gradle Kotlin DSL |
| Backend API | PHP 8+ (no framework), bearer-token auth, MySQL/MariaDB |
| Admin Panel | PHP 8+ (no framework, no build step), vanilla JS + CSS, Leaflet (via CDN, maps only) |
| Database | MySQL / MariaDB, one shared schema |

## Requirements

- **Android**: a recent Android Studio release compatible with AGP `9.2.1` (this requires JDK 17 and Gradle `9.4.1`+ to run the build itself -- see [`v2/android/README.md`](./v2/android/README.md#building-the-app)), Android SDK with API 36, a device or emulator running API 24+.
- **Web**: PHP 8+, MySQL/MariaDB, an Apache-compatible host (the project relies on `.htaccess` for access control) or equivalent config on another web server.
- No Docker/CI setup exists yet for either side -- both are run/deployed directly.

## Running the project

Each module has its own detailed instructions -- this is just the shortest path to a working setup:

1. **Database**: create a MySQL/MariaDB database and import `v2/web/database/schema.sql` (idempotent -- safe to re-run on an existing database too).
2. **Web**: point `v2/web/database/credentials.php` at that database (via env vars or the fallback constants -- both `v2/web/config.php` and `v2/web/backend/includes/config.php` share this one file), then serve the `v2/web/` folder with PHP + Apache. See [`v2/web/README.md`](./v2/web/README.md).
3. **Android**: open `v2/android/` in Android Studio, sync Gradle, and run. By default it points at the production API URL; there's no in-app setting for this, so to point at a local backend instead, edit `DEFAULT_BASE_URL` in `MemberRepository.kt` and rebuild. See [`v2/android/README.md`](./v2/android/README.md).

## Module documentation

- [`v2/android/README.md`](./v2/android/README.md) -- folder structure, architecture, app workflow, build/run instructions, dependencies, troubleshooting.
- [`v2/web/README.md`](./v2/web/README.md) -- Admin Panel + Backend API structure, folder responsibilities, deployment, database, troubleshooting.

## Guide for new developers

1. Read this file (done).
2. Skim `v2/android/README.md` and `v2/web/README.md` for whichever side you'll be working on -- both explain their own architecture in depth.
3. Note the one hard constraint that spans both modules: `v2/web/backend/`'s folder name and API route paths are load-bearing for every installed Android client and must not change casually.
4. Passwords are stored in plain text throughout this project (`tams_users` and elsewhere) as a deliberate, current project decision -- not an oversight.
5. All timestamps across both apps use Asia/Jakarta (WIB, UTC+7) -- computed in PHP (`date()`/`time()`), never left to MySQL's own `NOW()`/`CURRENT_TIMESTAMP`, since the DB server's clock/timezone isn't guaranteed to match PHP's.
6. Because of point 4, both login entry points (`v2/web/login.php` for Admins, `v2/web/backend/api.php`'s `/auth/login` for Members) lock an account out for 15 minutes after 5 consecutive wrong passwords. Password length rules (minimum/maximum) are configurable through the Admin Panel's Remote Management page, backed by the `tams_remote_management` database table -- both PHP apps and the Android app read the same table instead of a shared PHP file or hardcoded constants; see `v2/web/README.md`'s Remote Management section for details.
7. (Optional) This repo can be graphed with [Graphify](https://graphify.com) (`pip install graphifyy`, no API key, on-device parsing) for a quick architecture map -- run `graphify update .` from `v2/`. No generated report is kept in this repo: an earlier one was reviewed and found to duplicate what this README and `v2/android/README.md`/`v2/web/README.md` already explain, so it wasn't committed as a standing file.
