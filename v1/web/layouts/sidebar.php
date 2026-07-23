<?php
/**
 * Sidebar navigation. Included by layouts/header.php ($activeMenu is
 * already set). Collapses to an off-canvas panel on small screens via CSS
 * (.app-shell--sidebar-open) rather than separate mobile markup.
 */
$navItems = [
    'dashboard' => ['label' => 'Dashboard', 'href' => 'dashboard.php', 'icon' => 'grid'],
    'members' => ['label' => 'Members', 'href' => 'members.php', 'icon' => 'users'],
    'live_tracking' => ['label' => 'Live Tracking', 'href' => 'live_tracking.php', 'icon' => 'map-pin'],
    'history' => ['label' => 'Member History', 'href' => 'history.php', 'icon' => 'route'],
    'member_log' => ['label' => 'Member Log', 'href' => 'member_log.php', 'icon' => 'list'],
    // Its own section rather than folded under an "Application Management"
    // parent menu: this sidebar has no established pattern for nested/
    // collapsible groups, and forcing one in for a single child page would
    // add UI complexity with nothing yet to justify it (KISS). Revisit if a
    // second Application Management page is ever added.
    'ota_update' => ['label' => 'OTA Update', 'href' => 'ota_update.php', 'icon' => 'smartphone'],
    'remote_management' => ['label' => 'Remote Management', 'href' => 'remote_management.php', 'icon' => 'sliders'],
];

$icons = [
    'grid' => '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
    'users' => '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    'map-pin' => '<path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/>',
    'route' => '<circle cx="6" cy="19" r="2.5"/><circle cx="18" cy="5" r="2.5"/><path d="M8.5 19H15a3 3 0 0 0 3-3v-1a3 3 0 0 0-3-3H9a3 3 0 0 1-3-3v-1a3 3 0 0 1 3-3h6.5"/>',
    'smartphone' => '<rect x="5" y="2" width="14" height="20" rx="2"/><line x1="12" y1="18" x2="12.01" y2="18"/>',
    'sliders' => '<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/>',
    'list' => '<line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>',
];
?>
<aside class="sidebar" id="sidebar">
    <div class="sidebar__brand">
        <span class="sidebar__brand-mark">T</span>
        <span class="sidebar__brand-name">TAMS Admin</span>
    </div>

    <nav class="sidebar__nav">
        <?php foreach ($navItems as $key => $item): ?>
            <a href="<?= e($item['href']) ?>" class="sidebar__link<?= $activeMenu === $key ? ' sidebar__link--active' : '' ?>">
                <svg class="sidebar__icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><?= $icons[$item['icon']] ?></svg>
                <span><?= e($item['label']) ?></span>
            </a>
        <?php endforeach; ?>
    </nav>
</aside>
