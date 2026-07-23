# TAMS — Trubus Alami Monitoring System

GPS location monitoring for field members: an Android app that tracks a
member's location and reports it to a PHP backend, plus a web admin panel
for staff to manage members and watch live/historical locations. If this is
the first time you're opening this project, start here.

## Overview

TAMS is two separate applications sharing one database:

- **Android app** (`android/`) -- what field members and admins use day to
  day. Members get continuous background GPS tracking; admins get a
  live map, active-member list, and location history, all inside the same
  app (role-based, not separate builds).
- **Web** (`web/`) -- a small PHP admin panel (browser-based, mirrors the
  Android app's admin role) *and* the REST API the Android app talks to,
  deployed together as one PHP codebase.

Both talk to the same MySQL/MariaDB database. There's no message queue, no
separate services layer -- it's intentionally simple: two clients, one
database, one source of truth for member data and location history.

## Project structure

```
tams/
├── README.md              <- you are here
├── .gitignore              Repo-wide ignores (OS/editor cruft)
├── android/                Native Android app (Kotlin, Jetpack Compose)
│   └── README.md           Android-specific documentation
└── web/                    PHP admin panel + REST API for the Android app
    └── README.md           Web-specific documentation
```

## How Android and Web relate

```
┌─────────────┐        HTTPS / JSON        ┌──────────────────────┐
│  Android app │ ────────────────────────> │ web/backend/          │
│  (members +  │ <──────────────────────── │ api.php (REST API)   │
│   admins)    │                            └──────────┬───────────┘
└─────────────┘                                        │
                                                         │ same DB
┌─────────────┐                                        │
│  Admin Panel │ ───────────────────────────────────────┘
│ (web/        │  reads/writes the DB directly (PHP on
│  pages, ajax)│  the same server -- no HTTP hop needed)
└─────────────┘
```

- The **Android app** only ever talks to `web/backend/api.php` over
  HTTPS, authenticated with a bearer token issued at login.
- The **Admin Panel** (the rest of `web/`) is a separate, browser-facing
  PHP app that reads/writes the same database tables directly -- it does
  not go through the backend API, since both live on the same server.
- Both sides read/write the same `tams_users`, `tams_auth_tokens`,
  `tams_live_tracking_current`, `tams_member_history_locations`,
  `tams_ota_update`, `tams_remote_management`, and `tams_member_log` tables.
  The one schema for all of them lives at `web/database/schema.sql`.
- **OTA updates**: the Android app checks `web/backend/api.php`'s
  `/app/version` route on every launch and can prompt (or, if the app is
  below the configured minimum supported version, require) an in-app
  update -- entirely configured through the Admin Panel's OTA Update page,
  including uploading the APK itself, no manual file editing or FTP step on
  the server. See `web/README.md`'s "OTA Update" section and
  `android/README.md`'s.
- **Remote Management**: an Administrator can change safe Android app
  behavior (GPS Update Interval, Sync Interval) from the Admin Panel without
  shipping a new APK -- the app reads these from `web/backend/api.php`'s
  public `/app/config` route and caches them locally, falling back to
  built-in defaults if the server is unreachable. See `web/README.md`'s and
  `android/README.md`'s "Remote Management" sections.
- **Member Log**: a central audit trail of Member activity (profile changes,
  Start/Stop location, sync failures, and other important events),
  written by the Android app via `/activity/log` and viewed on the Admin
  Panel's "Member Log" page. See `web/README.md`'s "Member Log" section.
- **Member Version Monitoring**: the Android app reports its version and
  device info at login and on every location sync; the Admin Panel's
  Members page shows each member's status (latest / outdated-but-supported
  / unsupported) computed against the OTA Update page's configured
  thresholds. See `web/README.md`'s "Member Version Monitoring" section.
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
  from the Member. See both READMEs' "Force Location" sections.
- **`web/backend/` must never be renamed or moved.** The Android app has
  `https://your-tams-domain.example/backend/` hardcoded as its default API URL
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

- **Android**: a recent Android Studio release compatible with AGP `9.2.1` (this requires JDK 17 and Gradle `9.4.1`+ to run the build itself -- see [`android/README.md`](./android/README.md#building-the-app)), Android SDK with API 36, a device or emulator running API 24+.
- **Web**: PHP 8+, MySQL/MariaDB, an Apache-compatible host (the project relies on `.htaccess` for access control) or equivalent config on another web server.
- No Docker/CI setup exists yet for either side -- both are run/deployed directly.

## Running the project

Each module has its own detailed instructions -- this is just the shortest path to a working setup:

1. **Database**: create a MySQL/MariaDB database and import `web/database/schema.sql` (idempotent -- safe to re-run on an existing database too).
2. **Web**: point `web/database/credentials.php` at that database (via env vars or the fallback constants -- both `web/config.php` and `web/backend/includes/config.php` share this one file), then serve the `web/` folder with PHP + Apache. See [`web/README.md`](./web/README.md).
3. **Android**: open `android/` in Android Studio, sync Gradle, and run. By default it points at the production API URL; there's no in-app setting for this, so to point at a local backend instead, edit `DEFAULT_BASE_URL` in `MemberRepository.kt` and rebuild. See [`android/README.md`](./android/README.md).

## Module documentation

- [`android/README.md`](./android/README.md) -- folder structure, architecture, app workflow, build/run instructions, dependencies, troubleshooting.
- [`web/README.md`](./web/README.md) -- Admin Panel + Backend API structure, folder responsibilities, deployment, database, troubleshooting.

## Guide for new developers

1. Read this file (done).
2. Skim `android/README.md` and `web/README.md` for whichever side you'll be working on -- both explain their own architecture in depth.
3. Note the one hard constraint that spans both modules: `web/backend/`'s folder name and API route paths are load-bearing for every installed Android client and must not change casually.
4. Passwords are stored in plain text throughout this project (`tams_users` and elsewhere) as a deliberate, current project decision -- not an oversight.
5. All timestamps across both apps use Asia/Jakarta (WIB, UTC+7) -- computed in PHP (`date()`/`time()`), never left to MySQL's own `NOW()`/`CURRENT_TIMESTAMP`, since the DB server's clock/timezone isn't guaranteed to match PHP's.
6. Because of point 4, both login entry points (`web/login.php` for Admins, `backend/api.php`'s `/auth/login` for Members) lock an account out for 15 minutes after 5 consecutive wrong passwords. Password length rules (minimum/maximum) are configurable through the Admin Panel's Remote Management page, backed by the `tams_remote_management` database table -- both PHP apps and the Android app read the same table instead of a shared PHP file or hardcoded constants; see `web/README.md`'s Remote Management section for details.
