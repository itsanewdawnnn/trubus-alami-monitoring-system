<?php
/**
 * POST endpoint: saves the single tams_ota_update row (id=1) that drives
 * the OTA update feature, and optionally replaces the published APK file.
 * There is no "generate version.json" step -- backend/api.php's
 * /app/version route always reads this same row live, so saving here takes
 * effect immediately for the Android app.
 *
 * multipart/form-data (not JSON -- a file upload requires it):
 *   version_name, version_code, min_supported_version_code, release_notes,
 *   and an optional `apk` file field. Force Update is no longer a field
 *   here at all -- backend/api.php's /app/version route derives it from
 *   version_code vs min_supported_version_code (see schema.sql's comment on
 *   tams_ota_update). apk_url is no longer a field either -- it's
 *   generated automatically from where the uploaded file is saved, below.
 *
 * `apk` is optional per request (an Admin editing only, say, release notes
 * shouldn't have to re-upload the same APK every time), but mandatory the
 * very first time -- see the validation block below.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login_ajax();
csrf_require_ajax();

// The live file's fixed name -- never the uploader's own filename (that
// would let a client control a server-side path/extension; a hardcoded
// target name closes that off entirely regardless of what the browser
// sends). Also what keeps apk_url stable across every future re-upload, so
// nothing else in the system ever needs to learn a new URL.
const APK_LIVE_FILENAME = 'TAMS.apk';
// Generous but bounded -- independent of whatever upload_max_filesize/
// post_max_size happen to be configured on this hosting (see
// ajax/.htaccess), so a misconfigured host that allows more than intended
// still can't fill the disk with one request.
const APK_MAX_SIZE_BYTES = 200 * 1024 * 1024;
// ZIP local file header signature -- every .apk is a ZIP archive, and a
// genuine one always starts with this regardless of what extension or MIME
// type the browser reports (both are trivially spoofable, so neither is
// trusted alone). The one real content check here.
const ZIP_MAGIC_BYTES = "PK\x03\x04";

function version_str_field(array $source, string $key): string
{
    return isset($source[$key]) && is_string($source[$key]) ? trim($source[$key]) : '';
}

function format_bytes(int $bytes): string
{
    if ($bytes >= 1024 * 1024) {
        return round($bytes / (1024 * 1024), 1) . ' MB';
    }
    if ($bytes >= 1024) {
        return round($bytes / 1024, 1) . ' KB';
    }
    return $bytes . ' B';
}

/**
 * Absolute, publicly-reachable URL for the live APK -- built from the
 * current request's own host rather than a hardcoded domain, so this works
 * unchanged across local/staging/production without any config value to
 * keep in sync. Mirrors config.php's own $isHttps detection.
 */
