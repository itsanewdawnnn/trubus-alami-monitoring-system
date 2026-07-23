<?php
/**
 * GET endpoint: returns a page of tams_outlets (every status, Admin sees
 * everything) as JSON, with search/status-filter/sort/pagination applied
 * server-side. Read-only, so no CSRF token is required -- mirrors
 * ajax/members_list.php's own shape and conventions.
 *
 * Also doubles as the data source for the Merge modal's outlet picker
 * (assets/js/outlet.js re-queries this with a search term and a larger
 * per_page rather than a separate endpoint -- same data, same shape, no
 * reason to duplicate the query).
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../helpers/outlet_functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();

$page = max(1, (int) ($_GET['page'] ?? 1));
$perPage = (int) ($_GET['per_page'] ?? 10);
if (!in_array($perPage, [10, 25, 50], true)) {
    $perPage = 10;
}
$offset = ($page - 1) * $perPage;

// Column names can't be bound as PDO parameters -- $_GET['sort'] is mapped
// through a whitelist instead of interpolated directly (SQL injection),
// same idiom as ajax/members_list.php's own $sortMap.
$sortMap = [
    'name' => 'o.name',
    'status' => 'o.status',
    'created_at' => 'o.created_at',
];
$sortKey = $_GET['sort'] ?? 'created_at';
$sortColumn = $sortMap[$sortKey] ?? 'o.created_at';
$sortDir = (strtoupper($_GET['dir'] ?? 'DESC') === 'ASC') ? 'ASC' : 'DESC';

// Soft-deleted/merged outlets (deleted_at IS NOT NULL) never appear here --
// this is the "active outlets" view used by both the approval table and the
// Merge picker. Their visit history is untouched and still resolvable via
// tams_outlets.merged_into_outlet_id; the Visit Report
// (ajax/outlet_visit_report.php) never needs to look past deleted_at either
// -- it reads tams_outlet_visits' own outlet_name_snapshot directly, with no
// join back to tams_outlets at all, so a since-deleted/merged outlet's past
// visits still show correctly under the name they had at visit time.
$where = 'WHERE o.deleted_at IS NULL';
$params = [];

$statusFilter = $_GET['status'] ?? '';
if (in_array($statusFilter, ['PENDING', 'APPROVED', 'REJECTED'], true)) {
    $where .= ' AND o.status = :status';
    $params['status'] = $statusFilter;
}

// Real server-side filter now that o.member_id is a plain column
// (one-to-one from the outlet's side) -- combines with Search/Status as a
// true AND directly in SQL, superseding the client-side gather-all-pages
// workaround assets/js/outlet.js used to need when this was only derivable
// via the many-to-many tams_outlet_members join table.
$memberFilter = (int) ($_GET['member_id'] ?? 0);
if ($memberFilter > 0) {
    $where .= ' AND o.member_id = :member_id';
    $params['member_id'] = $memberFilter;
}

$search = trim((string) ($_GET['q'] ?? ''));
if ($search !== '') {
    // Three distinct placeholders bound to the identical value, not the same
    // :search reused three times -- config.php's PDO::ATTR_EMULATE_PREPARES
    // => false means MySQL's native prepared-statement protocol requires one
    // bound value per placeholder *occurrence*; reusing one named parameter
    // across multiple positions throws PDOException "SQLSTATE[HY093]:
    // Invalid parameter number" as soon as $search is non-empty (PHP 8 made
    // this throw explicitly). That exception was uncaught here, and with
    // config.php's display_errors=0 it reached the browser as a non-JSON
    // response, which is what surfaced client-side as "Failed to load data.
    // Check your connection." on any non-empty search.
    $where .= ' AND (o.name LIKE :search_name OR o.address LIKE :search_address OR creator.name LIKE :search_creator)';
    $searchTerm = '%' . $search . '%';
    $params['search_name'] = $searchTerm;
    $params['search_address'] = $searchTerm;
    $params['search_creator'] = $searchTerm;
}

$countStmt = $pdo->prepare("
    SELECT COUNT(*)
    FROM tams_outlets o
    INNER JOIN tams_users creator ON creator.id = o.created_by_user_id
    {$where}
");
$countStmt->execute($params);
$total = (int) $countStmt->fetchColumn();

// LEFT JOIN onto at most one PENDING edit request per outlet -- the
// application enforces "at most one PENDING row per outlet" itself (see
// tams_outlet_edit_requests' schema.sql comment), so this can never
// duplicate a tams_outlets row.
$sql = "
    SELECT
        o.id, o.name, o.address, o.latitude, o.longitude, o.status,
        o.rejection_reason, o.created_by_user_id, o.member_id, o.created_at, o.updated_at,
        creator.name AS creator_name, creator.role AS creator_role,
        member.name AS member_name,
        er.id AS pending_edit_id, er.proposed_name, er.proposed_address,
        er.proposed_latitude, er.proposed_longitude
    FROM tams_outlets o
    INNER JOIN tams_users creator ON creator.id = o.created_by_user_id
    INNER JOIN tams_users member ON member.id = o.member_id
    LEFT JOIN tams_outlet_edit_requests er
        ON er.outlet_id = o.id AND er.status = 'PENDING'
    {$where}
    ORDER BY {$sortColumn} {$sortDir}
    LIMIT :limit OFFSET :offset
";
$stmt = $pdo->prepare($sql);
foreach ($params as $key => $value) {
    $stmt->bindValue(":{$key}", $value, PDO::PARAM_STR);
}
$stmt->bindValue(':limit', $perPage, PDO::PARAM_INT);
$stmt->bindValue(':offset', $offset, PDO::PARAM_INT);
$stmt->execute();
$rows = $stmt->fetchAll();

// Assigned Member is now a plain column, joined directly above -- no second
// query needed the way the old many-to-many tams_outlet_members shape
// required (see this endpoint's own history: previously a separate
// $memberStmt keyed by outlet_id).
$data = array_map(function (array $row): array {
    $id = (int) $row['id'];
    $hasPendingEdit = $row['pending_edit_id'] !== null;

    return [
        'id' => $id,
        'name' => $row['name'],
        'display_name' => outlet_display_name($row['creator_role'], $row['creator_name'], $row['name']),
        'address' => $row['address'],
        'latitude' => (float) $row['latitude'],
        'longitude' => (float) $row['longitude'],
        'status' => $row['status'],
        'rejection_reason' => $row['rejection_reason'],
        'created_by_user_id' => (int) $row['created_by_user_id'],
        'creator_name' => $row['creator_name'],
        'creator_role' => $row['creator_role'],
        'assigned_member' => ['id' => (int) $row['member_id'], 'name' => $row['member_name']],
        'has_pending_edit' => $hasPendingEdit,
        'pending_edit' => $hasPendingEdit ? [
            'name' => $row['proposed_name'],
            'address' => $row['proposed_address'],
            'latitude' => (float) $row['proposed_latitude'],
            'longitude' => (float) $row['proposed_longitude'],
        ] : null,
        'created_at' => $row['created_at'],
        'updated_at' => $row['updated_at'],
    ];
}, $rows);

json_response([
    'success' => true,
    'data' => $data,
    'total' => $total,
    'page' => $page,
    'per_page' => $perPage,
]);
