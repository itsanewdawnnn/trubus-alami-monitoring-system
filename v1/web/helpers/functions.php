<?php
/**
 * Small helper functions shared across the admin panel. Plain functions,
 * no framework -- appropriate for a project this size.
 */

/**
 * Escapes a value for safe HTML output. The only XSS defense used in this
 * project, so every user- or database-sourced value must go through it.
 */
function e(?string $value): string
{
    return htmlspecialchars($value ?? '', ENT_QUOTES, 'UTF-8');
}

function redirect(string $path): never
{
    header('Location: ' . $path);
    exit;
}

/**
 * Reads a $_POST field, trimmed, collapsing empty strings to null.
 */
function input(string $key, ?array $source = null): ?string
{
    $source = $source ?? $_POST;
    if (!isset($source[$key])) {
        return null;
    }
    $trimmed = trim((string) $source[$key]);
    return $trimmed === '' ? null : $trimmed;
}

/**
 * Great-circle distance in kilometers. Must stay identical to
 * backend/api.php's haversineDistance() -- ajax/history.php
 * recomputes the same "distance traveled" figure the Android app shows,
 * and the two need to agree on the same trip.
 */
function haversine_distance_km(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $earthRadius = 6371.0;
    $dLat = deg2rad($lat2 - $lat1);
    $dLon = deg2rad($lon2 - $lon1);
    $a = sin($dLat / 2) * sin($dLat / 2)
        + cos(deg2rad($lat1)) * cos(deg2rad($lat2)) * sin($dLon / 2) * sin($dLon / 2);
    $c = 2 * atan2(sqrt($a), sqrt(1 - $a));
    return $earthRadius * $c;
}

/**
 * Sends a {success, message, ...} JSON response and stops execution.
 * Every ajax/*.php endpoint uses this so the response shape stays consistent.
 *
 * Explicit no-cache headers (not just relying on session.cache_limiter's
 * default, which varies by hosting/php.ini) -- without this, a browser can
 * serve a stale cached GET response (e.g. ajax/members_list.php) after a
 * page refresh, showing an out-of-date Force Override toggle state even
 * though the database is already correct. GET and POST endpoints alike get
 * this: nothing under ajax/*.php is ever meant to be cached, since every
 * response reflects live, frequently-changing DB state.
 */
