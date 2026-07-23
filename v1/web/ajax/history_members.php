<?php
/**
 * GET endpoint: full roster of enabled Members, for the "select member"
 * dropdown on pages/history.php (Member History). Not filtered by
 * "currently online" -- an admin can pull history for any member regardless
 * of live status.
 *
 * Lives under the Member History feature (not Members) because this page is
 * its only consumer -- see assets/js/history.js's loadMembers(). Reads
 * tams_users directly rather than going through ajax/members_list.php
 * because that one is the paginated CRUD table feed for the Members page;
 * this always returns the complete list, which is what a <select> needs.
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
    SELECT id, name, note, username
    FROM tams_users
    WHERE role = 'member' AND is_active = 1
    ORDER BY name ASC
");
$rows = $stmt->fetchAll();

$members = array_map(static function (array $row): array {
    return [
        'id' => (int) $row['id'],
        'name' => $row['name'],
        'note' => $row['note'],
        'username' => $row['username'],
    ];
}, $rows);

json_response(['success' => true, 'data' => $members]);
