/**
 * Members CRUD page logic: table (search/sort/paginate) via
 * ajax/members_list.php, add/edit/delete modals via members_save.php and
 * members_delete.php. Loaded only on pages/members.php.
 */
(function () {
    'use strict';

    var toast = window.TAMS.toast;
    var escapeHtml = window.TAMS.escapeHtml;
    var getJson = window.TAMS.getJson;
    var postJson = window.TAMS.postJson;
    var formatDate = window.TAMS.formatDate;

    var state = {
        page: 1,
        perPage: 10,
        q: '',
        sort: 'created_at',
        dir: 'desc',
    };

    var tbody = document.getElementById('memberTableBody');
    var pagination = document.getElementById('memberPagination');
    var searchInput = document.getElementById('memberSearch');

    var versionStatusMeta = {
        latest: { badge: 'badge--success', label: 'Latest' },
        outdated: { badge: 'badge--warning', label: 'Supported (Outdated)' },
        unsupported: { badge: 'badge--danger', label: 'Unsupported' },
        unknown: { badge: 'badge--neutral', label: 'Not reported yet' },
    };

    /* ---- Data loading ----------------------------------------------------- */
    // Bumped on every loadMembers() call so an in-flight request whose
    // response arrives after a newer one (e.g. clicking two different rows'
    // Force Override toggles in quick succession, each triggering their own
    // reload) is discarded instead of overwriting the table with stale data
    // -- same out-of-order guard as history.js's historyRequestSeq/calRequestSeq.
    var membersRequestSeq = 0;

    function loadMembers() {
        tbody.innerHTML = '<tr><td colspan="6" class="loading-state">Loading data...</td></tr>';

        var params = new URLSearchParams({
            page: state.page,
            per_page: state.perPage,
            q: state.q,
            sort: state.sort,
            dir: state.dir,
        });

        var requestId = ++membersRequestSeq;

        getJson('../ajax/members_list.php?' + params.toString())
            .then(function (body) {
                if (requestId !== membersRequestSeq) return; // superseded by a newer request

                if (!body.success) {
                    tbody.innerHTML = '<tr><td colspan="6" class="empty-state">Failed to load data.</td></tr>';
                    return;
                }
                renderRows(body.data);
                renderPagination(body.total, body.page, body.per_page);
            })
            .catch(function (err) {
                if (requestId !== membersRequestSeq) return; // superseded by a newer request
                if (err.message === 'unauthenticated') return;
                tbody.innerHTML = '<tr><td colspan="6" class="empty-state">Failed to load data. Check your connection.</td></tr>';
            });
    }

    function renderRows(rows) {
        if (!rows.length) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty-state">No member data.</td></tr>';
            return;
        }

        var html = rows.map(function (row) {
            var statusMeta = versionStatusMeta[row.version_status] || versionStatusMeta.unknown;
            var versionText = row.app_version_name ? ('v' + row.app_version_name) : 'Not reported yet';
            var deviceText = row.device_model
                ? (row.device_model + (row.android_version ? ' (Android ' + row.android_version + ')' : ''))
                : '-';

            // data-label feeds CSS's content: attr(data-label) on narrow
            // screens, where the table collapses into cards (style.css).
            return (
                '<tr data-id="' + row.id + '">' +
                '<td class="cell-name" data-label="Name">' + escapeHtml(row.name) + '</td>' +
                '<td data-label="Username">' + escapeHtml(row.username) + '</td>' +
                '<td data-label="App Version"><span class="badge ' + statusMeta.badge + '" title="' + escapeHtml(statusMeta.label) + '">' + escapeHtml(versionText) + '</span></td>' +
                '<td class="cell-muted" data-label="Device">' + escapeHtml(deviceText) + '</td>' +
                '<td class="cell-muted" data-label="Created At">' + escapeHtml(formatDate(row.created_at)) + '</td>' +
                '<td data-label="Actions">' +
                '<div class="row-actions">' +
                '<button type="button" class="btn-icon btn-force' + (row.force_tracking_hours ? ' btn-icon--active' : '') + '" title="' + (row.force_tracking_hours ? 'Force ON - can Start Location any time. Click to disable.' : 'Force OFF - restricted to operational hours (07:00-16:00 WIB). Click to enable.') + '" data-id="' + row.id + '" data-name="' + escapeHtml(row.name) + '" data-force="' + (row.force_tracking_hours ? '1' : '0') + '">' +
                '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>' +
                '</button>' +
                '<button type="button" class="btn-icon btn-edit" title="Edit" data-id="' + row.id + '" data-name="' + escapeHtml(row.name) + '" data-username="' + escapeHtml(row.username) + '" data-active="' + (row.is_active ? '1' : '0') + '">' +
                '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>' +
                '</button>' +
                '<button type="button" class="btn-icon btn-icon--danger btn-delete" title="Delete" data-id="' + row.id + '" data-name="' + escapeHtml(row.name) + '">' +
                '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
                '</button>' +
                '</div>' +
                '</td>' +
                '</tr>'
            );
        }).join('');

        tbody.innerHTML = html;
    }

    function renderPagination(total, page, perPage) {
        var totalPages = Math.max(1, Math.ceil(total / perPage));
        var start = total === 0 ? 0 : (page - 1) * perPage + 1;
        var end = Math.min(total, page * perPage);

        var html = '<div class="pagination__info">Showing ' + start + '-' + end + ' of ' + total + ' members</div>';
        html += '<div class="pagination__pages">';
        html += '<button type="button" class="pagination__btn" data-page="' + (page - 1) + '"' + (page <= 1 ? ' disabled' : '') + '>&laquo;</button>';

        // Cap the page-number list to 5 buttons, centered on the current page.
        var windowStart = Math.max(1, page - 2);
        var windowEnd = Math.min(totalPages, windowStart + 4);
        windowStart = Math.max(1, windowEnd - 4);

        for (var p = windowStart; p <= windowEnd; p++) {
            html += '<button type="button" class="pagination__btn' + (p === page ? ' pagination__btn--active' : '') + '" data-page="' + p + '">' + p + '</button>';
        }

        html += '<button type="button" class="pagination__btn" data-page="' + (page + 1) + '"' + (page >= totalPages ? ' disabled' : '') + '>&raquo;</button>';
        html += '</div>';

        pagination.innerHTML = html;

        pagination.querySelectorAll('.pagination__btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var target = parseInt(btn.getAttribute('data-page'), 10);
                if (!isNaN(target) && target >= 1 && target <= totalPages) {
                    state.page = target;
                    loadMembers();
                }
            });
        });
    }

    /* ---- Search (debounced) ------------------------------------------------ */
    var searchTimer = null;
    searchInput.addEventListener('input', function () {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(function () {
            state.q = searchInput.value.trim();
            state.page = 1;
            loadMembers();
        }, 350);
    });

    /* ---- Sorting ------------------------------------------------------------ */
    document.querySelectorAll('th.sortable').forEach(function (th) {
        th.addEventListener('click', function () {
            var key = th.getAttribute('data-sort');
            if (state.sort === key) {
                state.dir = state.dir === 'asc' ? 'desc' : 'asc';
            } else {
                state.sort = key;
                state.dir = 'asc';
            }
            document.querySelectorAll('th.sortable').forEach(function (other) {
                other.classList.remove('sort-active');
                var arrow = other.querySelector('.sort-arrow');
                if (arrow) arrow.innerHTML = '&#9662;';
            });
            th.classList.add('sort-active');
            var arrowEl = th.querySelector('.sort-arrow');
            if (arrowEl) arrowEl.innerHTML = state.dir === 'asc' ? '&#9652;' : '&#9662;';
            loadMembers();
        });
    });

    /* ---- Refresh -------------------------------------------------------------- */
    document.getElementById('btnRefresh').addEventListener('click', loadMembers);

    /* ---- Add / Edit modal ------------------------------------------------------ */
    var modalOverlay = document.getElementById('memberModalOverlay');
    var modalTitle = document.getElementById('memberModalTitle');
    var form = document.getElementById('memberForm');
    var submitBtn = document.getElementById('memberSubmitBtn');
    var passwordHint = document.getElementById('passwordHint');
    var passwordOptionalHint = document.getElementById('passwordOptionalHint');

    function clearErrors() {
        ['name', 'username', 'password', 'status'].forEach(function (field) {
            document.getElementById('group' + capitalize(field)).classList.remove('form-group--invalid');
            document.getElementById('error' + capitalize(field)).textContent = '';
        });
    }

    function capitalize(s) {
        return s.charAt(0).toUpperCase() + s.slice(1);
    }

    function showErrors(errors) {
        Object.keys(errors).forEach(function (field) {
            var group = document.getElementById('group' + capitalize(field));
            var errorEl = document.getElementById('error' + capitalize(field));
            if (group && errorEl) {
                group.classList.add('form-group--invalid');
                errorEl.textContent = errors[field];
            }
        });
    }

    function openAddModal() {
        form.reset();
        clearErrors();
        document.getElementById('memberId').value = '';
        document.getElementById('memberStatus').value = '1';
        modalTitle.textContent = 'Add Member';
        document.getElementById('memberPassword').setAttribute('required', 'required');
        passwordHint.style.display = 'none';
        passwordOptionalHint.textContent = '';
        modalOverlay.classList.add('modal-overlay--open');
        document.getElementById('memberName').focus();
    }

    function openEditModal(btn) {
        form.reset();
        clearErrors();
        document.getElementById('memberId').value = btn.getAttribute('data-id');
        document.getElementById('memberName').value = btn.getAttribute('data-name');
        document.getElementById('memberUsername').value = btn.getAttribute('data-username');
        document.getElementById('memberStatus').value = btn.getAttribute('data-active');
        modalTitle.textContent = 'Edit Member';
        document.getElementById('memberPassword').removeAttribute('required');
        passwordHint.style.display = 'block';
        passwordOptionalHint.textContent = '(optional)';
        modalOverlay.classList.add('modal-overlay--open');
        document.getElementById('memberName').focus();
    }

    function closeMemberModal() {
        modalOverlay.classList.remove('modal-overlay--open');
    }

    document.getElementById('btnAddMember').addEventListener('click', openAddModal);

    tbody.addEventListener('click', function (e) {
        var editBtn = e.target.closest('.btn-edit');
        if (editBtn) {
            openEditModal(editBtn);
            return;
        }
        var deleteBtn = e.target.closest('.btn-delete');
        if (deleteBtn) {
            openDeleteModal(deleteBtn);
            return;
        }
        var forceBtn = e.target.closest('.btn-force');
        if (forceBtn) {
            toggleForce(forceBtn);
        }
    });

    /* ---- Actions column: Force Override --------------------------------
     * Lives alongside Edit/Delete in the Actions column -- there is no
     * separate Control column (see pages/members.php).
     */
    function toggleForce(btn) {
        if (btn.disabled) return;
        var id = btn.getAttribute('data-id');
        var nextEnabled = btn.getAttribute('data-force') !== '1';
        btn.disabled = true;

        postJson('../ajax/members_force_toggle.php', { id: id, enabled: nextEnabled })
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Updated successfully.', 'success');
                    loadMembers();
                } else {
                    toast(body.message || 'Failed to update Force Override.', 'error');
                    btn.disabled = false;
                }
            })
            .catch(function (err) {
                btn.disabled = false;
                if (err.message !== 'unauthenticated') {
                    toast('A network error occurred.', 'error');
                }
            });
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        clearErrors();

        var payload = {
            id: document.getElementById('memberId').value,
            name: document.getElementById('memberName').value.trim(),
            username: document.getElementById('memberUsername').value.trim(),
            password: document.getElementById('memberPassword').value,
            status: document.getElementById('memberStatus').value,
        };

        submitBtn.disabled = true;
        submitBtn.textContent = 'Saving...';

        postJson('../ajax/members_save.php', payload)
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Saved successfully.', 'success');
                    closeMemberModal();
                    loadMembers();
                } else if (body.errors) {
                    showErrors(body.errors);
                } else {
                    toast(body.message || 'Failed to save data.', 'error');
                }
            })
            .catch(function (err) {
                if (err.message !== 'unauthenticated') {
                    toast('A network error occurred.', 'error');
                }
            })
            .finally(function () {
                submitBtn.disabled = false;
                submitBtn.textContent = 'Save';
            });
    });

    /* ---- Delete modal ------------------------------------------------------- */
    var deleteModalOverlay = document.getElementById('deleteModalOverlay');
    var deleteMemberName = document.getElementById('deleteMemberName');
    var confirmDeleteBtn = document.getElementById('confirmDeleteBtn');
    var pendingDeleteId = null;

    function openDeleteModal(btn) {
        pendingDeleteId = btn.getAttribute('data-id');
        deleteMemberName.textContent = btn.getAttribute('data-name');
        deleteModalOverlay.classList.add('modal-overlay--open');
    }

    function closeDeleteModal() {
        deleteModalOverlay.classList.remove('modal-overlay--open');
        pendingDeleteId = null;
    }

    confirmDeleteBtn.addEventListener('click', function () {
        if (!pendingDeleteId) return;
        confirmDeleteBtn.disabled = true;
        confirmDeleteBtn.textContent = 'Deleting...';

        postJson('../ajax/members_delete.php', { id: pendingDeleteId })
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Member deleted successfully.', 'success');
                    closeDeleteModal();
                    loadMembers();
                } else {
                    toast(body.message || 'Failed to delete member.', 'error');
                }
            })
            .catch(function (err) {
                if (err.message !== 'unauthenticated') {
                    toast('A network error occurred.', 'error');
                }
            })
            .finally(function () {
                confirmDeleteBtn.disabled = false;
                confirmDeleteBtn.textContent = 'Delete';
            });
    });

    /* ---- Shared modal close handlers (backdrop click, close/cancel buttons, Esc) --- */
    window.TAMS.bindModalClose(modalOverlay);
    window.TAMS.bindModalClose(deleteModalOverlay);

    /* ---- Init ----------------------------------------------------------------- */
    var defaultSortTh = document.querySelector('th.sortable[data-sort="' + state.sort + '"]');
    if (defaultSortTh) {
        defaultSortTh.classList.add('sort-active');
    }

    loadMembers();
})();
