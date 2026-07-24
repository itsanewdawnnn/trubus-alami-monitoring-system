<?php
/**
 * Outlet management page. Renders a static shell only -- assets/js/outlet.js
 * populates the table/pagination/modals via the ajax/outlet_*.php endpoints,
 * mirroring pages/members.php + assets/js/members.js's own pattern.
 *
 * Two tabs: "Outlets" (approval queue + full CRUD/merge) and "Visit Report"
 * (per-day, per-Member distinct-outlet visit count against the Minimum
 * Outlet Visits target, for Members who visited at least one outlet that
 * day, backed by ajax/outlet_visit_report.php -- see that file's own header
 * comment). The Report tab's data is fetched lazily, the first time the tab
 * is opened (see assets/js/outlet.js), not on initial page load, since the
 * Outlets tab is what's shown by default. Each Report row is clickable,
 * opening a read-only detail popup (#visitDetailModalOverlay below) built
 * entirely from that same already-fetched response -- no extra request.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

// Rendered server-side once per page load, not fetched via ajax -- this is
// the same static list every time the Add/Edit modal opens, only the
// *selected* option differs per outlet (set client-side by
// assets/js/outlet.js from the row's own assigned_member). Also reused,
// unchanged, to populate the toolbar's Member filter dropdown below -- both
// consumers share this one query, so the page still needs no seventh
// "members for select" endpoint for either purpose.
$activeMembersStmt = $pdo->query("SELECT id, name FROM tams_users WHERE role = 'member' AND is_active = 1 ORDER BY name ASC");
$activeMembersForAssignment = $activeMembersStmt->fetchAll();

$outletMinVisits = remote_management_values($pdo, ['outlet_min_visits_per_day'])['outlet_min_visits_per_day'];

$pageTitle = 'Outlet';
$activeMenu = 'outlet';
$pageHasMap = true;
$pageScript = 'outlet.js';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">Review outlet submissions, or create and assign an outlet directly to a Member.</p>
    <button type="button" class="btn btn-primary" id="btnAddOutlet">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        Add Outlet
    </button>
</div>

<div class="tab-bar" role="tablist">
    <button type="button" class="tab-bar__item tab-bar__item--active" data-tab="outlets" role="tab">Outlets</button>
    <button type="button" class="tab-bar__item" data-tab="report" role="tab">Visit Report</button>
</div>

<div class="tab-panel" id="panelOutlets">
    <div class="toolbar">
        <div class="search-box">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            <input type="text" class="form-control" id="outletSearch" placeholder="Search by outlet name, address, or creator...">
        </div>
        <select class="form-control toolbar__select" id="outletMemberFilter" aria-label="Filter by Member">
            <option value="">All Members</option>
            <?php foreach ($activeMembersForAssignment as $m): ?>
                <option value="<?= (int) $m['id'] ?>"><?= e($m['name']) ?></option>
            <?php endforeach; ?>
        </select>
        <select class="form-control toolbar__select" id="outletStatusFilter" aria-label="Filter by Status">
            <option value="">All Statuses</option>
            <option value="PENDING">Pending</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
        </select>
        <div class="toolbar__spacer"></div>
        <button type="button" class="btn-icon" id="btnRefresh" title="Reload data">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
        </button>
    </div>

    <div class="table-wrap">
        <table>
            <thead>
                <tr>
                    <th class="sortable" data-sort="name">Outlet <span class="sort-arrow">&#9662;</span></th>
                    <th class="sortable" data-sort="status">Status <span class="sort-arrow">&#9662;</span></th>
                    <th>Assigned To</th>
                    <th class="sortable" data-sort="created_at">Created <span class="sort-arrow">&#9662;</span></th>
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody id="outletTableBody">
                <tr><td colspan="5" class="loading-state">Loading data...</td></tr>
            </tbody>
        </table>
        <div class="pagination" id="outletPagination"></div>
    </div>
</div>

<div class="tab-panel" id="panelReport" hidden>
    <div class="toolbar">
        <div class="form-group" style="margin-bottom: 0;">
            <label class="form-label" for="visitReportDate">Date</label>
            <input type="date" class="form-control" id="visitReportDate" value="<?= e(date('Y-m-d')) ?>" max="<?= e(date('Y-m-d')) ?>">
        </div>
        <div class="toolbar__spacer"></div>
        <div class="cell-muted" style="font-size: 13px;">
            Minimum Outlet Visits target: <strong><?= (int) $outletMinVisits ?></strong> visit(s)/day
            (configurable on the Remote Management page).
        </div>
    </div>

    <div class="table-wrap">
        <table>
            <thead>
                <tr>
                    <th>Member</th>
                    <th>Visits</th>
                    <th>Visited Outlets</th>
                    <th>Status</th>
                </tr>
            </thead>
            <tbody id="visitReportTableBody">
                <tr><td colspan="4" class="loading-state">Loading data...</td></tr>
            </tbody>
        </table>
    </div>
</div>

<!-- Add / Edit modal -->
<div class="modal-overlay" id="outletModalOverlay">
    <div class="modal modal--lg">
        <form id="outletForm" novalidate>
            <div class="modal__header">
                <h2 class="modal__title" id="outletModalTitle">Add Outlet</h2>
                <button type="button" class="modal__close" data-close-modal aria-label="Close">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </button>
            </div>
            <div class="modal__body">
                <input type="hidden" id="outletId" name="id">
                <input type="hidden" id="outletLatitude" name="latitude">
                <input type="hidden" id="outletLongitude" name="longitude">

                <div class="form-group" id="groupName">
                    <label class="form-label" for="outletName">Outlet Name</label>
                    <input type="text" class="form-control" id="outletName" name="name" maxlength="150" required>
                    <div class="form-error" id="errorName"></div>
                </div>

                <div class="form-group" id="groupAddress">
                    <label class="form-label" for="outletAddress">Address</label>
                    <input type="text" class="form-control" id="outletAddress" name="address" maxlength="255" required>
                    <div class="form-error" id="errorAddress"></div>
                </div>

                <div class="form-group" id="groupLocation">
                    <label class="form-label">Location</label>
                    <div class="map-picker">
                        <div id="outletPickerMap" class="map-picker__canvas"></div>
                    </div>
                    <div class="map-picker__coords" id="outletCoordDisplay">Click the map to place a pin, or drag the pin to adjust.</div>
                    <div class="form-error" id="errorLocation"></div>
                </div>

                <div class="form-group" id="groupMemberId">
                    <label class="form-label" for="outletMemberSelect">Assign to Member</label>
                    <select class="form-control" id="outletMemberSelect" name="member_id" <?= empty($activeMembersForAssignment) ? 'disabled' : 'required' ?>>
                        <?php if (empty($activeMembersForAssignment)): ?>
                            <option value="">No active Members available.</option>
                        <?php else: ?>
                            <option value="">Select a Member...</option>
                            <?php foreach ($activeMembersForAssignment as $m): ?>
                                <option value="<?= (int) $m['id'] ?>"><?= e($m['name']) ?></option>
                            <?php endforeach; ?>
                        <?php endif; ?>
                    </select>
                    <div class="form-error" id="errorMemberId"></div>
                </div>
            </div>
            <div class="modal__footer">
                <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
                <button type="submit" class="btn btn-primary" id="outletSubmitBtn">Save</button>
            </div>
        </form>
    </div>
</div>

<!-- Reject modal -->
<div class="modal-overlay" id="outletRejectModalOverlay">
    <div class="modal modal--sm">
        <form id="outletRejectForm" novalidate>
            <div class="modal__header">
                <h2 class="modal__title">Reject Outlet</h2>
                <button type="button" class="modal__close" data-close-modal aria-label="Close">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </button>
            </div>
            <div class="modal__body">
                <p class="modal__confirm-text" style="margin-bottom: var(--space-4);">Rejecting <strong id="rejectOutletName"></strong>.</p>
                <div class="form-group" id="groupRejectReason">
                    <label class="form-label" for="outletRejectReason">Rejection Reason</label>
                    <textarea class="form-control" id="outletRejectReason" name="reason" maxlength="255" rows="3" required></textarea>
                    <div class="form-error" id="errorReason"></div>
                </div>
            </div>
            <div class="modal__footer">
                <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
                <button type="submit" class="btn btn-danger" id="outletRejectSubmitBtn">Reject</button>
            </div>
        </form>
    </div>
</div>

<!-- Merge modal -->
<div class="modal-overlay" id="outletMergeModalOverlay">
    <div class="modal modal--lg">
        <div class="modal__header">
            <h2 class="modal__title">Merge Outlet</h2>
            <button type="button" class="modal__close" data-close-modal aria-label="Close">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
        </div>
        <div class="modal__body">
            <p class="modal__confirm-text" style="margin-bottom: var(--space-4);">Merging <strong id="mergeSourceName"></strong> into another outlet. Visit history will be transferred to the selected destination. The current outlet will be permanently deleted, while the destination outlet and its assigned Member remain unchanged.</p>
            <div class="form-group">
                <label class="form-label" for="mergeSearch">Search target outlet</label>
                <input type="text" class="form-control" id="mergeSearch" placeholder="Search by name or address...">
            </div>
            <div class="form-group">
                <select class="form-control merge-picker__list" id="mergeTargetSelect" size="8"></select>
                <div class="merge-picker__hint">Only outlets within the configured Outlet Radius can be merged together.</div>
                <div class="form-error" id="errorMergeTarget"></div>
            </div>
        </div>
        <div class="modal__footer">
            <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
            <button type="button" class="btn btn-primary" id="confirmMergeBtn">Merge</button>
        </div>
    </div>
</div>

<!-- Delete confirmation modal -->
<div class="modal-overlay" id="outletDeleteModalOverlay">
    <div class="modal modal--sm">
        <div class="modal__body modal__body--headless">
            <div class="modal__icon-warning">
                <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
            </div>
            <p class="modal__confirm-text">Delete outlet <strong id="deleteOutletName"></strong>? Its visit history is preserved, but it will no longer be active for geofencing.</p>
        </div>
        <div class="modal__footer">
            <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
            <button type="button" class="btn btn-danger" id="confirmDeleteOutletBtn">Delete</button>
        </div>
    </div>
</div>

<!-- Visit Report row detail modal -- read-only, no edit/delete action; opened
     from a Visit Report row click using data already fetched by that tab's
     own ajax/outlet_visit_report.php call (see assets/js/outlet.js), never a
     second request. Reuses the existing modal shell and the same
     label/value row markup (.map-detail-card__field/__label/__value)
     Live Tracking's own detail card already uses, instead of introducing a
     new display component. -->
<div class="modal-overlay" id="visitDetailModalOverlay">
    <div class="modal modal--sm">
        <div class="modal__header">
            <h2 class="modal__title" id="visitDetailMemberName">Visit Detail</h2>
            <button type="button" class="modal__close" data-close-modal aria-label="Close">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
        </div>
        <div class="modal__body">
            <div class="map-detail-card__row" style="margin: 0 0 var(--space-3);">
                <span class="badge" id="visitDetailStatusBadge"></span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Username</span>
                <span class="map-detail-card__value" id="visitDetailUsername">-</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Total Visits</span>
                <span class="map-detail-card__value" id="visitDetailCount">-</span>
            </div>
            <label class="form-label" style="margin-top: var(--space-4);">Visited Outlets</label>
            <div id="visitDetailOutletList"></div>
        </div>
        <div class="modal__footer">
            <button type="button" class="btn btn-secondary" data-close-modal>Close</button>
        </div>
    </div>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
