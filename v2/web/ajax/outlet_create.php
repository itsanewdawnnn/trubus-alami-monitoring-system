<?php
/**
 * POST endpoint: Admin directly creates a new outlet, auto-approved (no
 * PENDING step -- an Admin-created outlet is authoritative the moment it's
 * created, unlike a Member's own submission via backend/api.php's
 * /outlet/create). Assigns it to exactly one Member -- one-to-one from the
 * outlet's side, see tams_outlets.member_id's own schema.sql comment.
 *
 * Body: { name, address, latitude, longitude, member_id: int }
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

$name = isset($body['name']) && is_string($body['name']) ? trim($body['name']) : '';
$address = isset($body['address']) && is_string($body['address']) ? trim($body['address']) : '';
$latitude = isset($body['latitude']) ? filter_var($body['latitude'], FILTER_VALIDATE_FLOAT) : null;
$longitude = isset($body['longitude']) ? filter_var($body['longitude'], FILTER_VALIDATE_FLOAT) : null;
$rawMemberId = $body['member_id'] ?? null;

$errors = validate_outlet_fields($name, $address, $latitude, $longitude);

$memberId = validate_outlet_member_id($pdo, $rawMemberId);
if ($memberId === null) {
    $errors['member_id'] = 'Select an active Member.';
}

if (!empty($errors)) {
    json_response(['success' => false, 'message' => 'Please review the fields you entered.', 'errors' => $errors], 422);
}

$adminId = (int) $_SESSION['admin_id'];

try {
    // Auto-approved: approved_by_user_id/approved_at are set to the
    // creating Admin/now, exactly as if a separate approval step had just
    // run against a PENDING row -- keeps those two columns meaning the same
    // thing regardless of which path an outlet reached APPROVED through.
    // member_id is set directly on this single INSERT (one-to-one from the
    // outlet's side) -- no separate assignment table/statement needed.
    $insert_stmt = $pdo->prepare("
        INSERT INTO tams_outlets
            (created_by_user_id, member_id, name, address, latitude, longitude, status, approved_by_user_id, approved_at)
        VALUES
            (:created_by, :member_id, :name, :address, :latitude, :longitude, 'APPROVED', :approved_by, NOW())
    ");
    $insert_stmt->execute([
        'created_by' => $adminId,
        'member_id' => $memberId,
        'name' => $name,
        'address' => $address,
        'latitude' => $latitude,
        'longitude' => $longitude,
        'approved_by' => $adminId,
    ]);
    $outletId = (int) $pdo->lastInsertId();
} catch (PDOException $e) {
    error_log('[TAMS Admin] outlet_create failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}

json_response(['success' => true, 'message' => 'Outlet created and approved.', 'id' => $outletId]);
