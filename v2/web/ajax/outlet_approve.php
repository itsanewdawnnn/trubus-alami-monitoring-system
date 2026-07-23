<?php
/**
 * POST endpoint: approves either a PENDING/REJECTED outlet itself, or a
 * PENDING edit request against an already-APPROVED outlet -- which of the
 * two applies is read fresh from the locked row, not passed by the caller,
 * so a stale client can never approve the wrong thing. This dual meaning
 * mirrors backend/api.php's Member-facing /outlet/update, which is the
 * route that decides whether a Member's edit lands directly on
 * tams_outlets or opens a tams_outlet_edit_requests row in the first place
 * -- see that route's own comments for the full rationale.
 *
 * Row-lock-based idempotency: SELECT ... FOR UPDATE against the current
 * state before mutating, same pattern as members_force_toggle.php and
 * backend/api.php's Force Location fix -- a second, near-simultaneous
 * Approve click waits for the first to commit, then correctly sees "already
 * approved" instead of double-applying anything.
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
    json_response(['success' => false, 'message' => 'Invalid outlet.'], 422);
}

$adminId = (int) $_SESSION['admin_id'];
$message = '';

try {
    $pdo->beginTransaction();

    $lock_stmt = $pdo->prepare('SELECT id, status FROM tams_outlets WHERE id = :id AND deleted_at IS NULL FOR UPDATE');
    $lock_stmt->execute(['id' => $id]);
    $outlet = $lock_stmt->fetch();

    if (!$outlet) {
        $pdo->rollBack();
        json_response(['success' => false, 'message' => 'Outlet not found.'], 404);
    }

    if ($outlet['status'] === 'PENDING' || $outlet['status'] === 'REJECTED') {
        // Admin may approve a previously-REJECTED outlet directly (a
        // reversed decision) exactly like a fresh PENDING one -- no
        // separate "reconsider" action needed. rejection_reason is cleared
        // since it no longer describes this outlet's current state.
        $update_stmt = $pdo->prepare("
            UPDATE tams_outlets
            SET status = 'APPROVED', approved_by_user_id = :admin_id, approved_at = NOW(), rejection_reason = NULL
            WHERE id = :id
        ");
        $update_stmt->execute(['admin_id' => $adminId, 'id' => $id]);
        $message = 'Outlet approved.';
    } elseif ($outlet['status'] === 'APPROVED') {
        $edit_lock_stmt = $pdo->prepare("
            SELECT id, proposed_name, proposed_address, proposed_latitude, proposed_longitude
            FROM tams_outlet_edit_requests
            WHERE outlet_id = :outlet_id AND status = 'PENDING'
            FOR UPDATE
        ");
        $edit_lock_stmt->execute(['outlet_id' => $id]);
        $edit = $edit_lock_stmt->fetch();

        if (!$edit) {
            $pdo->rollBack();
            json_response(['success' => false, 'message' => 'This outlet has no pending changes to approve.'], 409);
        }

        // Copies the proposed snapshot onto tams_outlets' own live columns
        // -- the same "become the new live data" moment
        // tams_outlet_edit_requests' schema.sql comment describes.
        $apply_stmt = $pdo->prepare('
            UPDATE tams_outlets
            SET name = :name, address = :address, latitude = :latitude, longitude = :longitude
            WHERE id = :id
        ');
        $apply_stmt->execute([
            'name' => $edit['proposed_name'],
            'address' => $edit['proposed_address'],
            'latitude' => $edit['proposed_latitude'],
            'longitude' => $edit['proposed_longitude'],
            'id' => $id,
        ]);

        $review_stmt = $pdo->prepare("
            UPDATE tams_outlet_edit_requests
            SET status = 'APPROVED', reviewed_by_user_id = :admin_id, reviewed_at = NOW()
            WHERE id = :edit_id
        ");
        $review_stmt->execute(['admin_id' => $adminId, 'edit_id' => $edit['id']]);
        $message = 'Outlet changes approved.';
    } else {
        $pdo->rollBack();
        json_response(['success' => false, 'message' => 'This outlet is not awaiting approval.'], 409);
    }

    $pdo->commit();
} catch (PDOException $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[TAMS Admin] outlet_approve failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}

json_response(['success' => true, 'message' => $message]);
