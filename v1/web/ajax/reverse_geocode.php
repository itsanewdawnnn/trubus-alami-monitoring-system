<?php
/**
 * GET endpoint: turns a lat/lng into a short "near X" label, e.g. "Jalan
 * Sudirman, Menteng". Proxied server-side (not called directly from the
 * browser) because Nominatim's usage policy requires a descriptive
 * User-Agent, and this lets lookups share one server-side cache.
 *
 * Query: ?lat=<float>&lng=<float>
 * Always responds success:true even on lookup failure (label: null) -- a
 * missing landmark must never block the rest of the detail card.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(['success' => false, 'message' => 'Method not allowed.'], 405);
}

$lat = isset($_GET['lat']) ? (float) $_GET['lat'] : null;
$lng = isset($_GET['lng']) ? (float) $_GET['lng'] : null;

if ($lat === null || $lng === null || $lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
    json_response(['success' => false, 'message' => 'Invalid coordinates.'], 422);
}

// Rounded to ~11m so nearby GPS fixes share one cache entry.
$roundedLat = round($lat, 4);
$roundedLng = round($lng, 4);
$cacheKey = sys_get_temp_dir() . '/tams_geocode_' . str_replace(['-', '.'], ['n', '_'], $roundedLat . '_' . $roundedLng) . '.json';

if (is_readable($cacheKey)) {
    $cached = json_decode((string) file_get_contents($cacheKey), true);
    if (is_array($cached) && array_key_exists('label', $cached)) {
        json_response(['success' => true, 'data' => ['label' => $cached['label']]]);
    }
}

$label = null;

$url = 'https://nominatim.openstreetmap.org/reverse?' . http_build_query([
    'format' => 'jsonv2',
    'lat' => $roundedLat,
    'lon' => $roundedLng,
    'zoom' => 16,
    'addressdetails' => 1,
]);

if (function_exists('curl_init')) {
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 4,
        CURLOPT_CONNECTTIMEOUT => 3,
        // Required by Nominatim's usage policy.
        CURLOPT_USERAGENT => 'TAMS Admin Panel (admin panel reverse geocoding)',
    ]);
    $body = curl_exec($ch);
    $ok = $body !== false && curl_getinfo($ch, CURLINFO_HTTP_CODE) === 200;
    curl_close($ch);

    if ($ok) {
        $json = json_decode((string) $body, true);
        $address = $json['address'] ?? [];

        $road = $address['road'] ?? null;
        $area = $address['suburb'] ?? $address['village'] ?? $address['city_district']
            ?? $address['town'] ?? $address['city'] ?? null;

        if ($road && $area) {
            $label = $road . ', ' . $area;
        } elseif ($road) {
            $label = $road;
        } elseif ($area) {
            $label = $area;
        } elseif (!empty($json['display_name'])) {
            // Fallback: first two segments of the full address.
            $parts = array_map('trim', explode(',', $json['display_name']));
            $label = implode(', ', array_slice($parts, 0, 2));
        }
    }
}

// Cache even a "not found" (null) so a landmark-less area isn't re-queried.
@file_put_contents($cacheKey, json_encode(['label' => $label]));

// This cache has no cron job to expire it, and members visiting new areas
// over months/years means the distinct-coordinate file count only ever
// grows. A cheap, no-infrastructure fix: every write has a small chance of
// sweeping out entries old enough that they're very unlikely to be reused,
// bounding this directory's long-term size without a scheduled task or
// per-request scan cost.
if (mt_rand(1, 200) === 1) {
    foreach (glob(sys_get_temp_dir() . '/tams_geocode_*.json') ?: [] as $file) {
        if (is_file($file) && (time() - (int) @filemtime($file)) > 90 * 86400) {
            @unlink($file);
        }
    }
}

json_response(['success' => true, 'data' => ['label' => $label]]);
