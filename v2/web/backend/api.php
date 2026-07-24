<?php
// Main REST API Router for the Real-Time Member Monitoring Application
// Target: PHP 8+, cPanel environment, PDO prepared statements

require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/includes/middleware.php';

// --- Tunable constants -------------------------------------------------

// Safety-net fallback: if a member's row wasn't explicitly marked offline
// (app killed before it could notify the server) but hasn't received a
// fresh coordinate in this many seconds, treat it as offline. The primary
// path is the explicit status flag set on Stop / location unavailable.
define('OFFLINE_STALE_SECONDS', 90);

// Minimum displacement (meters) between two fixes before it counts as real
// movement rather than GPS noise.
define('MOVEMENT_DISTANCE_FLOOR_M', 15.0);

// Displacement must also imply at least this speed (m/s) to count as
// movement -- filters out a stale/low-frequency update looking like a jump.
define('MOVEMENT_MIN_SPEED_MPS', 0.5);

// Brute-force protection for /auth/login: passwords are plain text with only
// a short minimum length (configurable via Remote Management, 4 characters
// by default -- see CLAUDE.md) and this endpoint is reachable from
// the public internet, so a wrong-password streak locks the account out
// instead of allowing unlimited guesses. Mirrored in web/login.php for the
// Admin Panel's own login form, since both authenticate against the same
// `tams_users` row.
define('MAX_FAILED_LOGIN_ATTEMPTS', 5);
define('LOGIN_LOCKOUT_MINUTES', 15);

// --- GPS sanity-filtering bounds -----------------------------------------
// Deliberately generous: catches a fix that's essentially impossible for
// ANY real mode of travel (sensor glitch, cold-start fix, multipath jump),
// never a genuinely fast-but-real fix (e.g. a member on a highway).

// ~198 km/h -- faster than this between two consecutive fixes is treated
// as a GPS jump rather than real movement (reuses the same haversine/
// elapsed-time values already computed for movement detection).
define('GPS_JUMP_MAX_SPEED_MPS', 55.0);

// A fix worse than this is a broken/cold-start reading, not just
// "imprecise" -- 1km of uncertainty isn't usable at any zoom this app renders.
define('GPS_MAX_USABLE_ACCURACY_M', 1000.0);

// --- Password length fallbacks -------------------------------------------
// Used only when tams_remote_management has no row (or an out-of-range one) for
// 'password_min_length'/'password_max_length' -- see getRemoteManagementInt()
// below. The Admin Panel's Remote Management page is the actual source of
// truth (tams_remote_management, via helpers/functions.php's
// remote_management_definitions() on that independent app); these bounds must
// stay numerically equal to that definitions array, since this app doesn't
// share PHP files with the Admin Panel (see root CLAUDE.md). Formerly a
// shared web/database/validation_rules.php file plus a manually-mirrored
// Android constant -- both replaced by tams_remote_management as the true
// single source of truth.
define('PASSWORD_MIN_LENGTH_DEFAULT', 4);
define('PASSWORD_MIN_LENGTH_LOWER_BOUND', 4);
define('PASSWORD_MIN_LENGTH_UPPER_BOUND', 50);
define('PASSWORD_MAX_LENGTH_DEFAULT', 255);
define('PASSWORD_MAX_LENGTH_LOWER_BOUND', 8);
// tams_users.password is VARCHAR(255) -- this upper bound must never rise
// above 255 regardless of what's stored in tams_remote_management.
define('PASSWORD_MAX_LENGTH_UPPER_BOUND', 255);

// --- Force Override (Operational Hours) -----------------------------------
// A Member may only run Start Location within this window unless their own
// tams_users.force_tracking_hours override is enabled by an Admin (Web
// Admin -> Members -> Actions -> Force). Deliberately hardcoded, not a
// Remote Management key (tams_remote_management): unlike GPS/Sync interval,
// this is a security boundary enforced against a potentially-hostile
// client, not a cosmetic/behavioral tuning value, and Remote Management's
// /app/config route is explicitly unauthenticated (see that route's own
// doc comment) -- putting it there would let anyone read (and, if that
// route is ever made writable, tamper with) the operational-hours policy.
// Always compared against date('H:i:s') (this server's own clock, WIB/GMT+7
// per deployment) -- NEVER a client-supplied timestamp -- so a spoofed
// device clock can never widen this window; see isWithinOperationalHours().
define('TRACKING_HOUR_START', '07:00:00');
define('TRACKING_HOUR_END', '16:00:00');

/**
 * True if the server's current time-of-day (WIB) falls within the
 * Force Override window, inclusive of both bounds. Never takes a
 * client-supplied time -- see TRACKING_HOUR_START's doc comment for why
 * that would defeat the whole point of this check.
 */
function isWithinOperationalHours(): bool {
    $now = date('H:i:s');
    return $now >= TRACKING_HOUR_START && $now <= TRACKING_HOUR_END;
}

// --- Outlet Management (geofencing) ---------------------------------------
// Fallback values used only when tams_remote_management has no row (or an
// out-of-range one) for 'outlet_radius_meters'/'outlet_min_dwell_seconds' --
// see getRemoteManagementInt() below. Must stay numerically equal to
// helpers/functions.php's remote_management_definitions() 'default'/'min'/
// 'max' for the same two keys, and to schema.sql's seed INSERT -- same
// three-way-agreement requirement as PASSWORD_MIN_LENGTH_DEFAULT above.
// 'outlet_min_visits_per_day' has no equivalent here: it never gates
// anything a fix could be rejected for, it is purely a reporting threshold
// read by the Web Admin's Visit Report (ajax/outlet_visit_report.php).
define('OUTLET_RADIUS_METERS_DEFAULT', 100);
define('OUTLET_RADIUS_METERS_LOWER_BOUND', 10);
define('OUTLET_RADIUS_METERS_UPPER_BOUND', 1000);
define('OUTLET_MIN_DWELL_SECONDS_DEFAULT', 0);
define('OUTLET_MIN_DWELL_SECONDS_LOWER_BOUND', 0);
define('OUTLET_MIN_DWELL_SECONDS_UPPER_BOUND', 3600);

// VARCHAR widths mirrored from schema.sql's tams_outlets -- rejected up
// front with a clear 400 rather than reaching a truncated/failed INSERT.
define('OUTLET_NAME_MAX_LENGTH', 150);
define('OUTLET_ADDRESS_MAX_LENGTH', 255);

// Generous upper bound on /outlet/reorder's outlet_ids payload -- far above
// any real Member's outlet count, just to reject a pathologically large
// array outright rather than locking/updating an unbounded number of rows
// per request.
define('OUTLET_REORDER_MAX_IDS', 1000);

$route = isset($_GET['route']) ? $_GET['route'] : '';
if (empty($route)) {
    $path_info = isset($_SERVER['PATH_INFO']) ? $_SERVER['PATH_INFO'] : '';
    if (empty($path_info)) {
        $request_uri = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
        if (strpos($request_uri, 'api.php') !== false) {
            $parts = explode('api.php', $request_uri);
            $route = isset($parts[1]) ? $parts[1] : '';
        } else {
            $route = $request_uri;
        }
    } else {
        $route = $path_info;
    }
}

$route = '/' . ltrim($route, '/');
$method = $_SERVER['REQUEST_METHOD'];

function getJsonPayload() {
    $raw = file_get_contents('php://input');
    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : [];
}

function haversineDistance($lat1, $lon1, $lat2, $lon2) {
    $earthRadius = 6371.0; // km
    $dLat = deg2rad($lat2 - $lat1);
    $dLon = deg2rad($lon2 - $lon1);
    $a = sin($dLat / 2) * sin($dLat / 2) +
         cos(deg2rad($lat1)) * cos(deg2rad($lat2)) *
         sin($dLon / 2) * sin($dLon / 2);
    $c = 2 * atan2(sqrt($a), sqrt(1 - $a));
    return $earthRadius * $c;
}

// PDO may return DECIMAL columns as PHP strings -- coerce to a real JSON
// number so strict clients (e.g. Moshi codegen) don't fail to parse it.
function toFloatOrNull($value) {
    if ($value === null) return null;
    return (float) $value;
}

function toIntBool($value): bool {
    return ((int) $value) === 1;
}

// Reads a single Remote Management value from tams_remote_management, falling
// back to $default if the row doesn't exist yet or its value is malformed/
// outside [$min, $max] -- a tampered or manually-edited row must never make
// validation silently stricter/looser than intended. Mirrors
// helpers/functions.php's remote_management_values() on the Admin Panel side;
// kept as an independent copy since this app doesn't share PHP files with
// that one (see root CLAUDE.md).
function getRemoteManagementInt(PDO $pdo, string $key, int $default, int $min, int $max): int {
    $stmt = $pdo->prepare("SELECT config_value FROM tams_remote_management WHERE config_key = :key LIMIT 1");
    $stmt->execute([':key' => $key]);
    $row = $stmt->fetch();
    if (!$row) {
        return $default;
    }
    $value = filter_var($row['config_value'], FILTER_VALIDATE_INT);
    if ($value === false || $value < $min || $value > $max) {
        return $default;
    }
    return $value;
}

/**
 * Shared name/address/coordinate validation for /outlet/create and
 * /outlet/update -- both routes accept an identical payload shape and must
 * reject malformed input the same way, so this is one function instead of
 * the same block duplicated into each route. Throws the same
 * {message, http code} Exception shape every other route in this file
 * already relies on the outer catch(Exception) block to turn into a
 * response. Returns [name, address, latitude, longitude] once all four are
 * confirmed valid.
 */
function validateOutletPayload(array $payload): array {
    $name = isset($payload['name']) ? trim((string) $payload['name']) : '';
    $address = isset($payload['address']) ? trim((string) $payload['address']) : '';
    $latitude = isset($payload['latitude']) ? filter_var($payload['latitude'], FILTER_VALIDATE_FLOAT) : null;
    $longitude = isset($payload['longitude']) ? filter_var($payload['longitude'], FILTER_VALIDATE_FLOAT) : null;

    if ($name === '' || mb_strlen($name) > OUTLET_NAME_MAX_LENGTH) {
        throw new Exception("Outlet name is required and must be at most " . OUTLET_NAME_MAX_LENGTH . " characters.", 400);
    }
    if ($address === '' || mb_strlen($address) > OUTLET_ADDRESS_MAX_LENGTH) {
        throw new Exception("Address is required and must be at most " . OUTLET_ADDRESS_MAX_LENGTH . " characters.", 400);
    }
    if ($latitude === false || $latitude === null || $latitude < -90.0 || $latitude > 90.0 ||
        $longitude === false || $longitude === null || $longitude < -180.0 || $longitude > 180.0) {
        throw new Exception("Invalid coordinates. Latitude must be -90..90 and Longitude -180..180.", 400);
    }

    return [$name, $address, $latitude, $longitude];
}

