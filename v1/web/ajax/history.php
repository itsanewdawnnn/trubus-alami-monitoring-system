<?php
/**
 * GET endpoint: one member's GPS trail for one calendar day, plus derived
 * trip stats (distance, duration, point count). Powers pages/history.php's
 * stats cards and route polyline.
 *
 * Mirrors backend/api.php's `/location/history` route (same
 * cumulative-haversine distance, same duration formatting) so both apps
 * report identical numbers for the same trip.
 *
 * Query: ?user_id=<int>&date=YYYY-MM-DD
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
$date = isset($_GET['date']) ? trim((string) $_GET['date']) : '';

if ($userId <= 0 || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) {
    json_response(['success' => false, 'message' => 'The user_id and date (YYYY-MM-DD) parameters are required.'], 422);
}

$dayStart = $date . ' 00:00:00';
$dayEnd = $date . ' 23:59:59';
$stmt = $pdo->prepare("
    SELECT latitude, longitude, accuracy, speed, is_moving, recorded_at
    FROM tams_member_history_locations
    WHERE user_id = :user_id AND recorded_at BETWEEN :day_start AND :day_end
    ORDER BY recorded_at ASC
");
$stmt->execute([
    'user_id' => $userId,
    'day_start' => $dayStart,
    'day_end' => $dayEnd,
]);
$rawPoints = $stmt->fetchAll();

$totalPoints = count($rawPoints);
$totalDistance = 0.0;
$startTime = null;
$endTime = null;
$durationSeconds = 0;

if ($totalPoints > 0) {
    $startTime = $rawPoints[0]['recorded_at'];
    $endTime = $rawPoints[$totalPoints - 1]['recorded_at'];
    $durationSeconds = strtotime($endTime) - strtotime($startTime);

    for ($i = 0; $i < $totalPoints - 1; $i++) {
        $totalDistance += haversine_distance_km(
            (float) $rawPoints[$i]['latitude'],
            (float) $rawPoints[$i]['longitude'],
            (float) $rawPoints[$i + 1]['latitude'],
            (float) $rawPoints[$i + 1]['longitude']
        );
    }
}

$hours = intdiv($durationSeconds, 3600);
$minutes = intdiv($durationSeconds % 3600, 60);
$seconds = $durationSeconds % 60;
$durationFormatted = sprintf('%02dh %02dm %02ds', $hours, $minutes, $seconds);

$points = array_map(static function (array $p): array {
    return [
        'latitude' => (float) $p['latitude'],
        'longitude' => (float) $p['longitude'],
        'accuracy' => (float) $p['accuracy'],
        'speed' => (float) $p['speed'],
        'is_moving' => ((int) ($p['is_moving'] ?? 0)) === 1,
        'recorded_at' => $p['recorded_at'],
    ];
}, $rawPoints);

json_response([
    'success' => true,
    'data' => [
        'user_id' => $userId,
        'date' => $date,
        'total_points' => $totalPoints,
        'total_distance_km' => round($totalDistance, 3),
        'start_time' => $startTime,
        'end_time' => $endTime,
        'duration_seconds' => $durationSeconds,
        'duration_formatted' => $durationFormatted,
        'points' => $points,
    ],
]);
