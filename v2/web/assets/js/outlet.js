/**
 * Outlet page logic: table (search/status/member filter/sort/paginate) via
 * ajax/outlet_list.php, Add/Edit (with an embedded map picker + single
 * Member select) via outlet_create.php/outlet_update.php, Approve/Reject
 * via outlet_approve.php/outlet_reject.php, Delete via outlet_delete.php,
 * Merge via outlet_merge.php, and the Visit Report tab via
 * outlet_visit_report.php. Loaded only on pages/outlet.php. Mirrors
 * assets/js/members.js's table/modal conventions throughout.
 */
(function () {
    'use strict';

    var toast = window.TAMS.toast;
    var escapeHtml = window.TAMS.escapeHtml;
    var getJson = window.TAMS.getJson;
    var postJson = window.TAMS.postJson;
    var formatDate = window.TAMS.formatDate;
    var TAMSMap = window.TAMSMap;

    var state = {
        page: 1,
        perPage: 10,
        q: '',
        status: '',
        memberId: '',
        sort: 'created_at',
        dir: 'desc',
        rowsById: {},
    };

    var tbody = document.getElementById('outletTableBody');
    var pagination = document.getElementById('outletPagination');
    var searchInput = document.getElementById('outletSearch');
    var memberFilter = document.getElementById('outletMemberFilter');
    var statusFilter = document.getElementById('outletStatusFilter');

    var statusBadgeMeta = {
        PENDING: { cls: 'badge--warning', label: 'Pending' },
        APPROVED: { cls: 'badge--success', label: 'Approved' },
        REJECTED: { cls: 'badge--danger', label: 'Rejected' },
    };

    /* ---- Tabs ----------------------------------------------------------- */
    var panelOutlets = document.getElementById('panelOutlets');
    var panelReport = document.getElementById('panelReport');

    document.querySelectorAll('.tab-bar__item').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.tab-bar__item').forEach(function (b) {
                b.classList.remove('tab-bar__item--active');
            });
            btn.classList.add('tab-bar__item--active');

            var tab = btn.getAttribute('data-tab');
            panelOutlets.hidden = tab !== 'outlets';
            panelReport.hidden = tab !== 'report';

            // Fetched on every switch into this tab, not cached -- the
            // Outlets tab (fetched once on page load, refreshed on its own
            // actions) is the default view, so this keeps the Report tab's
            // data from ever going stale without adding a manual refresh
            // control; a single day's aggregate query is cheap enough that
            // this isn't worth optimizing away.
            if (tab === 'report') {
                loadVisitReport();
            }
        });
    });

    /* ---- Data loading ----------------------------------------------------- */
    // Bumped on every loadOutlets() call so an in-flight request superseded
    // by a newer one is discarded -- same out-of-order guard as
    // members.js's membersRequestSeq.
    var outletRequestSeq = 0;

    function loadOutlets() {
        tbody.innerHTML = '<tr><td colspan="5" class="loading-state">Loading data...</td></tr>';

        var requestId = ++outletRequestSeq;

        // member_id is a real, indexed column on tams_outlets (one-to-one
        // from the outlet's side) -- ajax/outlet_list.php filters on it
        // server-side directly, combining with Search/Status as a true AND
        // in SQL, same as the Status filter below. No client-side
        // gather-all-pages workaround needed anymore.
        var params = new URLSearchParams({
            page: state.page,
            per_page: state.perPage,
            q: state.q,
            status: state.status,
            member_id: state.memberId,
            sort: state.sort,
            dir: state.dir,
        });

        getJson('../ajax/outlet_list.php?' + params.toString())
            .then(function (body) {
                if (requestId !== outletRequestSeq) return;

                if (!body.success) {
                    tbody.innerHTML = '<tr><td colspan="5" class="empty-state">Failed to load data.</td></tr>';
                    return;
                }
                renderRows(body.data);
                renderPagination(body.total, body.page, body.per_page);
            })
            .catch(function (err) {
                if (requestId !== outletRequestSeq) return;
                if (err.message === 'unauthenticated') return;
                tbody.innerHTML = '<tr><td colspan="5" class="empty-state">Failed to load data. Check your connection.</td></tr>';
            });
    }

    function assignedMemberText(row) {
        return row.assigned_member ? row.assigned_member.name : '-';
    }

    function renderRows(rows) {
        state.rowsById = {};
        rows.forEach(function (row) { state.rowsById[row.id] = row; });

        if (!rows.length) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty-state">No outlet data.</td></tr>';
            return;
        }

        var html = rows.map(function (row) {
            var statusMeta = statusBadgeMeta[row.status] || statusBadgeMeta.PENDING;
            var canApprove = row.status === 'PENDING' || row.status === 'REJECTED'
                || (row.status === 'APPROVED' && row.has_pending_edit);
            var canReject = row.status === 'PENDING'
                || (row.status === 'APPROVED' && row.has_pending_edit);

            var statusCell = '<div class="outlet-status-cell">'
                + '<span class="badge ' + statusMeta.cls + '">' + escapeHtml(statusMeta.label) + '</span>';
            if (row.status === 'APPROVED' && row.has_pending_edit) {
                statusCell += '<span class="badge badge--warning" title="A change to this outlet is awaiting review.">Pending changes</span>';
            }
            if (row.status === 'REJECTED' && row.rejection_reason) {
                statusCell += '<span class="cell-muted" style="font-size:12px;" title="' + escapeHtml(row.rejection_reason) + '">' + escapeHtml(row.rejection_reason) + '</span>';
            }
            statusCell += '</div>';

            var actions = '<div class="row-actions">';
            if (canApprove) {
                actions += '<button type="button" class="btn-icon btn-approve" title="Approve" data-id="' + row.id + '">'
                    + '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>'
                    + '</button>';
            }
            if (canReject) {
                actions += '<button type="button" class="btn-icon btn-icon--danger btn-reject" title="Reject" data-id="' + row.id + '">'
                    + '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>'
                    + '</button>';
            }
            actions += '<button type="button" class="btn-icon btn-edit" title="Edit" data-id="' + row.id + '">'
                + '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>'
                + '</button>';
            actions += '<button type="button" class="btn-icon btn-merge" title="Merge into another outlet" data-id="' + row.id + '">'
                + '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M6 9v6"/><path d="M18 9a9 9 0 0 1-9 9"/><circle cx="18" cy="6" r="3"/></svg>'
                + '</button>';
            actions += '<button type="button" class="btn-icon btn-icon--danger btn-delete" title="Delete" data-id="' + row.id + '">'
                + '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>'
                + '</button>';
            actions += '</div>';

            return (
                '<tr data-id="' + row.id + '">' +
                '<td data-label="Outlet"><div class="cell-name">' + escapeHtml(row.display_name) + '</div><div class="cell-muted" style="font-size:12px;">' + escapeHtml(row.address) + '</div></td>' +
                '<td data-label="Status">' + statusCell + '</td>' +
                '<td class="cell-muted" data-label="Assigned To">' + escapeHtml(assignedMemberText(row)) + '</td>' +
                '<td class="cell-muted" data-label="Created">' + escapeHtml(formatDate(row.created_at)) + '</td>' +
                '<td data-label="Actions">' + actions + '</td>' +
                '</tr>'
            );
        }).join('');

        tbody.innerHTML = html;
    }

    function renderPagination(total, page, perPage) {
        var totalPages = Math.max(1, Math.ceil(total / perPage));
        var start = total === 0 ? 0 : (page - 1) * perPage + 1;
        var end = Math.min(total, page * perPage);

        var html = '<div class="pagination__info">Showing ' + start + '-' + end + ' of ' + total + ' outlets</div>';
        html += '<div class="pagination__pages">';
        html += '<button type="button" class="pagination__btn" data-page="' + (page - 1) + '"' + (page <= 1 ? ' disabled' : '') + '>&laquo;</button>';

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
                    loadOutlets();
                }
            });
        });
    }

    /* ---- Search (debounced) + Member/Status filters -------------------------- */
    var searchTimer = null;

    function applySearchNow() {
        clearTimeout(searchTimer);
        state.q = searchInput.value.trim();
        state.page = 1;
        loadOutlets();
    }

    searchInput.addEventListener('input', function () {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(applySearchNow, 350);
    });

    // Enter searches immediately instead of waiting out the debounce.
    searchInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            applySearchNow();
        }
    });

    memberFilter.addEventListener('change', function () {
        state.memberId = memberFilter.value;
        state.page = 1;
        loadOutlets();
    });

    statusFilter.addEventListener('change', function () {
        state.status = statusFilter.value;
        state.page = 1;
        loadOutlets();
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
            loadOutlets();
        });
    });

    document.getElementById('btnRefresh').addEventListener('click', loadOutlets);

    /* ---- Add / Edit modal: map picker --------------------------------------- */
    var pickerMap = null;
    var pickerMarker = null;
    var latInput = document.getElementById('outletLatitude');
    var lngInput = document.getElementById('outletLongitude');
    var coordDisplay = document.getElementById('outletCoordDisplay');
    var addressInput = document.getElementById('outletAddress');

    function ensurePickerMap() {
        if (pickerMap) return;
        pickerMap = TAMSMap.initMap('outletPickerMap');
        pickerMap.on('click', function (e) {
            setPickerMarker(e.latlng.lat, e.latlng.lng, true);
        });
    }

    function setPickerMarker(lat, lng, lookupAddress) {
        var latLng = [lat, lng];
        if (pickerMarker) {
            pickerMarker.setLatLng(latLng);
        } else {
            pickerMarker = L.marker(latLng, { draggable: true }).addTo(pickerMap);
            pickerMarker.on('dragend', function () {
                var pos = pickerMarker.getLatLng();
                updateCoordFields(pos.lat, pos.lng);
            });
        }
        updateCoordFields(lat, lng);

        // Best-effort convenience: only fills the Address field if it's
        // still empty, never overwrites something the Admin already typed.
        if (lookupAddress && addressInput.value.trim() === '') {
            TAMSMap.reverseGeocode(lat, lng).then(function (label) {
                if (label && addressInput.value.trim() === '') {
                    addressInput.value = label;
                }
            });
        }
    }

    function updateCoordFields(lat, lng) {
        latInput.value = lat;
        lngInput.value = lng;
        coordDisplay.textContent = lat.toFixed(6) + ', ' + lng.toFixed(6);
    }

    function resetPicker() {
        if (pickerMap && pickerMarker) {
            pickerMap.removeLayer(pickerMarker);
        }
        pickerMarker = null;
        latInput.value = '';
        lngInput.value = '';
        coordDisplay.textContent = 'Click the map to place a pin, or drag the pin to adjust.';
        if (pickerMap) {
            pickerMap.setView([-6.2, 106.816666], 11);
        }
    }

    /* ---- Add / Edit modal: Member select (single, one-to-one) --------------- */
    var memberSelect = document.getElementById('outletMemberSelect');

    function setMemberId(id) {
        memberSelect.value = id ? String(id) : '';
    }

    function getMemberId() {
        var val = parseInt(memberSelect.value, 10);
        return isNaN(val) ? null : val;
    }

    /* ---- Add / Edit modal: open/close/submit -------------------------------- */
    var modalOverlay = document.getElementById('outletModalOverlay');
    var modalTitle = document.getElementById('outletModalTitle');
    var form = document.getElementById('outletForm');
    var submitBtn = document.getElementById('outletSubmitBtn');
    var fieldNames = ['name', 'address', 'location', 'memberId'];

    function capitalize(s) {
        return s.charAt(0).toUpperCase() + s.slice(1);
    }

    function clearErrors() {
        fieldNames.forEach(function (field) {
            var group = document.getElementById('group' + capitalize(field));
            var errorEl = document.getElementById('error' + capitalize(field));
            if (group) group.classList.remove('form-group--invalid');
            if (errorEl) errorEl.textContent = '';
        });
    }

    // Server errors keyed by 'member_id' (snake_case, matches ajax/outlet_*.php);
    // the local <div id="errorMemberId"> follows this file's own camelCase id
    // convention, so a single mapping bridges the two.
    var errorFieldMap = { name: 'name', address: 'address', location: 'location', member_id: 'memberId' };

    function showErrors(errors) {
        Object.keys(errors).forEach(function (field) {
            var mapped = errorFieldMap[field] || field;
            var group = document.getElementById('group' + capitalize(mapped));
            var errorEl = document.getElementById('error' + capitalize(mapped));
            if (group && errorEl) {
                group.classList.add('form-group--invalid');
                errorEl.textContent = errors[field];
            }
        });
    }

    function openAddModal() {
        form.reset();
        clearErrors();
        document.getElementById('outletId').value = '';
        setMemberId(null);
        modalTitle.textContent = 'Add Outlet';
        modalOverlay.classList.add('modal-overlay--open');
        setTimeout(function () {
            ensurePickerMap();
            resetPicker();
            pickerMap.invalidateSize();
        }, 0);
        document.getElementById('outletName').focus();
    }

    function openEditModal(id) {
        var row = state.rowsById[id];
        if (!row) return;

        form.reset();
        clearErrors();
        document.getElementById('outletId').value = row.id;
        document.getElementById('outletName').value = row.name;
        document.getElementById('outletAddress').value = row.address;
        setMemberId(row.assigned_member ? row.assigned_member.id : null);
        modalTitle.textContent = 'Edit Outlet';
        modalOverlay.classList.add('modal-overlay--open');
        setTimeout(function () {
            ensurePickerMap();
            resetPicker();
            setPickerMarker(row.latitude, row.longitude, false);
            pickerMap.setView([row.latitude, row.longitude], 16);
            pickerMap.invalidateSize();
        }, 0);
    }

    function closeOutletModal() {
        modalOverlay.classList.remove('modal-overlay--open');
    }

    document.getElementById('btnAddOutlet').addEventListener('click', openAddModal);

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        clearErrors();

        var id = document.getElementById('outletId').value;
        var payload = {
            name: document.getElementById('outletName').value.trim(),
            address: document.getElementById('outletAddress').value.trim(),
            latitude: latInput.value,
            longitude: lngInput.value,
            member_id: getMemberId(),
        };

        var url = '../ajax/outlet_create.php';
        if (id) {
            payload.id = id;
            url = '../ajax/outlet_update.php';
        }

        submitBtn.disabled = true;
        submitBtn.textContent = 'Saving...';

        postJson(url, payload)
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Saved successfully.', 'success');
                    closeOutletModal();
                    loadOutlets();
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

    /* ---- Approve (direct action, no modal) ---------------------------------- */
    function approveOutlet(id, btn) {
        if (btn.disabled) return;
        btn.disabled = true;

        postJson('../ajax/outlet_approve.php', { id: id })
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Approved.', 'success');
                    loadOutlets();
                } else {
                    toast(body.message || 'Failed to approve.', 'error');
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

    /* ---- Reject modal -------------------------------------------------------- */
    var rejectModalOverlay = document.getElementById('outletRejectModalOverlay');
    var rejectForm = document.getElementById('outletRejectForm');
    var rejectSubmitBtn = document.getElementById('outletRejectSubmitBtn');
    var rejectOutletName = document.getElementById('rejectOutletName');
    var rejectReasonInput = document.getElementById('outletRejectReason');
    var pendingRejectId = null;

    function openRejectModal(id) {
        var row = state.rowsById[id];
        if (!row) return;
        pendingRejectId = id;
        rejectOutletName.textContent = row.display_name;
        rejectReasonInput.value = '';
        document.getElementById('groupRejectReason').classList.remove('form-group--invalid');
        document.getElementById('errorReason').textContent = '';
        rejectModalOverlay.classList.add('modal-overlay--open');
        rejectReasonInput.focus();
    }

    function closeRejectModal() {
        rejectModalOverlay.classList.remove('modal-overlay--open');
        pendingRejectId = null;
    }

    rejectForm.addEventListener('submit', function (e) {
        e.preventDefault();
        if (!pendingRejectId) return;

        rejectSubmitBtn.disabled = true;
        rejectSubmitBtn.textContent = 'Rejecting...';

        postJson('../ajax/outlet_reject.php', { id: pendingRejectId, reason: rejectReasonInput.value.trim() })
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Rejected.', 'success');
                    closeRejectModal();
                    loadOutlets();
                } else if (body.errors) {
                    document.getElementById('groupRejectReason').classList.add('form-group--invalid');
                    document.getElementById('errorReason').textContent = body.errors.reason || body.message;
                } else {
                    toast(body.message || 'Failed to reject.', 'error');
                }
            })
            .catch(function (err) {
                if (err.message !== 'unauthenticated') {
                    toast('A network error occurred.', 'error');
                }
            })
            .finally(function () {
                rejectSubmitBtn.disabled = false;
                rejectSubmitBtn.textContent = 'Reject';
            });
    });

    /* ---- Delete modal ------------------------------------------------------- */
    var deleteModalOverlay = document.getElementById('outletDeleteModalOverlay');
    var deleteOutletName = document.getElementById('deleteOutletName');
    var confirmDeleteBtn = document.getElementById('confirmDeleteOutletBtn');
    var pendingDeleteId = null;

    function openDeleteModal(id) {
        var row = state.rowsById[id];
        if (!row) return;
        pendingDeleteId = id;
        deleteOutletName.textContent = row.display_name;
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

        postJson('../ajax/outlet_delete.php', { id: pendingDeleteId })
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Outlet deleted successfully.', 'success');
                    closeDeleteModal();
                    loadOutlets();
                } else {
                    toast(body.message || 'Failed to delete outlet.', 'error');
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

    /* ---- Merge modal ---------------------------------------------------------
     * Candidates are (re-)fetched from ajax/outlet_list.php (same endpoint the
     * table itself uses) rather than a dedicated endpoint -- see that file's
     * own header comment. Distance is computed client-side (Haversine) against
     * the source outlet's coordinates and used purely to sort/label the
     * candidate list; the server independently re-validates the same radius
     * limit against Remote Management before actually merging (never trust a
     * client-side distance calculation for an authorization decision).
     */
    var mergeModalOverlay = document.getElementById('outletMergeModalOverlay');
    var mergeSourceName = document.getElementById('mergeSourceName');
    var mergeSearchInput = document.getElementById('mergeSearch');
    var mergeTargetSelect = document.getElementById('mergeTargetSelect');
    var confirmMergeBtn = document.getElementById('confirmMergeBtn');
    var pendingMergeSourceId = null;
    var mergeSearchTimer = null;

    function haversineMeters(lat1, lon1, lat2, lon2) {
        var R = 6371000;
        var dLat = (lat2 - lat1) * Math.PI / 180;
        var dLon = (lon2 - lon1) * Math.PI / 180;
        var a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    function formatDistance(meters) {
        if (meters < 1000) return Math.round(meters) + ' m';
        return (meters / 1000).toFixed(1) + ' km';
    }

    function loadMergeCandidates(sourceRow, term) {
        mergeTargetSelect.innerHTML = '<option disabled>Loading...</option>';

        var params = new URLSearchParams({ page: 1, per_page: 50, q: term || '', sort: 'name', dir: 'asc' });

        getJson('../ajax/outlet_list.php?' + params.toString())
            .then(function (body) {
                if (!body.success) {
                    mergeTargetSelect.innerHTML = '<option disabled>Failed to load outlets.</option>';
                    return;
                }
                var candidates = body.data
                    .filter(function (o) { return o.id !== sourceRow.id; })
                    .map(function (o) {
                        return { row: o, distance: haversineMeters(sourceRow.latitude, sourceRow.longitude, o.latitude, o.longitude) };
                    })
                    .sort(function (a, b) { return a.distance - b.distance; });

                if (!candidates.length) {
                    mergeTargetSelect.innerHTML = '<option disabled>No other outlets found.</option>';
                    return;
                }

                mergeTargetSelect.innerHTML = candidates.map(function (c) {
                    return '<option value="' + c.row.id + '">' + escapeHtml(c.row.display_name) + ' — ' + formatDistance(c.distance) + '</option>';
                }).join('');
            })
            .catch(function (err) {
                if (err.message !== 'unauthenticated') {
                    mergeTargetSelect.innerHTML = '<option disabled>A network error occurred.</option>';
                }
            });
    }

    function openMergeModal(id) {
        var row = state.rowsById[id];
        if (!row) return;
        pendingMergeSourceId = id;
        mergeSourceName.textContent = row.display_name;
        mergeSearchInput.value = '';
        document.getElementById('errorMergeTarget').textContent = '';
        mergeModalOverlay.classList.add('modal-overlay--open');
        loadMergeCandidates(row, '');
    }

    function closeMergeModal() {
        mergeModalOverlay.classList.remove('modal-overlay--open');
        pendingMergeSourceId = null;
    }

    mergeSearchInput.addEventListener('input', function () {
        clearTimeout(mergeSearchTimer);
        var term = mergeSearchInput.value.trim();
        mergeSearchTimer = setTimeout(function () {
            var row = state.rowsById[pendingMergeSourceId];
            if (row) loadMergeCandidates(row, term);
        }, 350);
    });

    confirmMergeBtn.addEventListener('click', function () {
        if (!pendingMergeSourceId) return;
        var targetId = parseInt(mergeTargetSelect.value, 10);
        if (!targetId) {
            document.getElementById('errorMergeTarget').textContent = 'Select a target outlet to merge into.';
            return;
        }
        document.getElementById('errorMergeTarget').textContent = '';

        confirmMergeBtn.disabled = true;
        confirmMergeBtn.textContent = 'Merging...';

        postJson('../ajax/outlet_merge.php', { source_id: pendingMergeSourceId, target_id: targetId })
            .then(function (body) {
                if (body.success) {
                    toast(body.message || 'Outlets merged.', 'success');
                    closeMergeModal();
                    loadOutlets();
                } else {
                    toast(body.message || 'Failed to merge outlets.', 'error');
                }
            })
            .catch(function (err) {
                if (err.message !== 'unauthenticated') {
                    toast('A network error occurred.', 'error');
                }
            })
            .finally(function () {
                confirmMergeBtn.disabled = false;
                confirmMergeBtn.textContent = 'Merge';
            });
    });

    /* ---- Visit Report tab ----------------------------------------------------
     * Backed by ajax/outlet_visit_report.php -- one row per active Member who
     * visited at least one outlet on the selected day (Members with zero
     * visits that day are excluded server-side, see that file's own header
     * comment). Fetched lazily on tab switch (see the Tabs section above),
     * not on initial page load. Each row is clickable, opening a read-only
     * detail popup (visitDetailModalOverlay) -- built entirely from this same
     * already-fetched response (visitReportRowsById below), never a second
     * request.
     */
    var visitReportDate = document.getElementById('visitReportDate');
    var visitReportTableBody = document.getElementById('visitReportTableBody');
    var visitReportRequestSeq = 0;
    var visitReportRowsById = {};

    function loadVisitReport() {
        visitReportTableBody.innerHTML = '<tr><td colspan="4" class="loading-state">Loading data...</td></tr>';

        var requestId = ++visitReportRequestSeq;
        var params = new URLSearchParams({ date: visitReportDate.value });

        getJson('../ajax/outlet_visit_report.php?' + params.toString())
            .then(function (body) {
                if (requestId !== visitReportRequestSeq) return;

                if (!body.success) {
                    visitReportTableBody.innerHTML = '<tr><td colspan="4" class="empty-state">Failed to load data.</td></tr>';
                    return;
                }
                renderVisitReportRows(body.data);
            })
            .catch(function (err) {
                if (requestId !== visitReportRequestSeq) return;
                if (err.message === 'unauthenticated') return;
                visitReportTableBody.innerHTML = '<tr><td colspan="4" class="empty-state">Failed to load data. Check your connection.</td></tr>';
            });
    }

    function renderVisitReportRows(rows) {
        visitReportRowsById = {};
        rows.forEach(function (row) { visitReportRowsById[row.member_id] = row; });

        if (!rows.length) {
            visitReportTableBody.innerHTML = '<tr><td colspan="4" class="empty-state">No Member visited any outlet on this date.</td></tr>';
            return;
        }

        visitReportTableBody.innerHTML = rows.map(function (row) {
            var statusBadge = row.meets_target
                ? '<span class="badge badge--success">Target met</span>'
                : '<span class="badge badge--warning">Below target</span>';
            var visitedOutletsText = row.visited_outlets.length ? row.visited_outlets.join(', ') : '-';

            return (
                '<tr data-member-id="' + row.member_id + '">' +
                '<td data-label="Member">' + escapeHtml(row.member_name) + '</td>' +
                '<td data-label="Visits">' + row.visit_count + '</td>' +
                '<td class="cell-muted" data-label="Visited Outlets">' + escapeHtml(visitedOutletsText) + '</td>' +
                '<td data-label="Status">' + statusBadge + '</td>' +
                '</tr>'
            );
        }).join('');
    }

    visitReportDate.addEventListener('change', loadVisitReport);

    /* ---- Visit Report row detail modal (read-only) ---------------------------
     * Populated entirely from the row object already held in
     * visitReportRowsById -- no ajax call on click. visit_details (per-outlet
     * name/confirmed_at/dwell_seconds) is additive on
     * ajax/outlet_visit_report.php's response; visited_outlets (plain name
     * array) is untouched and still drives the table cell above.
     */
    var visitDetailModalOverlay = document.getElementById('visitDetailModalOverlay');
    var visitDetailMemberName = document.getElementById('visitDetailMemberName');
    var visitDetailStatusBadge = document.getElementById('visitDetailStatusBadge');
    var visitDetailUsername = document.getElementById('visitDetailUsername');
    var visitDetailCount = document.getElementById('visitDetailCount');
    var visitDetailOutletList = document.getElementById('visitDetailOutletList');

    function formatDwell(seconds) {
        if (!seconds) return '';
        var m = Math.floor(seconds / 60);
        var s = seconds % 60;
        return m > 0 ? (m + 'm ' + s + 's') : (s + 's');
    }

    function openVisitDetailModal(row) {
        visitDetailMemberName.textContent = row.member_name;
        visitDetailUsername.textContent = row.username || '-';
        visitDetailCount.textContent = row.visit_count + ' visit(s)';
        visitDetailStatusBadge.className = 'badge ' + (row.meets_target ? 'badge--success' : 'badge--warning');
        visitDetailStatusBadge.textContent = row.meets_target ? 'Target met' : 'Below target';

        var details = row.visit_details && row.visit_details.length ? row.visit_details : null;

        if (details) {
            visitDetailOutletList.innerHTML = details.map(function (d) {
                var dwellText = formatDwell(d.dwell_seconds);
                var valueText = window.TAMS.timeOnly(d.confirmed_at) + ' WIB' + (dwellText ? ' (' + dwellText + ' dwell)' : '');
                return (
                    '<div class="map-detail-card__field">' +
                    '<span class="map-detail-card__label">' + escapeHtml(d.outlet_name) + '</span>' +
                    '<span class="map-detail-card__value">' + escapeHtml(valueText) + '</span>' +
                    '</div>'
                );
            }).join('');
        } else {
            // Falls back to the name-only list if visit_details is ever
            // unavailable -- every row shown here has at least one visit
            // (zero-visit Members are excluded server-side), so this is
            // purely a defensive fallback, not an expected path.
            visitDetailOutletList.innerHTML = row.visited_outlets.map(function (name) {
                return '<div class="map-detail-card__field">'
                    + '<span class="map-detail-card__label">' + escapeHtml(name) + '</span>'
                    + '<span class="map-detail-card__value">-</span>'
                    + '</div>';
            }).join('');
        }

        visitDetailModalOverlay.classList.add('modal-overlay--open');
    }

    visitReportTableBody.addEventListener('click', function (e) {
        var tr = e.target.closest('tr[data-member-id]');
        if (!tr) return;
        var row = visitReportRowsById[tr.getAttribute('data-member-id')];
        if (row) openVisitDetailModal(row);
    });

    /* ---- Table action delegation --------------------------------------------- */
    tbody.addEventListener('click', function (e) {
        var btn = e.target.closest('.btn-approve, .btn-reject, .btn-edit, .btn-delete, .btn-merge');
        if (!btn) return;
        var id = parseInt(btn.getAttribute('data-id'), 10);

        if (btn.classList.contains('btn-approve')) {
            approveOutlet(id, btn);
        } else if (btn.classList.contains('btn-reject')) {
            openRejectModal(id);
        } else if (btn.classList.contains('btn-edit')) {
            openEditModal(id);
        } else if (btn.classList.contains('btn-delete')) {
            openDeleteModal(id);
        } else if (btn.classList.contains('btn-merge')) {
            openMergeModal(id);
        }
    });

    /* ---- Shared modal close handlers ----------------------------------------- */
    window.TAMS.bindModalClose(modalOverlay);
    window.TAMS.bindModalClose(rejectModalOverlay);
    window.TAMS.bindModalClose(mergeModalOverlay);
    window.TAMS.bindModalClose(deleteModalOverlay);
    window.TAMS.bindModalClose(visitDetailModalOverlay);

    /* ---- Init ----------------------------------------------------------------- */
    var defaultSortTh = document.querySelector('th.sortable[data-sort="' + state.sort + '"]');
    if (defaultSortTh) {
        defaultSortTh.classList.add('sort-active');
    }

    loadOutlets();
})();
