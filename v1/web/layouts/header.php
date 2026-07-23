<?php
/**
 * Shared page shell: <head> + sidebar/topbar markup. Included from every
 * file under pages/, which must set $pageTitle and $activeMenu first.
 *
 *   $pageTitle  (string) shown in the topbar heading (not the <title> tag,
 *               which is a fixed string).
 *   $activeMenu (string) 'dashboard' | 'members' | 'live_tracking' | 'history' --
 *               tells layouts/sidebar.php which nav item to mark active.
 *   $pageHasMap (bool, optional) loads Leaflet. Only live_tracking.php/history.php set this.
 */
$activeMenu = $activeMenu ?? '';
$pageHasMap = $pageHasMap ?? false;

// Powers the topbar's "who am I" button, which opens the Account Menu
// dropdown (assets/js/account_menu.js). The dropdown's My Profile item
// opens the My Profile modal (layouts/footer.php + assets/js/profile.js);
// its Logout item is a plain link to logout.php, unchanged from the one
// layouts/sidebar.php used to render in its own removed footer.
$adminName = $_SESSION['admin_name'] ?? '';
$adminUsername = $_SESSION['admin_username'] ?? '';
$adminInitial = $adminName !== '' ? mb_strtoupper(mb_substr($adminName, 0, 1)) : '?';
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TAMS Admin Panel</title>
    <meta name="csrf-token" content="<?= e(csrf_token()) ?>">
    <link rel="stylesheet" href="../assets/css/style.css<?= asset_version('assets/css/style.css') ?>">
    <?php if ($pageHasMap): ?>
        <!-- No SRI hash: cdnjs serves an immutable, version-pinned URL, and
             a stale hash would silently break the map. -->
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
    <?php endif; ?>
</head>
<body>
    <div class="app-shell">
        <?php require __DIR__ . '/sidebar.php'; ?>

        <div class="app-shell__backdrop" id="sidebarBackdrop"></div>

        <div class="main">
            <header class="topbar">
                <button type="button" class="topbar__toggle" id="sidebarToggle" aria-label="Open/close menu">
                    <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
                </button>
                <h1 class="topbar__title"><?= e($pageTitle) ?></h1>

                <!-- Account menu: dropdown open/close (including closing
                     itself on any .account-menu__item click, generic to
                     both items below and any added later) is owned by
                     assets/js/account_menu.js. My Profile stays entirely
                     owned by assets/js/profile.js (it listens on
                     #accountMenuProfileBtn below); Logout is a plain link,
                     same as layouts/sidebar.php's former footer -- no
                     feature-specific JS, so its endpoint/CSRF/session/
                     redirect behavior is byte-for-byte unchanged. -->
                <div class="account-menu" id="accountMenu">
                    <button type="button" class="topbar__user" id="topbarUserBtn" data-name="<?= e($adminName) ?>" data-username="<?= e($adminUsername) ?>" title="Account menu" aria-haspopup="true" aria-expanded="false" aria-controls="accountMenuDropdown">
                        <span class="topbar__user-avatar"><?= e($adminInitial) ?></span>
                        <span class="topbar__user-name"><?= e($adminName) ?></span>
                        <svg class="topbar__user-chevron" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                    </button>
                    <div class="account-menu__dropdown" id="accountMenuDropdown">
                        <button type="button" class="account-menu__item" id="accountMenuProfileBtn">
                            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                            <span>My Profile</span>
                        </button>
                        <div class="account-menu__divider"></div>
                        <a href="../logout.php" class="account-menu__item account-menu__item--danger">
                            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                            <span>Logout</span>
                        </a>
                    </div>
                </div>
            </header>

            <main class="page">
