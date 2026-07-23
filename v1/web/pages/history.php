<?php
/**
 * Member History page -- pick a member + date, see that day's trip stats
 * and route. Static shell only; assets/js/history.js drives the dropdown,
 * calendar picker, stats cards, and map via the ajax/*.php endpoints.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

$pageTitle = 'Member History';
$activeMenu = 'history';
$pageHasMap = true;
$pageScript = 'history.js';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">View a member's trip history by date.</p>
</div>

<div class="card history-filter-card">
    <div class="card__body history-filter-bar">
        <div class="form-group history-filter-bar__field">
            <label class="form-label" for="historyMemberSelect">Member</label>
            <select class="form-control" id="historyMemberSelect">
                <option value="">Select a member...</option>
            </select>
        </div>
        <div class="form-group history-filter-bar__field">
            <label class="form-label">Date</label>
            <button type="button" class="btn btn-secondary btn-block" id="historyDateBtn">Select date</button>
        </div>
        <button type="button" class="btn-icon" id="btnHistoryRefreshMembers" title="Reload member list">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
        </button>
    </div>
</div>

<div class="card" id="historyEmptyState">
    <div class="card__body empty-state">Select a member and date to view trip history.</div>
</div>

<div class="card" id="historyNoData" hidden>
    <div class="card__body empty-state">No GPS data for the selected date.</div>
</div>

<div id="historyContent" hidden>
    <div class="history-stats">
        <div class="history-stats__hero">
            <div class="history-stats__hero-value" id="histDistance">0 km</div>
            <div class="history-stats__hero-label">Distance Traveled</div>
        </div>
        <div class="stat-grid stat-grid--compact history-stats__tiles">
            <div class="stat-card">
                <div class="stat-card__icon"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg></div>
                <div><div class="stat-card__value" id="histDuration">-</div><div class="stat-card__label">Duration</div></div>
            </div>
            <div class="stat-card">
                <div class="stat-card__icon"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg></div>
                <div><div class="stat-card__value" id="histPoints">0</div><div class="stat-card__label">GPS Points</div></div>
            </div>
            <div class="stat-card">
                <div class="stat-card__icon"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="20" x2="12" y2="10"/><line x1="18" y1="20" x2="18" y2="4"/><line x1="6" y1="20" x2="6" y2="16"/></svg></div>
                <div><div class="stat-card__value" id="histTimeRange">-</div><div class="stat-card__label">Time Range</div></div>
            </div>
        </div>
    </div>

    <div class="map-container">
        <div id="historyMap" class="map-canvas"></div>
        <div class="map-detail-card" id="historyDetailCard" hidden>
            <button type="button" class="map-detail-card__close" id="historyDetailClose" aria-label="Close">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
            <div class="map-detail-card__title" id="historyDetailTime">-</div>
            <div class="map-detail-card__field" id="historyDetailLandmarkRow">
                <span class="map-detail-card__label">Location</span>
                <span class="map-detail-card__value" id="historyDetailLandmark">Finding nearest location...</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Latitude</span>
                <span class="map-detail-card__value" id="historyDetailLat">-</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Longitude</span>
                <span class="map-detail-card__value" id="historyDetailLng">-</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">GPS Accuracy</span>
                <span class="map-detail-card__value" id="historyDetailAccuracy">-</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Status</span>
                <span class="map-detail-card__value" id="historyDetailStatus">-</span>
            </div>
        </div>
    </div>

    <div class="history-gap-legend" id="historyGapLegend" hidden></div>
</div>

<!-- Calendar date-picker modal -->
<div class="modal-overlay" id="calendarModalOverlay">
    <div class="modal modal--sm">
        <div class="modal__header">
            <h2 class="modal__title">Select Date</h2>
            <button type="button" class="modal__close" data-close-modal aria-label="Close">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
        </div>
        <div class="modal__body">
            <div class="calendar__nav">
                <button type="button" class="btn-icon" id="calPrevMonth" aria-label="Previous month">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
                </button>
                <div class="calendar__month-label" id="calMonthLabel">-</div>
                <button type="button" class="btn-icon" id="calNextMonth" aria-label="Next month">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
                </button>
            </div>
            <div class="calendar__weekdays">
                <span>Sun</span><span>Mon</span><span>Tue</span><span>Wed</span><span>Thu</span><span>Fri</span><span>Sat</span>
            </div>
            <div class="calendar__grid" id="calGrid"></div>
            <p class="form-hint calendar__hint">Highlighted dates have GPS data available.</p>
        </div>
    </div>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
