<?php
/**
 * Shared database connection credentials.
 *
 * Required by BOTH web/config.php (Admin Panel) and
 * web/backend/includes/config.php (Backend API) -- this is the ONLY place
 * these values should be edited. Changing credentials here automatically
 * applies to both sides, instead of needing two files kept in sync by hand.
 *
 * Credentials should come from environment variables in production; the
 * literal fallbacks below are placeholders only and should be treated as
 * compromised/rotated in MySQL if this file was ever deployed as-is.
 */

define('DB_HOST', getenv('DB_HOST') ?: 'localhost');
define('DB_PORT', getenv('DB_PORT') ?: '3306');
define('DB_NAME', getenv('DB_NAME') ?: 'u412058299_tams');
define('DB_USER', getenv('DB_USER') ?: 'u412058299_tams');
define('DB_PASS', getenv('DB_PASS') ?: 'Tams@321');
