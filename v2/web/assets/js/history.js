/**
 * Member History page: member dropdown + custom calendar date picker, trip
 * stats, and route map with gap-segment styling. Backed by
 * ajax/history_members.php, ajax/history_dates.php, and ajax/history.php.
 */
(function () {
    'use strict';

    var getJson = window.TAMS.getJson;
    var escapeHtml = window.TAMS.escapeHtml;
    var toast = window.TAMS.toast;
    var timeOnly = window.TAMS.timeOnly;
    var TAMSMap = window.TAMSMap;

    // Segments with a wider time gap than this are drawn dashed/red (missing
    // data) instead of solid -- matches the Android app's own threshold.
    var GAP_THRESHOLD_SECONDS = 60;

    var today = new Date();
    var todayStr = today.getFullYear() + '-' + pad(today.getMonth() + 1) + '-' + pad(today.getDate());

    var state = {
        memberId: '',
        date: '',
        map: null,
        routeHandle: null,
        calYear: today.getFullYear(),
        calMonth: today.getMonth() + 1, // 1-12
        availableDates: {},
    };

    function pad(n) {
        return n < 10 ? '0' + n : String(n);
    }

    var memberSelect = document.getElementById('historyMemberSelect');
    var dateBtn = document.getElementById('historyDateBtn');
    var emptyState = document.getElementById('historyEmptyState');
    var noDataState = document.getElementById('historyNoData');
    var content = document.getElementById('historyContent');
    var gapLegend = document.getElementById('historyGapLegend');
    var detailCard = document.getElementById('historyDetailCard');

    /* ---- Member dropdown --------------------------------------------------- */
    function loadMembers() {
        var previous = memberSelect.value;
        return getJson('../ajax/history_members.php').then(function (body) {
            if (!body.success) return;
            memberSelect.innerHTML = '<option value="">Select a member...</option>' + body.data.map(function (m) {
                return '<option value="' + m.id + '">' + escapeHtml(m.name) + '</option>';
            }).join('');
            if (previous && body.data.some(function (m) { return String(m.id) === previous; })) {
                memberSelect.value = previous;
            }
        });
    }

    memberSelect.addEventListener('change', function () {
        state.memberId = memberSelect.value;
        if (state.memberId && state.date) {
            fetchHistory();
        }
    });

    document.getElementById('btnHistoryRefreshMembers').addEventListener('click', loadMembers);

    /* ---- Calendar modal ------------------------------------------------------ */
    var calendarOverlay = document.getElementById('calendarModalOverlay');
    var calGrid = document.getElementById('calGrid');
    var calMonthLabel = document.getElementById('calMonthLabel');
    var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

    dateBtn.addEventListener('click', function () {
        if (!state.memberId) {
            toast('Please select a member first.', 'error');
            return;
        }
        var base = state.date ? new Date(state.date + 'T00:00:00') : today;
        state.calYear = base.getFullYear();
        state.calMonth = base.getMonth() + 1;
        openCalendar();
    });

    function openCalendar() {
        calendarOverlay.classList.add('modal-overlay--open');
        loadAvailableDatesAndRender();
    }

    document.getElementById('calPrevMonth').addEventListener('click', function () {
        state.calMonth -= 1;
        if (state.calMonth < 1) { state.calMonth = 12; state.calYear -= 1; }
        loadAvailableDatesAndRender();
    });

    document.getElementById('calNextMonth').addEventListener('click', function () {
        state.calMonth += 1;
        if (state.calMonth > 12) { state.calMonth = 1; state.calYear += 1; }
        loadAvailableDatesAndRender();
    });

    // Same out-of-order-response guard as historyRequestSeq below -- rapid
    // prev/next month clicks (or a member switch while the modal is open)
    // must not let a stale month's response overwrite a newer month's grid.
    var calRequestSeq = 0;

    function loadAvailableDatesAndRender() {
        var monthStr = state.calYear + '-' + pad(state.calMonth);
        var requestId = ++calRequestSeq;
        calMonthLabel.textContent = MONTHS[state.calMonth - 1] + ' ' + state.calYear;
        calGrid.innerHTML = '<div class="calendar__loading">Loading...</div>';

        getJson('../ajax/history_dates.php?user_id=' + encodeURIComponent(state.memberId) + '&month=' + monthStr)
            .then(function (body) {
                if (requestId !== calRequestSeq) return; // superseded by a newer request

                state.availableDates = {};
                if (body.success) {
                    body.data.dates.forEach(function (d) { state.availableDates[d] = true; });
                }
                renderCalendarGrid();
            })
            .catch(function () {
                if (requestId !== calRequestSeq) return;
                calGrid.innerHTML = '<div class="calendar__loading">Failed to load.</div>';
            });
    }

    function renderCalendarGrid() {
        var firstOfMonth = new Date(state.calYear, state.calMonth - 1, 1);
        var startWeekday = firstOfMonth.getDay();
        var daysInMonth = new Date(state.calYear, state.calMonth, 0).getDate();

        var cells = [];
        for (var i = 0; i < startWeekday; i++) {
            cells.push('<span class="calendar__cell calendar__cell--empty"></span>');
        }
        for (var day = 1; day <= daysInMonth; day++) {
            var dateStr = state.calYear + '-' + pad(state.calMonth) + '-' + pad(day);
            var hasData = !!state.availableDates[dateStr];
            var isFuture = dateStr > todayStr;
            var isToday = dateStr === todayStr;
            var isSelected = dateStr === state.date;
            var disabled = !hasData || isFuture;

            var classes = ['calendar__cell'];
            if (disabled) classes.push('calendar__cell--disabled');
            if (hasData && !isFuture) classes.push('calendar__cell--has-data');
            if (isToday) classes.push('calendar__cell--today');
            if (isSelected) classes.push('calendar__cell--selected');

            cells.push(
                '<button type="button" class="' + classes.join(' ') + '" data-date="' + dateStr + '"' + (disabled ? ' disabled' : '') + '>' + day + '</button>'
            );
        }

        calGrid.innerHTML = cells.join('');
        calGrid.querySelectorAll('.calendar__cell[data-date]:not(:disabled)').forEach(function (cell) {
            cell.addEventListener('click', function () {
                state.date = cell.getAttribute('data-date');
                dateBtn.textContent = window.TAMS.formatDate(state.date);
                calendarOverlay.classList.remove('modal-overlay--open');
                fetchHistory();
            });
        });
    }

    window.TAMS.bindModalClose(calendarOverlay);

    /* ---- History fetch + rendering -------------------------------------------- */
    // Bumped on every fetchHistory() call so an in-flight request whose
    // response arrives after a newer one (out-of-order network response, or
    // the admin switching dates again before the first reply lands) is
    // silently discarded instead of overwriting the map/stats with stale data.
    var historyRequestSeq = 0;

    function fetchHistory() {
        if (!state.memberId || !state.date) return;

        var requestId = ++historyRequestSeq;

        emptyState.hidden = true;
        noDataState.hidden = true;
        content.hidden = true;

        getJson('../ajax/history.php?user_id=' + encodeURIComponent(state.memberId) + '&date=' + encodeURIComponent(state.date))
            .then(function (body) {
                if (requestId !== historyRequestSeq) return; // superseded by a newer request

                if (!body.success) {
                    toast(body.message || 'Failed to load history.', 'error');
                    emptyState.hidden = false;
                    return;
                }
                var data = body.data;
                if (data.total_points === 0) {
                    noDataState.hidden = false;
                    return;
                }
                renderHistory(data);
            })
            .catch(function (err) {
                if (requestId !== historyRequestSeq) return; // superseded by a newer request

                if (err.message !== 'unauthenticated') {
                    toast('A network error occurred.', 'error');
                    emptyState.hidden = false;
                }
            });
    }

    function renderHistory(data) {
        document.getElementById('histDistance').textContent = data.total_distance_km.toFixed(2) + ' km';
        document.getElementById('histDuration').textContent = data.duration_formatted;
        document.getElementById('histPoints').textContent = data.total_points;
        document.getElementById('histTimeRange').textContent = timeOnly(data.start_time) + '–' + timeOnly(data.end_time);

        content.hidden = false;
        ensureMap();
        setTimeout(function () { state.map.invalidateSize(); }, 0);

        TAMSMap.clearRoute(state.routeHandle);
        detailCard.hidden = true;
        state.routeHandle = TAMSMap.drawRoute(state.map, data.points, GAP_THRESHOLD_SECONDS, showPointDetail);

        if (state.routeHandle.bounds) {
            state.map.fitBounds(state.routeHandle.bounds, { padding: [32, 32] });
        }

        if (state.routeHandle.gapCount > 0) {
            gapLegend.hidden = false;
            gapLegend.textContent = 'Dashed red line: ' + state.routeHandle.gapCount + ' segment(s) with missing location data (not the actual route)';
        } else {
            gapLegend.hidden = true;
        }
    }

    function ensureMap() {
        if (state.map) return;
        state.map = TAMSMap.initMap('historyMap');
    }

    function showPointDetail(point) {
        document.getElementById('historyDetailTime').textContent = timeOnly(point.recorded_at) + ' WIB';
        document.getElementById('historyDetailLat').textContent = point.latitude.toFixed(6);
        document.getElementById('historyDetailLng').textContent = point.longitude.toFixed(6);
        document.getElementById('historyDetailAccuracy').textContent = '±' + point.accuracy.toFixed(1) + ' m';
        document.getElementById('historyDetailStatus').textContent = point.is_moving ? 'Moving' : 'Stationary';

        // Advisory-only mock-location warning -- see ajax/history.php's own
        // comment on is_mock_location/gnss_satellites_used. Same
        // "corroborating signal, not standalone trigger" reasoning as Live
        // Tracking's identical badge (assets/js/live_tracking.js).
        document.getElementById('historyDetailMockBadge').hidden =
            !(point.is_mock_location === true || point.gnss_satellites_used === 0);

        detailCard.hidden = false;

        var landmarkEl = document.getElementById('historyDetailLandmark');
        landmarkEl.textContent = 'Finding nearest location...';
        TAMSMap.reverseGeocode(point.latitude, point.longitude).then(function (label) {
            landmarkEl.textContent = label || 'Unknown';
        });
    }

    document.getElementById('historyDetailClose').addEventListener('click', function () {
        detailCard.hidden = true;
    });

    /* ---- Init ------------------------------------------------------------------- */
    loadMembers();
})();
