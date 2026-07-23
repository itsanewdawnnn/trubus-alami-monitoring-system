<?php
/**
 * POST endpoint: deletes a single Member (tams_users row, role='member').
 * Confirmation happens in the browser (members.js); this just performs the delete.
 *
 * Body: { id }
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();
csrf_require_ajax();

$body = json_decode(file_get_contents('php://input'), true);
$id = isset($body['id']) ? (int) $body['id'] : 0;

if ($id <= 0) {
    json_response(['success' => false, 'message' => 'Invalid member.'], 422);
}

// role = 'member' scoping prevents deleting an Admin account via a tampered id.
try {
    $stmt = $pdo->prepare("DELETE FROM tams_users WHERE id = :id AND role = 'member'");
    $stmt->execute(['id' => $id]);
} catch (PDOException $e) {
    // 23000 = a foreign key constraint blocked the delete -- since the
    // Outlet feature, this is expected whenever the Member created an
    // outlet (tams_outlets.created_by_user_id), owns one
    // (tams_outlets.member_id), or has visit history
    // (tams_outlet_visits.member_id), all ON DELETE RESTRICT by design
    // (see schema.sql's comments on each). A friendly, actionable message
    // instead of a raw DB error, same idiom as ajax/members_save.php's own
    // 23000 catch for a duplicate username.
    if ($e->getCode() === '23000') {
        json_response(['success' => false, 'message' => 'This member cannot be deleted because they have Outlet data (created/assigned outlets, or visit history). Reassign or remove their outlets first.'], 409);
    }
    error_log('[TAMS Admin] members_delete failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}

if ($stmt->rowCount() === 0) {
    json_response(['success' => false, 'message' => 'Member not found.'], 404);
}

// tams_auth_tokens/tams_live_tracking_current/tams_member_history_locations
// all have ON DELETE CASCADE on user_id (see database/schema.sql), so this
// also cleans up the member's tokens, live position, and tracking history.
json_response(['success' => true, 'message' => 'Member deleted successfully.']);
