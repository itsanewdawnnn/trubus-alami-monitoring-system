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
           lc.is_moving, lc.status, lc.updated_at
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
        ];
    }
}

json_response(['success' => true, 'data' => $members]);
