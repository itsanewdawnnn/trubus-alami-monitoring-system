# TAMS — Trubus Alami Monitoring System

This repository root holds versioned snapshots of the TAMS project: `v1/`
and `v2/`. The active folder is always the highest-numbered `vN/` present --
today that's `v2/`. This file explains what actually differs between the
snapshots present today, `v1/` and `v2/`.

## v1 vs v2

**`v1/`** is a snapshot of the project from before the Outlet Management
feature existed. Confirmed by direct comparison against `v2/`, it lacks:

- **Outlet Management** entirely -- no `tams_outlets`/`tams_outlet_edit_requests`/
  `tams_outlet_visits`/`tams_outlet_dwell_state` tables, no `web/pages/outlet.php`
  Admin Panel page, no Android Outlet screens/repository methods. This was the
  headline feature added after this snapshot was taken.
- **Mock-location / GNSS trust signals** -- `is_mock_location`/
  `gnss_satellites_used` don't exist anywhere in `v1` (not on the Android
  side, not on the two location tables, not on the Web Admin's detail cards).
- **`LocationSyncWorker`'s one-shot stale-fix watchdog** -- `v1`'s
  `LocationSyncWorker` has no fallback one-shot GPS fix attempt when the
  foreground service isn't running; that logic (and the
  `OneShotLocationProvider` helper it uses) doesn't exist in `v1` at all.
- **`MemberRepository.postLocation()`'s `lastKnownLocation` recency guard** --
  `v1` always overwrites `lastKnownLocation` unconditionally (last-write-wins,
  no timestamp comparison). This specific gap only became an exploitable race
  once the one-shot stale-fix watchdog above was introduced, which is why the
  guard itself is `v2`-only.
- **Any automated test suite** -- `v1/android/app/src/` has no `test/`
  source set at all (no JUnit/Robolectric, no test dependencies in
  `build.gradle.kts`/`libs.versions.toml`).

Everything else present in `v1` at the time of the snapshot -- Force
Location, Remote Management, OTA Update, Member Log, the write-ahead offline
queue's earlier failure-only variant, background tracking reliability
(`onTaskRemoved` restart alarm, exact alarms) -- already existed there and
carried forward into `v2` unchanged in kind, only refined further since.

**`v2/`** is the current, actively developed version: everything above, plus
all the ordinary bug fixes, audits, and documentation upkeep done since.

## Which folder to use

- **`v2/`** -- the default for any task: development, bug fixes, features,
  analysis, documentation. See `v2/README.md` for the full project
  (architecture, commands, module docs).
- **`v1/`** -- read-only archive. Only touch it if a task explicitly asks to
  compare current behavior against this earlier snapshot.

If a `v3/` (or later) folder is ever added, it becomes the new active
version -- always the highest-numbered `vN/` folder present -- and this file
should be updated to describe what changed against the version before it.
