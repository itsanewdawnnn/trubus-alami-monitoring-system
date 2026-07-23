# TAMS Web

This folder contains **two independent PHP applications** that happen to be
deployed to the same web space. They don't share application code, but they
do share the same database, and -- deliberately -- the one file that
configures how to connect to it (`database/credentials.php`, see below).
What counts as a valid account field (password length) is *also* shared, but
via a database table (`tams_remote_management`, configurable through the Admin
Panel's Remote Management page) rather than a shared PHP file -- the same
table the Android app reads too, so all three sides agree without any file
needing to be required across the two independent apps. Aside from that,
they don't know about each other's file structure. If you're new to this
project, read this file before opening anything else.

```
web/
├── README.md                   <- you are here
│
├── index.php                   ┐
├── login.php                   │
├── logout.php                  │
├── config.php                  │
├── ajax/                       │  Admin Panel -- browser-facing web app,
├── assets/                     │  see "Admin Panel" below
├── security/                   │
├── helpers/                    │
├── layouts/                    │
├── pages/                      ┘
│
├── download/                    Fixed home for the currently-published APK
│                                 (always TAMS.apk) -- written by
│                                 ajax/ota_update_save.php's upload
│                                 handler, see "OTA Update" below. Its own
│                                 .htaccess blocks directory listing and PHP
│                                 execution but not the file download
│                                 itself; not otherwise read by any PHP code.
│
├── database/                    ┐  Shared by both apps below: schema.sql
│   ├── schema.sql               │  (both read/write these same tables,
│   └── credentials.php          ┘  including tams_remote_management) and
│                                   credentials.php (both connect using this)
│
└── backend/                    ┐  Backend API -- REST API for the
    ├── api.php                 │  Android app, see "Backend API" below
    └── includes/                  (config.php, middleware.php)
```

## The two apps

### Admin Panel

A small PHP-native (no framework, no router, no build step) web app for
staff to manage members and watch their live location -- the web
equivalent of the Android app's own Admin role. Deployed to
`https://your-tams-domain.example/`.

| Folder      | Responsibility |
|-------------|----------------|
| `pages/`    | One file per screen (dashboard, members, live tracking, member history, member log, OTA update, remote management). Each is both the controller and the view -- there's no separate template layer, which is a deliberate choice for an app this small. |
| `ajax/`     | JSON endpoints called by `assets/js/*.js` via `fetch()`. This is the app's own API, distinct from `backend/` (which is the *Android app's* API). |
| `layouts/`  | Shared `<head>`/sidebar/topbar/footer markup, included by every file in `pages/`. |
| `security/` | `auth.php` (session/login guard) and `csrf.php` (CSRF token issue/verify) -- the two files that enforce who's allowed to do what. |
| `helpers/`  | `functions.php` -- small, general-purpose utilities (`e()` for HTML-escaping, `redirect()`, `input()`, `json_response()`, `asset_version()`, `haversine_distance_km()`), plus the Remote Management/Member Log features' single-source-of-truth definitions (`remote_management_definitions()`, `remote_management_values()`, `member_log_action_types()`) that don't belong to any one page. |
| `assets/`   | `css/style.css` (one stylesheet, no framework) and `js/*.js` (one file per page, plus `app.js`, `account_menu.js`, and `profile.js` shared on every page -- see below). |
| `config.php`| Session hardening, app-wide constants, and the DB connection (via `database/credentials.php`, see below). Sits at the root (not in a subfolder) so it's required with a single `__DIR__ . '/config.php'`/`__DIR__ . '/../config.php'` everywhere; blocked from direct browser access by name in the root `.htaccess` instead of a folder-wide rule. |

**Entry points** (the only files meant to be requested directly by a
browser): `index.php`, `login.php`, `logout.php`, everything under `pages/`,
and everything under `ajax/`. `security/`, `helpers/`, and `layouts/` each
have a `.htaccess` with `Require all denied` -- those files are only ever
`require`'d by PHP, never loaded directly, and are blocked at the web
server level as defense in depth. `config.php` gets the same treatment via
a `<Files "config.php">` rule in the root `.htaccess`, since it can't have
a folder-wide block (it shares the root with the public entry points).

