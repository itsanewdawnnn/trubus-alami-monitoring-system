<?php
/**
 * Logout endpoint. GET is fine here -- there's no cross-site value in
 * tricking someone into logging themselves out, so no CSRF token needed.
 */
require __DIR__ . '/config.php';
require __DIR__ . '/helpers/functions.php';
require __DIR__ . '/security/auth.php';

session_logout();
redirect('login.php');
