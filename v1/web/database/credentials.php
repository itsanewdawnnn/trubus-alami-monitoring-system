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
define('DB_PORT', getenv('DB_PORT') ?: 'your_db_port');
define('DB_NAME', getenv('DB_NAME') ?: 'your_db_name');
define('DB_USER', getenv('DB_USER') ?: 'your_db_user');
define('DB_PASS', getenv('DB_PASS') ?: 'your_db_password');
