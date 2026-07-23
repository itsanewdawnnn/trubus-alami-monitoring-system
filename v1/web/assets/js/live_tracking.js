/**
 * Live Tracking page: polls ajax/live_tracking.php every 5 seconds
 * (matching the Android app's polling interval) and drives both the
 * "Active Members" list and "Real-Time Map" map from that one feed.
 */
(function () {
    'use strict';

    var getJson = window.TAMS.getJson;
    var escapeHtml = window.TAMS.escapeHtml;
    var formatDateTime = window.TAMS.formatDateTime;
    var TAMSMap = window.TAMSMap;

    var POLL_INTERVAL_MS = 5000;

    var state = {
        members: [],
        map: null,
        markerRegistry: {},
        pollTimer: null,
        selectedUserId: null,
    };

    var activeMemberList = document.getElementById('activeMemberList');
    var statActiveMember = document.getElementById('statActiveMember');
    var statMoving = document.getElementById('statMoving');
    var panelActive = document.getElementById('panelActive');
    var panelMap = document.getElementById('panelMap');
    var detailCard = document.getElementById('liveDetailCard');

    /* ---- Tabs --------------------------------------------------------------- */
    document.querySelectorAll('.tab-bar__item').forEach(function (btn) {
        btn.addEventListener('click', function () {
            document.querySelectorAll('.tab-bar__item').forEach(function (b) {
                b.classList.remove('tab-bar__item--active');
            });
            btn.classList.add('tab-bar__item--active');

            var tab = btn.getAttribute('data-tab');
            panelActive.hidden = tab !== 'active';
            panelMap.hidden = tab !== 'map';

            if (tab === 'map') {
                ensureMap();
                // Leaflet needs a visible container to size its tiles -- the
                // panel was display:none until this click.
                setTimeout(function () {
                    state.map.invalidateSize();
                }, 0);
            }
        });
    });

    function ensureMap() {
        if (state.map) return;
        state.map = TAMSMap.initMap('liveMap');
        renderMarkers();
    }

    /* ---- Movement/status text --------------------------------------------- */
    function movementSnippet(member) {
        if (member.status !== 'active') {
            return 'OFFLINE (last update ' + formatDateTime(member.updated_at) + ')';
        }
        return member.is_moving ? 'ACTIVE - Moving' : 'ACTIVE - Stationary';
    }

    /* ---- Active Member list --------------------------------------------------- */
    function renderActiveList() {
        var activeMembers = state.members.filter(function (m) { return m.status === 'active'; });

        if (!activeMembers.length) {
            activeMemberList.innerHTML = '<li class="empty-state">No members are currently active.</li>';
            return;
        }

        activeMemberList.innerHTML = activeMembers.map(function (m) {
            var dotClass = m.is_moving ? 'member-list__dot--moving' : 'member-list__dot--idle';
            return (
                '<li class="member-list__item" data-user-id="' + m.user_id + '">' +
                '<div class="member-list__main">' +
                '<span class="member-list__dot ' + dotClass + '"></span>' +
                '<div class="member-list__body">' +
                '<div class="member-list__name">' + escapeHtml(m.name) + '</div>' +
                (m.note ? '<div class="member-list__note">' + escapeHtml(m.note) + '</div>' : '') +
                '</div>' +
                '</div>' +
                '<div class="member-list__meta">' + escapeHtml(movementSnippet(m)) + '</div>' +
                '</li>'
            );
        }).join('');

        activeMemberList.querySelectorAll('.member-list__item').forEach(function (li) {
            li.addEventListener('click', function () {
                var id = parseInt(li.getAttribute('data-user-id'), 10);
                var member = state.members.find(function (m) { return m.user_id === id; });
                if (!member) return;

                document.querySelector('.tab-bar__item[data-tab="map"]').click();
                selectMember(member);
            });
        });
    }

    /* ---- Summary cards ------------------------------------------------------- */
    function renderSummary() {
        var total = state.members.length;
        var active = state.members.filter(function (m) { return m.status === 'active'; }).length;
        var moving = state.members.filter(function (m) { return m.status === 'active' && m.is_moving; }).length;

        statActiveMember.textContent = active + '/' + total;
        statMoving.textContent = moving + '/' + Math.max(active, 0);
    }

    /* ---- Map markers ----------------------------------------------------------- */
    function renderMarkers() {
        if (!state.map) return;
        TAMSMap.syncMemberMarkers(state.map, state.markerRegistry, state.members, function (member) {
            selectMember(member);
        });

        // Keep the detail card in sync if the selected member's marker moved.
        if (state.selectedUserId !== null) {
            var updated = state.members.find(function (m) { return m.user_id === state.selectedUserId; });
            if (updated && updated.latitude !== null) {
                populateDetailCard(updated, false);
            }
        }
    }

    /* ---- Detail card ------------------------------------------------------------ */
    function selectMember(member) {
        if (member.latitude === null || member.longitude === null) {
            window.TAMS.toast(member.name + ' is currently offline and has no current position.', 'error');
            return;
        }
        state.selectedUserId = member.user_id;
        ensureMap();
        state.map.setView([member.latitude, member.longitude], Math.max(state.map.getZoom(), 15));
        populateDetailCard(member, true);
    }

    function populateDetailCard(member, lookupLandmark) {
        document.getElementById('liveDetailName').textContent = member.name;
        document.getElementById('liveDetailNote').textContent = member.note || '';
        document.getElementById('liveDetailNote').style.display = member.note ? 'block' : 'none';

        var badge = document.getElementById('liveDetailStatusBadge');
        badge.textContent = member.status === 'active' ? 'ONLINE' : 'OFFLINE';
        badge.className = 'badge ' + (member.status === 'active' ? 'badge--success' : 'badge--danger');

        document.getElementById('liveDetailMovement').textContent = member.status === 'active'
            ? (member.is_moving ? 'Moving' : 'Stationary')
            : '';

        document.getElementById('liveDetailLat').textContent = member.latitude.toFixed(6);
        document.getElementById('liveDetailLng').textContent = member.longitude.toFixed(6);
        document.getElementById('liveDetailAccuracy').textContent = member.accuracy !== null ? ('±' + member.accuracy.toFixed(1) + ' m') : '-';
        document.getElementById('liveDetailUpdated').textContent = formatDateTime(member.updated_at);

        detailCard.hidden = false;

        if (lookupLandmark) {
            var landmarkEl = document.getElementById('liveDetailLandmark');
            landmarkEl.textContent = 'Finding nearest location...';
            TAMSMap.reverseGeocode(member.latitude, member.longitude).then(function (label) {
                landmarkEl.textContent = label || 'Unknown';
            });
        }
    }

    document.getElementById('liveDetailClose').addEventListener('click', function () {
        detailCard.hidden = true;
        state.selectedUserId = null;
    });

    /* ---- Polling (paused while the tab/window isn't visible) ------------------- */
    function pollOnce() {
        return getJson('../ajax/live_tracking.php')
            .then(function (body) {
                if (!body.success) return;
                state.members = body.data;
                renderActiveList();
                renderSummary();
                renderMarkers();
            })
            .catch(function (err) {
                // No toast -- a single failed poll on a flaky connection isn't
                // worth interrupting the admin; the next poll just retries.
                if (err.message !== 'unauthenticated') {
                    console.error('[TAMS] live_tracking poll failed', err);
                }
            });
    }

    function startPolling() {
        stopPolling();
        state.pollTimer = setInterval(pollOnce, POLL_INTERVAL_MS);
    }

    function stopPolling() {
        if (state.pollTimer) {
            clearInterval(state.pollTimer);
            state.pollTimer = null;
        }
    }

    document.addEventListener('visibilitychange', function () {
        if (document.hidden) {
            stopPolling();
        } else {
            pollOnce();
            startPolling();
        }
    });

    document.getElementById('btnLiveRefresh').addEventListener('click', pollOnce);

    /* ---- Init ------------------------------------------------------------------- */
    pollOnce();
    startPolling();
})();
