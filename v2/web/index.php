<?php
/**
 * Root gateway: no router, so "/" forwards to the dashboard.
 * require_login() handles the "not logged in" redirect.
 */
require __DIR__ . '/config.php';
require __DIR__ . '/helpers/functions.php';
require __DIR__ . '/security/auth.php';

require_login();
redirect('pages/dashboard.php');
