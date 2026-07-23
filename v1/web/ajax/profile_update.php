<?php
/**
 * POST endpoint: lets the logged-in Admin update their own tams_users row
 * (name, username, optionally password) from the "Profil Saya" modal.
 * Scoped to `id = session admin id AND role = 'admin'`, so it can never
 * touch another account even with a tampered id in the request body.
 *
 * Body: { name, username, new_password?, confirm_password? }
 * Blank new_password keeps the existing password. No current-password
 * check -- the session is already authenticated.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();
csrf_require_ajax();

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    $body = [];
}

function profile_str_field(array $body, string $key): string
{
    return isset($body[$key]) && is_string($body[$key]) ? trim($body[$key]) : '';
}

$adminId = (int) $_SESSION['admin_id'];
$name = profile_str_field($body, 'name');
$username = profile_str_field($body, 'username');
// Not trimmed -- whitespace in a password is meaningful.
$newPassword = isset($body['new_password']) && is_string($body['new_password']) ? $body['new_password'] : '';
$confirmPassword = isset($body['confirm_password']) && is_string($body['confirm_password']) ? $body['confirm_password'] : '';

$errors = [];

if ($name === '') {
    $errors['name'] = 'Name is required.';
} elseif (mb_strlen($name) > 100) {
    $errors['name'] = 'Name must be at most 100 characters.';
}

if ($username === '') {
    $errors['username'] = 'Username is required.';
} elseif (mb_strlen($username) > 50) {
    $errors['username'] = 'Username must be at most 50 characters.';
} elseif (!preg_match('/^[a-zA-Z0-9_.]+$/', $username)) {
    $errors['username'] = 'Username may only contain letters, numbers, dots, and underscores.';
}

// Re-fetched rather than trusted from $_SESSION, so a since-deleted/demoted
// account fails safely.
$stmt = $pdo->prepare("SELECT id, username FROM tams_users WHERE id = :id AND role = 'admin' LIMIT 1");
$stmt->execute(['id' => $adminId]);
$account = $stmt->fetch();

if (!$account) {
    json_response(['success' => false, 'message' => 'Account not found.'], 404);
}

$changingPassword = $newPassword !== '';
if ($changingPassword) {
    // Configurable via the Admin Panel's Remote Management page
    // (tams_remote_management), not a hardcoded constant -- see
    // remote_management_values()'s doc comment.
    $pwLimits = remote_management_values($pdo, ['password_min_length', 'password_max_length']);
    if (mb_strlen($newPassword) < $pwLimits['password_min_length']) {
        $errors['new_password'] = 'New password must be at least ' . $pwLimits['password_min_length'] . ' characters.';
    } elseif (mb_strlen($newPassword) > $pwLimits['password_max_length']) {
        // tams_users.password is VARCHAR(255) at most -- password_max_length
        // is never allowed to exceed that (see remote_management_definitions()).
        $errors['new_password'] = 'New password must be at most ' . $pwLimits['password_max_length'] . ' characters.';
    }

    if ($confirmPassword === '') {
        $errors['confirm_password'] = 'Password confirmation is required.';
    } elseif ($newPassword !== $confirmPassword) {
        $errors['confirm_password'] = 'Password confirmation does not match.';
    }
}

// Excludes this admin's own row so an unchanged username isn't flagged as a conflict.
if (empty($errors['username'])) {
    $stmt = $pdo->prepare('SELECT id FROM tams_users WHERE username = :username AND id != :id LIMIT 1');
    $stmt->execute(['username' => $username, 'id' => $adminId]);
    if ($stmt->fetch()) {
        $errors['username'] = 'Username is already in use.';
    }
}

if (!empty($errors)) {
    json_response(['success' => false, 'message' => 'Please review the fields you entered.', 'errors' => $errors], 422);
}

try {
    if ($changingPassword) {
        // Plain text, same contract as everywhere else this table is written (see members_save.php).
        $stmt = $pdo->prepare('UPDATE tams_users SET name = :name, username = :username, password = :password WHERE id = :id');
        $stmt->execute([
            'name' => $name,
            'username' => $username,
            'password' => $newPassword,
            'id' => $adminId,
        ]);
    } else {
        $stmt = $pdo->prepare('UPDATE tams_users SET name = :name, username = :username WHERE id = :id');
        $stmt->execute([
            'name' => $name,
            'username' => $username,
            'id' => $adminId,
        ]);
    }

    // Session ID is NOT regenerated -- this must never cause an unwanted
    // logout on another open tab.
    $_SESSION['admin_name'] = $name;
    $_SESSION['admin_username'] = $username;

    json_response(['success' => true, 'message' => 'Profile updated successfully.', 'name' => $name, 'username' => $username]);
} catch (PDOException $e) {
    // 23000 = the UNIQUE username index catching a race the pre-check above missed.
    if ($e->getCode() === '23000') {
        json_response(['success' => false, 'message' => 'Username is already in use.', 'errors' => ['username' => 'Username is already in use.']], 422);
    }
    error_log('[TAMS Admin] profile_update failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}
