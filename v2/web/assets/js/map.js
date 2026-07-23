/**
 * Shared Leaflet helpers for the two map pages (live_tracking.php,
 * history.php), loaded only when $pageHasMap is set. Two modes: a live
 * multi-marker map, and a single-route history map with gap-segment styling.
 */
(function () {
    'use strict';

    var DEFAULT_CENTER = [-6.2, 106.816666]; // Jakarta
    var DEFAULT_ZOOM = 11;

    /**
     * Creates a Leaflet map in the given container id with OSM tiles.
     */
    function initMap(elementId) {
        var map = L.map(elementId, { zoomControl: true }).setView(DEFAULT_CENTER, DEFAULT_ZOOM);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        }).addTo(map);
        return map;
    }

    function circleIcon(color, sizePx) {
        return L.divIcon({
            className: 'tams-map-marker',
            html: '<span style="display:block;width:' + sizePx + 'px;height:' + sizePx + 'px;border-radius:50%;background:' + color + ';border:2px solid #fff;box-shadow:0 1px 4px rgba(15,23,42,0.45);"></span>',
            iconSize: [sizePx, sizePx],
            iconAnchor: [sizePx / 2, sizePx / 2],
        });
    }

    var ICON_ONLINE = circleIcon('#16a34a', 18);
    var ICON_OFFLINE = circleIcon('#9ca3af', 16);

    var ICON_START = L.divIcon({
        className: 'tams-map-marker',
        html: '<span style="display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:#16a34a;border:2px solid #fff;box-shadow:0 1px 4px rgba(15,23,42,0.45);"><svg width="10" height="10" viewBox="0 0 24 24" fill="#fff"><polygon points="5,3 19,12 5,21"/></svg></span>',
        iconSize: [24, 24],
        iconAnchor: [12, 12],
    });

    var ICON_FINISH = L.divIcon({
        className: 'tams-map-marker',
        html: '<span style="display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;background:#dc2626;border:2px solid #fff;box-shadow:0 1px 4px rgba(15,23,42,0.45);"><svg width="10" height="10" viewBox="0 0 24 24" fill="#fff"><rect x="5" y="5" width="14" height="14"/></svg></span>',
        iconSize: [24, 24],
        iconAnchor: [12, 12],
    });

    /**
     * Adds/updates/removes member markers on `map` to match `members`.
     * `registry` ({ [user_id]: L.Marker }) is kept by the caller across
     * polls so markers persist (and just move) instead of being rebuilt
     * every 5 seconds. Returns the members that were actually plotted.
     */
    function syncMemberMarkers(map, registry, members, onClick) {
        var seenIds = {};
        var plotted = [];

        members.forEach(function (member) {
            if (member.latitude === null || member.longitude === null) {
                return;
            }
            seenIds[member.user_id] = true;
            plotted.push(member);

            var latLng = [member.latitude, member.longitude];
            var icon = member.status === 'active' ? ICON_ONLINE : ICON_OFFLINE;
            var existing = registry[member.user_id];

            if (existing) {
                existing.setLatLng(latLng);
                existing.setIcon(icon);
                existing._tamsMember = member;
            } else {
                var marker = L.marker(latLng, { icon: icon }).addTo(map);
                marker._tamsMember = member;
                marker.on('click', function () {
                    onClick(marker._tamsMember);
                });
                registry[member.user_id] = marker;
            }
        });

        Object.keys(registry).forEach(function (id) {
            if (!seenIds[id]) {
                map.removeLayer(registry[id]);
                delete registry[id];
            }
        });

        return plotted;
    }

    /**
     * Draws a history route: solid polyline for consecutive points, red
     * dashed where the gap exceeds `gapThresholdSeconds` (missing data, not
     * a real path). Adds start/finish markers.
     *
     * Returns a handle for clearRoute() plus the gap count (for the legend).
     */
    function drawRoute(map, points, gapThresholdSeconds, onPointClick) {
        var group = L.layerGroup().addTo(map);
        if (!points.length) {
            return { group: group, gapCount: 0, bounds: null };
        }

        var gapCount = 0;
        var segments = [];
        var current = { latlngs: [[points[0].latitude, points[0].longitude]], isGap: false };

        for (var i = 1; i < points.length; i++) {
            var prev = points[i - 1];
            var curr = points[i];
            var gapSeconds = (new Date(curr.recorded_at.replace(' ', 'T')).getTime()
                - new Date(prev.recorded_at.replace(' ', 'T')).getTime()) / 1000;
            var isGap = gapSeconds > gapThresholdSeconds;

            if (isGap) gapCount++;

            if (isGap !== current.isGap) {
                segments.push(current);
                current = { latlngs: [[prev.latitude, prev.longitude]], isGap: isGap };
            }
            current.latlngs.push([curr.latitude, curr.longitude]);
        }
        segments.push(current);

        segments.forEach(function (seg) {
            L.polyline(seg.latlngs, {
                color: seg.isGap ? '#d32f2f' : '#1976d2',
                weight: 4,
                opacity: seg.isGap ? 0.85 : 0.9,
                dashArray: seg.isGap ? '6, 8' : null,
            }).addTo(group);
        });

        // Wide invisible overlay makes the thin route easy to tap without
        // changing the visible line width.
        if (onPointClick) {
            var allLatLngs = points.map(function (p) { return [p.latitude, p.longitude]; });
            var hitLine = L.polyline(allLatLngs, { color: '#000', opacity: 0, weight: 22 }).addTo(group);
            hitLine.on('click', function (e) {
                var nearest = nearestPoint(points, e.latlng);
                if (nearest) onPointClick(nearest);
            });
        }

        var startPoint = points[0];
        var finishPoint = points[points.length - 1];
        L.marker([startPoint.latitude, startPoint.longitude], { icon: ICON_START })
            .addTo(group)
            .on('click', function () { if (onPointClick) onPointClick(startPoint); });
        if (points.length > 1) {
            L.marker([finishPoint.latitude, finishPoint.longitude], { icon: ICON_FINISH })
                .addTo(group)
                .on('click', function () { if (onPointClick) onPointClick(finishPoint); });
        }

        var bounds = L.latLngBounds(points.map(function (p) { return [p.latitude, p.longitude]; }));
        return { group: group, gapCount: gapCount, bounds: bounds };
    }

    function clearRoute(handle) {
        if (handle && handle.group) {
            handle.group.clearLayers();
            handle.group.remove();
        }
    }

    // Squared-distance comparison, not haversine -- sufficient for ranking
    // a handful of nearby points against each other.
    function nearestPoint(points, latlng) {
        var best = null;
        var bestDist = Infinity;
        points.forEach(function (p) {
            var dLat = p.latitude - latlng.lat;
            var dLng = p.longitude - latlng.lng;
            var dist = dLat * dLat + dLng * dLng;
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        });
        return best;
    }

    /* ---- Reverse geocoding (with a session-lifetime cache) ---------------- */
    var geocodeCache = {};

    /**
     * Resolves a short "near X" label via ajax/reverse_geocode.php, cached
     * by ~11m-rounded coordinate for the page's lifetime. Never rejects --
     * a failed lookup resolves to null.
     */
    function reverseGeocode(lat, lng) {
        var key = lat.toFixed(4) + ',' + lng.toFixed(4);
        if (geocodeCache.hasOwnProperty(key)) {
            return Promise.resolve(geocodeCache[key]);
        }
        return window.TAMS.getJson('../ajax/reverse_geocode.php?lat=' + encodeURIComponent(lat) + '&lng=' + encodeURIComponent(lng))
            .then(function (body) {
                var label = body.success && body.data ? body.data.label : null;
                geocodeCache[key] = label;
                return label;
            })
            .catch(function () {
                return null;
            });
    }

    window.TAMSMap = {
        initMap: initMap,
        syncMemberMarkers: syncMemberMarkers,
        drawRoute: drawRoute,
        clearRoute: clearRoute,
        reverseGeocode: reverseGeocode
    };
})();
