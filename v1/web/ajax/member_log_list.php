<?php
/**
 * GET endpoint: returns a page of tams_member_log as JSON, with
 * search/filter/sort/pagination applied server-side -- same shape as
 * ajax/members_list.php, plus two extra filters (action_type, date range).
 * Read-only, so no CSRF token is required.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();

$page = max(1, (int) ($_GET['page'] ?? 1));
$perPage = (int) ($_GET['per_page'] ?? 25);
if (!in_array($perPage, [10, 25, 50, 100], true)) {
    $perPage = 25;
}
$offset = ($page - 1) * $perPage;

// Column names can't be bound as PDO parameters, so $_GET['sort'] is mapped
// through a whitelist instead of interpolated directly (SQL injection) --
// same approach as members_list.php's $sortMap.
$sortMap = [
    'created_at' => 'created_at',
    'user_name' => 'user_name',
];
$sortKey = $_GET['sort'] ?? 'created_at';
$sortColumn = $sortMap[$sortKey] ?? 'created_at';
$sortDir = (strtoupper($_GET['dir'] ?? 'DESC') === 'ASC') ? 'ASC' : 'DESC';

$where = 'WHERE 1=1';
$params = [];

$search = trim((string) ($_GET['q'] ?? ''));
if ($search !== '') {
    $where .= ' AND (user_name LIKE :search OR message LIKE :search)';
    $params['search'] = '%' . $search . '%';
}

// action_type is validated against the same whitelist the filter dropdown
// is built from, rather than trusted as an arbitrary string.
$actionType = trim((string) ($_GET['action_type'] ?? ''));
if ($actionType !== '' && array_key_exists($actionType, member_log_action_types())) {
    $where .= ' AND action_type = :action_type';
    $params['action_type'] = $actionType;
}

// Dates are validated as strict Y-m-d before use -- DATE(created_at)
// comparisons stay index-friendly enough at this table's expected volume,
// and avoids timezone-conversion complexity since created_at is already
// stored as the server's local (WIB) time throughout this project.
$dateFrom = (string) ($_GET['date_from'] ?? '');
if ($dateFrom !== '' && DateTime::createFromFormat('Y-m-d', $dateFrom) !== false) {
    $where .= ' AND DATE(created_at) >= :date_from';
    $params['date_from'] = $dateFrom;
}
$dateTo = (string) ($_GET['date_to'] ?? '');
if ($dateTo !== '' && DateTime::createFromFormat('Y-m-d', $dateTo) !== false) {
    $where .= ' AND DATE(created_at) <= :date_to';
    $params['date_to'] = $dateTo;
}

$countStmt = $pdo->prepare("SELECT COUNT(*) FROM tams_member_log {$where}");
$countStmt->execute($params);
$total = (int) $countStmt->fetchColumn();

$sql = "
    SELECT id, user_name, action_type, status, field_before, field_after, message, created_at
    FROM tams_member_log
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

$actionTypeLabels = member_log_action_types();

$data = array_map(static function (array $row) use ($actionTypeLabels): array {
    return [
        'id' => (int) $row['id'],
        'user_name' => $row['user_name'],
        'action_type' => $row['action_type'],
        'action_type_label' => $actionTypeLabels[$row['action_type']] ?? $row['action_type'],
        'status' => $row['status'],
        'field_before' => $row['field_before'],
        'field_after' => $row['field_after'],
        'message' => $row['message'],
        'created_at' => $row['created_at'],
    ];
}, $rows);

json_response([
    'success' => true,
    'data' => $data,
    'total' => $total,
    'page' => $page,
    'per_page' => $perPage,
]);
