<?php
/**
 * POST endpoint: saves every tams_remote_management row defined by
 * helpers/functions.php's remote_management_definitions(). Unknown keys in the
 * request body are ignored -- this endpoint only ever writes the whitelisted
 * settings, never arbitrary key/value pairs, since GET /app/config serves
 * this table with no authentication at all.
 *
 * Body: { <config_key>: <int>, ... } -- one field per remote_management_definitions() entry.
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

$definitions = remote_management_definitions();
$errors = [];
$values = [];

foreach ($definitions as $key => $def) {
    $raw = $body[$key] ?? null;
    $value = filter_var($raw, FILTER_VALIDATE_INT);
    if ($value === false || $value < $def['min'] || $value > $def['max']) {
        $errors[$key] = $def['label'] . " must be a whole number between {$def['min']} and {$def['max']}.";
        continue;
    }
    $values[$key] = $value;
}

// Cross-field check the generic per-field loop above can't express: an
// admin setting the max below the min would make every password invalid,
// including their own next password change.
if (isset($values['password_min_length'], $values['password_max_length'])
    && $values['password_max_length'] < $values['password_min_length']) {
    $errors['password_max_length'] = 'Maximum Password Length must not be less than Minimum Password Length.';
}

if (!empty($errors)) {
    json_response(['success' => false, 'message' => 'Please review the fields you entered.', 'errors' => $errors], 422);
}

try {
    $stmt = $pdo->prepare("
        INSERT INTO tams_remote_management (config_key, config_value, updated_at)
        VALUES (:config_key, :config_value, NOW())
        ON DUPLICATE KEY UPDATE
            config_value = VALUES(config_value),
            updated_at = VALUES(updated_at)
    ");
    // One row per setting -- the table has at most a handful of rows
    // (remote_management_definitions() entries), so a short loop of prepared
    // statement executions is simpler and just as fast as a multi-row
    // upsert here, with no measurable performance cost.
    foreach ($values as $key => $value) {
        $stmt->execute([
            'config_key' => $key,
            'config_value' => (string) $value,
        ]);
    }

    json_response(['success' => true, 'message' => 'Remote configuration saved successfully.']);
} catch (PDOException $e) {
    error_log('[TAMS Admin] remote_management_save failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
}
