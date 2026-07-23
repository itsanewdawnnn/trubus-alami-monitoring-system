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
$stmt = $pdo->prepare("DELETE FROM tams_users WHERE id = :id AND role = 'member'");
$stmt->execute(['id' => $id]);

if ($stmt->rowCount() === 0) {
    json_response(['success' => false, 'message' => 'Member not found.'], 404);
}

// tams_auth_tokens/tams_live_tracking_current/tams_member_history_locations
// all have ON DELETE CASCADE on user_id (see database/schema.sql), so this
// also cleans up the member's tokens, live position, and tracking history.
json_response(['success' => true, 'message' => 'Member deleted successfully.']);
