<?php
/**
 * Live Tracking page -- web equivalent of the Android app's Admin
 * "Active Members" + "Real-Time Map" tabs. Static shell only; both tabs
 * are driven by assets/js/live_tracking.js polling ajax/live_tracking.php.
 */
require __DIR__ . '/../config.php';
require __DIR__ . '/../helpers/functions.php';
require __DIR__ . '/../security/csrf.php';
require __DIR__ . '/../security/auth.php';

require_login();

$pageTitle = 'Live Tracking';
$activeMenu = 'live_tracking';
$pageHasMap = true;
$pageScript = 'live_tracking.js';
require __DIR__ . '/../layouts/header.php';
?>

<div class="page-header">
    <p class="page-header__desc">Monitor member position and status in real time.</p>
    <button type="button" class="btn-icon" id="btnLiveRefresh" title="Reload now">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
    </button>
</div>

<div class="stat-grid stat-grid--compact">
    <div class="stat-card">
        <div class="stat-card__icon">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
        </div>
        <div>
            <div class="stat-card__value" id="statActiveMember">0/0</div>
            <div class="stat-card__label">Active Members</div>
        </div>
    </div>
    <div class="stat-card stat-card--success">
        <div class="stat-card__icon">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
        </div>
        <div>
            <div class="stat-card__value" id="statMoving">0/0</div>
            <div class="stat-card__label">Currently Moving</div>
        </div>
    </div>
</div>

<div class="tab-bar" role="tablist">
    <button type="button" class="tab-bar__item tab-bar__item--active" data-tab="active" role="tab">Active Members</button>
    <button type="button" class="tab-bar__item" data-tab="map" role="tab">Real-Time Map</button>
</div>

<div class="tab-panel" id="panelActive">
    <div class="table-wrap">
        <ul class="member-list" id="activeMemberList">
            <li class="loading-state">Loading data...</li>
        </ul>
    </div>
</div>

<div class="tab-panel" id="panelMap" hidden>
    <div class="map-container">
        <div id="liveMap" class="map-canvas"></div>
        <div class="map-detail-card" id="liveDetailCard" hidden>
            <button type="button" class="map-detail-card__close" id="liveDetailClose" aria-label="Close">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
            <div class="map-detail-card__title" id="liveDetailName"></div>
            <div class="map-detail-card__subtitle" id="liveDetailNote"></div>
            <div class="map-detail-card__row">
                <span class="badge" id="liveDetailStatusBadge"></span>
                <span class="map-detail-card__movement" id="liveDetailMovement"></span>
            </div>
            <div class="map-detail-card__field" id="liveDetailLandmarkRow">
                <span class="map-detail-card__label">Location</span>
                <span class="map-detail-card__value" id="liveDetailLandmark">Finding nearest location...</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Latitude</span>
                <span class="map-detail-card__value" id="liveDetailLat">-</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Longitude</span>
                <span class="map-detail-card__value" id="liveDetailLng">-</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">GPS Accuracy</span>
                <span class="map-detail-card__value" id="liveDetailAccuracy">-</span>
            </div>
            <div class="map-detail-card__field">
                <span class="map-detail-card__label">Last Updated</span>
                <span class="map-detail-card__value" id="liveDetailUpdated">-</span>
            </div>
        </div>
    </div>
</div>

<?php require __DIR__ . '/../layouts/footer.php'; ?>
