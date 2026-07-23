<?php
/**
 * Version Management page (OTA update configuration). Single-row settings
 * form, not a CRUD table -- tams_ota_update always has exactly one row
 * (id=1, guaranteed by database/schema.sql's seed INSERT), so this reads it
 * directly server-side like dashboard.php's stats do, and only uses AJAX
 * (ajax/ota_update_save.php) for the Save action itself.
 *
 * This is the ONLY place an Administrator edits version.json's underlying
 * data -- backend/api.php's /app/version route always reads live from this
 * same row, so Save here takes effect for the Android app immediately, with
 * no separate "generate version.json" step to remember.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

$stmt = $pdo->query("SELECT version_code, min_supported_version_code, version_name, apk_url, release_notes, updated_at FROM tams_ota_update WHERE id = 1 LIMIT 1");
$version = $stmt->fetch() ?: [
    'version_code' => 0,
    'min_supported_version_code' => 1,
    'version_name' => '',
    'apk_url' => '',
    'release_notes' => '',
    'updated_at' => null,
];

// Force Update is derived, not a stored/editable field -- see
// backend/api.php's /app/version route and schema.sql's tams_ota_update
// comment for the same logic applied server-side for the Android app.
$isForceUpdate = (int) $version['version_code'] > 0
    && (int) $version['min_supported_version_code'] >= (int) $version['version_code'];

// Read straight off disk rather than tracked in the DB -- the DB only ever
// needs to store the URL, so "what's actually published" can't drift out of
// sync with what ajax/ota_update_save.php's upload handler last wrote.
$apkPath = __DIR__ . '/../download/TAMS.apk';
$apkExists = is_file($apkPath);
$apkSizeFormatted = null;
if ($apkExists) {
    $bytes = filesize($apkPath);
    $apkSizeFormatted = $bytes >= 1024 * 1024
        ? round($bytes / (1024 * 1024), 1) . ' MB'
        : round($bytes / 1024, 1) . ' KB';
}

$pageTitle = 'OTA Update';
$activeMenu = 'ota_update';
$pageScript = 'ota_update.js';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">Manage the current version of the TAMS Android app. Changes here take effect for all users immediately -- no need to edit any file on hosting.</p>
</div>

<div class="card" style="max-width: 720px;">
    <div class="card__body">
        <p class="form-hint" style="margin-bottom: 16px;">
            Last updated:
            <strong id="versionUpdatedAt"><?= $version['updated_at'] ? e(date('d M Y, H:i', strtotime($version['updated_at'])) . ' WIB') : 'Never set' ?></strong>
        </p>

        <form id="versionForm" novalidate enctype="multipart/form-data">
            <div class="form-group" id="groupVersionName">
                <label class="form-label" for="versionName">Version Name</label>
                <input type="text" class="form-control" id="versionName" name="version_name" maxlength="20" value="<?= e($version['version_name']) ?>" required>
                <div class="form-hint">Example: 2.7 -- shown to the user in the update dialog.</div>
                <div class="form-error" id="errorVersionName"></div>
            </div>

            <div class="form-row">
                <div class="form-group" id="groupVersionCode">
                    <label class="form-label" for="versionCode">Version Code</label>
                    <input type="number" class="form-control" id="versionCode" name="version_code" min="1" step="1" value="<?= (int) $version['version_code'] ?>" required>
                    <div class="form-hint">Must exactly match versionCode in android/app/build.gradle.kts for the uploaded APK, and be greater than the version code currently live.</div>
                    <div class="form-error" id="errorVersionCode"></div>
                </div>

                <div class="form-group" id="groupMinSupportedVersionCode">
                    <label class="form-label" for="minSupportedVersionCode">Minimum Supported Version Code</label>
                    <input type="number" class="form-control" id="minSupportedVersionCode" name="min_supported_version_code" min="1" step="1" value="<?= (int) $version['min_supported_version_code'] ?>" required>
                    <div class="form-hint">Member Version Monitoring's floor (Members page) -- also drives Force Update below. Must be less than or equal to Version Code.</div>
                    <div class="form-error" id="errorMinSupportedVersionCode"></div>
                </div>
            </div>

            <div class="form-group">
                <span class="form-label">Force Update</span>
                <div>
                    <span class="badge <?= $isForceUpdate ? 'badge--danger' : 'badge--success' ?>" id="forceUpdateBadge">
                        <?= $isForceUpdate ? 'Yes -- mandatory' : 'No -- optional' ?>
                    </span>
                </div>
                <div class="form-hint">Automatic, no longer a manual setting: becomes Yes the moment Minimum Supported Version Code catches up to Version Code (every installed copy is at the floor and must update), No whenever it's still lower.</div>
            </div>

            <div class="form-group" id="groupApk">
                <label class="form-label" for="apkFile">APK File</label>
                <div class="file-dropzone" id="apkDropzone">
                    <svg class="file-dropzone__icon" viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                    <div class="file-dropzone__prompt">
                        <button type="button" class="btn btn-secondary btn-sm" id="apkChooseBtn">Choose File&hellip;</button>
                        <span>or drag and drop a .apk file here</span>
                    </div>
                    <span class="file-dropzone__name" id="apkFileName">No new file chosen</span>
                </div>
                <input type="file" id="apkFile" name="apk" accept=".apk" style="display:none;">
                <div class="form-hint" id="apkCurrentInfo">
                    <?php if ($apkExists): ?>
                        Currently published: <a href="<?= e($version['apk_url']) ?>" id="apkCurrentLink" target="_blank" rel="noopener"><?= e($version['apk_url']) ?></a>
                        (<span id="apkCurrentSize"><?= e($apkSizeFormatted) ?></span>) -- choose a new file above to replace it.
                    <?php else: ?>
                        No APK published yet -- upload one to enable the OTA update feature.
                    <?php endif; ?>
                </div>
                <div class="form-error" id="errorApk"></div>
            </div>

            <div class="form-group" id="groupReleaseNotes">
                <label class="form-label" for="releaseNotes">Release Notes</label>
                <textarea class="form-control" id="releaseNotes" name="release_notes" rows="5" placeholder="One line per note, for example:&#10;Improve GPS accuracy&#10;Fix history bug&#10;Performance improvements"><?= e($version['release_notes']) ?></textarea>
                <div class="form-hint">One note per line. Blank lines are ignored.</div>
                <div class="form-error" id="errorReleaseNotes"></div>
            </div>

            <div class="progress-line" id="saveProgress" hidden>
                <div class="progress-line__bar" id="saveProgressBar"></div>
            </div>

            <div style="display: flex; justify-content: flex-end; margin-top: var(--space-2);">
                <button type="submit" class="btn btn-primary" id="versionSubmitBtn">Save</button>
            </div>
        </form>
    </div>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
