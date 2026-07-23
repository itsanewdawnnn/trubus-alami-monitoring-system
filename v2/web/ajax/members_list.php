<?php
/**
 * GET endpoint: returns a page of tams_users (role='member') as JSON, with
 * search/sort/pagination applied server-side. Read-only, so no CSRF token
 * is required.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();

$page = max(1, (int) ($_GET['page'] ?? 1));
$perPage = (int) ($_GET['per_page'] ?? 10);
if (!in_array($perPage, [10, 25, 50], true)) {
    $perPage = 10;
}
$offset = ($page - 1) * $perPage;

// Column names can't be bound as PDO parameters, so $_GET['sort'] is mapped
// through a whitelist instead of interpolated directly (SQL injection).
$sortMap = [
    'name' => 'u.name',
    'username' => 'u.username',
    'created_at' => 'u.created_at',
];
$sortKey = $_GET['sort'] ?? 'created_at';
$sortColumn = $sortMap[$sortKey] ?? 'u.created_at';
$sortDir = (strtoupper($_GET['dir'] ?? 'DESC') === 'ASC') ? 'ASC' : 'DESC';

$search = trim((string) ($_GET['q'] ?? ''));
$where = "WHERE u.role = 'member'";
$params = [];
if ($search !== '') {
    // Two distinct placeholders bound to the identical value, not the same
    // :search reused twice -- config.php's PDO::ATTR_EMULATE_PREPARES =>
    // false means MySQL's native prepared-statement protocol requires one
    // bound value per placeholder *occurrence*; reusing one named parameter
    // across multiple positions throws PDOException "SQLSTATE[HY093]:
    // Invalid parameter number" as soon as $search is non-empty (same root
    // cause identified and fixed in ajax/outlet_list.php's own search).
    $where .= ' AND (u.name LIKE :search_name OR u.username LIKE :search_username)';
    $searchTerm = '%' . $search . '%';
    $params['search_name'] = $searchTerm;
    $params['search_username'] = $searchTerm;
}

$countStmt = $pdo->prepare("SELECT COUNT(*) FROM tams_users u {$where}");
$countStmt->execute($params);
$total = (int) $countStmt->fetchColumn();

// No JOIN needed -- app_version_name/app_version_code/android_version/
// device_model live directly on tams_users (Member/Device Identity, not a
// Live Tracking fact; see schema.sql's migration comment). A member who has
// never logged in since Member Version Monitoring shipped simply has these
// columns NULL on their own row, and still appears in the list (with an
// "Unknown" version status) exactly as before.
$sql = "
    SELECT u.id, u.name, u.username, u.is_active, u.created_at,
           u.force_tracking_hours,
           u.app_version_name, u.app_version_code, u.android_version, u.device_model
    FROM tams_users u
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

// Single-row read, done once for the whole page (not per member) -- the
// same tams_ota_update row ota_update.php edits. Missing row (fresh
// install, OTA never configured) falls back to "every reported version is
// at least supported" rather than crashing or marking everyone unsupported.
$versionStmt = $pdo->query("SELECT version_code, min_supported_version_code FROM tams_ota_update WHERE id = 1 LIMIT 1");
$versionRow = $versionStmt->fetch();
$latestVersionCode = $versionRow ? (int) $versionRow['version_code'] : null;
$minSupportedVersionCode = $versionRow ? (int) $versionRow['min_supported_version_code'] : 1;

$data = array_map(static function (array $row) use ($latestVersionCode, $minSupportedVersionCode): array {
    $appVersionCode = $row['app_version_code'] !== null ? (int) $row['app_version_code'] : null;

    // Member Version Monitoring's 🟢/🟡/🔴 status, computed the same way
    // documented in database/schema.sql next to min_supported_version_code:
    // unknown until the app has reported at least once, red below the
    // configured floor, green once caught up to the latest published
    // version, yellow anywhere supported in between.
    if ($appVersionCode === null || $latestVersionCode === null) {
        $versionStatus = 'unknown';
    } elseif ($appVersionCode < $minSupportedVersionCode) {
        $versionStatus = 'unsupported';
    } elseif ($appVersionCode >= $latestVersionCode) {
        $versionStatus = 'latest';
    } else {
        $versionStatus = 'outdated';
    }

    return [
        'id' => (int) $row['id'],
        'name' => $row['name'],
        'username' => $row['username'],
        'is_active' => (bool) $row['is_active'],
        'created_at' => $row['created_at'],
        'force_tracking_hours' => (bool) $row['force_tracking_hours'],
        'app_version_name' => $row['app_version_name'],
        'app_version_code' => $appVersionCode,
        'android_version' => $row['android_version'],
        'device_model' => $row['device_model'],
        'version_status' => $versionStatus,
    ];
}, $rows);

json_response([
    'success' => true,
    'data' => $data,
    'total' => $total,
    'page' => $page,
    'per_page' => $perPage,
]);
