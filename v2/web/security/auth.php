<?php
/**
 * Authentication guard. Authenticates against the same `tams_users` table
 * the Android app uses, restricted to role = 'admin'. Requires
 * config.php to have already run.
 */

/**
 * True if the session is a logged-in Admin and hasn't idled past
 * SESSION_TIMEOUT_SECONDS. Checked on every protected page/endpoint load,
 * not just at login, so an idle session actually expires.
 */
function is_logged_in(): bool
{
    if (empty($_SESSION['admin_id'])) {
        return false;
    }

    $lastActivity = $_SESSION['last_activity'] ?? 0;
    if (time() - $lastActivity > SESSION_TIMEOUT_SECONDS) {
        session_logout();
        return false;
    }

    $_SESSION['last_activity'] = time();
    return true;
}

/**
 * Guard for browser-navigated pages: redirects to login if not authenticated.
 * Called as the first line of every file under pages/.
 */
function require_login(): void
{
    if (!is_logged_in()) {
        redirect('../login.php?reason=auth');
    }
}

/**
 * Same guard for ajax/*.php endpoints -- returns 401 JSON instead of an
 * HTML redirect, since the caller is fetch(), not a browser navigation.
 */
function require_login_ajax(): void
{
    if (!is_logged_in()) {
        json_response(['success' => false, 'message' => 'Session expired, please log in again.'], 401);
    }
}

/**
 * Establishes a logged-in session (caller must already have verified
 * role = 'admin' and the password). Regenerates the session ID as a
 * defense against session fixation.
 */
function session_login(array $adminUser): void
{
    session_regenerate_id(true);
    $_SESSION['admin_id'] = (int) $adminUser['id'];
    $_SESSION['admin_name'] = $adminUser['name'] !== '' ? $adminUser['name'] : $adminUser['username'];
    $_SESSION['admin_username'] = $adminUser['username'];
    $_SESSION['last_activity'] = time();
    $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
}

/**
 * Clears session data, deletes the cookie, and destroys the server-side
 * session store -- a full teardown, not just an is-logged-in flag flip.
 */
function session_logout(): void
{
    $_SESSION = [];

    if (ini_get('session.use_cookies')) {
        $params = session_get_cookie_params();
        setcookie(
            session_name(),
            '',
            time() - 42000,
            $params['path'],
            $params['domain'],
            $params['secure'],
            $params['httponly']
        );
    }

    session_destroy();
}
