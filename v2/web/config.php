<?php
/**
 * Central config: DB connection, session hardening, shared constants.
 * Included first by every entry point (login.php, index.php, pages/*, ajax/*).
 */

// Never show raw errors to visitors -- leaks schema/paths/library versions.
// Still logged to the server's PHP error log.
ini_set('display_errors', '0');
error_reporting(E_ALL);
date_default_timezone_set('Asia/Jakarta');

// Session hardening must run before session_start().

// Idle timeout, checked against $_SESSION['last_activity'] in security/auth.php.
define('SESSION_TIMEOUT_SECONDS', 30 * 60);

// Must match web/backend/api.php's OFFLINE_STALE_SECONDS exactly -- both
// read the same tams_live_tracking_current table, and would disagree about a
// member's online status otherwise.
define('OFFLINE_STALE_SECONDS', 90);

$isHttps = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
    || (!empty($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');

session_set_cookie_params([
    'lifetime' => 0,
    'path' => '/',
    'httponly' => true,
    'secure' => $isHttps, // Only over HTTPS -- hardcoding true would break plain-HTTP deployments.
    'samesite' => 'Lax',
]);
session_name('tams_admin_session');

if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

// Shared with web/backend/includes/config.php -- both read the same
// tams_users table. Credentials live in ONE place now: edit them in
// web/database/credentials.php, not here.
require __DIR__ . '/database/credentials.php';

try {
    $dsn = 'mysql:host=' . DB_HOST . ';port=' . DB_PORT . ';dbname=' . DB_NAME . ';charset=utf8mb4';
    $pdo = new PDO($dsn, DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
} catch (PDOException $e) {
    error_log('[TAMS Admin] DB connection failure: ' . $e->getMessage());
    http_response_code(500);
    // No layout/auth machinery available yet at this point, so this can't depend on either.
    die('<!DOCTYPE html><html><head><meta charset="utf-8"><title>Error</title></head>'
        . '<body style="font-family:system-ui,sans-serif;padding:2rem;color:#333;">'
        . '<h1>Service temporarily unavailable</h1>'
        . '<p>Could not connect to the database. Please try again shortly.</p>'
        . '</body></html>');
}
