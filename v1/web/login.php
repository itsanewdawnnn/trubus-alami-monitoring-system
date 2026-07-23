<?php
/**
 * Login page/handler. Authenticates against the same `tams_users` table
 * the Android app uses -- only role = 'admin', is_active = 1 rows may sign
 * in. One file handles both the form (GET) and its submission (POST).
 */
require __DIR__ . '/config.php';
require __DIR__ . '/helpers/functions.php';
require __DIR__ . '/security/csrf.php';
require __DIR__ . '/security/auth.php';

// Brute-force protection, mirrored from backend/api.php's /auth/login --
// both authenticate against the same plain-text `tams_users` row, so both
// must lock an account out after the same number of wrong guesses. Kept as
// separate constants (same convention as OFFLINE_STALE_SECONDS) since the
// Admin Panel and Backend API are two independently-deployed entry points;
// if either value changes, update both.
define('MAX_FAILED_LOGIN_ATTEMPTS', 5);
define('LOGIN_LOCKOUT_MINUTES', 15);

// Already authenticated -- no reason to show the login form again.
if (is_logged_in()) {
    redirect('index.php');
}

$error = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!csrf_verify()) {
        $error = 'Invalid form session. Please try again.';
    } else {
        $username = input('username');
        $password = $_POST['password'] ?? '';

        if ($username === null || $password === '') {
            $error = 'Username and password are required.';
        } else {
            $stmt = $pdo->prepare("SELECT id, username, password, name, failed_login_attempts, locked_until FROM tams_users WHERE username = :username AND role = 'admin' AND is_active = 1 LIMIT 1");
            $stmt->execute(['username' => $username]);
            $account = $stmt->fetch();

            // Same message for "no such user", "wrong password", and "locked
            // out" -- avoids letting this form enumerate valid usernames or
            // reveal lockout state to anyone but the real account owner.
            $error = 'Invalid username or password.';
            $locked = $account && $account['locked_until'] !== null && strtotime($account['locked_until']) > time();

            // Plain-text compare (see backend/api.php). hash_equals()
            // avoids a timing side-channel, not used as hashing.
            if (!$locked && $account && hash_equals($account['password'], $password)) {
                if ((int) $account['failed_login_attempts'] !== 0 || $account['locked_until'] !== null) {
                    $reset_stmt = $pdo->prepare("UPDATE tams_users SET failed_login_attempts = 0, locked_until = NULL WHERE id = :id");
                    $reset_stmt->execute(['id' => $account['id']]);
                }
                session_login($account);
                redirect('index.php');
            } elseif (!$locked && $account) {
                // A previous lockout that has already expired must not
                // compound into an even stricter one -- see
                // backend/api.php's identical comment for the full
                // reasoning. Reaching this branch (with $locked already
                // false) means locked_until is either null or in the past.
                $priorAttempts = $account['locked_until'] !== null ? 0 : (int) $account['failed_login_attempts'];
                $attempts = $priorAttempts + 1;
                $lockNow = $attempts >= MAX_FAILED_LOGIN_ATTEMPTS;

                // Computed in PHP (already Asia/Jakarta via
                // date_default_timezone_set in config.php), NOT MySQL's own
                // NOW()/DATE_ADD -- see backend/api.php's identical comment
                // for why: the DB server's clock/timezone isn't guaranteed
                // to match PHP's, so a MySQL-computed value compared against
                // PHP's time() could silently read as already expired.
                if ($lockNow) {
                    $fail_stmt = $pdo->prepare("
                        UPDATE tams_users
                        SET failed_login_attempts = :attempts, locked_until = :locked_until
                        WHERE id = :id
                    ");
                    $fail_stmt->bindValue(':locked_until', date('Y-m-d H:i:s', time() + LOGIN_LOCKOUT_MINUTES * 60));
                } else {
                    $fail_stmt = $pdo->prepare("
                        UPDATE tams_users SET failed_login_attempts = :attempts WHERE id = :id
                    ");
                }
                $fail_stmt->bindValue(':attempts', $attempts, PDO::PARAM_INT);
                $fail_stmt->bindValue(':id', $account['id'], PDO::PARAM_INT);
                $fail_stmt->execute();
            }
        }
    }
} elseif (isset($_GET['reason'])) {
    if ($_GET['reason'] === 'timeout') {
        $error = 'Your session has expired. Please log in again.';
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TAMS Admin Panel</title>
    <link rel="stylesheet" href="assets/css/style.css<?= asset_version('assets/css/style.css') ?>">
</head>
<body>
    <div class="login-page">
        <div class="login-card">
            <div class="login-card__brand">
                <span class="login-card__mark">T</span>
                <h1 class="login-card__title">TAMS Admin Panel</h1>
                <p class="login-card__subtitle">Sign in to access the administration dashboard.</p>
            </div>

            <?php if ($error): ?>
                <div class="login-alert"><?= e($error) ?></div>
            <?php endif; ?>

            <form method="post" action="login.php" novalidate>
                <?= csrf_field() ?>
                <div class="form-group">
                    <label class="form-label" for="username">Username</label>
                    <input class="form-control" type="text" id="username" name="username" autocomplete="username" required autofocus value="<?= e($_POST['username'] ?? '') ?>">
                </div>
                <div class="form-group">
                    <label class="form-label" for="password">Password</label>
                    <input class="form-control" type="password" id="password" name="password" autocomplete="current-password" required>
                </div>
                <button type="submit" class="btn btn-primary btn-block">Login</button>
            </form>
        </div>
    </div>
</body>
</html>
