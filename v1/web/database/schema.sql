-- Database Schema for Aplikasi Pemantauan Member Real-Time
-- Target DB: MariaDB (Akses via phpMyAdmin / PDO mysql)
--
-- NOTE: This file is safe to re-run on a fresh database (uses CREATE TABLE IF
-- NOT EXISTS) AND on an already-deployed database (the ALTER TABLE migration
-- block below brings older installations up to date with the current schema
-- expected by backend/api.php). All ALTER statements use MariaDB's
-- "IF NOT EXISTS" / "MODIFY COLUMN" idioms so they can be executed multiple
-- times without error.

CREATE TABLE IF NOT EXISTS `tams_users` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `name` VARCHAR(100) NOT NULL,
  `note` VARCHAR(255) NOT NULL DEFAULT '',
  `username` VARCHAR(50) NOT NULL UNIQUE,
  `password` VARCHAR(255) NOT NULL,
  `role` ENUM('member', 'admin') NOT NULL,
  `is_active` TINYINT(1) DEFAULT 1,
  -- Brute-force lockout counters for both login.php (Admin Panel) and
  -- backend/api.php's /auth/login -- see the migration block below for why
  -- these exist (plain-text, 4-char-minimum passwords on public endpoints).
  `failed_login_attempts` INT NOT NULL DEFAULT 0,
  -- DATETIME, not TIMESTAMP: TIMESTAMP is internally a 4-byte UNIX time and
  -- cannot represent a moment past 2038-01-19 03:14:07 UTC -- a hard
  -- failure ceiling this project's own "used for years" lifetime could
  -- plausibly reach. DATETIME has no such limit (up to year 9999), stores
  -- the same literal wall-clock value with no session-time_zone-dependent
  -- conversion, and every column below carrying this comment changed for
  -- the same reason. No application code changes: PDO already returns both
  -- types as the same "YYYY-MM-DD HH:MM:SS" string, and nothing in this
  -- codebase uses a TIMESTAMP-specific SQL function (UNIX_TIMESTAMP(),
  -- CONVERT_TZ(), etc.) on any of these columns.
  `locked_until` DATETIME NULL DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX `idx_users_role_active` (`role`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tokens never expire -- a session stays valid until the user explicitly
-- logs out, or an admin deactivates the account (see
-- backend/includes/middleware.php). Deliberate project decision: field
-- members shouldn't be forced to re-login just because 30 days passed.
CREATE TABLE IF NOT EXISTS `tams_auth_tokens` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `user_id` INT NOT NULL,
  `token_hash` CHAR(64) NOT NULL UNIQUE,
  `device_info` VARCHAR(255) DEFAULT NULL,
  -- DATETIME, not TIMESTAMP -- see tams_users.created_at's comment above for why.
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (`user_id`) REFERENCES `tams_users` (`id`) ON DELETE CASCADE,
  INDEX `idx_auth_tokens_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- `tams_live_tracking_current` holds exactly one "live" row per user.
--   - `status`    is the authoritative online/offline flag. It is set
--                 explicitly by the backend (never inferred from NULL
--                 coordinates) so that a member sitting exactly on the
--                 equator/meridian (lat/lng == 0) is never misreported as
--                 offline, and so that the admin panel can flip to OFFLINE
--                 the instant a member presses Stop (or the device disables
--                 location), rather than waiting on a stale-timestamp
--                 fallback.
--   - `is_moving` is a server-computed flag (distance + elapsed-time +
--                 accuracy validated) indicating actual physical movement
--                 between the previous and current fix, as opposed to
--                 merely "currently sending updates".
--   - `recorded_at` is the fix's own device-side timestamp (same value
--                 stored per-point in tams_member_history_locations), NOT the
--                 server's arrival time (`updated_at`). backend/api.php's
--                 /location/update only lets a request overwrite this row
--                 when its `recorded_at` is not older than what is already
--                 stored, so a request that is merely slow in transit (and
--                 completes after a later fix already landed) can never
--                 regress the live position back to a stale point -- see
--                 that route's "$applyToCurrent" guard.
-- Coordinates/accuracy/speed/updated_at/recorded_at are nullable because
-- they are cleared out when a member goes offline.
CREATE TABLE IF NOT EXISTS `tams_live_tracking_current` (
  `user_id` INT PRIMARY KEY,
  `latitude` DECIMAL(10,7) NULL DEFAULT NULL,
  `longitude` DECIMAL(10,7) NULL DEFAULT NULL,
  `accuracy` FLOAT NULL DEFAULT NULL,
  `speed` FLOAT NULL DEFAULT NULL,
  `is_moving` TINYINT(1) NOT NULL DEFAULT 0,
  `status` ENUM('online', 'offline') NOT NULL DEFAULT 'offline',
  -- DATETIME, not TIMESTAMP -- see tams_users.created_at's comment above for why.
  `updated_at` DATETIME NULL DEFAULT NULL,
  `recorded_at` DATETIME NULL DEFAULT NULL,
  FOREIGN KEY (`user_id`) REFERENCES `tams_users` (`id`) ON DELETE CASCADE,
  INDEX `idx_live_tracking_current_updated` (`updated_at`),
  INDEX `idx_live_tracking_current_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- UNIQUE(user_id, recorded_at) is a deliberate defense-in-depth guard
-- against duplicate rows -- from a client retry, from a queued
-- offline-sync point being flushed twice by a race (see
-- MemberRepository.syncOfflineLocations' Mutex, which is the primary
-- guard), or any other future code path that might resend the exact same
-- fix. backend/api.php inserts into this table with `INSERT IGNORE`
-- specifically so a genuine duplicate is silently dropped rather than
-- surfacing as a hard 500 error to the app. Two DIFFERENT real fixes
-- sharing the same user_id + recorded_at (second-resolution) would also
-- collide under this constraint, but given the app's normal >=10s fix
-- interval that is not a realistic scenario, and correctness (never
-- double-counting a point) matters far more here than that theoretical
-- edge case.
CREATE TABLE IF NOT EXISTS `tams_member_history_locations` (
  -- BIGINT UNSIGNED, not plain INT: this is the one table expected to keep
  -- growing every single day for as long as the project runs (see the
  -- "Long-Term Scalability" notes near the bottom of this file). Plain INT
  -- AUTO_INCREMENT tops out at ~2.147 billion -- comfortably far off today,
  -- but a real ceiling a "used for years, nothing ever deleted" system
  -- could eventually reach, unlike every other table here (Members, tokens,
  -- config, app version) whose row counts stay small regardless of how long
  -- the system runs. BIGINT UNSIGNED's ~1.8x10^19 ceiling removes that
  -- concern permanently at a one-time cost of 4 extra bytes/row.
  `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  `user_id` INT NOT NULL,
  `latitude` DECIMAL(10,7) NOT NULL,
  `longitude` DECIMAL(10,7) NOT NULL,
  `accuracy` FLOAT NOT NULL,
  `speed` FLOAT NOT NULL,
  `is_moving` TINYINT(1) NOT NULL DEFAULT 0,
  -- DATETIME, not TIMESTAMP -- see tams_users.created_at's comment above for why.
  `recorded_at` DATETIME NOT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (`user_id`) REFERENCES `tams_users` (`id`) ON DELETE CASCADE,
  UNIQUE INDEX `idx_history_user_recorded` (`user_id`, `recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Every row here is a genuine continuous-tracking fix -- this table holds
-- nothing else. Trip History's Total Distance/Duration/Start/End Time/
-- Polyline/Movement Detection/point count (backend/api.php's
-- /location/history, ajax/history.php) all read this table with a single
-- unfiltered `WHERE user_id = ? AND recorded_at BETWEEN ? AND ?` and no
-- other condition -- keep it that way. Any future Heatmap/Analytics/Report
-- feature can do the same: query this table directly with zero filtering.

-- Renames this table from its old tams_app_version name on an
-- already-deployed database, BEFORE the CREATE TABLE IF NOT EXISTS below
-- runs -- ordering matters here, unlike every other rename in this file's
-- later "Migration block": if this ran down there instead, the CREATE TABLE
-- below would already have created an empty tams_ota_update table by that
-- point (fresh-install path), which would then make RENAME TABLE fail
-- outright on an already-deployed database too (its destination name would
-- already exist). Native `RENAME TABLE ... IF EXISTS` isn't supported by
-- MySQL/MariaDB, so this checks INFORMATION_SCHEMA itself and only builds
-- the RENAME statement when the old table is actually still there --
-- a no-op (SELECT 1) on both a fresh install and a database that already
-- ran this once.
SET @tams_app_version_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tams_app_version'
);
SET @tams_ota_update_rename_sql = IF(
  @tams_app_version_exists > 0,
  'RENAME TABLE `tams_app_version` TO `tams_ota_update`',
  'SELECT 1'
);
PREPARE tams_ota_update_rename_stmt FROM @tams_ota_update_rename_sql;
EXECUTE tams_ota_update_rename_stmt;
DEALLOCATE PREPARE tams_ota_update_rename_stmt;

-- Single-row "settings" table (id is always 1) backing the OTA update
-- feature: the Admin Panel's "OTA Update" page is the only place this
-- is ever written, and backend/api.php's /app/version route is the only
-- place it's ever read -- one row, one writer surface, one reader surface.
-- release_notes is stored as plain newline-separated text (one note per
-- line) rather than JSON, so the Admin Panel form can just be a plain
-- <textarea> with no client-side JSON handling; /app/version splits it into
-- a JSON array when serving it to the Android app.
CREATE TABLE IF NOT EXISTS `tams_ota_update` (
  `id` TINYINT UNSIGNED NOT NULL DEFAULT 1 PRIMARY KEY,
  `version_code` INT NOT NULL,
  `version_name` VARCHAR(20) NOT NULL,
  -- Member Version Monitoring's floor: any device reporting an
  -- app_version_code (tams_users) below this is "unsupported"
  -- (red) rather than merely "outdated" (yellow) -- see
  -- pages/members.php's version-status computation. Defaults to 1 so a
  -- fresh install never marks every member unsupported before an Admin
  -- explicitly raises it.
  `min_supported_version_code` INT NOT NULL DEFAULT 1,
  -- Force Update is no longer a manually-set flag (removed column, see the
  -- migration below) -- backend/api.php's /app/version route now derives it
  -- at read time: min_supported_version_code >= version_code means every
  -- installed copy is at or below the floor, so the update is mandatory.
  -- apk_url is populated automatically by ajax/ota_update_save.php's APK
  -- upload handling (download/TAMS.apk), not typed in by hand anymore.
  `apk_url` VARCHAR(500) NOT NULL DEFAULT '',
  `release_notes` TEXT NOT NULL,
  -- DATETIME, not TIMESTAMP -- see tams_users.created_at's comment above for why.
  `updated_at` DATETIME NULL DEFAULT NULL,
  CONSTRAINT `chk_ota_update_single_row` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Remote Management: generic key/value store for Android app behavior that
-- an Administrator can tune without a new APK build (GPS update interval,
-- sync interval, and any future safe-to-externalize setting). Deliberately
-- key/value rather than one column per setting -- adding a new tunable
-- later is a single INSERT, not a schema migration, and
-- backend/api.php's /app/config route can serve the whole table with one
-- unfiltered SELECT * regardless of how many keys exist. Never put secrets
-- or security-sensitive values here -- this table is served to any
-- installed copy of the app with no authentication (see /app/config).
CREATE TABLE IF NOT EXISTS `tams_remote_management` (
  `config_key` VARCHAR(64) NOT NULL PRIMARY KEY,
  `config_value` VARCHAR(255) NOT NULL,
  -- DATETIME, not TIMESTAMP -- see tams_users.created_at's comment above for why.
  `updated_at` DATETIME NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Log: audit trail of Member activity (profile changes, tracking
-- start/stop, sync failures, other notable errors). `user_name` is
-- deliberately denormalized (copied at write time, not joined from
-- tams_users) so a log entry still reads correctly after the account is
-- later renamed or deleted -- an audit trail that goes blank the moment
-- its subject is removed defeats its own purpose. `user_id` keeps
-- ON DELETE SET NULL (not CASCADE) for the same reason: deleting a member
-- must never delete their history of past activity.
CREATE TABLE IF NOT EXISTS `tams_member_log` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `user_id` INT NULL DEFAULT NULL,
  `user_name` VARCHAR(100) NOT NULL,
  `action_type` VARCHAR(40) NOT NULL,
  `status` ENUM('success', 'failed') NOT NULL DEFAULT 'success',
  `field_before` VARCHAR(500) NULL DEFAULT NULL,
  `field_after` VARCHAR(500) NULL DEFAULT NULL,
  `message` VARCHAR(500) NULL DEFAULT NULL,
  -- DATETIME, not TIMESTAMP -- see tams_users.created_at's comment above for why.
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (`user_id`) REFERENCES `tams_users` (`id`) ON DELETE SET NULL,
  INDEX `idx_member_log_created` (`created_at`),
  INDEX `idx_member_log_user` (`user_id`),
  INDEX `idx_member_log_action` (`action_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Migration block: brings a database created from an older version of
-- this schema up to date. Safe to run repeatedly.
-- ---------------------------------------------------------------------

-- Request One-Time Location was removed as a feature entirely -- drops its
-- dedicated snapshot table from any already-deployed database. No-op on a
-- fresh install, since the CREATE TABLE for it no longer exists above. Any
-- rows that existed were, by design, never read by anything (see this
-- table's own former comment in this file's history) -- a point-in-time
-- audit trail with no feature left to serve, safe to drop outright rather
-- than migrate.
DROP TABLE IF EXISTS `tams_members_location_snapshots`;

ALTER TABLE `tams_live_tracking_current`
  MODIFY COLUMN `latitude` DECIMAL(10,7) NULL DEFAULT NULL,
  MODIFY COLUMN `longitude` DECIMAL(10,7) NULL DEFAULT NULL,
  MODIFY COLUMN `accuracy` FLOAT NULL DEFAULT NULL,
  MODIFY COLUMN `speed` FLOAT NULL DEFAULT NULL,
  MODIFY COLUMN `updated_at` TIMESTAMP NULL DEFAULT NULL;

ALTER TABLE `tams_live_tracking_current`
  ADD COLUMN IF NOT EXISTS `is_moving` TINYINT(1) NOT NULL DEFAULT 0 AFTER `speed`,
  ADD COLUMN IF NOT EXISTS `status` ENUM('online', 'offline') NOT NULL DEFAULT 'offline' AFTER `is_moving`;

ALTER TABLE `tams_live_tracking_current`
  ADD INDEX IF NOT EXISTS `idx_live_tracking_current_status` (`status`);

ALTER TABLE `tams_member_history_locations`
  ADD COLUMN IF NOT EXISTS `is_moving` TINYINT(1) NOT NULL DEFAULT 0 AFTER `speed`;

ALTER TABLE `tams_auth_tokens`
  ADD INDEX IF NOT EXISTS `idx_auth_tokens_user` (`user_id`);

-- Tokens no longer expire (see backend/includes/middleware.php) -- drops
-- the now-unused expiry column/index from any already-deployed database.
-- No-op on a fresh install, since the CREATE TABLE above never defines
-- them there.
ALTER TABLE `tams_auth_tokens`
  DROP INDEX IF EXISTS `idx_auth_tokens_expires`;

ALTER TABLE `tams_auth_tokens`
  DROP COLUMN IF EXISTS `expires_at`;

-- Speeds up /location/current, /member/list, and /location/update's
-- WHERE role = 'member' AND is_active = 1 filtering as the roster grows.
ALTER TABLE `tams_users`
  ADD INDEX IF NOT EXISTS `idx_users_role_active` (`role`, `is_active`);

-- Renames the old `phone` column (a mandatory VARCHAR(20) phone number) to
-- `note` -- a free-text field the Member edits about themselves and the
-- Admin can read, no longer specifically a phone number. Safe/idempotent:
-- on a fresh database `phone` never existed (the CREATE TABLE above already
-- defines `note` directly), so "IF EXISTS" makes this a no-op there; on an
-- already-deployed database it renames the column (and any existing phone
-- numbers become the initial note text) exactly once.
ALTER TABLE `tams_users`
  CHANGE COLUMN IF EXISTS `phone` `note` VARCHAR(255) NOT NULL DEFAULT '';

-- Brute-force lockout for login.php and backend/api.php's /auth/login: both
-- reject a plain-text password comparison after too many wrong guesses in a
-- row, without needing a separate attempts table. No-op on a fresh install
-- (the CREATE TABLE above already defines both columns there).
ALTER TABLE `tams_users`
  ADD COLUMN IF NOT EXISTS `failed_login_attempts` INT NOT NULL DEFAULT 0 AFTER `is_active`,
  ADD COLUMN IF NOT EXISTS `locked_until` TIMESTAMP NULL DEFAULT NULL AFTER `failed_login_attempts`;

-- Adds the device-side fix timestamp to the "current position" row (see the
-- CREATE TABLE comment above for why this matters: without it, an
-- out-of-order network response could regress the live map back to a stale
-- point). NULL by default so existing rows simply have no ordering
-- information until their next real update, which is harmless -- the
-- ordering guard in backend/api.php only activates once a previous
-- recorded_at value actually exists.
ALTER TABLE `tams_live_tracking_current`
  ADD COLUMN IF NOT EXISTS `recorded_at` TIMESTAMP NULL DEFAULT NULL AFTER `updated_at`;

-- De-duplicate any (user_id, recorded_at) pairs that may already exist from
-- before this constraint was introduced (e.g. from the sync race this
-- migration's UNIQUE index is meant to prevent going forward), keeping only
-- the earliest-inserted row of each duplicate group. Required before adding
-- the UNIQUE index below -- MariaDB refuses to create a UNIQUE index over
-- data that already violates it. Safe to re-run: once no duplicates remain,
-- this DELETE matches zero rows.
DELETE h1 FROM `tams_member_history_locations` h1
INNER JOIN `tams_member_history_locations` h2
  ON h1.`user_id` = h2.`user_id`
 AND h1.`recorded_at` = h2.`recorded_at`
 AND h1.`id` > h2.`id`;

-- Replace the old non-unique composite index with a UNIQUE one covering the
-- exact same columns (so /location/history's existing
-- `WHERE user_id = ? AND DATE(recorded_at) = ? ORDER BY recorded_at ASC`
-- query keeps the same index support) while also enforcing the
-- no-duplicate-points guarantee described above.
ALTER TABLE `tams_member_history_locations`
  DROP INDEX IF EXISTS `idx_composite_user_recorded`;

ALTER TABLE `tams_member_history_locations`
  ADD UNIQUE INDEX IF NOT EXISTS `idx_history_user_recorded` (`user_id`, `recorded_at`);

-- Backfill `status` for rows written by the previous NULL-inference logic,
-- so existing "currently online" members are not shown as offline right
-- after the migration runs.
UPDATE `tams_live_tracking_current`
SET `status` = 'online'
WHERE `status` = 'offline' AND `latitude` IS NOT NULL AND `updated_at` IS NOT NULL
  AND `updated_at` >= (NOW() - INTERVAL 5 MINUTE);

-- Undo the earlier bcrypt migration for any database that already ran it:
-- restores the seed accounts below to plain-text 'CHANGE_ME', matching
-- backend/api.php's plain-text comparison (hash_equals() there is only a
-- constant-time string compare, not a hash).
UPDATE `tams_users`
SET `password` = 'CHANGE_ME'
WHERE `username` IN ('member1', 'member2', 'admin1')
  AND `password` LIKE '$2%$%';

-- Member Version Monitoring: no-op on a fresh install (the CREATE TABLE
-- above already defines this column there); brings an already-deployed
-- tams_ota_update row up to date otherwise. References the new table name
-- because the rename guard above this file's tams_ota_update CREATE TABLE
-- has already renamed it by the time this runs, on every path.
ALTER TABLE `tams_ota_update`
  ADD COLUMN IF NOT EXISTS `min_supported_version_code` INT NOT NULL DEFAULT 1 AFTER `version_code`;

-- Force Update is derived (see CREATE TABLE's comment above), not stored,
-- as of this migration -- drops the now-unused column from an
-- already-deployed database. No-op on a fresh install (the CREATE TABLE
-- above never creates it in the first place).
ALTER TABLE `tams_ota_update`
  DROP COLUMN IF EXISTS `force_update`;

-- Member Version Monitoring's device-reported columns used to be
-- piggybacked onto this table (added here, then superseded -- see the
-- "Member/Device Identity" migration block further down this file, which
-- both moves them onto `tams_users` and drops them from here). Left as a
-- no-op marker, not deleted outright, so the history of where these columns
-- physically lived is not lost from this file's own migration trail.

-- Force Override (pages/members.php's Actions column): a per-member
-- override an Admin sets directly on tams_users, read/written by both the
-- Admin Panel (web/ajax/members_force_toggle.php -- direct DB access, see
-- root CLAUDE.md on why the Admin Panel never calls the Backend API) and
-- backend/api.php (GET /location/status, POST /location/update). Living on
-- tams_users rather than a new table keeps both a straightforward "one row
-- per member" shape and lets every route that already loads a user by id
-- read it with zero extra joins.
--
--   force_tracking_hours   -- 0 (default) means this Member may only run
--                           Start Location within backend/api.php's
--                           TRACKING_HOUR_START..TRACKING_HOUR_END window
--                           (server/WIB time, never the device's own
--                           clock); 1 lets them track any time. Enforced
--                           unconditionally in POST /location/update, on
--                           EVERY fix for as long as tracking runs -- never
--                           trust the Android app's own pre-flight GET
--                           /location/status check alone, since a modified
--                           client could otherwise skip it entirely. This is
--                           also what makes an Admin's ON->OFF toggle
--                           self-enforcing: the very next fix after the
--                           flip is checked against this column's fresh
--                           value, and the Android app auto-stops itself if
--                           that fix is rejected -- see
--                           MemberLocationService.handleNewLocation's doc
--                           comment on the Android side of this contract.
ALTER TABLE `tams_users`
  ADD COLUMN IF NOT EXISTS `force_tracking_hours` TINYINT(1) NOT NULL DEFAULT 0 AFTER `locked_until`;

-- Request One-Time Location was removed as a feature entirely -- drops its
-- two supporting columns from any already-deployed database. No-op on a
-- fresh install, since the ADD COLUMN above never creates them there.
ALTER TABLE `tams_users`
  DROP COLUMN IF EXISTS `location_request_pending`,
  DROP COLUMN IF EXISTS `location_requested_at`;

-- Member/Device Identity: app_version_name/app_version_code/android_version/
-- device_model move here from `tams_live_tracking_current` (DDD column-
-- ownership audit, see CLAUDE.md). These four columns describe the
-- MEMBER's account/installed app/device, not a live-tracking session -- they
-- are written on every /auth/login call (before any GPS fix exists for that
-- session) and read exclusively by ajax/members_list.php's Member Version
-- Monitoring feature; Live Tracking (pages/live_tracking.php,
-- /location/current) never reads them. Keeping them on
-- `tams_live_tracking_current` meant this 1:1 member/device data structurally
-- depended on a row that only exists once a member has been tracked at
-- least once, forcing ajax/members_list.php into an unnecessary LEFT JOIN.
-- NULL by default: a member who has never logged in since this feature
-- existed simply has no version info yet, same NULL-until-known convention
-- this project already uses for coordinates.
--
-- This migration is three statements that MUST stay in this order:
--   1. ADD the four columns to tams_users (this ALTER).
--   2. Backfill any values already sitting in `tams_live_tracking_current`
--      into the new columns. Guarded by an INFORMATION_SCHEMA check (the
--      same dynamic-SQL/PREPARE idiom this file already uses for the
--      tams_app_version -> tams_ota_update rename guard near the top) so
--      the backfill becomes a harmless no-op -- instead of an "unknown
--      column" fatal error -- on any database where step 3 has already run
--      (a fresh install, or an already-migrated database re-running this
--      file, both of which never have these columns on
--      `tams_live_tracking_current` to begin with).
--   3. DROP the four columns from `tams_live_tracking_current` (further
--      below, once every write/read path in backend/api.php and
--      ajax/members_list.php has moved to `tams_users`).
ALTER TABLE `tams_users`
  ADD COLUMN IF NOT EXISTS `app_version_name` VARCHAR(20) NULL DEFAULT NULL AFTER `force_tracking_hours`,
  ADD COLUMN IF NOT EXISTS `app_version_code` INT NULL DEFAULT NULL AFTER `app_version_name`,
  ADD COLUMN IF NOT EXISTS `android_version` VARCHAR(50) NULL DEFAULT NULL AFTER `app_version_code`,
  ADD COLUMN IF NOT EXISTS `device_model` VARCHAR(100) NULL DEFAULT NULL AFTER `android_version`;

SET @tams_live_tracking_current_has_app_version_name = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tams_live_tracking_current'
    AND COLUMN_NAME = 'app_version_name'
);
SET @tams_users_device_backfill_sql = IF(
  @tams_live_tracking_current_has_app_version_name > 0,
  'UPDATE `tams_users` u INNER JOIN `tams_live_tracking_current` lc ON lc.`user_id` = u.`id`
     SET u.`app_version_name` = COALESCE(u.`app_version_name`, lc.`app_version_name`),
         u.`app_version_code` = COALESCE(u.`app_version_code`, lc.`app_version_code`),
         u.`android_version` = COALESCE(u.`android_version`, lc.`android_version`),
         u.`device_model` = COALESCE(u.`device_model`, lc.`device_model`)
   WHERE lc.`app_version_name` IS NOT NULL OR lc.`app_version_code` IS NOT NULL
      OR lc.`android_version` IS NOT NULL OR lc.`device_model` IS NOT NULL',
  'SELECT 1'
);
PREPARE tams_users_device_backfill_stmt FROM @tams_users_device_backfill_sql;
EXECUTE tams_users_device_backfill_stmt;
DEALLOCATE PREPARE tams_users_device_backfill_stmt;

-- Step 3 (see the numbered comment above): drop the now-migrated columns
-- from `tams_live_tracking_current`, leaving it a Live Tracking-only table
-- (latitude/longitude/accuracy/speed/is_moving/status/updated_at/
-- recorded_at -- nothing else). IF EXISTS makes this a no-op both on a
-- fresh install (these columns are never created there anymore) and on a
-- database that has already run this block once.
ALTER TABLE `tams_live_tracking_current`
  DROP COLUMN IF EXISTS `app_version_name`,
  DROP COLUMN IF EXISTS `app_version_code`,
  DROP COLUMN IF EXISTS `android_version`,
  DROP COLUMN IF EXISTS `device_model`;

-- Naming consistency: renames every index still carrying the OLD table
-- name it was created under, left behind when `tams_locations_current` and
-- `tams_activity_log` were renamed to `tams_live_tracking_current` and
-- `tams_member_log` (see CLAUDE.md's Feature Ownership Convention). Index
-- names are pure internal metadata -- verified nowhere in this codebase
-- does any query use a `FORCE INDEX`/`USE INDEX` hint naming any of these
-- -- so this is a zero-behavior-change rename. DROP + ADD (not `RENAME
-- INDEX`, for consistency with every other conditional DDL statement in
-- this file, all of which use the `IF EXISTS`/`IF NOT EXISTS` idiom rather
-- than mixing in a different one) keeps this idempotent: on a fresh
-- install the CREATE TABLE above (and, for `idx_live_tracking_current_status`,
-- the ADD INDEX just above this comment) already created the new names
-- directly, so every DROP below is a no-op (the old name never existed)
-- and every ADD is a no-op (the new name already exists); on an
-- already-deployed database this drops each old-named index and adds its
-- new-named replacement exactly once, safe to re-run indefinitely after.
ALTER TABLE `tams_live_tracking_current`
  DROP INDEX IF EXISTS `idx_locations_current_status`,
  DROP INDEX IF EXISTS `idx_locations_current_updated`;

ALTER TABLE `tams_live_tracking_current`
  ADD INDEX IF NOT EXISTS `idx_live_tracking_current_status` (`status`),
  ADD INDEX IF NOT EXISTS `idx_live_tracking_current_updated` (`updated_at`);

ALTER TABLE `tams_member_log`
  DROP INDEX IF EXISTS `idx_activity_log_created`,
  DROP INDEX IF EXISTS `idx_activity_log_user`,
  DROP INDEX IF EXISTS `idx_activity_log_action`;

ALTER TABLE `tams_member_log`
  ADD INDEX IF NOT EXISTS `idx_member_log_created` (`created_at`),
  ADD INDEX IF NOT EXISTS `idx_member_log_user` (`user_id`),
  ADD INDEX IF NOT EXISTS `idx_member_log_action` (`action_type`);

-- Long-Term Scalability: brings every already-deployed database's TIMESTAMP
-- columns up to DATETIME (see tams_users.created_at's CREATE TABLE comment
-- for the full rationale -- the Year 2038 ceiling, not a performance
-- concern). No-op on a fresh install, since every CREATE TABLE above already
-- declares these as DATETIME directly. MODIFY COLUMN is safe to re-run
-- (idempotent in effect, matching this file's existing MODIFY COLUMN
-- migrations above) and changes no stored value -- MySQL/MariaDB converts
-- through the connection's own time_zone on both sides of the ALTER, so the
-- same displayed wall-clock string comes out the other end.
ALTER TABLE `tams_users`
  MODIFY COLUMN `locked_until` DATETIME NULL DEFAULT NULL,
  MODIFY COLUMN `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  MODIFY COLUMN `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

ALTER TABLE `tams_auth_tokens`
  MODIFY COLUMN `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE `tams_live_tracking_current`
  MODIFY COLUMN `updated_at` DATETIME NULL DEFAULT NULL,
  MODIFY COLUMN `recorded_at` DATETIME NULL DEFAULT NULL;

ALTER TABLE `tams_ota_update`
  MODIFY COLUMN `updated_at` DATETIME NULL DEFAULT NULL;

ALTER TABLE `tams_remote_management`
  MODIFY COLUMN `updated_at` DATETIME NULL DEFAULT NULL;

ALTER TABLE `tams_member_log`
  MODIFY COLUMN `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP;

-- tams_member_history_locations gets its own block, separate from the DATETIME
-- migration above, since it also widens `id` (see that table's CREATE
-- TABLE comment) -- a heavier ALTER on a large, already-deployed table
-- (InnoDB must rebuild the clustered index when widening an AUTO_INCREMENT
-- PRIMARY KEY, unlike a plain nullable-column MODIFY, which is a much
-- lighter metadata-only or instant change on modern MySQL/MariaDB). Exactly
-- why this project applies it now, while the table is still small, rather
-- than deferring it -- the same ALTER only gets slower and riskier to run
-- the longer it's put off. On a deployment where this table has already
-- grown very large, run this specific statement during a low-traffic
-- window; every other ALTER in this migration block is unaffected either way.
ALTER TABLE `tams_member_history_locations`
  MODIFY COLUMN `id` BIGINT UNSIGNED AUTO_INCREMENT,
  MODIFY COLUMN `recorded_at` DATETIME NOT NULL,
  MODIFY COLUMN `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP;

-- ---------------------------------------------------------------------
-- Seed Sample Users
-- Passwords are stored and compared as plain text, per project requirement.
-- ---------------------------------------------------------------------
INSERT INTO `tams_users` (`name`, `note`, `username`, `password`, `role`, `is_active`) VALUES
('Member One', '', 'member1', 'CHANGE_ME', 'member', 1),
('Member Two', '', 'member2', 'CHANGE_ME', 'member', 1),
('Admin', '', 'admin1', 'CHANGE_ME', 'admin', 1)
ON DUPLICATE KEY UPDATE `is_active` = 1;

-- Guarantees the single settings row (id=1) always exists so
-- backend/api.php's /app/version route never 404s on a fresh install or an
-- already-deployed database that predates this table -- INSERT IGNORE is a
-- no-op if it's already there (e.g. an Admin already configured it through
-- the OTA Update page), never overwriting real configuration.
-- version_code/version_name below match this schema's shipped Android
-- build at the time this migration was written; update them through the
-- Admin Panel afterwards, not by re-running this file.
INSERT IGNORE INTO `tams_ota_update`
  (`id`, `version_code`, `version_name`, `apk_url`, `release_notes`)
VALUES
  (1, 1, '1.0', '', '');

-- Remote Management defaults: match the values MemberLocationService.kt and
-- LocationSyncWorker.kt fall back to when the server is unreachable (see
-- their RemoteConfigRepository.DEFAULT_* constants), so a fresh install's
-- actual behavior is identical whether or not this row has been fetched
-- yet. INSERT IGNORE is a no-op if an Admin already changed these through
-- the Remote Management page.
--
-- password_min_length/password_max_length replace what used to be the
-- hardcoded PASSWORD_MIN_LENGTH/PASSWORD_MAX_LENGTH constants in the now-
-- removed web/database/validation_rules.php -- this table is the single
-- source of truth for both PHP apps (helpers/functions.php's
-- remote_management_definitions() and backend/api.php's own copy) and the
-- Android app now, instead of a shared PHP file plus a manually-mirrored
-- Kotlin constant. password_max_length must never exceed 255: it's bounded
-- by tams_users.password's VARCHAR(255) column width (see that table above).
INSERT IGNORE INTO `tams_remote_management` (`config_key`, `config_value`, `updated_at`) VALUES
  ('gps_interval_seconds', '10', NULL),
  ('sync_interval_minutes', '3', NULL),
  ('password_min_length', '4', NULL),
  ('password_max_length', '255', NULL);