function apk_public_url(): string
{
    $isHttps = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (!empty($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');
    $scheme = $isHttps ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    return $scheme . '://' . $host . '/download/' . APK_LIVE_FILENAME;
}

$versionName = version_str_field($_POST, 'version_name');
$versionCodeRaw = $_POST['version_code'] ?? null;
$minSupportedVersionCodeRaw = $_POST['min_supported_version_code'] ?? null;
// Not trimmed as a whole -- trimming would collapse intentional blank
// separator lines between notes; each individual line is trimmed instead
// when this is split into a JSON array by backend/api.php's /app/version.
$releaseNotes = isset($_POST['release_notes']) && is_string($_POST['release_notes']) ? $_POST['release_notes'] : '';

$errors = [];

if ($versionName === '') {
    $errors['version_name'] = 'Version Name is required.';
} elseif (mb_strlen($versionName) > 20) {
    $errors['version_name'] = 'Version Name must be at most 20 characters.';
}

// filter_var(..., FILTER_VALIDATE_INT) instead of a loose is_numeric()
// check -- rejects "1.5", "1e3", leading/trailing junk, and empty string
// outright, all of which (int) would otherwise silently coerce into some
// unintended integer.
$versionCode = filter_var($versionCodeRaw, FILTER_VALIDATE_INT);
if ($versionCode === false || $versionCode < 1) {
    $errors['version_code'] = 'Version Code is required and must be a positive whole number.';
}

// Member Version Monitoring's threshold for "unsupported" (red) status on
// the Members page, and (as of this change) also what Force Update is
// derived from -- must never exceed the version currently being published.
$minSupportedVersionCode = filter_var($minSupportedVersionCodeRaw, FILTER_VALIDATE_INT);
if ($minSupportedVersionCode === false || $minSupportedVersionCode < 1) {
    $errors['min_supported_version_code'] = 'Minimum Supported Version Code is required and must be a positive whole number.';
} elseif ($versionCode !== false && $minSupportedVersionCode > $versionCode) {
    $errors['min_supported_version_code'] = 'Minimum Supported Version Code cannot be greater than Version Code.';
}

if (mb_strlen($releaseNotes) > 2000) {
    $errors['release_notes'] = 'Release Notes must be at most 2000 characters.';
}

// Warn (not block) if the new version code doesn't move forward -- an
// operator could legitimately want to re-push the same code after fixing a
// bad APK, so this stays a soft validation error the admin can still see,
// not a hard rule enforced elsewhere.
// Fetched unconditionally (not just when version_code itself is valid) --
// the APK-upload check further below needs to know the current apk_url
// regardless of whether version_code also happens to have a problem this
// request, otherwise a version_code error could be shown alongside a
// spurious "please upload an APK" one even when an APK is already published.
$current_stmt = $pdo->query("SELECT version_code, apk_url FROM tams_ota_update WHERE id = 1 LIMIT 1");
$currentRow = $current_stmt->fetch();
if ($versionCode !== false && empty($errors['version_code']) && $currentRow && $versionCode < (int) $currentRow['version_code']) {
    $errors['version_code'] = 'Version Code cannot be lower than the version currently live (' . (int) $currentRow['version_code'] . ').';
}

// --- APK upload (optional per request, mandatory the first time ever) ---
$fileError = $_FILES['apk']['error'] ?? UPLOAD_ERR_NO_FILE;
$fileProvided = $fileError !== UPLOAD_ERR_NO_FILE;
$apkTmpPath = null;

if ($fileProvided) {
    switch ($fileError) {
        case UPLOAD_ERR_OK:
            $originalName = (string) ($_FILES['apk']['name'] ?? '');
            $tmpName = (string) ($_FILES['apk']['tmp_name'] ?? '');
            $size = (int) ($_FILES['apk']['size'] ?? 0);

            if (strtolower(pathinfo($originalName, PATHINFO_EXTENSION)) !== 'apk') {
                $errors['apk'] = 'The uploaded file must be a .apk file.';
            } elseif ($size <= 0 || $size > APK_MAX_SIZE_BYTES) {
                $errors['apk'] = 'The APK file must be larger than 0 bytes and at most ' . format_bytes(APK_MAX_SIZE_BYTES) . '.';
            } elseif (!is_uploaded_file($tmpName)) {
                // Defense in depth -- move_uploaded_file() below re-checks
                // this itself, but failing loudly here with a clear message
                // is friendlier than a generic 500 later.
                $errors['apk'] = 'File upload failed. Please try again.';
            } else {
                // Content check, not just extension/MIME (both are
                // trivially spoofable) -- a genuine .apk is always a ZIP
                // archive, so it must start with ZIP's local file header
                // signature. Read directly, not via move_uploaded_file()
                // yet -- nothing about reading a few bytes needs that
                // API's "was this really uploaded via HTTP" guarantee,
                // only actually relocating the file does.
                $handle = @fopen($tmpName, 'rb');
                $header = $handle ? fread($handle, 4) : false;
                if ($handle) {
                    fclose($handle);
                }
                if ($header !== ZIP_MAGIC_BYTES) {
                    $errors['apk'] = 'This file does not look like a valid APK (failed content check).';
                } else {
                    $apkTmpPath = $tmpName;
                }
            }
            break;
        case UPLOAD_ERR_INI_SIZE:
        case UPLOAD_ERR_FORM_SIZE:
            $errors['apk'] = 'The APK file is too large for this server\'s current upload limit. Ask your hosting provider to raise upload_max_filesize/post_max_size (see web/ajax/.htaccess).';
            break;
        case UPLOAD_ERR_PARTIAL:
            $errors['apk'] = 'The APK file was only partially uploaded. Please try again.';
            break;
        default:
            error_log('[TAMS Admin] ota_update_save: APK upload error code ' . $fileError);
            $errors['apk'] = 'The APK file could not be uploaded. Please try again.';
    }
} elseif (empty($currentRow['apk_url'] ?? '')) {
    // Nothing published yet and nothing was uploaded this request -- OTA
    // update has no APK to point to at all.
    $errors['apk'] = 'Please upload an APK file.';
}

if (!empty($errors)) {
    json_response(['success' => false, 'message' => 'Please review the fields you entered.', 'errors' => $errors], 422);
}

$downloadDir = __DIR__ . '/../download';
// Computed up front regardless of order below -- apk_public_url() is a pure
// function of this request's own host plus the fixed live filename, so it's
// already known before the file itself is actually moved into place.
$apkUrl = $apkTmpPath !== null ? apk_public_url() : ($currentRow['apk_url'] ?? '');
$apkUploaded = false;

// The DB row is written FIRST, inside a transaction, but not committed until
// AFTER the APK file is confirmed safely in place -- not the other way
// around. Writing the file first and the DB row second (the original order)
// left a real inconsistency window: if the process died or the DB write
// failed at any point after a successful rename(), the live TAMS.apk would
// already be the NEW binary while /app/version kept reporting the OLD
// version_code/min_supported_version_code/release_notes indefinitely --
// exactly the kind of half-completed state a Force Update bump can't afford
// to land in silently. With the DB write inside an uncommitted transaction,
// no other request can observe it early (InnoDB), and a failure at any
// point -- the DB write itself, or the file move/rename after it -- rolls
// back cleanly with no metadata/binary mismatch either way.
$pdo->beginTransaction();
try {
    $stmt = $pdo->prepare("
        INSERT INTO tams_ota_update (id, version_code, min_supported_version_code, version_name, apk_url, release_notes, updated_at)
        VALUES (1, :version_code, :min_supported_version_code, :version_name, :apk_url, :release_notes, NOW())
        ON DUPLICATE KEY UPDATE
            version_code = VALUES(version_code),
            min_supported_version_code = VALUES(min_supported_version_code),
            version_name = VALUES(version_name),
            apk_url = VALUES(apk_url),
            release_notes = VALUES(release_notes),
            updated_at = VALUES(updated_at)
    ");
    // NOW() here (not PHP's date()) is intentionally fine, unlike the login
    // lockout timestamps: this value is only ever displayed back to an
    // Admin as "last updated" (ota_update.php re-reads it on next page
    // load), never compared against PHP's time()/strtotime() the way
    // locked_until is -- so there's no cross-clock correctness risk to
    // avoid here.
    $stmt->execute([
        'version_code' => $versionCode,
        'min_supported_version_code' => $minSupportedVersionCode,
        'version_name' => $versionName,
        'apk_url' => $apkUrl,
        'release_notes' => $releaseNotes,
    ]);

    if ($apkTmpPath !== null) {
        if (!is_dir($downloadDir) && !@mkdir($downloadDir, 0755, true) && !is_dir($downloadDir)) {
            throw new RuntimeException('Could not create the download folder on this server.');
        }

        // Two-step move (upload -> temp name -> atomic rename over the live
        // name) so an in-flight Android download of the CURRENT file is
        // never handed a half-written replacement, and a failure partway
        // through never corrupts/truncates the file that's still live.
        $tempTarget = $downloadDir . '/.' . APK_LIVE_FILENAME . '.uploading-' . bin2hex(random_bytes(8));
        if (!move_uploaded_file($apkTmpPath, $tempTarget)) {
            throw new RuntimeException('Could not save the uploaded APK to the download folder.');
        }
        @chmod($tempTarget, 0644);

        $liveTarget = $downloadDir . '/' . APK_LIVE_FILENAME;
        if (!rename($tempTarget, $liveTarget)) {
            @unlink($tempTarget);
            throw new RuntimeException('Could not publish the uploaded APK.');
        }

        $apkUploaded = true;
    }

    $pdo->commit();

    $apkPath = $downloadDir . '/' . APK_LIVE_FILENAME;
    json_response([
        'success' => true,
        'message' => $apkUploaded ? 'App version configuration saved and APK published successfully.' : 'App version configuration saved successfully.',
        // Echoed back so the page can refresh its "current APK" panel and
        // Force Update preview without a full reload.
        'apk_url' => $apkUrl,
        'apk_size_formatted' => is_file($apkPath) ? format_bytes((int) filesize($apkPath)) : null,
        'force_update' => $minSupportedVersionCode >= $versionCode,
    ]);
} catch (PDOException $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[TAMS Admin] ota_update_save DB failure: ' . $e->getMessage());
    json_response(['success' => false, 'message' => 'A server error occurred.'], 500);
} catch (RuntimeException $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[TAMS Admin] ota_update_save upload failure: ' . $e->getMessage());
    json_response(['success' => false, 'errors' => ['apk' => $e->getMessage()]], 500);
}
