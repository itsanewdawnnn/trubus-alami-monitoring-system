<?php
/**
 * POST endpoint: toggles the Force Override for a single Member
 * (tams_users.force_tracking_hours). ON lets that Member run Start Location
 * any time; OFF (default) restricts them to backend/api.php's
 * TRACKING_HOUR_START..TRACKING_HOUR_END window. See database/schema.sql's
 * tams_users comment for the full column doc.
 *
 * Direct DB write, not a call to backend/api.php -- the Admin Panel never
 * calls the Backend API (see root CLAUDE.md). A single atomic UPDATE is the
 * entire revocation mechanism: there is no separate "push a stop signal to
 * the device" step. backend/api.php's POST /location/update re-reads this
 * column fresh on every single location fix (not just at Start), so turning
 * Force OFF here takes effect on that Member's very next fix -- if they're
 * still tracking outside the operational-hours window at that point, the
 * server rejects the fix and the Android app automatically stops its own
 * foreground service in response (see MemberLocationService.handleNewLocation's
 * doc comment). No re-login, app restart, or manual Stop press is needed on
 * the Member's side either way.
 *
 * Body: { id, enabled: bool }
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();
csrf_require_ajax();

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    $body = [];
}

$id = isset($body['id']) ? (int) $body['id'] : 0;
$enabled = !empty($body['enabled']);

if ($id <= 0) {
    json_response(['success' => false, 'message' => 'Invalid member.'], 422);
}

// role = 'member' scoping prevents toggling this for an Admin account via a
// tampered id, same defensive pattern as members_delete.php.
$stmt = $pdo->prepare("UPDATE tams_users SET force_tracking_hours = :enabled WHERE id = :id AND role = 'member'");
$stmt->execute([
    'enabled' => $enabled ? 1 : 0,
    'id' => $id,
]);

if ($stmt->rowCount() === 0) {
    // Could also mean "already at that value" -- confirm the member exists
    // at all before reporting a hard failure.
    $check = $pdo->prepare("SELECT id FROM tams_users WHERE id = :id AND role = 'member' LIMIT 1");
    $check->execute(['id' => $id]);
    if (!$check->fetch()) {
        json_response(['success' => false, 'message' => 'Member not found.'], 404);
    }
}

json_response([
    'success' => true,
    'message' => $enabled
        ? 'Force Override enabled -- this Member can now run Start Location at any time.'
        : 'Force Override disabled -- this Member is restricted to the standard operational hours again.',
    'enabled' => $enabled,
]);
