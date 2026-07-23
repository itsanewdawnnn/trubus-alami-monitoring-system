<?php
/**
 * Members CRUD page. Renders a static shell only -- assets/js/members.js
 * populates the table/pagination/modals via the ajax/members_*.php endpoints,
 * so search/sort/page/CRUD are JSON round-trips, not full page reloads.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

$pageTitle = 'Members';
$activeMenu = 'members';
$pageScript = 'members.js';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">Manage member data (name, username, password, status).</p>
    <button type="button" class="btn btn-primary" id="btnAddMember">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        Add Member
    </button>
</div>

<div class="toolbar">
    <div class="search-box">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" class="form-control" id="memberSearch" placeholder="Search by name or username...">
    </div>
    <div class="toolbar__spacer"></div>
    <button type="button" class="btn-icon" id="btnRefresh" title="Reload data">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
    </button>
</div>

<div class="table-wrap">
    <table>
        <thead>
            <tr>
                <th class="sortable" data-sort="name">Name <span class="sort-arrow">&#9662;</span></th>
                <th class="sortable" data-sort="username">Username <span class="sort-arrow">&#9662;</span></th>
                <th>App Version</th>
                <th>Device</th>
                <th class="sortable" data-sort="created_at">Created At <span class="sort-arrow">&#9662;</span></th>
                <th>Actions</th>
            </tr>
        </thead>
        <tbody id="memberTableBody">
            <tr><td colspan="6" class="loading-state">Loading data...</td></tr>
        </tbody>
    </table>
    <div class="pagination" id="memberPagination"></div>
</div>

<!-- Add / Edit modal -->
<div class="modal-overlay" id="memberModalOverlay">
    <div class="modal">
        <form id="memberForm" novalidate>
            <div class="modal__header">
                <h2 class="modal__title" id="memberModalTitle">Add Member</h2>
                <button type="button" class="modal__close" data-close-modal aria-label="Close">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </button>
            </div>
            <div class="modal__body">
                <input type="hidden" id="memberId" name="id">

                <div class="form-group" id="groupName">
                    <label class="form-label" for="memberName">Name</label>
                    <input type="text" class="form-control" id="memberName" name="name" maxlength="100" required>
                    <div class="form-error" id="errorName"></div>
                </div>

                <div class="form-group" id="groupUsername">
                    <label class="form-label" for="memberUsername">Username</label>
                    <input type="text" class="form-control" id="memberUsername" name="username" maxlength="50" required>
                    <div class="form-error" id="errorUsername"></div>
                </div>

                <div class="form-group" id="groupPassword">
                    <label class="form-label" for="memberPassword">Password <span class="form-optional" id="passwordOptionalHint"></span></label>
                    <input type="password" class="form-control" id="memberPassword" name="password" autocomplete="new-password">
                    <div class="form-hint" id="passwordHint">Leave blank if you don't want to change the password.</div>
                    <div class="form-error" id="errorPassword"></div>
                </div>

                <div class="form-group" id="groupStatus">
                    <label class="form-label" for="memberStatus">Status</label>
                    <select class="form-control" id="memberStatus" name="status">
                        <option value="1">Active</option>
                        <option value="0">Inactive</option>
                    </select>
                    <div class="form-error" id="errorStatus"></div>
                </div>
            </div>
            <div class="modal__footer">
                <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
                <button type="submit" class="btn btn-primary" id="memberSubmitBtn">Save</button>
            </div>
        </form>
    </div>
</div>

<!-- Delete confirmation modal -->
<div class="modal-overlay" id="deleteModalOverlay">
    <div class="modal modal--sm">
        <div class="modal__body modal__body--headless">
            <div class="modal__icon-warning">
                <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
            </div>
            <p class="modal__confirm-text">Delete member <strong id="deleteMemberName"></strong>? This action cannot be undone.</p>
        </div>
        <div class="modal__footer">
            <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
            <button type="button" class="btn btn-danger" id="confirmDeleteBtn">Delete</button>
        </div>
    </div>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
