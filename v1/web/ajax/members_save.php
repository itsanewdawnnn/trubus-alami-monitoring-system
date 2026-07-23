<?php
/**
 * POST endpoint: creates or updates a Member (tams_users row, role='member').
 * "id" present means edit, absent means create.
 *
 * Body: { id?, name, username, password?, status: "1"|"0" }
 * password is required when creating; blank/absent on edit keeps the
 * existing password.
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

function str_field(array $body, string $key): string
{
    return isset($body[$key]) && is_string($body[$key]) ? trim($body[$key]) : '';
}

$id = isset($body['id']) && $body['id'] !== '' ? (int) $body['id'] : null;
$name = str_field($body, 'name');
$username = str_field($body, 'username');
$password = isset($body['password']) && is_string($body['password']) ? $body['password'] : '';
$status = str_field($body, 'status');

$isEdit = $id !== null;
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

// Configurable via the Admin Panel's Remote Management page (tams_remote_management),
// not a hardcoded constant -- see remote_management_values()'s doc comment.
$pwLimits = remote_management_values($pdo, ['password_min_length', 'password_max_length']);
$passwordMinLength = $pwLimits['password_min_length'];
$passwordMaxLength = $pwLimits['password_max_length'];

if (!$isEdit && $password === '') {
    $errors['password'] = 'Password is required.';
} elseif ($password !== '' && mb_strlen($password) < $passwordMinLength) {
    $errors['password'] = 'Password must be at least ' . $passwordMinLength . ' characters.';
} elseif (mb_strlen($password) > $passwordMaxLength) {
    // tams_users.password is VARCHAR(255) at most -- password_max_length is
    // never allowed to exceed that (see remote_management_definitions()), but
    // this check still guards against whatever value is actually configured.
    // Without it, a longer value would either be silently truncated on
    // write (permanently locking the member out, since they'd type the
    // full untruncated password back at login) or hard-fail the
    // INSERT/UPDATE under strict SQL mode.
    $errors['password'] = 'Password must be at most ' . $passwordMaxLength . ' characters.';
}

if ($status !== '1' && $status !== '0') {
    $errors['status'] = 'Invalid status.';
}

// role = 'member' check ensures this can never edit an Admin account, even
// with a guessed/tampered id.
$existing = null;
if ($isEdit && empty($errors)) {
    $stmt = $pdo->prepare("SELECT id FROM tams_users WHERE id = :id AND role = 'member' LIMIT 1");
    $stmt->execute(['id' => $id]);
    $existing = $stmt->fetch();
    if (!$existing) {
        json_response(['success' => false, 'message' => 'Member not found.'], 404);
    }
}

// username is UNIQUE across the whole table (both roles); checked up front
// so a duplicate reads as a field error, not a raw DB exception. The DB
// constraint is still the authoritative guard against a race with the
// INSERT/UPDATE below.
if (empty($errors)) {
    $sql = 'SELECT id FROM tams_users WHERE username = :username';
    $params = ['username' => $username];
    if ($isEdit) {
        $sql .= ' AND id != :id';
        $params['id'] = $id;
    }
    $stmt = $pdo->prepare($sql . ' LIMIT 1');
    $stmt->execute($params);
    if ($stmt->fetch()) {
        $errors['username'] = 'Username is already in use.';
    }
}

if (!empty($errors)) {
    json_response(['success' => false, 'message' => 'Please review the fields you entered.', 'errors' => $errors], 422);
}

$isActive = $status === '1' ? 1 : 0;

try {
    if ($isEdit) {
        if ($password !== '') {
            // tams_users.password is plain text, compared with hash_equals()
            // by backend/api.php -- an existing contract with the
            // Android app that this must not silently break by hashing it here.
            $stmt = $pdo->prepare('UPDATE tams_users SET name = :name, username = :username, password = :password, is_active = :is_active WHERE id = :id');
            $stmt->execute([
                'name' => $name,
                'username' => $username,
                'password' => $password,
                'is_active' => $isActive,
                'id' => $id,
            ]);
        } else {
            $stmt = $pdo->prepare('UPDATE tams_users SET name = :name, username = :username, is_active = :is_active WHERE id = :id');
            $stmt->execute([
                'name' => $name,
                'username' => $username,
                'is_active' => $isActive,
                'id' => $id,
            ]);
        }
        json_response(['success' => true, 'message' => 'Member updated successfully.']);
    } else {
        $stmt = $pdo->prepare("INSERT INTO tams_users (name, note, username, password, role, is_active) VALUES (:name, '', :username, :password, 'member', :is_active)");
        $stmt->execute([
            'name' => $name,
            'username' => $username,
            'password' => $password,
            'is_active' => $isActive,
        ]);
        json_response(['success' => true, 'message' => 'Member added successfully.']);
    }
} catch (PDOException $e) {
    // 23000 = the UNIQUE username index catching a race the pre-check above missed.
    if ($e->getCode() === '23000') {
        json_response(['success' => false, 'message' => 'Username is already in use.', 'errors' => ['username' => 'Username is already in use.']], 422);
    }
    error_log('[TAMS Admin] members_save failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}
