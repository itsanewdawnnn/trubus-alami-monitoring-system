<?php
/**
 * Dashboard: at-a-glance member counts. Read-only, so plain server-rendered
 * PHP -- no AJAX/JS needed.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

// Admin-role rows are excluded from every count -- this panel manages Members only.
$stmt = $pdo->query("
    SELECT
        COUNT(*) AS total,
        SUM(CASE WHEN is_active = 1 THEN 1 ELSE 0 END) AS active,
        SUM(CASE WHEN is_active = 0 THEN 1 ELSE 0 END) AS inactive,
        SUM(CASE WHEN created_at >= CURDATE() - INTERVAL 30 DAY THEN 1 ELSE 0 END) AS recent
    FROM tams_users
    WHERE role = 'member'
");
$stats = $stmt->fetch() ?: ['total' => 0, 'active' => 0, 'inactive' => 0, 'recent' => 0];

$pageTitle = 'Dashboard';
$activeMenu = 'dashboard';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">Welcome back, <?= e($_SESSION['admin_name'] ?? '') ?>.</p>
</div>

<div class="stat-grid">
    <div class="stat-card">
        <div class="stat-card__icon">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
        </div>
        <div>
            <div class="stat-card__value"><?= (int) $stats['total'] ?></div>
            <div class="stat-card__label">Total Members</div>
        </div>
    </div>

    <div class="stat-card stat-card--success">
        <div class="stat-card__icon">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
        </div>
        <div>
            <div class="stat-card__value"><?= (int) $stats['active'] ?></div>
            <div class="stat-card__label">Active Members</div>
        </div>
    </div>

    <div class="stat-card stat-card--danger">
        <div class="stat-card__icon">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
        </div>
        <div>
            <div class="stat-card__value"><?= (int) $stats['inactive'] ?></div>
            <div class="stat-card__label">Inactive Members</div>
        </div>
    </div>

    <div class="stat-card stat-card--info">
        <div class="stat-card__icon">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </div>
        <div>
            <div class="stat-card__value"><?= (int) $stats['recent'] ?></div>
            <div class="stat-card__label">New Members (30 days)</div>
        </div>
    </div>
</div>

<h2 class="section-title">Quick Actions</h2>
<div class="quick-actions">
    <a href="members.php" class="quick-action">
        <span class="quick-action__icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
        </span>
        <span class="quick-action__body">
            <span class="quick-action__title">Manage Members</span>
            <span class="quick-action__desc">Add, edit, search, or delete member data</span>
        </span>
        <svg class="quick-action__chevron" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
    </a>
    <a href="live_tracking.php" class="quick-action">
        <span class="quick-action__icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
        </span>
        <span class="quick-action__body">
            <span class="quick-action__title">Live Tracking</span>
            <span class="quick-action__desc">Monitor members' current position and status</span>
        </span>
        <svg class="quick-action__chevron" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
    </a>
    <a href="history.php" class="quick-action">
        <span class="quick-action__icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="19" r="2.5"/><circle cx="18" cy="5" r="2.5"/><path d="M8.5 19H15a3 3 0 0 0 3-3v-1a3 3 0 0 0-3-3H9a3 3 0 0 1-3-3v-1a3 3 0 0 1 3-3h6.5"/></svg>
        </span>
        <span class="quick-action__body">
            <span class="quick-action__title">Member History</span>
            <span class="quick-action__desc">View routes and trip statistics by date</span>
        </span>
        <svg class="quick-action__chevron" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
    </a>
    <a href="member_log.php" class="quick-action">
        <span class="quick-action__icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
        </span>
        <span class="quick-action__body">
            <span class="quick-action__title">Member Log</span>
            <span class="quick-action__desc">Review the member activity audit trail</span>
        </span>
        <svg class="quick-action__chevron" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
    </a>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
