<?php
/**
 * Member Log (audit trail) page. Renders a static shell only --
 * assets/js/member_log.js populates the table/pagination via
 * ajax/member_log_list.php, mirroring members.php + members.js's
 * search/sort/paginate pattern with two extra filters (Activity Type,
 * Date range) layered on top.
 *
 * Read-only: nothing on this page ever writes to tams_member_log. Entries
 * are written exclusively by backend/api.php's POST /activity/log, called
 * by the Android app.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

$actionTypes = member_log_action_types();

$pageTitle = 'Member Log';
$activeMenu = 'member_log';
$pageScript = 'member_log.js';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">Member activity audit trail: profile changes, location tracking, sync failures, and other important events.</p>
</div>

<div class="toolbar">
    <div class="search-box">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" class="form-control" id="logSearch" placeholder="Search by member name or detail...">
    </div>
    <select class="form-control toolbar__select" id="logActionType">
        <option value="">All activity types</option>
        <?php foreach ($actionTypes as $key => $label): ?>
            <option value="<?= e($key) ?>"><?= e($label) ?></option>
        <?php endforeach; ?>
    </select>
    <input type="date" class="form-control toolbar__select" id="logDateFrom" title="From date">
    <input type="date" class="form-control toolbar__select" id="logDateTo" title="To date">
    <div class="toolbar__spacer"></div>
    <button type="button" class="btn-icon" id="btnRefresh" title="Reload data">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
    </button>
</div>

<div class="table-wrap">
    <table>
        <thead>
            <tr>
                <th class="sortable" data-sort="created_at">Date/Time (WIB) <span class="sort-arrow">&#9662;</span></th>
                <th class="sortable" data-sort="user_name">Member <span class="sort-arrow">&#9662;</span></th>
                <th>Activity</th>
                <th>Status</th>
                <th>Detail</th>
            </tr>
        </thead>
        <tbody id="logTableBody">
            <tr><td colspan="5" class="loading-state">Loading data...</td></tr>
        </tbody>
    </table>
    <div class="pagination" id="logPagination"></div>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
