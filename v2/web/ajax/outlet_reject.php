<?php
/**
 * POST endpoint: mirror image of ajax/outlet_approve.php -- rejects either
 * a PENDING outlet itself, or a PENDING edit request against an
 * already-APPROVED outlet, decided the same way (read fresh from the locked
 * row, never trusted from the caller). Rejecting an edit request never
 * touches tams_outlets itself -- the live name/address/latitude/longitude
 * backend/api.php's geofencing hook reads stays exactly what it already
 * was, the proposed change is simply discarded.
 *
 * Body: { id, reason }
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();
csrf_require_ajax();

$body = json_decode(file_get_contents('php://input'), true);
$id = isset($body['id']) ? (int) $body['id'] : 0;
$reason = isset($body['reason']) && is_string($body['reason']) ? trim($body['reason']) : '';

if ($id <= 0) {
    json_response(['success' => false, 'message' => 'Invalid outlet.'], 422);
}
if ($reason === '' || mb_strlen($reason) > 255) {
    json_response(['success' => false, 'message' => 'A rejection reason is required (max 255 characters).', 'errors' => ['reason' => 'A rejection reason is required (max 255 characters).']], 422);
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

    if ($outlet['status'] === 'PENDING') {
        $update_stmt = $pdo->prepare("
            UPDATE tams_outlets
            SET status = 'REJECTED', rejection_reason = :reason
            WHERE id = :id
        ");
        $update_stmt->execute(['reason' => $reason, 'id' => $id]);
        $message = 'Outlet rejected.';
    } elseif ($outlet['status'] === 'APPROVED') {
        $edit_lock_stmt = $pdo->prepare("
            SELECT id FROM tams_outlet_edit_requests
            WHERE outlet_id = :outlet_id AND status = 'PENDING'
            FOR UPDATE
        ");
        $edit_lock_stmt->execute(['outlet_id' => $id]);
        $edit = $edit_lock_stmt->fetch();

        if (!$edit) {
            $pdo->rollBack();
            json_response(['success' => false, 'message' => 'This outlet has no pending changes to reject.'], 409);
        }

        $review_stmt = $pdo->prepare("
            UPDATE tams_outlet_edit_requests
            SET status = 'REJECTED', reviewed_by_user_id = :admin_id, reviewed_at = NOW(), rejection_reason = :reason
            WHERE id = :edit_id
        ");
        $review_stmt->execute(['admin_id' => $adminId, 'reason' => $reason, 'edit_id' => $edit['id']]);
        $message = 'Outlet changes rejected. The outlet keeps its current approved data.';
    } else {
        $pdo->rollBack();
        json_response(['success' => false, 'message' => 'This outlet is not awaiting approval.'], 409);
    }

    $pdo->commit();
} catch (PDOException $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[TAMS Admin] outlet_reject failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}

json_response(['success' => true, 'message' => $message]);