function json_response(array $payload, int $statusCode = 200): never
{
    http_response_code($statusCode);
    header('Content-Type: application/json; charset=UTF-8');
    header('Cache-Control: no-store, no-cache, must-revalidate');
    header('Pragma: no-cache');
    echo json_encode($payload, JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * Appends a `?v=<mtime>` cache-busting query string to a local CSS/JS path,
 * so browsers/proxies re-fetch it after a deploy without a manual version
 * bump. Falls back to no query string if the file can't be stat'd.
 */
function asset_version(string $relativePath): string
{
    $absolutePath = __DIR__ . '/../' . $relativePath;
    $mtime = @filemtime($absolutePath);
    return $mtime !== false ? ('?v=' . $mtime) : '';
}

/**
 * Single source of truth for every Remote Management setting -- both
 * pages/remote_management.php (renders the form) and
 * ajax/remote_management_save.php (validates + persists it) read this same
 * list instead of duplicating
 * labels/bounds/defaults. Backed 1:1 by tams_remote_management rows, keyed by
 * config_key.
 *
 * 'default' here must stay numerically equal to the matching fallback
 * constant in android/.../data/remote/RemoteConfigRepository.kt and to
 * database/schema.sql's seed INSERT -- all three describe "what the app
 * does if Remote Management has never been touched or is unreachable."
 *
 * IMPORTANT: this table is served with no authentication at all via
 * backend/api.php's GET /app/config route. Never add a setting here that
 * is sensitive/security-relevant (tokens, secrets, credentials, internal
 * URLs, etc.) -- Remote Management is for safe, cosmetic/behavioral
 * tuning only.
 */
function remote_management_definitions(): array
{
    return [
        'gps_interval_seconds' => [
            'label' => 'GPS Update Interval (seconds)',
            'hint' => 'How often the Android app requests a GPS fix while tracking is active. Lower = more accurate/battery-hungry, higher = more battery-friendly.',
            'default' => 10,
            'min' => 5,
            'max' => 300,
        ],
        'sync_interval_minutes' => [
            'label' => 'Sync Interval (minutes)',
            'hint' => 'How often the Android app pushes queued location points to the server in the background.',
            'default' => 3,
            'min' => 1,
            'max' => 60,
        ],
        'password_min_length' => [
            'label' => 'Minimum Password Length',
            'hint' => 'Shortest password allowed when a Member or Admin sets/changes a password, enforced by the Admin Panel and the Backend API alike.',
            'default' => 4,
            'min' => 4,
            'max' => 50,
        ],
        'password_max_length' => [
            'label' => 'Maximum Password Length',
            'hint' => 'Longest password allowed. Must never exceed 255 -- tams_users.password is a VARCHAR(255) column, and a longer value would either be silently truncated or hard-fail the write.',
            'default' => 255,
            'min' => 8,
            'max' => 255,
        ],
    ];
}

/**
 * Reads one or more Remote Management values from tams_remote_management,
 * returned as [config_key => int value]. Falls back to
 * remote_management_definitions()'s own 'default' -- never a separately
 * hardcoded literal -- for any key with no stored row yet, or whose stored
 * value is malformed/out of that definition's min/max range (a tampered or
 * manually-edited row must never make validation silently stricter/looser
 * than intended). Mirrors the fail-safe philosophy already used by
 * Android's RemoteConfigRepository and backend/api.php's own copy of this
 * same pattern (see that file's getRemoteManagementInt()).
 *
 * Used instead of the old hardcoded PASSWORD_MIN_LENGTH/PASSWORD_MAX_LENGTH
 * constants (formerly database/validation_rules.php, now removed) by
 * ajax/members_save.php and ajax/profile_update.php.
 */
function remote_management_values(PDO $pdo, array $keys): array
{
    $definitions = remote_management_definitions();
    $result = [];
    foreach ($keys as $key) {
        $result[$key] = $definitions[$key]['default'] ?? null;
    }
    if (empty($keys)) {
        return $result;
    }

    $placeholders = implode(',', array_fill(0, count($keys), '?'));
    $stmt = $pdo->prepare("SELECT config_key, config_value FROM tams_remote_management WHERE config_key IN ($placeholders)");
    $stmt->execute(array_values($keys));

    foreach ($stmt->fetchAll() as $row) {
        $def = $definitions[$row['config_key']] ?? null;
        $value = filter_var($row['config_value'], FILTER_VALIDATE_INT);
        if ($def !== null && ($value === false || $value < $def['min'] || $value > $def['max'])) {
            continue; // Out of range/malformed -- keep the default already seeded above.
        }
        if ($value !== false) {
            $result[$row['config_key']] = $value;
        }
    }

    return $result;
}

/**
 * Single source of truth for the Member Log feature's action_type values --
 * used by pages/member_log.php (filter dropdown) and
 * ajax/member_log_list.php (filter whitelist, same rationale as
 * members_list.php's $sortMap: a raw $_GET-sourced string is never
 * interpolated into SQL or trusted as-is).
 *
 * Must stay in sync with backend/api.php's POST /activity/log
 * $allowedActionTypes -- that is the only writer of tams_member_log, this
 * is only a reader/display concern, so the two lists intentionally live in
 * their own independent PHP apps rather than sharing a file (see root
 * CLAUDE.md: Admin Panel and Backend API are independent apps that only
 * share the database).
 */
function member_log_action_types(): array
{
    return [
        'profile_update' => 'Profile Update',
        'start_location' => 'Start Location',
        'stop_location' => 'Stop Location',
        'sync_failed' => 'Sync Failed',
        'error' => 'Error',
    ];
}