**Auth**: the panel authenticates against the *same* `tams_users` table the
Android app uses (not a separate table) -- only rows with `role = 'admin'`
may log in. Passwords are plain text throughout this project (`tams_users`
and everywhere it's written), per an explicit, current project decision --
`hash_equals()` is used for comparisons only to avoid a timing
side-channel, not as hashing. Because of this, `login.php` locks an account
out for 15 minutes after 5 consecutive wrong-password attempts
(`tams_users.failed_login_attempts` / `locked_until`), mirroring the same
lockout `backend/api.php`'s `/auth/login` enforces for the Android app --
one shared table, one shared brute-force guard, enforced identically on
both entry points.

A logged-in session (`security/auth.php`) idles out after 30 minutes of
inactivity (`SESSION_TIMEOUT_SECONDS` in `config.php`), checked on every
protected page/`ajax/` request, not just at login -- `pages/*.php` gets an
HTML redirect back to the login screen, `ajax/*.php` gets a JSON 401, so a
stale browser tab never silently keeps failing requests forever. The
session cookie is `HttpOnly`, `SameSite=Lax`, and marked `Secure` whenever
the request arrived over HTTPS; the session ID is regenerated on every
successful login (`session_regenerate_id(true)`) as a defense against
session fixation. Every state-changing `ajax/*.php` request additionally
requires a CSRF token (`security/csrf.php`), issued at login and checked on
each call.

## OTA Update

The Android app checks `backend/api.php`'s `/app/version` route once per
launch for a newer version than the one installed. Every configurable piece
of that (version name/code, minimum supported version code, release notes,
and the published APK file itself) lives in exactly one place: the
`tams_ota_update` table (a single row, id `1`), edited exclusively through
the Admin Panel's **OTA Update** page (`pages/ota_update.php` +
`ajax/ota_update_save.php`).
There is no separate `version.json` file generated or uploaded anywhere --
`/app/version` reads that same row live on every request, so saving the
form takes effect for the Android app immediately. See
[`android/README.md`](../android/README.md#ota-update) for the client side
of this flow.

**Force Update is derived, not a field an Administrator sets.**
`/app/version` computes it on every request from the other two numbers:
Minimum Supported Version Code >= Version Code means Force Update is
`true` (every installed copy is already at or below the floor, so the
update is mandatory); Minimum Supported Version Code < Version Code means
`false` (optional, the user may choose Later). There is no `force_update`
column anymore (removed by `database/schema.sql`'s migration) -- the App
Version page shows the current result as a read-only badge instead of a
dropdown, always in sync with the two numbers above it.

**Publishing an update:** open the OTA Update page, choose the new `.apk`
under "APK File" (validated server-side as a genuine APK -- wrong
extension or a file that doesn't start with a ZIP signature is rejected),
set the matching Version Code/Name, and Save. The upload handler stores the
file as `download/TAMS.apk` (always this exact name, replacing whatever was
there before) and builds the download URL automatically from the file's own
location plus the request's host -- there is nothing to type or edit by
hand anymore, and no FTP/file-manager step. `download/` is a plain public
folder (its own `.htaccess` blocks directory listing and PHP execution, but
not the file download itself) -- cleartext `http://` still won't reach it
either way, since the Android app blocks that scheme by default on the OS
versions it targets, so the auto-generated URL always needs to resolve over
HTTPS in production. Re-uploading (a new build of the same version, or a
fix to a bad upload) simply replaces `download/TAMS.apk` again -- the file
is written to a temporary name first and only renamed into place once
fully and validly uploaded, so a failed or in-progress upload never
corrupts or interrupts a fix currently mid-download by a Member's device.

Version code must be strictly increasing -- the form rejects a value lower
than what's already published, since that's almost always a mistake (e.g.
forgetting to bump `versionCode` in `android/app/build.gradle.kts` before
building). Minimum Supported Version Code is a separate field on the same
page, driving both Member Version Monitoring's status indicator (see below)
and Force Update above -- it must never exceed the Version Code being
published.

**Hosting requirement:** APK files routinely exceed PHP's default upload
limits (`upload_max_filesize`/`post_max_size`, often 2-8M out of the box).
`ajax/.htaccess` raises these for the whole `ajax/` folder, but only takes
effect under `mod_php` -- on PHP-FPM hosting (where `.htaccess`
`php_value`/`php_flag` directives don't apply), raise
`upload_max_filesize`/`post_max_size` (the latter must be >= the former)
via the hosting control panel or a `.user.ini` file instead, or the upload
will fail with a clear "too large for this server's current upload limit"
error before ajax/ota_update_save.php's own 200MB application-level ceiling
is ever reached.

## Remote Management

Lets an Administrator change safe, behavioral Android app settings (GPS
Update Interval, Sync Interval, and minimum/maximum password length)
without shipping a new APK -- or, for the password bounds, without editing
a PHP file at all. Backed by the generic key/value `tams_remote_management`
table -- the set of editable settings (label, hint, default, min/max) is
defined once in `helpers/functions.php`'s `remote_management_definitions()` and
both `pages/remote_management.php` (the form) and
`ajax/remote_management_save.php` (validation + persistence) read that same
list, so adding a new setting later is a one-function edit, not a page
rewrite. `remote_management_save.php` also enforces one cross-field rule the
generic per-field validation can't
express on its own: `password_max_length` may never be set below
`password_min_length`.

Password length validation used to live in a separate shared PHP file
(`database/validation_rules.php`, now removed) required by both `config.php`
and `backend/includes/config.php`; it's a `tams_remote_management` row like
every other Remote Management setting instead, read via
`remote_management_values()` (Admin Panel side, `helpers/functions.php`) and
`getRemoteManagementInt()` (Backend API side, `backend/api.php`'s own local
copy -- these two apps don't share PHP files, only the database).

The Android app reads all of these values from `backend/api.php`'s public,
unauthenticated `GET /app/config` route (same rationale as `/app/version`
-- must be readable independent of login state) and caches them locally,
refreshing at most once every 30 minutes opportunistically rather than on
every request. See `android/README.md`'s "Remote Management" section for
the client side.

**Security invariant**: `/app/config` has no authentication at all, so
nothing sensitive (tokens, secrets, credentials, internal URLs) may ever be
added to `tams_remote_management` -- it's for safe, cosmetic/behavioral tuning
only.

## Member Log

A central audit trail of Member activity, viewed on the Admin Panel's
**Member Log** page (`pages/member_log.php` + `ajax/member_log_list.php`), mirroring
`pages/members.php`'s search/sort/paginate pattern with an added
Activity Type filter and date range filter. Backed by the
`tams_member_log` table.

Entries are written exclusively by the Android app via `backend/api.php`'s
`POST /activity/log` -- nothing on the Admin Panel side ever writes to this
table. Each entry stores the member's name (denormalized at write time, so
the log stays readable even after the account is later renamed or
deleted -- `user_id` uses `ON DELETE SET NULL`, never `CASCADE`), an
`action_type` (one of `profile_update`, `start_location`, `stop_location`,
`sync_failed`, `error` -- both the Android app and `backend/api.php`
validate against this same whitelist), a success/failed status, optional
before/after values for field-level changes, an optional free-text message,
and a timestamp.

**Security**: `/activity/log` always derives `user_id`/`user_name` from the
caller's own bearer token, never from the request body, so one member can
never write a log entry under another member's identity. Passwords and
other sensitive values must never be written to `field_before`/
`field_after`/`message` -- a password change is logged as the bare fact
"Password changed", never the value. The Member Log page itself is
Administrator-only, same as every other page in this Admin Panel.

## Member Version Monitoring

The Android app reports `app_version_name`, `app_version_code`,
`android_version`, and `device_model` at login and on every location sync
(piggybacked onto the existing `/auth/login` and `/location/update`
requests -- no extra endpoint or extra network call). These four columns
live on `tams_users` -- Member/Device Identity, not a Live Tracking fact,
per this project's own DDD column-ownership audit -- updated with
`COALESCE(:val, col)` so an older client that never sends them can't wipe
out a value a newer login already reported.

The Members page (`pages/members.php`) shows each member's reported App
Version and Device alongside a status badge, computed by
`ajax/members_list.php` against the OTA Update page's two thresholds
(`tams_ota_update.version_code` and `min_supported_version_code`):

| Status | Meaning |
|---|---|
| 🟢 Latest | Reported version code >= the currently published version code. |
| 🟡 Supported (Outdated) | Below the latest, but at or above the minimum supported version code. |
| 🔴 Unsupported | Below the minimum supported version code -- the member must update. |
| ⚪ Not reported yet | `app_version_code` still NULL on `tams_users` (never logged in/synced since this feature shipped). |

## Live Tracking, Member History & Reverse Geocoding

`pages/live_tracking.php` (two tabs -- Active Members list and a Real-Time
Map) and `pages/history.php` (per-member, per-date route playback) are the
Admin Panel's own equivalent of the Android app's Admin dashboard tabs. Both
map views are static shells driven by `assets/js/live_tracking.js`/`history.js` +
`assets/js/map.js` (shared Leaflet helpers), polling `ajax/live_tracking.php`,
`ajax/history.php`, and `ajax/history_dates.php` respectively;
`ajax/history_members.php` backs the Member History page's member picker (the full
member roster, unlike `ajax/members_list.php`'s paginated CRUD feed).

**Maps**: rendered with [Leaflet](https://leafletjs.com/) `1.9.4` (loaded
from a CDN, only on the two pages that need it -- see `layouts/header.php`'s
`$pageHasMap` flag) over OpenStreetMap tiles. This is a separate, independent
choice from the Android app's own map view, which uses osmdroid -- see
[`android/README.md`](../android/README.md#architecture); both happen to
render OpenStreetMap data, but share no code.

**Reverse Geocoding**: clicking a marker or history point shows a short
"near X" landmark hint alongside the raw coordinates, resolved by
`ajax/reverse_geocode.php` -- a server-side proxy to OpenStreetMap's
Nominatim API, never called directly from the browser (Nominatim's usage
policy requires a descriptive `User-Agent`, and proxying lets every admin's
lookups share one cache). Results are cached as flat files under the
server's temp directory, keyed by coordinates rounded to ~11m, with a
lightweight self-pruning step (a small random chance on every write to
delete entries older than 90 days) instead of a cron job. This is entirely
independent of the Android app's own client-side reverse geocoding
(`ReverseGeocodingService.kt`, called directly from the device with an
in-memory cache) -- see that file's entry in
[`android/README.md`](../android/README.md)'s Package layout table -- the
two features solve the same UX problem on each side without sharing any
code or cache.

**Trip History data model**: `ajax/history.php` (and `backend/api.php`'s
`/location/history`) read `tams_member_history_locations` with a single
unfiltered `WHERE user_id = ? AND recorded_at BETWEEN ? AND ?` -- no
`source`/`type` filter of any kind, because there is nothing to filter out.
That table holds *only* genuine continuous-tracking fixes, written by
exactly one route (`backend/api.php`'s `/location/update`). Any future
feature built on top of location history (Heatmap, Analytics, Reports,
etc.) can query `tams_member_history_locations` the same unfiltered way and
inherit the same guarantee for free.

## Force Location (Force Override)

Surfaced as a button in the Members page's **Actions** column
(`pages/members.php`, alongside Edit/Delete -- `ajax/members_save.php` and
`ajax/members_delete.php` respectively), implemented as
`ajax/members_force_toggle.php` with a direct database write -- consistent
with this Admin Panel never calling the Backend API (see the top of this
document). See
[`android/README.md`](../android/README.md#force-location-force-override)
for the client side.

**Force Location** toggles `tams_users.force_tracking_hours` for one
Member. By default (OFF) a Member may only run Start Location between
07:00-16:00 WIB (`backend/api.php`'s
`TRACKING_HOUR_START`/`TRACKING_HOUR_END`, checked against the server's own
clock); toggling this ON for a Member lets them track at any time. The
button is tinted (`.btn-icon--active` in `style.css`) while ON, so its
state reads at a glance.

Enforcement is entirely server-side, fresh on every single `POST
/location/update` call -- not just an imagined "start" call, since no such
endpoint exists, and not just the first fix of a session either. This
Admin Panel toggle is a single atomic UPDATE that just flips the one
column that gate reads, with no separate "push a stop signal to the
device" mechanism of its own -- a change takes effect on the Member's very
next location update, with no re-login or app restart required. This is
also what makes turning Force OFF self-enforcing: if the Member is still
tracking outside the operational-hours window at that point, their very
next fix is rejected (`HTTP 403`, `error_code: "outside_operational_hours"`),
and the Android app reacts to that specific rejection by automatically
stopping its own foreground service -- no user interaction needed on the
Member's side, and no polling loop or additional server-side state
required to make it happen. See `android/README.md`'s "Force Location
(Force Override)" section for the client side of this contract.

This gate is read atomically with the location write it guards, not via a
separate SELECT beforehand: `/location/update`'s transaction locks the
Member's `tams_users` row (`SELECT ... FOR UPDATE`, reading
`force_tracking_hours` alongside `is_active`) before it locks
`tams_live_tracking_current` and performs the update/insert. Because this
toggle's own UPDATE takes the same row lock, it can never interleave with
an in-flight fix -- whichever commits first is what the fix's locked read
sees, deterministically, not "usually" or "within a fix or two." The Stop
Location signal (`status: "offline"`) is exempt from this lock entirely, by
design, so it always succeeds immediately regardless of Force or account
state -- read this section fully for the locking-order rationale before
changing anything in this transaction.

### Backend API

The REST API the **Android app** talks to. Nothing in the Admin Panel calls
this over HTTP -- the Admin Panel reads the same database directly instead,
which avoids an unnecessary network hop for server-side code that's already
on the same machine.

| Path | Responsibility |
|------|-----------------|
| `backend/api.php` | The single router -- every Android app request hits this file with a `?route=` query param (see `android/app/.../data/api/ApiService.kt`). `/app/version` and `/app/config` are the two deliberately unauthenticated routes -- see "OTA Update" and "Remote Management" above. `/activity/log` backs the Member Log feature -- see "Member Log" above. `/location/status` backs the Force Location pre-flight check -- see "Force Location (Force Override)" above; it is never the actual enforcement boundary, which is the operational-hours gate applied unconditionally inside `/location/update` itself, on every call. |
| `backend/includes/config.php` | CORS headers and the DB connection (via `database/credentials.php`, see below). |
| `backend/includes/middleware.php` | Bearer-token authentication, shared by every route in `api.php`. |

**Why `backend/` keeps its name, and stays a sibling of the Admin Panel
instead of living in its own top-level folder:** the Android app has
`https://your-tams-domain.example/backend/api.php` hardcoded as its default API
URL (`MemberRepository.kt`'s `DEFAULT_BASE_URL`). Renaming or moving this
folder would break every already-installed copy of the app until it were
manually reconfigured, so restructuring intentionally leaves this one path
alone.

## Why the Admin Panel's shared PHP isn't in one `includes/`-style folder

`security/` (auth + CSRF) and `helpers/` (generic utilities) used to be a
single `includes/` folder. Split apart because the two are genuinely
different concerns -- one enforces who's allowed to do what, the other is
plain utility code with no security role -- and a folder named for *what a
file's contents get done to* (`include`d) rather than *what it's
responsible for* doesn't tell a new developer anything the filename didn't
already say.

## Deployment

There's no CI/CD here -- deployment is a manual upload (FTP / cPanel File
Manager) of this entire `web/` folder's contents to the hosting account's
document root for `your-tams-domain.example`. Both apps go up together, as
siblings, exactly as they sit here locally (i.e. don't flatten or rename
anything on the way up -- the Android app's hardcoded URL depends on
`backend/` staying exactly where it is).

The host has a static-asset cache in front of it (LiteSpeed Cache and/or
Cloudflare, depending on current hosting settings) that does **not** always
respect cache-busting query strings. After uploading a change, purge the
cache from the hosting control panel if it doesn't appear live within a
few minutes -- this has been the cause of "my change isn't showing up"
more than once. CSS/JS `<link>`/`<script>` tags already append a
`?v=<file mtime>` query string automatically (`helpers/functions.php`'s
`asset_version()`), which handles *browser*-side caching; it's the
*server/edge*-side cache that occasionally still needs a manual purge.

## Database

Both apps read and write the same MySQL/MariaDB database (`tams_users`,
`tams_live_tracking_current`, `tams_member_history_locations`,
`tams_ota_update`, `tams_remote_management`, `tams_member_log`, and friends).
There is exactly one schema, defined in `database/schema.sql` --
neither app "owns" it exclusively, which is why it sits as its own sibling
folder rather than nested inside `backend/`. It's safe to re-run on an
existing database too (idempotent `ALTER`s bring an older install up to
date).

There is also exactly one set of connection credentials, defined in
`database/credentials.php` (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
`DB_PASS`). Both `config.php` (Admin Panel) and `backend/includes/config.php`
(Backend API) `require` this same file rather than defining their own
copies -- changing database credentials is a one-file edit, not two. Prefer
setting these via environment variables in production; the literal values
in the file are placeholder fallbacks only.

Password length rules (`password_min_length`, `password_max_length`) used to
follow this same one-file-edit pattern via a shared `database/validation_rules.php`,
but that file has been removed -- they're now two more rows in
`tams_remote_management` (the same table Remote Management already uses for the
GPS Update Interval and Sync Interval), edited from the Admin Panel's Remote
Management page like any other setting there. `ajax/members_save.php` and
`ajax/profile_update.php` read them via `helpers/functions.php`'s
`remote_management_values()`; `backend/api.php`'s `/profile/update` reads them
via its own local `getRemoteManagementInt()` (these two apps don't share PHP
files, only the database -- see the top of this document). The Android app
reads the same table through `RemoteConfigRepository`, exactly like the GPS/
Sync intervals -- no more hardcoded/mirrored constants on any side.

**Long-term column types** (Production Server & Database Audit): every
timestamp column across all tables is `DATETIME`, not `TIMESTAMP` -- MySQL/
MariaDB's `TIMESTAMP` is a 4-byte UNIX time that cannot represent a moment
past 2038-01-19 03:14:07 UTC, a real ceiling for a system meant to run "for
years." `tams_member_history_locations.id` is `BIGINT UNSIGNED`, not plain `INT`,
since that table is the one expected to keep growing every day for the
project's whole lifetime -- every other table's row count stays small (one
row per Member/token/setting) regardless of how long the system runs, so
their `id` columns stay plain `INT`. Both changes are pure column-width/type
widening with no application-code impact (PDO already returns both
`DATETIME` and `TIMESTAMP` as the same string; nothing in this codebase uses
a `TIMESTAMP`-specific SQL function).

## Server Load & Scalability

Audited for concurrent use by 50-100 Members (see `android/README.md`'s own
"Server Load & Scalability" section for the Member-side request-frequency
breakdown this backs). Findings on this side:

- **Indexes**: every hot query path already has a supporting index --
  `tams_users(role, is_active)` (`/location/current`, `/member/list`,
  `/location/update`'s Force Override check), `tams_auth_tokens.token_hash`
  (unique, every authenticated request) and `.user_id`, `tams_live_tracking_current`'s
  primary key on `user_id` (every tracking upsert), and
  `tams_member_history_locations`'s unique `(user_id, recorded_at)` (both
  `/location/history` and `/location/history/dates`' sargable `BETWEEN`
  range queries). No missing index was found for any route in
  `backend/api.php` -- no schema change was made for this audit.
- **`/location/update`**: each request is a single short transaction scoped
  to one Member's own primary-key row in `tams_live_tracking_current`, so lock
  contention never crosses between different Members -- 100 Members
  tracking simultaneously at the default 10s interval is at most ~10
  requests/second system-wide, a light load for a single indexed upsert +
  insert on any PHP+MySQL host (shared or VPS).
- **`GET /location/status`**: `LocationSyncWorker`'s watchdog polls this
  route every ~3 minutes for every logged-in Member regardless of whether
  Start Location is even on, since it also doubles as this pass's session-
  death probe (see `android/README.md`). An adaptive, slower-while-idle
  version of that reschedule delay was built during this audit but reverted
  after a follow-up architecture review: at 50-100 Members this route's
  traffic is well under 1 request/second on average system-wide -- one
  indexed `tams_users` lookup by primary key -- never an actual server-side
  bottleneck, so the extra client-side branching wasn't worth keeping for a
  problem that doesn't exist at this scale. This route's own query needed no
  change either way.
- **OTA Update APK downloads**: served as a static file straight from
  `download/TAMS.apk` by Apache, not through PHP -- zero PHP/DB overhead per
  download regardless of how many Members fetch it at once, and Apache
  already serves HTTP Range requests for static files without any extra
  configuration, so an interrupted download resumes instead of restarting
  from zero. Considered and rejected: adding a `Cache-Control`/long-lived
  cache header for this file -- the upload flow (see "OTA Update" above)
  always keeps the same `TAMS.apk` filename and atomically replaces its
  bytes on every new version, so caching it for any meaningful duration
  risks serving a stale (possibly pre-force-update) APK to a Member right
  after an Administrator publishes a new one, and browser caching provides
  no benefit anyway against 50-100 *different* devices each downloading it
  for the first time. A large simultaneous download burst is a bandwidth
  concern for the hosting plan, not something an application-level change
  can remove -- if that becomes a real constraint at higher Member counts,
  the fix is infrastructure (a CDN in front of `download/`), not code.
- **Considered and rejected: batching GPS point uploads.** One of the
  suggested optimization strategies for this kind of audit is merging
  multiple requests into one. It wasn't applied to `/location/update`
  because the measured load doesn't warrant it (~10 requests/second
  system-wide at 100 concurrent Members, see above) and the change itself is
  risky: `/location/update`'s movement detection, out-of-order-arrival
  guard, and GPS sanity filtering (see that route's own comments) are all
  written around comparing exactly one incoming fix against exactly one
  previously-stored row, and would need a real rewrite to safely accept an
  array of points instead -- a materially larger, regression-prone change
  for a bottleneck that doesn't actually exist yet at this Member count.

## Query & Index Efficiency (Production Server & Database Audit)

A separate, focused pass over every endpoint's queries, indexes, transactions,
and locking -- not about request *count* (see "Server Load & Scalability"
above), but about whether each individual query stays cheap as
`tams_member_history_locations` and `tams_member_log` grow into the millions/tens
of millions of rows over the project's lifetime.

- **Confirmed efficient, no changes needed:** every endpoint in
  `backend/api.php` and `ajax/*.php` issues a small, fixed number of queries
  per request regardless of data volume -- most routes 1-3, `/location/update`
  up to 5 in its heaviest branch (Force Override check, previous-fix lookup,
  the position upsert, the Member Version Monitoring device-info update, and
  the history/snapshot insert) -- with no N+1 pattern (no query runs inside a
  per-row loop), no query lacks
  an index for its `WHERE`/`JOIN`/`ORDER BY` columns, and no query scans a
  table whose size scales with location history or activity volume. In
  particular `ajax/history.php`, `ajax/history_dates.php`, and
  `backend/api.php`'s `/location/history`, `/location/history/dates` all stay
  a sargable range scan against `idx_history_user_recorded (user_id,
  recorded_at)`, scoped to one Member's one day/month -- cost scales with
  that one Member's own point count, not the whole table, so this stays fast
  regardless of how large `tams_member_history_locations` grows overall (B-tree
  lookup depth grows logarithmically with total rows, not linearly).
  `/location/update`'s transaction touches at most two rows -- the acting
  Member's own primary-key row in `tams_live_tracking_current` (a table that
  only ever has one row per Member, never per location point) for the
  position upsert, and that same Member's own primary-key row in
  `tams_users` for the Member Version Monitoring device-info update -- plus
  one `INSERT IGNORE` into `tams_member_history_locations`. Every row
  touched is keyed by that same Member's own primary key on either table, so
  there is still no lock contention between different Members, and no lock
  whose scope grows with history size.
- **Confirmed harmless, deliberately not changed:** `tams_live_tracking_current`
  carries `idx_live_tracking_current_status` and `idx_live_tracking_current_updated`,
  neither of which any current query actually filters on in SQL (both
  columns are only ever `SELECT`ed and post-processed in PHP). Technically
  unused indexes, but removing them buys nothing -- this table permanently
  holds exactly one row per Member (never per location point), so index
  maintenance cost here is unmeasurable regardless. Left in place as
  free insurance against a future query that does filter on either column.
- **Fixed: Year 2038 ceiling.** Every `TIMESTAMP` column across all tables
  was converted to `DATETIME` (see "Database" above) -- a real, deterministic
  failure this project's own "used for years" lifetime could plausibly reach
  (2038 is about a decade out from when this audit was done), unlike a
  server-load concern that depends on Member count staying below some
  threshold. Zero behavior change, zero added code.
- **Fixed: `tams_member_history_locations.id` overflow ceiling.** Widened from
  `INT AUTO_INCREMENT` (~2.147 billion ceiling) to `BIGINT UNSIGNED
  AUTO_INCREMENT` (see "Database" above) -- this is the one table whose row
  count has no natural ceiling of its own (unlike Members, tokens, or
  config, which stay small for the system's whole lifetime). Applied now,
  while the table is still small, specifically because the same `ALTER
  TABLE ... MODIFY COLUMN` gets slower and riskier to run the longer it's
  deferred (InnoDB must rebuild the clustered index to widen an
  `AUTO_INCREMENT` primary key).
- **Watch list, not changed (no current bottleneck):** `ajax/member_log_list.php`'s
  free-text search (`user_name LIKE '%...%'` / `message LIKE '%...%'`) can
  never use an index -- a leading wildcard forces MySQL to scan every row
  that survives the other filters. Not fixed now because it's gated behind
  an Admin manually typing a search term on a page only a handful of Admins
  ever open, and `tams_member_log` grows far slower than location history
  (event-driven -- start/stop/profile-update/errors -- not one row per GPS
  fix), so it would take many years to reach a size where this is
  noticeably slow. If it ever is, the simplest fix is a `FULLTEXT` index on
  `message`/`user_name`, not a rewrite. Similarly, `member_log_list.php` and
  `members_list.php` both paginate with `LIMIT/OFFSET`, which gets linearly
  slower at very deep pages (MySQL must walk and discard `OFFSET` rows first)
  -- a non-issue at the current, human-paced admin usage pattern, and only
  worth revisiting with keyset/seek pagination if an Admin ever actually
  pages that deep.
- **Considered, deliberately not touched: database connections.** Both
  `config.php` (Admin Panel) and `backend/includes/config.php` (Backend API)
  open a fresh, non-persistent PDO connection per request. Persistent
  connections (`PDO::ATTR_PERSISTENT`) were considered and rejected: at this
  project's load (well under 1 request/second average, ~10/second peak, see
  "Server Load & Scalability" above), connection setup overhead is not
  measurable, while persistent connections on shared/cPanel hosting carry a
  real correctness risk this codebase's explicit transactions
  (`/location/update`'s `SELECT ... FOR UPDATE`) would be exposed to --
  leftover transaction/lock state leaking into a reused connection across
  unrelated requests. Trading a real correctness risk for an unmeasurable
  performance gain is the wrong trade at this scale.

## Troubleshooting

- **A CSS/JS change (or an uploaded APK/OTA Update save) isn't showing up after deploying** -- the host runs a static-asset cache (LiteSpeed Cache and/or Cloudflare) in front of this site that doesn't always respect cache-busting query strings. Purge the cache from the hosting control panel. `<link>`/`<script>` tags already append `?v=<file mtime>` automatically (`helpers/functions.php`'s `asset_version()`), which only handles *browser*-side caching -- it's the *edge*-side cache that occasionally needs a manual purge.
- **"Service temporarily unavailable" page on any Admin Panel screen** -- `config.php` couldn't connect to the database (wrong/missing `database/credentials.php` values, DB server down, or a firewall/socket issue). Check the server's PHP error log for the real `PDOException` message -- it's deliberately never shown to the browser, only logged, to avoid leaking schema/host/credential details.
- **Backend API request fails with `"Bearer token missing or invalid format"` or `"Invalid or revoked authentication token"`** -- the Android app must send `Authorization: Bearer <token>` exactly (case-insensitive header name, case-sensitive `Bearer` prefix); a token is invalidated by `/auth/logout` or by deactivating the account (`tams_users.is_active = 0`), and tokens never expire on their own -- see `backend/includes/middleware.php`.
- **`POST /location/update` (Android) returns HTTP 403 with `error_code: outside_operational_hours`** -- not a bug: Force Location's server-side gate rejected a tracking fix sent outside the 07:00-16:00 WIB window for a Member without an override. Grant one via the Force Location button in the Members page's Actions column -- see "Force Location (Force Override)" above. If this happens to a Member who was already tracking, expect the Android app to stop sending location automatically right after -- that's the intended auto-revoke behavior, not a client bug either.
- **Admin gets logged out unexpectedly while using the panel** -- sessions idle out after 30 minutes of inactivity (`SESSION_TIMEOUT_SECONDS` in `config.php`, see "Auth" above); this is by design, not a bug.
- **A Member's status flickers between online/offline around the same time on the Admin Panel and in the Android app** -- both must agree on `OFFLINE_STALE_SECONDS` (the safety-net threshold for treating a stale `updated_at` as offline). It's defined separately in `web/config.php` and `web/backend/api.php` and must be kept numerically equal by hand (see the comment on either constant) -- a mismatch after editing only one of them is the most likely cause.
