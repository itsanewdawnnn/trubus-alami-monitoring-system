<?php
/**
 * POST endpoint: Admin edits any outlet (any status, any creator) directly.
 * Unlike backend/api.php's Member-facing /outlet/update, this never goes
 * through tams_outlet_edit_requests -- an Admin's own edit IS the
 * authoritative decision, there is no higher authority left to approve it
 * against. The outlet's `status` itself is deliberately left untouched here
 * (Approve/Reject are their own dedicated actions, ajax/outlet_approve.php
 * and ajax/outlet_reject.php -- this endpoint only ever changes content).
 *
 * Member assignment is a plain `member_id` column, set in the same UPDATE as
 * name/address/coordinates -- one-to-one from the outlet's side (see
 * tams_outlets.member_id's own schema.sql comment). Reassigning to a
 * different Member automatically releases the previous one as a side effect
 * of the column simply holding a new value; there is no separate
 * delete-then-reinsert step to keep in sync.
 *
 * Any PENDING tams_outlet_edit_requests row for this outlet is superseded
 * (marked REJECTED, never silently deleted -- see that table's own
 * schema.sql comment on why in-place status updates are fine for workflow
 * state) as part of the same transaction. Without this, a Member's older
 * proposed snapshot would still be sitting there; a later Approve click
 * would blindly apply THAT stale data and silently overwrite the edit the
 * Admin just made here.
 *
 * Body: { id, name, address, latitude, longitude, member_id: int }
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../helpers/outlet_functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();
csrf_require_ajax();

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    $body = [];
}

$id = isset($body['id']) ? (int) $body['id'] : 0;
$name = isset($body['name']) && is_string($body['name']) ? trim($body['name']) : '';
$address = isset($body['address']) && is_string($body['address']) ? trim($body['address']) : '';
$latitude = isset($body['latitude']) ? filter_var($body['latitude'], FILTER_VALIDATE_FLOAT) : null;
$longitude = isset($body['longitude']) ? filter_var($body['longitude'], FILTER_VALIDATE_FLOAT) : null;
$rawMemberId = $body['member_id'] ?? null;

if ($id <= 0) {
    json_response(['success' => false, 'message' => 'Invalid outlet.'], 422);
}

$errors = validate_outlet_fields($name, $address, $latitude, $longitude);

$memberId = validate_outlet_member_id($pdo, $rawMemberId);
if ($memberId === null) {
    $errors['member_id'] = 'Select an active Member.';
}

if (!empty($errors)) {
    json_response(['success' => false, 'message' => 'Please review the fields you entered.', 'errors' => $errors], 422);
}

try {
    $pdo->beginTransaction();

    // No created_by_user_id scoping here (unlike backend/api.php's
    // Member-facing route) -- Admin may edit any non-deleted outlet
    // regardless of who created it. Locked for the rest of the transaction
    // so a concurrent Approve/Reject/Merge against the same row waits
    // rather than interleaving.
    $lock_stmt = $pdo->prepare('SELECT id FROM tams_outlets WHERE id = :id AND deleted_at IS NULL FOR UPDATE');
    $lock_stmt->execute(['id' => $id]);
    if (!$lock_stmt->fetch()) {
        $pdo->rollBack();
        json_response(['success' => false, 'message' => 'Outlet not found.'], 404);
    }

    $update_stmt = $pdo->prepare('
        UPDATE tams_outlets
        SET name = :name, address = :address, latitude = :latitude, longitude = :longitude, member_id = :member_id
        WHERE id = :id
    ');
    $update_stmt->execute([
        'name' => $name,
        'address' => $address,
        'latitude' => $latitude,
        'longitude' => $longitude,
        'member_id' => $memberId,
        'id' => $id,
    ]);

    // Supersede any still-open edit request -- see this file's header
    // comment. Scoped to status='PENDING' so an already-reviewed
    // (APPROVED/REJECTED) row from earlier history is left untouched.
    $supersede_stmt = $pdo->prepare("
        UPDATE tams_outlet_edit_requests
        SET status = 'REJECTED', reviewed_by_user_id = :admin_id, reviewed_at = NOW(),
            rejection_reason = 'Superseded by a direct edit made by an Admin.'
        WHERE outlet_id = :id AND status = 'PENDING'
    ");
    $supersede_stmt->execute(['admin_id' => (int) $_SESSION['admin_id'], 'id' => $id]);

    $pdo->commit();
} catch (PDOException $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[TAMS Admin] outlet_update failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}

json_response(['success' => true, 'message' => 'Outlet updated.']);