/**
 * Idempotently records one confirmed Outlet visit for today, for this
 * Member+outlet pair -- INSERT IGNORE against tams_outlet_visits'
 * UNIQUE(member_id, outlet_id, visited_date) so a Member re-entering the
 * same outlet's radius later the same day (or a duplicate/retried fix) is a
 * silent no-op rather than a second row or a hard error. Mirrors
 * tams_member_history_locations' own INSERT IGNORE + UNIQUE index dedup
 * idiom for exactly the same reason. Called only from /location/update's own
 * transaction -- see that route's "Outlet visit detection" block.
 *
 * $confirmedAt must be the confirming fix's own `recorded_at` (device time),
 * never the server's arrival time -- see tams_outlet_visits' schema.sql
 * comment on why this keeps a join to tams_member_history_locations
 * meaningful, since no coordinates are duplicated into this table. Read by
 * the Web Admin's Visit Report (ajax/outlet_visit_report.php).
 */
function confirmOutletVisit(PDO $pdo, int $memberId, int $outletId, string $outletName, string $confirmedAt, int $dwellSeconds): void {
    $stmt = $pdo->prepare("
        INSERT IGNORE INTO tams_outlet_visits
            (outlet_id, member_id, confirmed_at, dwell_seconds, outlet_name_snapshot)
        VALUES
            (:outlet_id, :member_id, :confirmed_at, :dwell_seconds, :outlet_name_snapshot)
    ");
    $stmt->execute([
        ':outlet_id' => $outletId,
        ':member_id' => $memberId,
        ':confirmed_at' => $confirmedAt,
        ':dwell_seconds' => $dwellSeconds,
        ':outlet_name_snapshot' => $outletName,
    ]);
}

try {
    switch ($route) {
        case '/auth/login':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $payload = getJsonPayload();
            $username = isset($payload['username']) ? trim($payload['username']) : '';
            $password = isset($payload['password']) ? $payload['password'] : '';

            // Fallback to $_POST if raw JSON payload is empty
            if (empty($username)) {
                $username = isset($_POST['username']) ? trim($_POST['username']) : '';
            }
            if (empty($password)) {
                $password = isset($_POST['password']) ? $_POST['password'] : '';
            }

            if (empty($username) || empty($password)) {
                throw new Exception("Username and password are required.", 400);
            }

            // Explicit column list -- every column below is actually read
            // further down this handler (locked_until/failed_login_attempts
            // for the lockout check, password for the credential compare,
            // is_active for the account-disabled check, id/name/note/
            // username/role for the token insert and response body);
            // created_at/updated_at/force_tracking_hours are never touched
            // here.
            $stmt = $pdo->prepare("SELECT id, name, note, username, password, role, is_active, failed_login_attempts, locked_until FROM tams_users WHERE username = :username LIMIT 1");
            $stmt->execute([':username' => $username]);
            $user = $stmt->fetch();

            // Generic message for both "unknown username" and "wrong password"
            // so the API never reveals whether a username exists (avoids
            // user-enumeration attacks).
            $invalidCredentialsMessage = "Invalid username or password.";

            if (!$user) {
                throw new Exception($invalidCredentialsMessage, 401);
            }

            // Reject before even comparing the password if a prior streak of
            // wrong guesses already locked this account out.
            if ($user['locked_until'] !== null && strtotime($user['locked_until']) > time()) {
                throw new Exception(
                    "Account temporarily locked due to too many failed login attempts. Please try again in a few minutes.",
                    429
                );
            }

            // Plain-text password comparison, per project requirement.
            // hash_equals() is used only for a constant-time string compare
            // (avoids leaking timing information) -- it does NOT hash the
            // password itself; both sides remain plain text.
            $db_password = $user['password'];
            $passwordOk = hash_equals($db_password, $password);

            if (!$passwordOk) {
                // A previous lockout that has already expired must not
                // compound into an even stricter one -- start a fresh count
                // first, otherwise a single wrong guess long after the lock
                // expired would immediately re-lock the account for another
                // LOGIN_LOCKOUT_MINUTES (failed_login_attempts is only ever
                // reset to 0 elsewhere on a SUCCESSFUL login). Reaching this
                // branch already guarantees locked_until is either null or
                // in the past -- a still-active lock throws before the
                // password is even checked (see above) -- so a non-null
                // value here specifically means "expired".
                $priorAttempts = $user['locked_until'] !== null ? 0 : (int) $user['failed_login_attempts'];
                $attempts = $priorAttempts + 1;
                $lockNow = $attempts >= MAX_FAILED_LOGIN_ATTEMPTS;

                // Computed in PHP (already Asia/Jakarta via
                // date_default_timezone_set), NOT MySQL's own NOW()/
                // DATE_ADD -- the DB server's clock/timezone isn't
                // guaranteed to match PHP's, and every other timestamp this
                // app writes (e.g. $now_time below) is PHP-computed for the
                // same reason. Comparing a MySQL-clock value against PHP's
                // time() would silently misjudge the lock as already
                // expired if the two clocks disagree.
                if ($lockNow) {
                    $fail_stmt = $pdo->prepare("
                        UPDATE tams_users
                        SET failed_login_attempts = :attempts, locked_until = :locked_until
                        WHERE id = :id
                    ");
                    $fail_stmt->bindValue(':locked_until', date('Y-m-d H:i:s', time() + LOGIN_LOCKOUT_MINUTES * 60));
                } else {
                    $fail_stmt = $pdo->prepare("
                        UPDATE tams_users SET failed_login_attempts = :attempts WHERE id = :id
                    ");
                }
                $fail_stmt->bindValue(':attempts', $attempts, PDO::PARAM_INT);
                $fail_stmt->bindValue(':id', $user['id'], PDO::PARAM_INT);
                $fail_stmt->execute();

                throw new Exception($invalidCredentialsMessage, 401);
            }

            if ($user['is_active'] != 1) {
                throw new Exception("Account is inactive.", 403);
            }

            // Everything from here is one logical "login succeeded" event
            // (lockout reset, token issuance, optional device-info upsert) --
            // wrapped in a transaction so a mid-request crash/DB disconnect
            // can never commit only part of it (e.g. a token inserted and
            // usable server-side, but the response never reaches the client
            // because the device-info write after it threw -- previously an
            // orphaned-but-valid token with no other ill effect, but still an
            // avoidable inconsistency). Mirrors the same
            // beginTransaction/commit/rollback-and-rethrow pattern already
            // used below by /location/update.
            $pdo->beginTransaction();
            try {
                // Correct password and active account -- clear any lockout state.
                if ((int) $user['failed_login_attempts'] !== 0 || $user['locked_until'] !== null) {
                    $reset_stmt = $pdo->prepare("UPDATE tams_users SET failed_login_attempts = 0, locked_until = NULL WHERE id = :id");
                    $reset_stmt->execute([':id' => $user['id']]);
                }

                $raw_token = bin2hex(random_bytes(32)); // 64 hex chars
                $token_hash = hash('sha256', $raw_token);
                $device_info = isset($_SERVER['HTTP_USER_AGENT']) ? substr($_SERVER['HTTP_USER_AGENT'], 0, 255) : 'Unknown Device';

                // Token never expires -- it stays valid until /auth/logout deletes
                // it, or an admin deactivates the account. See middleware.php.
                $tok_stmt = $pdo->prepare("
                    INSERT INTO tams_auth_tokens (user_id, token_hash, device_info)
                    VALUES (:user_id, :token_hash, :device_info)
                ");
                $tok_stmt->execute([
                    ':user_id' => $user['id'],
                    ':token_hash' => $token_hash,
                    ':device_info' => $device_info
                ]);

                // Member Version Monitoring: optional structured device info
                // reported at login (see /location/update below for the same
                // fields reported on every sync). Only written when the client
                // actually sent at least one -- an older client that never sends
                // these must never wipe out device info a newer login already
                // reported. COALESCE(:val, col) means an individual omitted
                // field (still null here) also doesn't clobber a previously-
                // known value for that one column. Lives on tams_users, not
                // tams_live_tracking_current -- this is member/device
                // identity, not a live-tracking fact (see schema.sql's
                // "Member/Device Identity" migration comment) -- and a plain
                // UPDATE (not upsert) is correct here since $user['id'] was
                // just authenticated against an existing tams_users row.
                $appVersionName = isset($payload['app_version_name']) ? mb_substr((string) $payload['app_version_name'], 0, 20) : null;
                $appVersionCode = isset($payload['app_version_code']) ? (filter_var($payload['app_version_code'], FILTER_VALIDATE_INT) ?: null) : null;
                $androidVersion = isset($payload['android_version']) ? mb_substr((string) $payload['android_version'], 0, 50) : null;
                $deviceModel = isset($payload['device_model']) ? mb_substr((string) $payload['device_model'], 0, 100) : null;
                if ($appVersionName !== null || $appVersionCode !== null || $androidVersion !== null || $deviceModel !== null) {
                    $device_stmt = $pdo->prepare("
                        UPDATE tams_users
                        SET app_version_name = COALESCE(:app_version_name, app_version_name),
                            app_version_code = COALESCE(:app_version_code, app_version_code),
                            android_version = COALESCE(:android_version, android_version),
                            device_model = COALESCE(:device_model, device_model)
                        WHERE id = :user_id
                    ");
                    $device_stmt->execute([
                        ':user_id' => $user['id'],
                        ':app_version_name' => $appVersionName,
                        ':app_version_code' => $appVersionCode,
                        ':android_version' => $androidVersion,
                        ':device_model' => $deviceModel,
                    ]);
                }

                $pdo->commit();
            } catch (Throwable $inner) {
                if ($pdo->inTransaction()) {
                    $pdo->rollBack();
                }
                throw $inner;
            }

            echo json_encode([
                "success" => true,
                "message" => "Login successful.",
                "data" => [
                    "token" => $raw_token,
                    "user" => [
                        "id" => $user['id'],
                        "name" => $user['name'],
                        "note" => $user['note'],
                        "username" => $user['username'],
                        "role" => $user['role']
                    ]
                ]
            ]);
            break;

        case '/app/version':
            // Deliberately public (no authenticateUser() call) -- the app
            // must be able to check for a mandatory update, and show a
            // force-update block, before the user has ever logged in.
            // Backed by tams_ota_update, the single-row table
            // pages/ota_update.php (Admin Panel) is the only writer of --
            // see that page for the actual configuration UI.
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $stmt = $pdo->query("
                SELECT version_code, min_supported_version_code, version_name, apk_url, release_notes
                FROM tams_ota_update
                WHERE id = 1
                LIMIT 1
            ");
            $versionRow = $stmt->fetch();
            if (!$versionRow) {
                // Only reachable if the DB migration adding tams_ota_update
                // (schema.sql) hasn't been run yet -- the seed INSERT there
                // guarantees row id=1 always exists otherwise.
                throw new Exception("App version configuration has not been set up yet.", 404);
            }

            // release_notes is stored as plain newline-separated text (see
            // schema.sql's table comment) -- split into a JSON array here,
            // trimming and dropping blank lines so stray empty lines typed
            // into the Admin Panel's textarea don't become empty bullets.
            $releaseNotes = array_values(array_filter(
                array_map('trim', explode("\n", (string) $versionRow['release_notes'])),
                static fn($line) => $line !== ''
            ));

            // Force Update is no longer a manually-set column -- derived here
            // instead: min_supported_version_code >= version_code means the
            // currently-published version IS the floor, so every older
            // install must update to keep working at all. Kept as ">="
            // rather than "==" purely as a defensive generalization (the
            // Admin Panel's own save validation already forbids
            // min_supported_version_code from exceeding version_code) --
            // functionally identical under that invariant, just not reliant
            // on it holding perfectly for legacy/edited rows.
            $forceUpdate = ((int) $versionRow['min_supported_version_code']) >= ((int) $versionRow['version_code']);

            echo json_encode([
                "success" => true,
                "message" => "OK",
                "data" => [
                    "version_code" => (int) $versionRow['version_code'],
                    "version_name" => $versionRow['version_name'],
                    "force_update" => $forceUpdate,
                    "apk_url" => $versionRow['apk_url'],
                    "release_notes" => $releaseNotes
                ]
            ]);
            break;

        case '/app/config':
            // Deliberately public, mirroring /app/version -- Remote
            // Management values (GPS update interval, sync interval, etc.)
            // must be readable independent of which account is logged in,
            // and before login for the earliest possible effect. Backed by
            // tams_remote_management, the Admin Panel's "Remote Management" page
            // is the only writer of. NEVER put secrets, tokens, or other
            // security-sensitive values in that table -- anything stored
            // there is served to any installed copy of the app with no
            // authentication at all.
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $stmt = $pdo->query("SELECT config_key, config_value FROM tams_remote_management");
            $config = [];
            foreach ($stmt->fetchAll() as $row) {
                $config[$row['config_key']] = $row['config_value'];
            }

            echo json_encode([
                "success" => true,
                "message" => "OK",
                "data" => $config
            ]);
            break;

        case '/auth/logout':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo);
            $stmt = $pdo->prepare("DELETE FROM tams_auth_tokens WHERE token_hash = :token_hash");
            $stmt->execute([':token_hash' => $user['current_token_hash']]);

            echo json_encode([
                "success" => true,
                "message" => "Logged out successfully.",
                "data" => null
            ]);
            break;

        case '/profile':
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo);
            echo json_encode([
                "success" => true,
                "message" => "Profile fetched successfully.",
                "data" => [
                    "id" => $user['id'],
                    "name" => $user['name'],
                    "note" => $user['note'],
                    "username" => $user['username'],
                    "role" => $user['role']
                ]
            ]);
            break;

        case '/profile/update':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            // Self-service only: always updates the authenticated caller's own
            // row (from the token), never one supplied in the payload.
            $user = authenticateUser($pdo);
            $payload = getJsonPayload();

            $name = isset($payload['name']) ? trim($payload['name']) : '';
            $username = isset($payload['username']) ? trim($payload['username']) : '';
            $note = isset($payload['note']) ? trim($payload['note']) : '';
            // Empty string means "keep the current password" -- no need to
            // retype it just to fix a typo in name/username.
            $password = isset($payload['password']) ? (string) $payload['password'] : '';

            if ($name === '' || $username === '') {
                throw new Exception("Name and username are required.", 400);
            }
            // Same bounds/format the Admin Panel enforces for this same table
            // (ajax/members_save.php, ajax/profile_update.php) -- this is the
            // only path into tams_users that skipped them, since it's driven
            // by the Android app rather than a <form> with matching HTML
            // constraints. Kept identical across both surfaces on purpose:
            // one account table, one set of rules for what's a valid row.
            if (mb_strlen($name) > 100) {
                throw new Exception("Name must be at most 100 characters.", 400);
            }
            if (mb_strlen($username) > 50) {
                throw new Exception("Username must be at most 50 characters.", 400);
            }
            if (!preg_match('/^[a-zA-Z0-9_.]+$/', $username)) {
                throw new Exception("Username may only contain letters, numbers, dots, and underscores.", 400);
            }
            // Configurable via the Admin Panel's Remote Management page
            // (tams_remote_management) -- see getRemoteManagementInt() and the
            // PASSWORD_M*_LENGTH_* fallback constants above.
            $passwordMinLength = getRemoteManagementInt(
                $pdo, 'password_min_length', PASSWORD_MIN_LENGTH_DEFAULT,
                PASSWORD_MIN_LENGTH_LOWER_BOUND, PASSWORD_MIN_LENGTH_UPPER_BOUND
            );
            $passwordMaxLength = getRemoteManagementInt(
                $pdo, 'password_max_length', PASSWORD_MAX_LENGTH_DEFAULT,
                PASSWORD_MAX_LENGTH_LOWER_BOUND, PASSWORD_MAX_LENGTH_UPPER_BOUND
            );
            if ($password !== '' && mb_strlen($password) < $passwordMinLength) {
                throw new Exception("New password must be at least " . $passwordMinLength . " characters.", 400);
            }
            if (mb_strlen($password) > $passwordMaxLength) {
                // tams_users.password is VARCHAR(255) at most -- rejected up
                // front rather than reaching the UPDATE below.
                throw new Exception("New password must be at most " . $passwordMaxLength . " characters.", 400);
            }

            // Friendly pre-check for a duplicate username (excluding the
            // caller's own row) -- the UNIQUE index below is the real
            // guarantee; this just gives a nicer message in the common case.
            $check_stmt = $pdo->prepare("SELECT id FROM tams_users WHERE username = :username AND id != :id LIMIT 1");
            $check_stmt->execute([':username' => $username, ':id' => $user['id']]);
            if ($check_stmt->fetch()) {
                throw new Exception("Username is already used by another account.", 409);
            }

            try {
                if ($password !== '') {
                    // Plain-text password, per project requirement (see
                    // /auth/login's hash_equals() comment).
                    $upd_stmt = $pdo->prepare("
                        UPDATE tams_users SET name = :name, username = :username, note = :note, password = :password
                        WHERE id = :id
                    ");
                    $upd_stmt->execute([
                        ':name' => $name,
                        ':username' => $username,
                        ':note' => $note,
                        ':password' => $password,
                        ':id' => $user['id']
                    ]);
                } else {
                    $upd_stmt = $pdo->prepare("
                        UPDATE tams_users SET name = :name, username = :username, note = :note
                        WHERE id = :id
                    ");
                    $upd_stmt->execute([
                        ':name' => $name,
                        ':username' => $username,
                        ':note' => $note,
                        ':id' => $user['id']
                    ]);
                }
            } catch (PDOException $e) {
                // Race-condition fallback: two requests could both pass the
                // pre-check above before either commits; the UNIQUE index
                // still guarantees correctness.
                if ((string) $e->getCode() === '23000') {
                    throw new Exception("Username is already used by another account.", 409);
                }
                throw $e;
            }

            echo json_encode([
                "success" => true,
                "message" => "Profile updated successfully.",
                "data" => [
                    "id" => $user['id'],
                    "name" => $name,
                    "note" => $note,
                    "username" => $username,
                    "role" => $user['role']
                ]
            ]);
            break;

        case '/activity/log':
            // Any authenticated role may write a log entry -- Member is the
            // primary caller (profile changes, Start/Stop, sync failures),
            // but nothing about this route is Member-specific.
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo);
            $payload = getJsonPayload();

            // Whitelist, not free text -- keeps the Log page's activity-type
            // filter finite, and keeps a buggy or compromised client from
            // writing arbitrary junk into what is meant to be a trustworthy
            // audit trail.
            $allowedActionTypes = ['profile_update', 'start_location', 'stop_location', 'sync_failed', 'error'];
            $actionType = isset($payload['action_type']) ? trim((string) $payload['action_type']) : '';
            if (!in_array($actionType, $allowedActionTypes, true)) {
                throw new Exception("Invalid action_type.", 400);
            }

            $status = (isset($payload['status']) && $payload['status'] === 'failed') ? 'failed' : 'success';

            // Truncated to the column width defensively -- a malformed or
            // oversized client payload must never hard-fail the insert.
            // Never accepts a "password" field at all (see ApiService.kt's
            // ActivityLogRepository, which never sends one) -- passwords
            // must never reach this table under any circumstance.
            $fieldBefore = isset($payload['field_before']) ? mb_substr((string) $payload['field_before'], 0, 500) : null;
            $fieldAfter = isset($payload['field_after']) ? mb_substr((string) $payload['field_after'], 0, 500) : null;
            $message = isset($payload['message']) ? mb_substr((string) $payload['message'], 0, 500) : null;

            // user_id/user_name always come from the authenticated token,
            // never the request body -- a caller must never be able to
            // write a log entry under someone else's identity.
            $stmt = $pdo->prepare("
                INSERT INTO tams_member_log (user_id, user_name, action_type, status, field_before, field_after, message)
                VALUES (:user_id, :user_name, :action_type, :status, :field_before, :field_after, :message)
            ");
            $stmt->execute([
                ':user_id' => $user['id'],
                ':user_name' => $user['name'],
                ':action_type' => $actionType,
                ':status' => $status,
                ':field_before' => $fieldBefore,
                ':field_after' => $fieldAfter,
                ':message' => $message
            ]);

            echo json_encode([
                "success" => true,
                "message" => "Activity logged.",
                "data" => null
            ]);
            break;

        case '/location/status':
            // Lightweight, cheap poll target for Force Override pre-flight:
            // MainViewModel calls this right before actually starting the
            // foreground tracking service, so a Member outside the allowed
            // window sees an immediate, specific message instead of a silent
            // background failure. Purely a UX convenience -- POST
            // /location/update enforces the same rule unconditionally on
            // EVERY call (not just Start), regardless of whether this was
            // ever called, so skipping/spoofing this check client-side can
            // never bypass the real restriction. This is also what makes
            // Force Location's ON->OFF transition self-enforcing: an Admin
            // flipping force_tracking_hours off takes effect on that
            // Member's very next location fix (see /location/update's Force
            // Override gate below), with no separate revocation call needed.
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['member']);
            $status_stmt = $pdo->prepare("
                SELECT force_tracking_hours
                FROM tams_users
                WHERE id = :id
                LIMIT 1
            ");
            $status_stmt->execute([':id' => $user['id']]);
            $status_row = $status_stmt->fetch();
            $forceEnabled = $status_row && toIntBool($status_row['force_tracking_hours']);
            $trackingAllowed = $forceEnabled || isWithinOperationalHours();

            echo json_encode([
                "success" => true,
                "message" => $trackingAllowed
                    ? "Tracking is currently allowed."
                    : "Tracking is only allowed between " . TRACKING_HOUR_START . " and " . TRACKING_HOUR_END . " WIB.",
                "data" => [
                    "tracking_allowed" => $trackingAllowed,
                    "force_override" => $forceEnabled,
                    "operational_hours_start" => TRACKING_HOUR_START,
                    "operational_hours_end" => TRACKING_HOUR_END
                ]
            ]);
            break;

        case '/location/update':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['member']);
            $payload = getJsonPayload();

            $status = isset($payload['status']) ? $payload['status'] : 'active';
            $now_time = date('Y-m-d H:i:s');

            // --- Transaction ----------------------------------------------------------
            // Started BEFORE authorization is validated (not after, as a plain
            // pre-transaction SELECT used to do) so the Force/account-active
            // gate and the GPS point write happen as one atomic unit. A
            // check-then-act SELECT ahead of beginTransaction() left a real
            // TOCTOU window: an Admin's Force-OFF toggle or account
            // deactivation could commit in the gap between that SELECT and
            // this route's own commit, letting 1-2 stray fixes land after the
            // toggle. Locking tams_users FIRST below (before
            // tams_live_tracking_current) closes that window deterministically
            // -- see the locked read's own comment for why -- and must stay
            // the first resource this transaction acquires; every other route
            // that ever touches both tables (members_delete.php's
            // ON DELETE CASCADE, and this same transaction's own later
            // device-info UPDATE further down) already locks tams_users
            // before it can reach tams_live_tracking_current, so this
            // ordering introduces no new deadlock risk. Reversing it would.
            $pdo->beginTransaction();
            try {
                if ($status !== 'offline') {
                    // --- Force Override + account-active gate (locked) ------------------
                    // Applies to every genuine tracking fix, not just the first
                    // one in a session -- there is no separate server-side
                    // "start tracking" call to gate instead (the Android app
                    // only ever calls this same route repeatedly), and gating
                    // only an initial call would let an already-running
                    // session silently keep posting straight through the
                    // boundary. Never applies to the offline/stop signal below
                    // (a Member must always be able to signal Stop regardless
                    // of time or account state) -- see that branch's own
                    // comment for why it deliberately never takes this lock.
                    //
                    // force_tracking_hours is also checked against this
                    // server's own clock unconditionally -- see
                    // isWithinOperationalHours()'s doc comment -- so neither a
                    // modified app build nor a spoofed device clock can bypass
                    // it; MainViewModel's own GET /location/status pre-flight
                    // check is purely a UX nicety layered on top of this,
                    // never a substitute for it.
                    //
                    // is_active is re-read here too, alongside
                    // force_tracking_hours, in the SAME locked row -- it's
                    // already checked once by authenticateUser() above, but
                    // that's a plain SELECT evaluated before this transaction
                    // even starts, so it's exposed to the identical TOCTOU
                    // race as force_tracking_hours (an Admin deactivating the
                    // account via members_save.php mid-request). Re-checking
                    // it here is free -- this row is already being locked for
                    // force_tracking_hours -- and closes that parallel gap
                    // deterministically instead of leaving it as a second,
                    // unfixed race. This is deliberately scoped to
                    // force_tracking_hours and is_active only: they're the two
                    // tams_users columns that gate whether THIS fix may
                    // produce a new GPS point. A future account-wide gate
                    // (e.g. suspended/banned) belongs in authenticateUser()'s
                    // own JOIN condition instead, next to is_active, since
                    // such a gate should block every route -- including Stop
                    // -- not just new fixes; only a gate with the same
                    // "blocks new fixes but not Stop" shape as
                    // force_tracking_hours belongs in this locked read.
                    //
                    // This gate is also Force Location's ON->OFF revocation
                    // path: an Admin turning Force off
                    // (web/ajax/members_force_toggle.php) only ever flips
                    // force_tracking_hours in the database -- there is
                    // deliberately no separate "push a stop signal to the
                    // device" mechanism. Because this is now a locked read
                    // inside the same transaction as the write below (not a
                    // separate pre-transaction SELECT), a still-tracking
                    // Member's very next fix after the toggle is rejected here
                    // with no race window at all, not merely "very soon
                    // after". Android's MemberRepository.postLocation turns
                    // that rejection into a TrackingNotAllowedException, which
                    // MemberLocationService reacts to by automatically
                    // stopping itself -- no user interaction, no separate
                    // polling loop, no additional server-side state to keep in
                    // sync. See MemberLocationService.handleNewLocation()'s
                    // doc comment for the client side of this contract.
                    $auth_stmt = $pdo->prepare("
                        SELECT force_tracking_hours, is_active
                        FROM tams_users
                        WHERE id = :id
                        FOR UPDATE
                    ");
                    $auth_stmt->execute([':id' => $user['id']]);
                    $auth_row = $auth_stmt->fetch();

                    // No row at all (account deleted mid-request) fails closed,
                    // same as is_active = 0.
                    $isActive = $auth_row && toIntBool($auth_row['is_active']);
                    $forceEnabled = $auth_row && toIntBool($auth_row['force_tracking_hours']);

                    if (!$isActive) {
                        // Same failure class authenticateUser() above already
                        // guards against (account no longer active) -- same
                        // message, and deliberately NO error_code, so
                        // Android's existing 401/403-without-
                        // outside_operational_hours branch maps this to
                        // SessionInvalidException exactly as it does for a
                        // dead/revoked token (see
                        // MemberRepository.postLocation's doc comment). This
                        // is not a new wire contract -- it's the same response
                        // shape authenticateUser() already sends for the same
                        // reason, just also reachable from a race this locked
                        // read closes.
                        $pdo->rollBack();
                        http_response_code(401);
                        echo json_encode([
                            "success" => false,
                            "message" => "Invalid or revoked authentication token."
                        ]);
                        exit();
                    }

                    if (!$forceEnabled && !isWithinOperationalHours()) {
                        // Identical response shape to before this locked read
                        // replaced the old pre-transaction SELECT -- error_code
                        // unchanged, so MemberRepository.postLocation's
                        // TrackingNotAllowedException mapping needs no changes.
                        $pdo->rollBack();
                        http_response_code(403);
                        echo json_encode([
                            "success" => false,
                            "message" => "Tracking is only allowed between " . TRACKING_HOUR_START . " and " . TRACKING_HOUR_END . " WIB.",
                            "error_code" => "outside_operational_hours"
                        ]);
                        exit();
                    }
                }

                $prev_stmt = $pdo->prepare("
                    SELECT latitude, longitude, accuracy, status, updated_at, recorded_at
                    FROM tams_live_tracking_current
                    WHERE user_id = :user_id
                    FOR UPDATE
                ");
                $prev_stmt->execute([':user_id' => $user['id']]);
                $previous = $prev_stmt->fetch();

                if ($status === 'offline') {
                    // Applies immediately and unconditionally -- no
                    // recorded_at to race against, so a deliberate stop
                    // should never be suppressed by an ordering guard.
                    // Clearing recorded_at too so the next real fix after
                    // resuming isn't compared against a stale pre-stop
                    // timestamp (see the ordering guard below).
                    $upsert_stmt = $pdo->prepare("
                        INSERT INTO tams_live_tracking_current
                            (user_id, latitude, longitude, accuracy, speed, is_moving, status, updated_at, recorded_at, is_mock_location, gnss_satellites_used)
                        VALUES
                            (:user_id, NULL, NULL, NULL, NULL, 0, 'offline', NULL, NULL, NULL, NULL)
                        ON DUPLICATE KEY UPDATE
                            latitude = NULL,
                            longitude = NULL,
                            accuracy = NULL,
                            speed = NULL,
                            is_moving = 0,
                            status = 'offline',
                            updated_at = NULL,
                            recorded_at = NULL,
                            is_mock_location = NULL,
                            gnss_satellites_used = NULL
                    ");
                    $upsert_stmt->execute([':user_id' => $user['id']]);

                    // Outlet Management: clear any open dwell-timer state for
                    // this Member. Found during final validation: without
                    // this, stopping tracking mid-dwell (inside an outlet's
                    // radius, before outlet_min_dwell_seconds elapsed) left
                    // tams_outlet_dwell_state's entered_at sitting
                    // unrefreshed indefinitely -- /location/update's own
                    // "left the radius" clear (below, in the online branch)
                    // never runs for a Member who is offline, since there is
                    // no fix to evaluate distance from. Resuming tracking
                    // much later, even after genuinely leaving and
                    // returning, would then see an elapsed duration measured
                    // from the ORIGINAL entry time (wall-clock, not
                    // continuous presence) and could confirm a visit off a
                    // single fix with no real dwell time behind it. A plain
                    // DELETE, not gated behind the tams_users lock above --
                    // this table has no relationship to Force Location/
                    // account-active state, so it does not conflict with
                    // this branch's own "Stop always succeeds regardless of
                    // Force or account state" guarantee (see root CLAUDE.md).
                    // Harmless no-op when dwell mode is off
                    // (outlet_min_dwell_seconds = 0, the default) -- that
                    // mode never writes to this table in the first place.
                    $dwell_clear_stmt = $pdo->prepare("DELETE FROM tams_outlet_dwell_state WHERE member_id = :member_id");
                    $dwell_clear_stmt->execute([':member_id' => $user['id']]);

                    $pdo->commit();

                    echo json_encode([
                        "success" => true,
                        "message" => "Status set to offline successfully.",
                        "data" => null
                    ]);
                    break;
                }

                // Member Version Monitoring: optional device info, resent on
                // every sync call by the Android app (cheap -- a few extra
                // string fields on a request that's already happening every
                // 10-15s). Written to tams_users (member/device identity,
                // not a live-tracking fact -- see schema.sql's "Member/
                // Device Identity" migration comment), via COALESCE(:val,
                // col) so an omitted field never clobbers a previously-known
                // value. See /auth/login above for the identical pattern.
                $appVersionName = isset($payload['app_version_name']) ? mb_substr((string) $payload['app_version_name'], 0, 20) : null;
                $appVersionCode = isset($payload['app_version_code']) ? (filter_var($payload['app_version_code'], FILTER_VALIDATE_INT) ?: null) : null;
                $androidVersion = isset($payload['android_version']) ? mb_substr((string) $payload['android_version'], 0, 50) : null;
                $deviceModel = isset($payload['device_model']) ? mb_substr((string) $payload['device_model'], 0, 100) : null;

                // Mock-location trust signals (see schema.sql's "Mock-location trust
                // signals" migration comment on tams_live_tracking_current/
                // tams_member_history_locations for full semantics, and
                // MemberLocationService.handleNewLocation's doc comment on the
                // Android side for the detection design). Advisory-only: stored
                // exactly as received and never read by $isSuspiciousFix/
                // $applyToCurrent or any other decision below -- do not change
                // that. isset() (not a default) means an app version that omits
                // gnss_satellites_used (older client, or a one-shot fallback fix
                // with no live GNSS subscription) stores NULL ("unknown"), never
                // a false "0 satellites".
                $isMockLocation = isset($payload['is_mock']) ? ((bool) $payload['is_mock'] ? 1 : 0) : null;
                $gnssSatellitesUsed = isset($payload['gnss_satellites_used']) ? (filter_var($payload['gnss_satellites_used'], FILTER_VALIDATE_INT) ?: null) : null;

                $latitude = isset($payload['latitude']) ? filter_var($payload['latitude'], FILTER_VALIDATE_FLOAT) : null;
                $longitude = isset($payload['longitude']) ? filter_var($payload['longitude'], FILTER_VALIDATE_FLOAT) : null;
                $accuracy = isset($payload['accuracy']) ? filter_var($payload['accuracy'], FILTER_VALIDATE_FLOAT) : 0.0;
                $speed = isset($payload['speed']) ? filter_var($payload['speed'], FILTER_VALIDATE_FLOAT) : 0.0;
                $recorded_at = isset($payload['recorded_at']) ? $payload['recorded_at'] : $now_time;
                // Client-supplied, so it must match the exact "YYYY-MM-DD
                // HH:MM:SS" format before use in a DATETIME insert or a
                // strtotime() calculation -- the Android app later slices
                // this string with substring(11, 16), so a malformed value
                // would round-trip into a crash in its trip-summary UI.
                if (!preg_match('/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/', $recorded_at)) {
                    $recorded_at = $now_time;
                }

                if ($accuracy === false || $accuracy === null) $accuracy = 0.0;
                if ($speed === false || $speed === null) $speed = 0.0;

                if ($latitude === false || $latitude === null || $latitude < -90.0 || $latitude > 90.0 ||
                    $longitude === false || $longitude === null || $longitude < -180.0 || $longitude > 180.0) {
                    throw new Exception("Invalid coordinates. Latitude must be -90..90 and Longitude -180..180.", 400);
                }

                // --- GPS sanity filtering ------------------------------------------------
                // Catches a fix that's essentially unusable before it becomes a spurious
                // jump on the live map or route. Intentionally NOT an HTTP error: a bad
                // GPS reading can't be fixed by retrying the same payload, and failing
                // the request would get MemberRepository.syncOfflineLocations stuck
                // behind an unfixable offline-queue entry. Instead the request succeeds
                // but the point is silently not persisted -- same shape as the
                // out-of-order guard below.
                $isSuspiciousFix = $accuracy > GPS_MAX_USABLE_ACCURACY_M;
                if ($isSuspiciousFix) {
                    error_log("[TAMS API] Discarded GPS fix for user {$user['id']}: accuracy {$accuracy}m exceeds usable bound (" . GPS_MAX_USABLE_ACCURACY_M . "m).");
                }

                // --- Out-of-order arrival detection --------------------------------------
                // Hoisted above movement detection (previously computed further below,
                // only for the $applyToCurrent guard) because the jump-speed check
                // immediately below has the exact same blind spot $applyToCurrent was
                // already written to guard against: under a flaky connection, two
                // requests for the same member can complete out of send order (fix A
                // sent, then B, but B's response arrives first -- see $applyToCurrent's
                // own comment below). When that happens, $previous already holds a fix
                // chronologically AFTER this incoming one, so the elapsed-time math right
                // below would go negative and get floored to 1 second, artificially
                // inflating the derived speed enough to trip the jump-speed check for
                // perfectly ordinary movement -- wrongly marking an accurate, real fix as
                // suspicious, which silently skips both the history insert and outlet
                // visit geofencing (see the "GPS sanity filtering" comment above), even
                // though this fix may be exactly the one that placed the Member inside an
                // outlet's radius. Computed once here and reused by both the jump-speed
                // check and $applyToCurrent further below, instead of two independent
                // computations of the same fact.
                $isOutOfOrderArrival = false;
                if ($previous && !empty($previous['recorded_at'])) {
                    $prevRecordedAtTs = strtotime($previous['recorded_at']);
                    $incomingRecordedAtTs = strtotime($recorded_at);
                    if ($prevRecordedAtTs !== false && $incomingRecordedAtTs !== false && $incomingRecordedAtTs < $prevRecordedAtTs) {
                        $isOutOfOrderArrival = true;
                    }
                }

                // --- Movement detection -------------------------------------------------
                // "Moving" requires a previous online fix to compare against, AND
                // displacement clearing both a fixed floor and the combined GPS-accuracy
                // uncertainty, AND implied speed above a walking-noise threshold. The
                // first fix after Start (no previous fix) is therefore never "moving".
                $is_moving = false;
                if ($previous && $previous['status'] === 'online' &&
                    $previous['latitude'] !== null && $previous['longitude'] !== null) {

                    $distance_m = haversineDistance(
                        (float) $previous['latitude'],
                        (float) $previous['longitude'],
                        $latitude,
                        $longitude
                    ) * 1000.0;

                    // Elapsed time uses each fix's own device timestamp
                    // (recorded_at), not server arrival time (updated_at) --
                    // arrival time would corrupt the derived speed whenever
                    // network latency differs (e.g. an offline fix synced
                    // minutes late). Falls back to updated_at only for rows
                    // written before this column existed.
                    $prev_time = !empty($previous['recorded_at'])
                        ? strtotime($previous['recorded_at'])
                        : ($previous['updated_at'] ? strtotime($previous['updated_at']) : strtotime($recorded_at));
                    $curr_time = strtotime($recorded_at);
                    $elapsed_seconds = max(1, $curr_time - $prev_time);
                    $derived_speed = $distance_m / $elapsed_seconds;

                    // Reuses $distance_m/$derived_speed rather than a second
                    // calculation path; a legitimate highway-speed fix stays
                    // well under this bound. Skipped for an out-of-order arrival (see
                    // $isOutOfOrderArrival above) -- $elapsed_seconds is not meaningful
                    // for two fixes that didn't arrive in temporal order relative to
                    // what's already stored, so a large $derived_speed here reflects
                    // that clock-math artifact, not a real GPS jump. Every genuinely
                    // in-order fix still reaches this check exactly as before -- this
                    // narrows, not weakens, jump-speed protection.
                    if (!$isOutOfOrderArrival && $derived_speed > GPS_JUMP_MAX_SPEED_MPS) {
                        $isSuspiciousFix = true;
                        error_log("[TAMS API] Discarded GPS fix for user {$user['id']}: implied speed {$derived_speed}m/s exceeds sanity bound (" . GPS_JUMP_MAX_SPEED_MPS . "m/s) over {$elapsed_seconds}s / {$distance_m}m.");
                    }

                    $prev_accuracy = $previous['accuracy'] !== null ? (float) $previous['accuracy'] : 0.0;
                    $accuracy_buffer = min(50.0, max(0.0, ($prev_accuracy + $accuracy) / 2.0));
                    $required_distance = max(MOVEMENT_DISTANCE_FLOOR_M, $accuracy_buffer);

                    $is_moving = ($distance_m > $required_distance) && ($derived_speed >= MOVEMENT_MIN_SPEED_MPS);
                }

                // --- Out-of-order arrival guard -----------------------------------------
                // Under a flaky connection, two requests for the same member can
                // complete out of send order (fix A sent, then B, but B's response
                // arrives first). If A then overwrote tams_live_tracking_current after B
                // already landed, the Admin's live map would visibly regress. Only
                // apply to "current" if not older (by its own recorded_at) than what's
                // already stored -- reuses $isOutOfOrderArrival computed above (same
                // recorded_at comparison) instead of recomputing it here. History is
                // unaffected: every fix is still appended to
                // tams_member_history_locations and read back ORDER BY recorded_at ASC --
                // and, since the jump-speed check above now also exempts this same fix
                // from suspicious-flagging on that basis, an out-of-order-but-otherwise-
                // valid fix reaches history and outlet-visit geofencing exactly like any
                // other non-suspicious fix.
                $applyToCurrent = !$isOutOfOrderArrival;
                // A suspicious fix must never become the "current" position either.
                $applyToCurrent = $applyToCurrent && !$isSuspiciousFix;

                if ($applyToCurrent) {
                    $upsert_stmt = $pdo->prepare("
                        INSERT INTO tams_live_tracking_current
                            (user_id, latitude, longitude, accuracy, speed, is_moving, status, updated_at, recorded_at, is_mock_location, gnss_satellites_used)
                        VALUES
                            (:user_id, :latitude, :longitude, :accuracy, :speed, :is_moving, 'online', :now_time, :recorded_at, :is_mock_location, :gnss_satellites_used)
                        ON DUPLICATE KEY UPDATE
                            latitude = VALUES(latitude),
                            longitude = VALUES(longitude),
                            accuracy = VALUES(accuracy),
                            speed = VALUES(speed),
                            is_moving = VALUES(is_moving),
                            status = 'online',
                            updated_at = VALUES(updated_at),
                            recorded_at = VALUES(recorded_at),
                            is_mock_location = VALUES(is_mock_location),
                            gnss_satellites_used = VALUES(gnss_satellites_used)
                    ");
                    $upsert_stmt->execute([
                        ':user_id' => $user['id'],
                        ':latitude' => $latitude,
                        ':longitude' => $longitude,
                        ':accuracy' => $accuracy,
                        ':speed' => $speed,
                        ':is_moving' => $is_moving ? 1 : 0,
                        ':now_time' => $now_time,
                        ':recorded_at' => $recorded_at,
                        ':is_mock_location' => $isMockLocation,
                        ':gnss_satellites_used' => $gnssSatellitesUsed,
                    ]);

                    // Same condition as /auth/login above (only write when
                    // the client sent at least one field) -- kept gated by
                    // $applyToCurrent, unchanged from before this column
                    // moved tables, so an out-of-order sync call still never
                    // updates device info, exactly as it never updated
                    // position either.
                    if ($appVersionName !== null || $appVersionCode !== null || $androidVersion !== null || $deviceModel !== null) {
                        $device_stmt = $pdo->prepare("
                            UPDATE tams_users
                            SET app_version_name = COALESCE(:app_version_name, app_version_name),
                                app_version_code = COALESCE(:app_version_code, app_version_code),
                                android_version = COALESCE(:android_version, android_version),
                                device_model = COALESCE(:device_model, device_model)
                            WHERE id = :user_id
                        ");
                        $device_stmt->execute([
                            ':user_id' => $user['id'],
                            ':app_version_name' => $appVersionName,
                            ':app_version_code' => $appVersionCode,
                            ':android_version' => $androidVersion,
                            ':device_model' => $deviceModel,
                        ]);
                    }
                } else {
                    error_log("[TAMS API] Skipped out-of-order location update for user {$user['id']}: incoming recorded_at={$recorded_at} is older than the fix already stored.");
                }

                // Only a fix that passed the GPS sanity filter is persisted -- an
                // out-of-order-but-valid fix still belongs in its ledger (unlike
                // "current"). INSERT IGNORE + this table's own UNIQUE(user_id,
                // recorded_at) index makes a duplicate submission (client retry, or
                // racing offline-queue flushes) a silent no-op instead of an error or
                // an inflated route.
                if (!$isSuspiciousFix) {
                    $hist_stmt = $pdo->prepare("
                        INSERT IGNORE INTO tams_member_history_locations (user_id, latitude, longitude, accuracy, speed, is_moving, recorded_at, created_at, is_mock_location, gnss_satellites_used)
                        VALUES (:user_id, :latitude, :longitude, :accuracy, :speed, :is_moving, :recorded_at, :now_time_hist, :is_mock_location, :gnss_satellites_used)
                    ");
                    $hist_stmt->execute([
                        ':user_id' => $user['id'],
                        ':latitude' => $latitude,
                        ':longitude' => $longitude,
                        ':accuracy' => $accuracy,
                        ':speed' => $speed,
                        ':is_moving' => $is_moving ? 1 : 0,
                        ':recorded_at' => $recorded_at,
                        ':now_time_hist' => $now_time,
                        ':is_mock_location' => $isMockLocation,
                        ':gnss_satellites_used' => $gnssSatellitesUsed,
                    ]);

                    // --- Outlet visit detection (geofencing) -----------------------------
                    // Reaches this point only for a fix that already passed every other
                    // gate on this request: Force Override/account-active (the locked read
                    // at the top of this transaction), tracking genuinely active (a
                    // `status === 'offline'` request returns far above, before this code),
                    // and the GPS sanity filter (this very `if (!$isSuspiciousFix)` guard).
                    // No further "is tracking active" check is needed here -- the placement
                    // itself is the guarantee.
                    //
                    // Deliberately does NOT also require $applyToCurrent. That guard exists
                    // solely to stop an out-of-order network arrival from regressing the
                    // Live Tracking "current position" snapshot -- a concern specific to
                    // tams_live_tracking_current. Outlet visit detection is a fact about
                    // history (did this Member's device genuinely report being at this
                    // outlet), and tams_member_history_locations above already records every
                    // non-suspicious fix unconditionally regardless of arrival order.
                    // Coupling geofencing to $applyToCurrent would silently under-count real
                    // visits whenever a fix merely arrives out of network order, for a
                    // reason that has nothing to do with whether the Member was actually
                    // there. Live Tracking and Outlet visit detection are different domains
                    // reading the same fix -- they must not depend on each other's guards.
                    //
                    // Scoped to the outlet(s) assigned to THIS Member only
                    // (tams_outlets.member_id, one-to-one from the outlet's side --
                    // see that column's own schema.sql comment), APPROVED, not
                    // soft-deleted, and not merged away -- a plain SELECT with no
                    // locking, since outlet ownership is private per-Member: no
                    // concurrent writer ever contends for this specific read the way
                    // Force Location's toggle contends for tams_users (see that
                    // gate's own comment above for the contrast -- an Admin
                    // approving/rejecting/merging/reassigning this same outlet
                    // mid-fix is a low-impact, self-correcting race, not worth a
                    // lock here). A Member with no assigned outlets pays for one
                    // cheap indexed lookup that returns zero rows, never a table scan.
                    $outlet_candidates_stmt = $pdo->prepare("
                        SELECT o.id, o.latitude, o.longitude, o.name
                        FROM tams_outlets o
                        WHERE o.member_id = :member_id
                          AND o.status = 'APPROVED'
                          AND o.deleted_at IS NULL
                          AND o.merged_into_outlet_id IS NULL
                    ");
                    $outlet_candidates_stmt->execute([':member_id' => $user['id']]);
                    $outlet_candidates = $outlet_candidates_stmt->fetchAll();

                    if (!empty($outlet_candidates)) {
                        $outletRadiusMeters = getRemoteManagementInt(
                            $pdo, 'outlet_radius_meters', OUTLET_RADIUS_METERS_DEFAULT,
                            OUTLET_RADIUS_METERS_LOWER_BOUND, OUTLET_RADIUS_METERS_UPPER_BOUND
                        );
                        $outletMinDwellSeconds = getRemoteManagementInt(
                            $pdo, 'outlet_min_dwell_seconds', OUTLET_MIN_DWELL_SECONDS_DEFAULT,
                            OUTLET_MIN_DWELL_SECONDS_LOWER_BOUND, OUTLET_MIN_DWELL_SECONDS_UPPER_BOUND
                        );

                        // Existing open dwell-timers for this Member, fetched once rather
                        // than once per candidate outlet -- only ever non-empty when
                        // outlet_min_dwell_seconds > 0, since the dwell-immediate branch
                        // below never writes to tams_outlet_dwell_state at all.
                        $dwell_state_by_outlet = [];
                        if ($outletMinDwellSeconds > 0) {
                            $dwell_stmt = $pdo->prepare("
                                SELECT outlet_id, entered_at FROM tams_outlet_dwell_state WHERE member_id = :member_id
                            ");
                            $dwell_stmt->execute([':member_id' => $user['id']]);
                            foreach ($dwell_stmt->fetchAll() as $dwell_row) {
                                $dwell_state_by_outlet[(int) $dwell_row['outlet_id']] = $dwell_row['entered_at'];
                            }
                        }

                        foreach ($outlet_candidates as $outlet) {
                            $outletId = (int) $outlet['id'];
                            $distance_to_outlet_m = haversineDistance(
                                $latitude, $longitude,
                                (float) $outlet['latitude'], (float) $outlet['longitude']
                            ) * 1000.0;

                            if ($distance_to_outlet_m > $outletRadiusMeters) {
                                // Left (or never entered) this outlet's radius -- clear any
                                // open dwell timer so a later re-entry always starts fresh.
                                if (isset($dwell_state_by_outlet[$outletId])) {
                                    $clear_stmt = $pdo->prepare("DELETE FROM tams_outlet_dwell_state WHERE member_id = :member_id AND outlet_id = :outlet_id");
                                    $clear_stmt->execute([':member_id' => $user['id'], ':outlet_id' => $outletId]);
                                }
                                continue;
                            }

                            if ($outletMinDwellSeconds <= 0) {
                                // Default configuration: the first in-radius fix confirms
                                // the visit immediately, no dwell bookkeeping at all.
                                confirmOutletVisit($pdo, $user['id'], $outletId, $outlet['name'], $recorded_at, 0);
                                continue;
                            }

                            // Dwell-time path -- entered_at/elapsed math uses $recorded_at
                            // (this fix's own device timestamp) throughout, never server
                            // arrival order, for the same reason movement detection above
                            // already uses recorded_at rather than updated_at: fixes can
                            // arrive out of order, and only the device's own clock gives a
                            // meaningful elapsed duration.
                            if (!isset($dwell_state_by_outlet[$outletId])) {
                                $enter_stmt = $pdo->prepare("
                                    INSERT IGNORE INTO tams_outlet_dwell_state (member_id, outlet_id, entered_at)
                                    VALUES (:member_id, :outlet_id, :entered_at)
                                ");
                                $enter_stmt->execute([
                                    ':member_id' => $user['id'],
                                    ':outlet_id' => $outletId,
                                    ':entered_at' => $recorded_at,
                                ]);
                                continue;
                            }

                            $enteredAtTs = strtotime($dwell_state_by_outlet[$outletId]);
                            $currentAtTs = strtotime($recorded_at);
                            $elapsedSeconds = ($enteredAtTs !== false && $currentAtTs !== false) ? ($currentAtTs - $enteredAtTs) : 0;

                            if ($elapsedSeconds >= $outletMinDwellSeconds) {
                                confirmOutletVisit($pdo, $user['id'], $outletId, $outlet['name'], $recorded_at, $elapsedSeconds);
                                // Cleared immediately after confirming, rather than left in
                                // place for the rest of the day -- once this Member+outlet+
                                // day is recorded (tams_outlet_visits' own UNIQUE index
                                // guarantees this regardless), there is no reason to keep
                                // re-evaluating elapsed time on every subsequent fix while
                                // the Member simply remains stationary inside the radius.
                                $clear_stmt = $pdo->prepare("DELETE FROM tams_outlet_dwell_state WHERE member_id = :member_id AND outlet_id = :outlet_id");
                                $clear_stmt->execute([':member_id' => $user['id'], ':outlet_id' => $outletId]);
                            }
                        }
                    }
                }

                $pdo->commit();

                // Always HTTP 200/success=true, even when discarded as suspicious
                // (see the GPS sanity filtering comment above).
                echo json_encode([
                    "success" => true,
                    "message" => $isSuspiciousFix
                        ? "Location received but discarded (failed GPS sanity check)."
                        : "Location updated successfully.",
                    "data" => [
                        "user_id" => $user['id'],
                        "latitude" => $isSuspiciousFix ? null : toFloatOrNull($latitude),
                        "longitude" => $isSuspiciousFix ? null : toFloatOrNull($longitude),
                        "is_moving" => $is_moving,
                        "updated_at" => $now_time
                    ]
                ]);
            } catch (Throwable $inner) {
                if ($pdo->inTransaction()) {
                    $pdo->rollBack();
                }
                throw $inner;
            }
            break;

        case '/location/current':
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['admin']);

            $stmt = $pdo->prepare("
                SELECT u.id as user_id, u.name, u.note, u.username,
                       lc.latitude, lc.longitude, lc.accuracy, lc.speed,
                       lc.is_moving, lc.status, lc.updated_at
                FROM tams_users u
                LEFT JOIN tams_live_tracking_current lc ON u.id = lc.user_id
                WHERE u.role = 'member' AND u.is_active = 1
                ORDER BY u.name ASC
            ");
            $stmt->execute();
            $rows = $stmt->fetchAll();

            $now = time();
            $member_locations = [];
            foreach ($rows as $loc) {
                $db_status = $loc['status'] ?? 'offline';
                $updated_at = $loc['updated_at'];

                // Primary signal: the explicit status flag. Secondary safety net:
                // if the device hasn't reported in a while despite claiming
                // online (e.g. killed without notifying the server), treat as offline.
                $effective_online = ($db_status === 'online');
                if ($effective_online) {
                    if (empty($updated_at)) {
                        $effective_online = false;
                    } else {
                        $updated_time = strtotime($updated_at);
                        if (($now - $updated_time) > OFFLINE_STALE_SECONDS) {
                            $effective_online = false;
                        }
                    }
                }

                if ($effective_online) {
                    $member_locations[] = [
                        "user_id" => $loc['user_id'],
                        "name" => $loc['name'],
                        "note" => $loc['note'],
                        "username" => $loc['username'],
                        "latitude" => toFloatOrNull($loc['latitude']),
                        "longitude" => toFloatOrNull($loc['longitude']),
                        "accuracy" => toFloatOrNull($loc['accuracy']),
                        "speed" => toFloatOrNull($loc['speed']),
                        "is_moving" => toIntBool($loc['is_moving'] ?? 0),
                        "updated_at" => $updated_at,
                        "status" => "active"
                    ];
                } else {
                    $member_locations[] = [
                        "user_id" => $loc['user_id'],
                        "name" => $loc['name'],
                        "note" => $loc['note'],
                        "username" => $loc['username'],
                        "latitude" => null,
                        "longitude" => null,
                        "accuracy" => null,
                        "speed" => null,
                        "is_moving" => false,
                        "updated_at" => $updated_at,
                        "status" => "offline"
                    ];
                }
            }

            echo json_encode([
                "success" => true,
                "message" => "Current locations loaded successfully.",
                "data" => $member_locations
            ]);
            break;

        case '/location/history':
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            // Admin can inspect any member's route; Member Dashboard's "Trip
            // Detail" reuses this same endpoint for its own data (enforced below).
            $user = authenticateUser($pdo, ['admin', 'member']);

            $date = isset($_GET['date']) ? trim($_GET['date']) : ''; // Format: YYYY-MM-DD

            // A member's token always forces this to their own id, regardless
            // of what the client sends -- user_id is only honored for admins.
            // This is what prevents a member from reading another member's
            // history by editing a query param; must never be relaxed.
            if ($user['role'] === 'member') {
                $target_user_id = intval($user['id']);
            } else {
                $target_user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;
            }

            if ($target_user_id <= 0 || empty($date) || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) {
                throw new Exception("Parameters user_id and date (YYYY-MM-DD) are required.", 400);
            }

            // A plain BETWEEN range on recorded_at (rather than WHERE
            // DATE(recorded_at) = :target_date) stays sargable against the
            // (user_id, recorded_at) index -- wrapping the column in DATE()
            // would force a row-by-row scan instead of an index seek. Same
            // fix applied to /location/history/dates below.
            $dayStart = $date . ' 00:00:00';
            $dayEnd = $date . ' 23:59:59';
            $stmt = $pdo->prepare("
                SELECT latitude, longitude, accuracy, speed, is_moving, recorded_at
                FROM tams_member_history_locations
                WHERE user_id = :user_id AND recorded_at BETWEEN :day_start AND :day_end
                ORDER BY recorded_at ASC
            ");
            $stmt->execute([
                ':user_id' => $target_user_id,
                ':day_start' => $dayStart,
                ':day_end' => $dayEnd
            ]);
            $raw_points = $stmt->fetchAll();

            $total_points = count($raw_points);
            $total_distance = 0.0;
            $start_time = null;
            $end_time = null;
            $duration_seconds = 0;

            if ($total_points > 0) {
                $start_time = $raw_points[0]['recorded_at'];
                $end_time = $raw_points[$total_points - 1]['recorded_at'];
                $duration_seconds = strtotime($end_time) - strtotime($start_time);

                for ($i = 0; $i < $total_points - 1; $i++) {
                    $total_distance += haversineDistance(
                        floatval($raw_points[$i]['latitude']),
                        floatval($raw_points[$i]['longitude']),
                        floatval($raw_points[$i + 1]['latitude']),
                        floatval($raw_points[$i + 1]['longitude'])
                    );
                }
            }

            // Format duration as readable string (e.g. "02h 15m 30s")
            $hours = floor($duration_seconds / 3600);
            $minutes = floor(($duration_seconds % 3600) / 60);
            $seconds = $duration_seconds % 60;
            $duration_formatted = sprintf('%02dh %02dm %02ds', $hours, $minutes, $seconds);

            $points = array_map(function ($p) {
                return [
                    "latitude" => toFloatOrNull($p['latitude']),
                    "longitude" => toFloatOrNull($p['longitude']),
                    "accuracy" => toFloatOrNull($p['accuracy']),
                    "speed" => toFloatOrNull($p['speed']),
                    "is_moving" => toIntBool($p['is_moving'] ?? 0),
                    "recorded_at" => $p['recorded_at']
                ];
            }, $raw_points);

            echo json_encode([
                "success" => true,
                "message" => "History logs loaded.",
                "data" => [
                    "user_id" => $target_user_id,
                    "date" => $date,
                    "total_points" => $total_points,
                    "total_distance_km" => round($total_distance, 3),
                    "start_time" => $start_time,
                    "end_time" => $end_time,
                    "duration_seconds" => $duration_seconds,
                    "duration_formatted" => $duration_formatted,
                    "points" => $points
                ]
            ]);
            break;

        // Lightweight companion to /location/history: for the Admin's date
        // picker, answers "which dates this month have GPS data" -- just a
        // list of date strings, no points or per-day stats.
        case '/location/history/dates':
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['admin']);

            $target_user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;
            $month = isset($_GET['month']) ? trim($_GET['month']) : ''; // Format: YYYY-MM

            if ($target_user_id <= 0 || empty($month) || !preg_match('/^\d{4}-\d{2}$/', $month)) {
                throw new Exception("Parameters user_id and month (YYYY-MM) are required.", 400);
            }

            // Same sargable-BETWEEN reasoning as /location/history above.
            $monthStartDate = DateTime::createFromFormat('Y-m-d', $month . '-01');
            if ($monthStartDate === false) {
                throw new Exception("Invalid month format.", 400);
            }
            $monthEndDate = clone $monthStartDate;
            $monthEndDate->modify('last day of this month');
            $monthStart = $monthStartDate->format('Y-m-d') . ' 00:00:00';
            $monthEnd = $monthEndDate->format('Y-m-d') . ' 23:59:59';

            $stmt = $pdo->prepare("
                SELECT DISTINCT DATE(recorded_at) AS d
                FROM tams_member_history_locations
                WHERE user_id = :user_id
                  AND recorded_at BETWEEN :month_start AND :month_end
                ORDER BY d ASC
            ");
            $stmt->execute([
                ':user_id' => $target_user_id,
                ':month_start' => $monthStart,
                ':month_end' => $monthEnd
            ]);
            $dates = array_map(fn($row) => $row['d'], $stmt->fetchAll());

            echo json_encode([
                "success" => true,
                "message" => "History dates loaded.",
                "data" => [
                    "user_id" => $target_user_id,
                    "month" => $month,
                    "dates" => $dates
                ]
            ]);
            break;

        case '/member/list':
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['admin']);

            // Not filtered by "currently online" -- admins need to pull
            // history for members regardless of whether they're tracking now.
            $stmt = $pdo->prepare("
                SELECT id, name, note, username, role, is_active
                FROM tams_users
                WHERE role = 'member' AND is_active = 1
                ORDER BY name ASC
            ");
            $stmt->execute();
            $member_list = $stmt->fetchAll();

            echo json_encode([
                "success" => true,
                "message" => "Member roster loaded.",
                "data" => $member_list
            ]);
            break;

        // --- Outlet Management -----------------------------------------------------
        // Approve/reject/merge are deliberately NOT routes here -- those are
        // Admin-only actions, and the Admin Panel never calls this Backend API (see
        // root CLAUDE.md); they are direct-DB-write ajax/outlet_*.php endpoints on
        // the Web Admin side instead. Everything below is what the Android app (a
        // Member) is allowed to do for its own outlets.

        case '/outlet/create':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['member']);
            $payload = getJsonPayload();
            [$name, $address, $latitude, $longitude] = validateOutletPayload($payload);

            // A Member-registered outlet is assigned only to themselves --
            // member_id is set to the creating Member's own id directly on this
            // single INSERT (one-to-one from the outlet's side; see
            // tams_outlets.member_id's own schema.sql comment). Admin-side
            // creation (Web Admin's ajax/outlet_create.php) is the only place an
            // outlet can be assigned to a Member other than its creator.
            $insert_stmt = $pdo->prepare("
                INSERT INTO tams_outlets (created_by_user_id, member_id, name, address, latitude, longitude, status)
                VALUES (:created_by, :member_id, :name, :address, :latitude, :longitude, 'PENDING')
            ");
            $insert_stmt->execute([
                ':created_by' => $user['id'],
                ':member_id' => $user['id'],
                ':name' => $name,
                ':address' => $address,
                ':latitude' => $latitude,
                ':longitude' => $longitude,
            ]);
            $outletId = (int) $pdo->lastInsertId();

            echo json_encode([
                "success" => true,
                "message" => "Outlet submitted, waiting for Admin approval.",
                "data" => [
                    "id" => $outletId,
                    "name" => $name,
                    "address" => $address,
                    "latitude" => $latitude,
                    "longitude" => $longitude,
                    "status" => "PENDING"
                ]
            ]);
            break;

        case '/outlet/list':
            if ($method !== 'GET') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['member']);

            // The outlet(s) assigned to this Member (tams_outlets.member_id,
            // one-to-one from the outlet's side), regardless of who created
            // them -- an Admin-assigned outlet appears here exactly like a
            // self-registered one. Excludes soft-deleted/merged outlets --
            // once gone, they no longer belong in the Member's own active list
            // (their past visits still exist in tams_outlet_visits regardless,
            // untouched by this filter).
            //
            // Ordered by the Member's own manual display_order first (see that
            // column's schema.sql comment), created_at DESC as the tie-break --
            // every outlet ties at display_order=0 until this Member has used
            // POST /outlet/reorder at least once, so this is the exact same
            // newest-first order the app has always shown until a Member
            // actually drags something.
            //
            // has_pending_edit is derived here via EXISTS, never stored -- see
            // tams_outlets.status's own schema.sql comment on why an outlet's
            // lifecycle status is never repurposed to signal this.
            //
            // last_edit_rejection_reason surfaces tams_outlet_edit_requests'
            // own rejection_reason (distinct from o.rejection_reason, which
            // only ever describes the OUTLET's own PENDING->REJECTED
            // transition, never an edit request's -- see /outlet/reject's
            // comment: rejecting an edit request never touches tams_outlets).
            // Without this, a Member whose proposed edit to an APPROVED
            // outlet gets rejected saw has_pending_edit quietly flip back to
            // false with no visible reason anywhere -- found during final
            // validation, fixed here rather than left as a silent gap, since
            // an outlet-level rejection already surfaces its reason the same
            // way. Picks the single MOST RECENT edit request for this outlet
            // (ORDER BY id DESC, any status) and only returns its reason when
            // that latest one is REJECTED -- so a later APPROVED resubmission
            // correctly stops showing an older, superseded rejection. Same
            // per-Member-outlet-count cost profile as the has_pending_edit
            // EXISTS subquery above, not a table scan.
            $stmt = $pdo->prepare("
                SELECT
                    o.id, o.name, o.address, o.latitude, o.longitude, o.status,
                    o.rejection_reason, o.created_by_user_id, o.created_at,
                    EXISTS (
                        SELECT 1 FROM tams_outlet_edit_requests er
                        WHERE er.outlet_id = o.id AND er.status = 'PENDING'
                    ) AS has_pending_edit,
                    (
                        SELECT CASE WHEN er2.status = 'REJECTED' THEN er2.rejection_reason ELSE NULL END
                        FROM tams_outlet_edit_requests er2
                        WHERE er2.outlet_id = o.id
                        ORDER BY er2.id DESC
                        LIMIT 1
                    ) AS last_edit_rejection_reason
                FROM tams_outlets o
                WHERE o.member_id = :member_id
                  AND o.deleted_at IS NULL
                ORDER BY o.display_order ASC, o.created_at DESC
            ");
            $stmt->execute([':member_id' => $user['id']]);
            $rows = $stmt->fetchAll();

            $outlets = array_map(function ($row) use ($user) {
                return [
                    "id" => (int) $row['id'],
                    "name" => $row['name'],
                    "address" => $row['address'],
                    "latitude" => toFloatOrNull($row['latitude']),
                    "longitude" => toFloatOrNull($row['longitude']),
                    "status" => $row['status'],
                    "rejection_reason" => $row['rejection_reason'],
                    "has_pending_edit" => toIntBool($row['has_pending_edit']),
                    "last_edit_rejection_reason" => $row['last_edit_rejection_reason'],
                    // Android needs this to know whether it created the row itself
                    // (editable/deletable, per /outlet/update and /outlet/delete's
                    // own ownership checks) or was merely assigned to an
                    // Admin-created outlet.
                    "is_own_outlet" => ((int) $row['created_by_user_id']) === (int) $user['id'],
                    "created_at" => $row['created_at']
                ];
            }, $rows);

            echo json_encode([
                "success" => true,
                "message" => "Outlets loaded.",
                "data" => $outlets
            ]);
            break;

        case '/outlet/update':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['member']);
            $payload = getJsonPayload();

            $outletId = isset($payload['id']) ? (int) $payload['id'] : 0;
            if ($outletId <= 0) {
                throw new Exception("A valid outlet id is required.", 400);
            }
            [$name, $address, $latitude, $longitude] = validateOutletPayload($payload);

            $pdo->beginTransaction();
            try {
                // Locks the outlet row for the remainder of this transaction --
                // required so a concurrent submission of a second edit (e.g. a
                // network retry racing itself) can never create two open edit
                // requests for the same outlet; the second call simply waits for
                // the first to commit, then sees its own effect and supersedes it
                // in place rather than duplicating it (see the PENDING-edit-request
                // branch below).
                //
                // Scoped to created_by_user_id = this Member -- ownership of
                // edit/delete rights follows who created the row, never
                // member_id, so an Admin-created outlet can never be edited by
                // the Member it happens to be assigned to (see
                // tams_outlets.created_by_user_id's own schema.sql comment).
                $lock_stmt = $pdo->prepare("
                    SELECT id, status FROM tams_outlets
                    WHERE id = :id AND created_by_user_id = :user_id AND deleted_at IS NULL
                    FOR UPDATE
                ");
                $lock_stmt->execute([':id' => $outletId, ':user_id' => $user['id']]);
                $outlet = $lock_stmt->fetch();

                if (!$outlet) {
                    throw new Exception("Outlet not found.", 404);
                }

                if ($outlet['status'] === 'PENDING' || $outlet['status'] === 'REJECTED') {
                    // No live/approved data to protect yet -- edit in place.
                    // Resubmitting a REJECTED outlet sends it back to PENDING for a
                    // fresh review, clearing the old reason.
                    $update_stmt = $pdo->prepare("
                        UPDATE tams_outlets
                        SET name = :name, address = :address, latitude = :latitude, longitude = :longitude,
                            status = 'PENDING', rejection_reason = NULL
                        WHERE id = :id
                    ");
                    $update_stmt->execute([
                        ':name' => $name, ':address' => $address,
                        ':latitude' => $latitude, ':longitude' => $longitude,
                        ':id' => $outletId,
                    ]);
                    $resultStatus = 'PENDING';
                } else {
                    // APPROVED: tams_outlets itself is left untouched -- the live
                    // name/address/latitude/longitude that /location/update's own
                    // geofencing hook reads stays exactly what it already was, for
                    // as long as this request sits unreviewed. See
                    // tams_outlet_edit_requests' own schema.sql comment for the
                    // full rationale.
                    $existing_stmt = $pdo->prepare("
                        SELECT id FROM tams_outlet_edit_requests
                        WHERE outlet_id = :outlet_id AND status = 'PENDING'
                        LIMIT 1
                    ");
                    $existing_stmt->execute([':outlet_id' => $outletId]);
                    $existing = $existing_stmt->fetch();

                    if ($existing) {
                        // Supersede in place -- the Member is refining an edit
                        // Admin hasn't reviewed yet, not opening a second,
                        // competing request. tams_outlet_edit_requests is workflow
                        // state, not a ledger -- updating an in-flight PENDING row
                        // is fine (unlike tams_outlet_visits, nothing here is
                        // meant to be immutable history yet).
                        $replace_stmt = $pdo->prepare("
                            UPDATE tams_outlet_edit_requests
                            SET proposed_name = :name, proposed_address = :address,
                                proposed_latitude = :latitude, proposed_longitude = :longitude,
                                created_at = CURRENT_TIMESTAMP
                            WHERE id = :id
                        ");
                        $replace_stmt->execute([
                            ':name' => $name, ':address' => $address,
                            ':latitude' => $latitude, ':longitude' => $longitude,
                            ':id' => $existing['id'],
                        ]);
                    } else {
                        $insert_stmt = $pdo->prepare("
                            INSERT INTO tams_outlet_edit_requests
                                (outlet_id, requested_by_user_id, proposed_name, proposed_address, proposed_latitude, proposed_longitude)
                            VALUES
                                (:outlet_id, :user_id, :name, :address, :latitude, :longitude)
                        ");
                        $insert_stmt->execute([
                            ':outlet_id' => $outletId, ':user_id' => $user['id'],
                            ':name' => $name, ':address' => $address,
                            ':latitude' => $latitude, ':longitude' => $longitude,
                        ]);
                    }
                    // The outlet's own status is deliberately left untouched
                    // (still APPROVED) -- see this route's comment above.
                    $resultStatus = 'APPROVED';
                }

                $pdo->commit();
            } catch (Throwable $inner) {
                if ($pdo->inTransaction()) {
                    $pdo->rollBack();
                }
                throw $inner;
            }

            echo json_encode([
                "success" => true,
                "message" => $resultStatus === 'APPROVED'
                    ? "Change submitted, awaiting Admin approval. The outlet's current data remains unchanged until approved."
                    : "Outlet updated.",
                "data" => [
                    "id" => $outletId,
                    "status" => $resultStatus
                ]
            ]);
            break;

        case '/outlet/delete':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['member']);
            $payload = getJsonPayload();
            $outletId = isset($payload['id']) ? (int) $payload['id'] : 0;

            if ($outletId <= 0) {
                throw new Exception("A valid outlet id is required.", 400);
            }

            // Hard delete -- safe only because a PENDING or REJECTED outlet has
            // never been APPROVED, so it structurally cannot have any
            // tams_outlet_visits rows referencing it (visits only ever confirm
            // against APPROVED outlets -- see /location/update's geofencing
            // hook). An APPROVED outlet's deletion is an Admin-only action (Web
            // Admin's own ajax/outlet_delete.php) and is always a SOFT delete
            // instead, since visit history may exist for it -- see
            // tams_outlets.deleted_at's schema.sql comment.
            $delete_stmt = $pdo->prepare("
                DELETE FROM tams_outlets
                WHERE id = :id AND created_by_user_id = :user_id
                  AND status IN ('PENDING', 'REJECTED')
            ");
            $delete_stmt->execute([':id' => $outletId, ':user_id' => $user['id']]);

            if ($delete_stmt->rowCount() === 0) {
                // rowCount() = 0 could mean "not found", "not yours", or
                // "already APPROVED" (not deletable by a Member) -- same
                // distinguish-with-a-follow-up-read pattern
                // members_force_toggle.php already uses.
                $check_stmt = $pdo->prepare("
                    SELECT status FROM tams_outlets WHERE id = :id AND created_by_user_id = :user_id
                ");
                $check_stmt->execute([':id' => $outletId, ':user_id' => $user['id']]);
                $existing = $check_stmt->fetch();
                if (!$existing) {
                    throw new Exception("Outlet not found.", 404);
                }
                throw new Exception("Only a PENDING or REJECTED outlet can be deleted by its creator.", 409);
            }

            echo json_encode([
                "success" => true,
                "message" => "Outlet deleted.",
                "data" => null
            ]);
            break;

        case '/outlet/reorder':
            if ($method !== 'POST') {
                throw new Exception("Method Not Allowed", 405);
            }
            $user = authenticateUser($pdo, ['member']);
            $payload = getJsonPayload();

            $rawIds = $payload['outlet_ids'] ?? null;
            if (!is_array($rawIds) || count($rawIds) === 0) {
                throw new Exception("outlet_ids must be a non-empty array.", 400);
            }
            if (count($rawIds) > OUTLET_REORDER_MAX_IDS) {
                throw new Exception("outlet_ids exceeds the maximum of " . OUTLET_REORDER_MAX_IDS . " entries.", 400);
            }

            // Sanitize to distinct positive ints, preserving the submitted
            // (i.e. the Member's desired) order. A duplicate or non-numeric
            // entry is silently dropped rather than rejecting the whole
            // request -- the client always resubmits its own full current
            // list, so a malformed single entry shouldn't block the rest of
            // an otherwise-valid reorder.
            $orderedIds = [];
            foreach ($rawIds as $rawId) {
                $id = (int) $rawId;
                if ($id > 0 && !in_array($id, $orderedIds, true)) {
                    $orderedIds[] = $id;
                }
            }
            if (count($orderedIds) === 0) {
                throw new Exception("outlet_ids must contain at least one valid id.", 400);
            }

            $pdo->beginTransaction();
            try {
                // Scoped to member_id, deliberately NOT created_by_user_id --
                // this endpoint only ever changes display_order (a personal
                // view preference), never the outlet's own data, so unlike
                // /outlet/update and /outlet/delete above, a Member reordering
                // an Admin-assigned outlet they can only view (is_own_outlet
                // false, see GET /outlet/list) is fully allowed here. Locks
                // every row this Member could legitimately reorder before
                // writing, so a concurrent duplicate submission (e.g. a fast
                // double-drag, or a network retry racing itself) can't
                // interleave two different final orders.
                // ORDER BY id ASC matches ajax/outlet_merge.php's own
                // convention for locking multiple rows: always ascending by
                // id, so two overlapping concurrent reorders always
                // acquire their locks in the same relative order instead of
                // potentially deadlocking against each other.
                $placeholders = implode(',', array_fill(0, count($orderedIds), '?'));
                $lock_stmt = $pdo->prepare("
                    SELECT id FROM tams_outlets
                    WHERE member_id = ? AND deleted_at IS NULL AND id IN ($placeholders)
                    ORDER BY id ASC
                    FOR UPDATE
                ");
                $lock_stmt->execute(array_merge([$user['id']], $orderedIds));
                $ownIds = array_flip(array_map('intval', array_column($lock_stmt->fetchAll(), 'id')));

                // Any id in the submitted array that doesn't currently belong
                // to this Member (stale client state from before an Admin
                // reassigned it away, or a tampered payload) is silently
                // skipped rather than failing the whole batch -- positions
                // are assigned only to ids this Member can actually see,
                // 0..N-1 with no gaps, in the order submitted.
                $update_stmt = $pdo->prepare("
                    UPDATE tams_outlets SET display_order = :position
                    WHERE id = :id AND member_id = :member_id
                ");
                $position = 0;
                foreach ($orderedIds as $id) {
                    if (isset($ownIds[$id])) {
                        $update_stmt->execute([
                            ':position' => $position,
                            ':id' => $id,
                            ':member_id' => $user['id'],
                        ]);
                        $position++;
                    }
                }

                // Every submitted id was stale/foreign (none belonged to
                // this Member) -- nothing was actually written. Report this
                // as a failure rather than a silent no-op "success", so the
                // Android client's failure path re-syncs from the server
                // instead of trusting an optimistic order that never saved.
                if ($position === 0) {
                    throw new Exception("None of the submitted outlet_ids belong to this member.", 400);
                }

                $pdo->commit();
            } catch (Throwable $inner) {
                if ($pdo->inTransaction()) {
                    $pdo->rollBack();
                }
                throw $inner;
            }

            echo json_encode([
                "success" => true,
                "message" => "Outlet order saved.",
                "data" => null
            ]);
            break;

        default:
            throw new Exception("Route not found.", 404);
    }
} catch (PDOException $e) {
    // Never leak raw DB error text (schema, table names, SQL) to clients.
    error_log("[TAMS API] PDOException on route {$route}: " . $e->getMessage());
    http_response_code(500);
    echo json_encode([
        "success" => false,
        "message" => "Internal server error. Please try again later.",
        "data" => null
    ]);
} catch (Exception $e) {
    $code = $e->getCode();
    if (!is_numeric($code) || $code < 100 || $code > 599) {
        $code = 500;
    }
    http_response_code($code);
    echo json_encode([
        "success" => false,
        "message" => $e->getMessage(),
        "data" => null
    ]);
} catch (Throwable $e) {
    // Last-resort catch-all (e.g. TypeError) so the client always gets a
    // well-formed JSON error response instead of a raw PHP fatal error page.
    error_log("[TAMS API] Unhandled {$route}: " . $e->getMessage());
    http_response_code(500);
    echo json_encode([
        "success" => false,
        "message" => "Internal server error. Please try again later.",
        "data" => null
    ]);
}
