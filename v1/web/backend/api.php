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
                            (user_id, latitude, longitude, accuracy, speed, is_moving, status, updated_at, recorded_at)
                        VALUES
                            (:user_id, NULL, NULL, NULL, NULL, 0, 'offline', NULL, NULL)
                        ON DUPLICATE KEY UPDATE
                            latitude = NULL,
                            longitude = NULL,
                            accuracy = NULL,
                            speed = NULL,
                            is_moving = 0,
                            status = 'offline',
                            updated_at = NULL,
                            recorded_at = NULL
                    ");
                    $upsert_stmt->execute([':user_id' => $user['id']]);

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
                    // well under this bound.
                    if ($derived_speed > GPS_JUMP_MAX_SPEED_MPS) {
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
                // already stored. History is unaffected: every fix is still appended to
                // tams_member_history_locations and read back ORDER BY recorded_at ASC.
                $applyToCurrent = true;
                if ($previous && !empty($previous['recorded_at'])) {
                    $prevRecordedAt = strtotime($previous['recorded_at']);
                    $incomingRecordedAt = strtotime($recorded_at);
                    if ($prevRecordedAt !== false && $incomingRecordedAt !== false && $incomingRecordedAt < $prevRecordedAt) {
                        $applyToCurrent = false;
                    }
                }
                // A suspicious fix must never become the "current" position either.
                $applyToCurrent = $applyToCurrent && !$isSuspiciousFix;

                if ($applyToCurrent) {
                    $upsert_stmt = $pdo->prepare("
                        INSERT INTO tams_live_tracking_current
                            (user_id, latitude, longitude, accuracy, speed, is_moving, status, updated_at, recorded_at)
                        VALUES
                            (:user_id, :latitude, :longitude, :accuracy, :speed, :is_moving, 'online', :now_time, :recorded_at)
                        ON DUPLICATE KEY UPDATE
                            latitude = VALUES(latitude),
                            longitude = VALUES(longitude),
                            accuracy = VALUES(accuracy),
                            speed = VALUES(speed),
                            is_moving = VALUES(is_moving),
                            status = 'online',
                            updated_at = VALUES(updated_at),
                            recorded_at = VALUES(recorded_at)
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
                        INSERT IGNORE INTO tams_member_history_locations (user_id, latitude, longitude, accuracy, speed, is_moving, recorded_at, created_at)
                        VALUES (:user_id, :latitude, :longitude, :accuracy, :speed, :is_moving, :recorded_at, :now_time_hist)
                    ");
                    $hist_stmt->execute([
                        ':user_id' => $user['id'],
                        ':latitude' => $latitude,
                        ':longitude' => $longitude,
                        ':accuracy' => $accuracy,
                        ':speed' => $speed,
                        ':is_moving' => $is_moving ? 1 : 0,
                        ':recorded_at' => $recorded_at,
                        ':now_time_hist' => $now_time
                    ]);
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
