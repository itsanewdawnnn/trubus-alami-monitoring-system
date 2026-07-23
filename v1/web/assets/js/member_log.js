/**
 * Member Log (audit trail) page logic: table (search/filter/sort/paginate)
 * via ajax/member_log_list.php. Read-only -- no modals, no CRUD -- so this
 * is considerably shorter than members.js, which this file's
 * table/pagination logic otherwise mirrors.
 */
(function () {
    'use strict';

    var escapeHtml = window.TAMS.escapeHtml;
    var getJson = window.TAMS.getJson;
    var formatDateTime = window.TAMS.formatDateTime;

    var state = {
        page: 1,
        perPage: 25,
        q: '',
        actionType: '',
        dateFrom: '',
        dateTo: '',
        sort: 'created_at',
        dir: 'desc',
    };

    var tbody = document.getElementById('logTableBody');
    var pagination = document.getElementById('logPagination');
    var searchInput = document.getElementById('logSearch');
    var actionTypeSelect = document.getElementById('logActionType');
    var dateFromInput = document.getElementById('logDateFrom');
    var dateToInput = document.getElementById('logDateTo');

    var statusLabels = { success: 'Success', failed: 'Failed' };

    /* ---- Data loading ----------------------------------------------------- */
    function loadLogs() {
        tbody.innerHTML = '<tr><td colspan="5" class="loading-state">Loading data...</td></tr>';

        var params = new URLSearchParams({
            page: state.page,
            per_page: state.perPage,
            q: state.q,
            action_type: state.actionType,
            date_from: state.dateFrom,
            date_to: state.dateTo,
            sort: state.sort,
            dir: state.dir,
        });

        getJson('../ajax/member_log_list.php?' + params.toString())
            .then(function (body) {
                if (!body.success) {
                    tbody.innerHTML = '<tr><td colspan="5" class="empty-state">Failed to load data.</td></tr>';
                    return;
                }
                renderRows(body.data);
                renderPagination(body.total, body.page, body.per_page);
            })
            .catch(function (err) {
                if (err.message === 'unauthenticated') return;
                tbody.innerHTML = '<tr><td colspan="5" class="empty-state">Failed to load data. Check your connection.</td></tr>';
            });
    }

    function detailText(row) {
        if (row.message) return row.message;
        if (row.field_before !== null || row.field_after !== null) {
            return (row.field_before || '(none)') + ' → ' + (row.field_after || '(none)');
        }
        return '-';
    }

    function renderRows(rows) {
        if (!rows.length) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty-state">No activity recorded.</td></tr>';
            return;
        }

        var html = rows.map(function (row) {
            var statusClass = row.status === 'failed' ? 'badge badge--danger' : 'badge badge--success';
            return (
                '<tr>' +
                '<td class="cell-muted" data-label="Date/Time (WIB)">' + escapeHtml(formatDateTime(row.created_at)) + '</td>' +
                '<td class="cell-name" data-label="Member">' + escapeHtml(row.user_name) + '</td>' +
                '<td data-label="Activity">' + escapeHtml(row.action_type_label) + '</td>' +
                '<td data-label="Status"><span class="' + statusClass + '">' + escapeHtml(statusLabels[row.status] || row.status) + '</span></td>' +
                '<td data-label="Detail">' + escapeHtml(detailText(row)) + '</td>' +
                '</tr>'
            );
        }).join('');

        tbody.innerHTML = html;
    }

    function renderPagination(total, page, perPage) {
        var totalPages = Math.max(1, Math.ceil(total / perPage));
        var start = total === 0 ? 0 : (page - 1) * perPage + 1;
        var end = Math.min(total, page * perPage);

        var html = '<div class="pagination__info">Showing ' + start + '-' + end + ' of ' + total + ' entries</div>';
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
                    loadLogs();
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
            loadLogs();
        }, 350);
    });

    /* ---- Filters ------------------------------------------------------------ */
    actionTypeSelect.addEventListener('change', function () {
        state.actionType = actionTypeSelect.value;
        state.page = 1;
        loadLogs();
    });
    dateFromInput.addEventListener('change', function () {
        state.dateFrom = dateFromInput.value;
        state.page = 1;
        loadLogs();
    });
    dateToInput.addEventListener('change', function () {
        state.dateTo = dateToInput.value;
        state.page = 1;
        loadLogs();
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
            loadLogs();
        });
    });

    /* ---- Refresh -------------------------------------------------------------- */
    document.getElementById('btnRefresh').addEventListener('click', loadLogs);

    /* ---- Init ----------------------------------------------------------------- */
    var defaultSortTh = document.querySelector('th.sortable[data-sort="' + state.sort + '"]');
    if (defaultSortTh) {
        defaultSortTh.classList.add('sort-active');
    }

    loadLogs();
})();
