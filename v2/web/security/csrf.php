<?php
/**
 * CSRF protection: one random token per session, embedded in every form and
 * AJAX request, checked before any state-changing request touches the
 * database. Requires config.php's session_start() to have run.
 */

/**
 * Returns the session's CSRF token, generating one on first use. The same
 * token is reused for the session's lifetime rather than rotated per
 * request, so multiple open tabs and back-button reloads keep working.
 */
function csrf_token(): string
{
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf_token'];
}

/**
 * Hidden <input> carrying the CSRF token, for plain <form> submissions.
 * AJAX requests send the same token via the X-CSRF-Token header instead
 * (see assets/js/app.js's postJson()).
 */
function csrf_field(): string
{
    return '<input type="hidden" name="csrf_token" value="' . e(csrf_token()) . '">';
}

/**
 * Verifies a submitted token (form field or X-CSRF-Token header) against
 * the session's, using a constant-time comparison.
 */
function csrf_verify(): bool
{
    $submitted = $_POST['csrf_token'] ?? ($_SERVER['HTTP_X_CSRF_TOKEN'] ?? null);
    if (!is_string($submitted) || $submitted === '' || empty($_SESSION['csrf_token'])) {
        return false;
    }
    return hash_equals($_SESSION['csrf_token'], $submitted);
}

/**
 * Guard for ajax/*.php POST endpoints: verifies CSRF or sends a 403 and stops.
 */
function csrf_require_ajax(): void
{
    if (!csrf_verify()) {
        json_response(['success' => false, 'message' => 'Invalid session, please reload the page.'], 403);
    }
}
