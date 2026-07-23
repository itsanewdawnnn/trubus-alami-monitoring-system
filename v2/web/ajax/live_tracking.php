<?php
/**
 * GET endpoint: current location + online/offline status for every enabled
 * Member. Powers both pages/live_tracking.php panels from a single call.
 *
 * Status logic mirrors backend/api.php's `/location/current` route
 * exactly (same OFFLINE_STALE_SECONDS fallback) -- this panel and the
 * Android app must agree on what "online" means for the same row.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(['success' => false, 'message' => 'Method not allowed.'], 405);
}

$stmt = $pdo->query("
    SELECT u.id AS user_id, u.name, u.note, u.username,
           lc.latitude, lc.longitude, lc.accuracy, lc.speed,
           lc.is_moving, lc.status, lc.updated_at,
           lc.is_mock_location, lc.gnss_satellites_used
    FROM tams_users u
    LEFT JOIN tams_live_tracking_current lc ON u.id = lc.user_id
    WHERE u.role = 'member' AND u.is_active = 1
    ORDER BY u.name ASC
");
$rows = $stmt->fetchAll();

$now = time();
$members = [];
foreach ($rows as $row) {
    $dbStatus = $row['status'] ?? 'offline';
    $updatedAt = $row['updated_at'];

    // The status flag is primary; a stale updated_at overrides a stuck "online".
    $effectiveOnline = ($dbStatus === 'online');
    if ($effectiveOnline) {
        if (empty($updatedAt)) {
            $effectiveOnline = false;
        } elseif ((time() - strtotime($updatedAt)) > OFFLINE_STALE_SECONDS) {
            $effectiveOnline = false;
        }
    }

    if ($effectiveOnline) {
        $members[] = [
            'user_id' => (int) $row['user_id'],
            'name' => $row['name'],
            'note' => $row['note'],
            'username' => $row['username'],
            'latitude' => $row['latitude'] !== null ? (float) $row['latitude'] : null,
            'longitude' => $row['longitude'] !== null ? (float) $row['longitude'] : null,
            'accuracy' => $row['accuracy'] !== null ? (float) $row['accuracy'] : null,
            'speed' => $row['speed'] !== null ? (float) $row['speed'] : null,
            'is_moving' => ((int) ($row['is_moving'] ?? 0)) === 1,
            'updated_at' => $updatedAt,
            'status' => 'active',
            // Advisory mock-location trust signals (see schema.sql's
            // "Mock-location trust signals" comment) -- purely informational
            // for Admin review, never affects online/offline status or
            // anything else above. null means "unknown" (older app version,
            // or a fix predating this feature), not "confirmed genuine".
            'is_mock_location' => $row['is_mock_location'] !== null ? ((int) $row['is_mock_location']) === 1 : null,
            'gnss_satellites_used' => $row['gnss_satellites_used'] !== null ? (int) $row['gnss_satellites_used'] : null,
        ];
    } else {
        $members[] = [
            'user_id' => (int) $row['user_id'],
            'name' => $row['name'],
            'note' => $row['note'],
            'username' => $row['username'],
            'latitude' => null,
            'longitude' => null,
            'accuracy' => null,
            'speed' => null,
            'is_moving' => false,
            'updated_at' => $updatedAt,
            'status' => 'offline',
            // Same reasoning as coordinates/accuracy/speed above -- cleared
            // out when a member is offline rather than showing a stale
            // signal from before they went offline.
            'is_mock_location' => null,
            'gnss_satellites_used' => null,
        ];
    }
}

json_response(['success' => true, 'data' => $members]);
