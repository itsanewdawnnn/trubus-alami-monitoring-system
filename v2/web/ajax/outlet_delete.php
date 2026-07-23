<?php
/**
 * POST endpoint: Admin soft-deletes an outlet, any status. Unlike
 * backend/api.php's Member-facing /outlet/delete (a hard DELETE, only ever
 * safe for a PENDING/REJECTED outlet that structurally cannot have visit
 * history yet), this must preserve tams_outlet_visits rows that may already
 * reference an APPROVED outlet -- see tams_outlets.deleted_at's schema.sql
 * comment. deleted_at IS NULL in the WHERE clause makes this idempotent
 * under InnoDB's row lock: two concurrent deletes of the same outlet can
 * never both report success, the second simply sees rowCount() = 0.
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

$stmt = $pdo->prepare('
    UPDATE tams_outlets
    SET deleted_at = NOW(), deleted_by_user_id = :admin_id
    WHERE id = :id AND deleted_at IS NULL
');
$stmt->execute(['admin_id' => $adminId, 'id' => $id]);

if ($stmt->rowCount() === 0) {
    json_response(['success' => false, 'message' => 'Outlet not found or already deleted.'], 404);
}

// member_id is left as-is on the now-soft-deleted row (harmless once
// deleted_at filters this outlet out of every active query) --
// tams_outlet_visits/dwell state are untouched entirely, per the
// ledger-immutability principle.
json_response(['success' => true, 'message' => 'Outlet deleted.']);
