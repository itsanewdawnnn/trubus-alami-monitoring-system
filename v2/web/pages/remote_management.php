<?php
/**
 * Remote Management page: lets an Administrator change safe, behavioral
 * Android app settings (GPS Update Interval, Sync Interval, ...) without
 * shipping a new APK. Backed by the generic key/value tams_remote_management
 * table -- the set of editable settings is defined once in
 * helpers/functions.php's remote_management_definitions() and rendered here,
 * so adding a new setting later never requires touching this page's markup.
 *
 * Like ota_update.php, this is a single settings form (not a CRUD table),
 * read directly server-side, saved via AJAX (ajax/remote_management_save.php).
 * The Android app reads these same values live from backend/api.php's
 * public GET /app/config route, so Save here takes effect for every
 * installed copy of the app on its next config refresh -- no APK update.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

$definitions = remote_management_definitions();

$stmt = $pdo->query("SELECT config_key, config_value, updated_at FROM tams_remote_management");
$rows = $stmt->fetchAll();

$values = [];
$lastUpdatedAt = null;
foreach ($rows as $row) {
    $values[$row['config_key']] = $row['config_value'];
    if ($row['updated_at'] !== null && ($lastUpdatedAt === null || $row['updated_at'] > $lastUpdatedAt)) {
        $lastUpdatedAt = $row['updated_at'];
    }
}

$pageTitle = 'Remote Management';
$activeMenu = 'remote_management';
$pageScript = 'remote_management.js';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">Change Android app behavior without publishing a new APK. Changes take effect the next time an installed app refreshes its configuration.</p>
</div>

<div class="card" style="max-width: 720px;">
    <div class="card__body">
        <p class="form-hint" style="margin-bottom: 16px;">
            Last updated:
            <strong id="remoteManagementUpdatedAt"><?= $lastUpdatedAt ? e(date('d M Y, H:i', strtotime($lastUpdatedAt)) . ' WIB') : 'Never set' ?></strong>
        </p>

        <form id="remoteManagementForm" novalidate>
            <?php foreach ($definitions as $key => $def): ?>
                <div class="form-group" id="group_<?= e($key) ?>">
                    <label class="form-label" for="field_<?= e($key) ?>"><?= e($def['label']) ?></label>
                    <input
                        type="number"
                        class="form-control"
                        id="field_<?= e($key) ?>"
                        name="<?= e($key) ?>"
                        min="<?= (int) $def['min'] ?>"
                        max="<?= (int) $def['max'] ?>"
                        step="1"
                        value="<?= e((string) ($values[$key] ?? $def['default'])) ?>"
                        required
                    >
                    <div class="form-hint"><?= e($def['hint']) ?> Allowed range: <?= (int) $def['min'] ?>&ndash;<?= (int) $def['max'] ?>. Default: <?= (int) $def['default'] ?>.</div>
                    <div class="form-error" id="error_<?= e($key) ?>"></div>
                </div>
            <?php endforeach; ?>

            <div style="display: flex; justify-content: flex-end; margin-top: var(--space-2);">
                <button type="submit" class="btn btn-primary" id="remoteManagementSubmitBtn">Save</button>
            </div>
        </form>
    </div>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
