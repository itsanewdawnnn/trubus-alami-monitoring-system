<?php
/**
 * GET endpoint: which dates in a given month have recorded GPS history for
 * a given member. Powers the "has data" highlighting in pages/history.php's
 * calendar picker. Filters on recorded_at BETWEEN (not DATE(recorded_at) =
 * ...) to stay sargable against the (user_id, recorded_at) index.
 *
 * Query: ?user_id=<int>&month=YYYY-MM
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(['success' => false, 'message' => 'Method not allowed.'], 405);
}

$userId = isset($_GET['user_id']) ? (int) $_GET['user_id'] : 0;
$month = isset($_GET['month']) ? trim((string) $_GET['month']) : '';

if ($userId <= 0 || !preg_match('/^\d{4}-\d{2}$/', $month)) {
    json_response(['success' => false, 'message' => 'The user_id and month (YYYY-MM) parameters are required.'], 422);
}

$monthStartDate = DateTime::createFromFormat('Y-m-d', $month . '-01');
if ($monthStartDate === false) {
    json_response(['success' => false, 'message' => 'Invalid month format.'], 422);
}
$monthEndDate = clone $monthStartDate;
$monthEndDate->modify('last day of this month');
$monthStart = $monthStartDate->format('Y-m-d') . ' 00:00:00';
$monthEnd = $monthEndDate->format('Y-m-d') . ' 23:59:59';

$stmt = $pdo->prepare("
    SELECT DISTINCT DATE(recorded_at) AS d
    FROM tams_member_history_locations
    WHERE user_id = :user_id AND recorded_at BETWEEN :month_start AND :month_end
    ORDER BY d ASC
");
$stmt->execute([
    'user_id' => $userId,
    'month_start' => $monthStart,
    'month_end' => $monthEnd,
]);
$dates = array_map(static fn(array $row) => $row['d'], $stmt->fetchAll());

json_response(['success' => true, 'data' => ['user_id' => $userId, 'month' => $month, 'dates' => $dates]]);
