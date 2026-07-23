package com.trubus.tams.ui.screens

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trubus.tams.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

// Shared styling for a normal (complete-data) route segment: solid blue,
// matching this app's existing map accent color.
private fun normalRoutePolyline(points: List<GeoPoint>): Polyline = Polyline().apply {
    setPoints(points)
    outlinePaint.color = Color.rgb(25, 118, 210) // Beautiful Blue
    outlinePaint.strokeWidth = 6.0f
}

// Material Red 700, as a plain ARGB int (the type osmdroid's Paint API
// needs) so this polyline AND the matching legend swatch in
// AdminHistoryScreen (MainAppScreen.kt) share one constant instead of two
// literals that could drift apart under a future edit.
const val HISTORY_GAP_COLOR_ARGB = 0xFFD32F2F.toInt()

// Red and dashed so a gap bridge reads as visually distinct from a real
// recorded path -- the dash pattern is a second signal in case color alone
// is missed (e.g. by a colorblind viewer).
private fun gapRoutePolyline(from: GeoPoint, to: GeoPoint): Polyline = Polyline().apply {
    setPoints(listOf(from, to))
    outlinePaint.color = HISTORY_GAP_COLOR_ARGB
    outlinePaint.strokeWidth = 6.0f
    outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
}

/**
 * Pure visual marker data -- deliberately holds nothing but what actually
 * affects rendering (position/title/snippet/online-offline icon). This is a
 * plain data class specifically so its generated equals()/hashCode() can be
 * used to detect "did this marker actually change" between two update()
 * passes (see OsmMap's diffing below). A per-marker callback used to live
 * here; it was pulled out into OsmMap's own `onMarkerClick` parameter
 * instead, because two structurally-identical markers built on two
 * different recompositions would carry two different (fresh) lambda
 * instances, and a data class's equals() compares every property including
 * a lambda field -- meaning `MapMarkerData` could never compare equal to
 * itself across recompositions with a callback still attached, which would
 * have defeated the whole point of diffing before it started.
 */
data class MapMarkerData(
    val id: Int,
    val position: GeoPoint,
    val title: String,
    val snippet: String,
    val isActive: Boolean
)

// Squared lat/lon distance, not true haversine -- only needs to rank points
// relative to each other for a single tap, and a day's history is bounded
// (a few thousand points), so this stays cheap without an indexing structure.
private fun nearestRoutePointIndex(points: List<GeoPoint>, target: GeoPoint): Int? {
    if (points.isEmpty()) return null
    var bestIndex = 0
    var bestDistSq = Double.MAX_VALUE
    for (i in points.indices) {
        val dLat = points[i].latitude - target.latitude
        val dLon = points[i].longitude - target.longitude
        val distSq = dLat * dLat + dLon * dLon
        if (distSq < bestDistSq) {
            bestDistSq = distSq
            bestIndex = i
        }
    }
    return bestIndex
}

@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    center: GeoPoint? = null,
    zoomLevel: Double = 14.0,
    markers: List<MapMarkerData> = emptyList(),
    // Single stable callback keyed by marker id, replacing a per-marker
    // callback that used to live on MapMarkerData itself -- see that data
    // class's doc comment for why. Looked up fresh from OsmMap's own
    // parameter on every update() pass (not `remember`'d), so it always
    // reflects the caller's latest lambda even on an update pass where the
    // marker diff below finds nothing visually changed and skips rebuilding.
    onMarkerClick: ((Int) -> Unit)? = null,
    routePoints: List<GeoPoint> = emptyList(),
    // Index i means the segment between routePoints[i] and [i+1] bridges a
    // real data gap (see MainAppScreen.computeHistoryGapIndices), not two
    // consecutive fixes -- drawn red/dashed instead of solid blue.
    routeGapAfterIndices: Set<Int> = emptySet(),
    // When set, tapping the history polyline resolves to the nearest actual
    // recorded point and reports its index into `routePoints`, so the caller
    // can show that point's detail (time, reverse-geocoded address, etc.).
    onRoutePointSelected: ((Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Cache marker drawables once per composition instead of re-resolving on
    // every `update` pass (every 5s admin poll) -- drawables are immutable
    // per Context, so reuse is safe.
    val presenceOnlineDrawable = remember { ContextCompat.getDrawable(context, R.drawable.ic_marker_online) }
    val presenceOfflineDrawable = remember { ContextCompat.getDrawable(context, R.drawable.ic_marker_offline) }

    // Start/finish markers share the live markers' ring+disc silhouette for a
    // consistent visual family, with a play-triangle/stop-square glyph
    // mirroring the Member dashboard's own START/STOP buttons.
    val startMarkerDrawable = remember { ContextCompat.getDrawable(context, R.drawable.ic_marker_start) }
    val endMarkerDrawable = remember { ContextCompat.getDrawable(context, R.drawable.ic_marker_finish) }

    // osmdroid's tap-hit tolerance for a Polyline tracks its rendered stroke
    // width, so the route's thin 6px line is also a thin tap target. A
    // separate, wider, fully-transparent overlay (see usage below) fixes
    // this without changing the visible line's appearance. 40dp matches
    // standard touch-target guidance.
    val hitAreaStrokeWidthPx = remember(context) { 40f * context.resources.displayMetrics.density }

    // osmdroid requires a user agent to avoid being blocked by tile servers.
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.filesDir
            osmdroidTileCache = File(context.cacheDir, "osmdroid")
        }
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // Tracks the last point the camera was explicitly animated to, so the
    // live admin map (which recomposes every ~5s with a fresh `center` even
    // when nothing moved) doesn't re-trigger `animateTo` and fight a manual
    // pan/zoom. A real position change still re-centers.
    val lastCenteredPoint = remember { mutableStateOf<GeoPoint?>(null) }

    // Same idea, for the history route: only centers on the start point once
    // per route, so a manually panned/zoomed history map isn't fought.
    val lastCenteredPolylineStart = remember { mutableStateOf<GeoPoint?>(null) }

    // --- Incremental-rendering state (all `remember`'d, not Compose State --
    // these are imperative bookkeeping for the update() callback below, not
    // UI state that should itself trigger recomposition) ---
    //
    // id -> live Marker actually attached to the map right now. The single
    // source of truth for "does a marker for this id already exist", so an
    // unchanged id is updated in place (or skipped entirely) instead of
    // being torn down and recreated every ~5s poll.
    val liveMarkerOverlays = remember { mutableMapOf<Int, Marker>() }
    // The exact marker list content applied on the last pass that touched
    // markers -- MapMarkerData's equals() (position/title/snippet/isActive
    // only, no callback -- see its doc comment) is what makes "did this
    // marker actually change" a meaningful, cheap comparison here. `.value`
    // style (not `by`) to match this file's existing convention (mapViewRef,
    // lastCenteredPoint above) without pulling in the extra
    // getValue/setValue imports a property delegate would need.
    val appliedMarkersState = remember { mutableStateOf<List<MapMarkerData>>(emptyList()) }
    // Route (history polyline) bookkeeping: the actual Polyline/Marker
    // objects currently attached, so a route that hasn't changed -- or has
    // only grown by appended points -- never has to allocate new osmdroid
    // objects, only an unchanged/extended route shape does.
    val appliedRoutePointsState = remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    val appliedGapIndicesState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val routeSegmentOverlays = remember { mutableStateOf<List<Polyline>>(emptyList()) }
    val routeHitAreaOverlay = remember { mutableStateOf<Polyline?>(null) }
    val routeStartEndMarkers = remember { mutableStateOf<Pair<Marker, Marker>?>(null) }

    // osmdroid's MapView runs its own tile-download thread pool and keeps a
    // bitmap tile cache; it is NOT lifecycle-aware on its own -- without
    // this, it keeps fetching/rendering tiles while backgrounded.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef.value?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { ctx ->
            // Configure osmdroid before initializing the MapView to avoid storage write crashes
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                osmdroidBasePath = ctx.filesDir
                osmdroidTileCache = File(ctx.cacheDir, "osmdroid")
                load(ctx, ctx.getSharedPreferences("osmdroid_prefs", android.content.Context.MODE_PRIVATE))
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                controller.setZoom(zoomLevel)
                center?.let {
                    controller.setCenter(it)
                }
                mapViewRef.value = this
            }
        },
        onRelease = { mapView ->
            // Stops tile-loading threads and releases cache/bitmaps when this
            // composable permanently leaves composition (e.g. switching tabs).
            mapView.onDetach()
            mapViewRef.value = null
        },
        update = { mapView ->
            // Tracks whether anything actually visible changed this pass --
            // gates the mapView.invalidate() call at the very end, so a poll
            // tick that received byte-for-byte identical data (or data that
            // differs only in fields nothing on the map reads) triggers no
            // redraw at all, not even an empty one.
            var visualChanged = false

            // ============================================================
            // 1. Live member markers -- diff by id instead of clear+rebuild
            // ============================================================
            if (markers != appliedMarkersState.value) {
                val previousById = appliedMarkersState.value.associateBy { it.id }
                val newIds = markers.mapTo(HashSet(markers.size)) { it.id }

                // Remove markers for ids no longer present.
                val staleIds = liveMarkerOverlays.keys - newIds
                for (staleId in staleIds) {
                    liveMarkerOverlays.remove(staleId)?.let { stale ->
                        mapView.overlays.remove(stale)
                        visualChanged = true
                    }
                }

                // Add new markers / update existing ones in place.
                for (data in markers) {
                    val existing = liveMarkerOverlays[data.id]
                    if (existing == null) {
                        val marker = Marker(mapView).apply {
                            position = data.position
                            title = data.title
                            snippet = data.snippet
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            val drawable = if (data.isActive) presenceOnlineDrawable else presenceOfflineDrawable
                            drawable?.let { icon = it }
                        }
                        liveMarkerOverlays[data.id] = marker
                        mapView.overlays.add(marker)
                        visualChanged = true
                    } else if (previousById[data.id] != data) {
                        // Same Marker object, mutated in place -- no new
                        // allocation, no overlay list churn.
                        existing.position = data.position
                        existing.title = data.title
                        existing.snippet = data.snippet
                        val drawable = if (data.isActive) presenceOnlineDrawable else presenceOfflineDrawable
                        drawable?.let { existing.icon = it }
                        visualChanged = true
                    }
                    // else: this marker's visual data is byte-for-byte the
                    // same as last pass -- touched not at all.
                }

                appliedMarkersState.value = markers
            }

            // Click handling is refreshed unconditionally (cheap -- just a
            // listener reference swap on an already-existing Marker, no
            // visual effect) so it always calls the caller's current
            // onMarkerClick even on a pass where the diff above found
            // nothing to redraw. This is exactly why onClick doesn't live
            // on MapMarkerData: it can change identity every recomposition
            // for reasons that have nothing to do with what's drawn.
            for ((id, marker) in liveMarkerOverlays) {
                marker.setOnMarkerClickListener { clickedMarker, _ ->
                    clickedMarker.showInfoWindow()
                    onMarkerClick?.invoke(id)
                    true
                }
            }

            // ============================================================
            // 2. Route (trip history) polyline -- skip / incremental
            //    append / full rebuild, in that order of preference
            // ============================================================
            if (routePoints != appliedRoutePointsState.value || routeGapAfterIndices != appliedGapIndicesState.value) {
                // A route can be incrementally extended in place (reusing the
                // same Polyline object via setPoints() instead of allocating
                // a new one) only in the simplest case: no gaps involved on
                // either side, and the new points are exactly the old points
                // plus more appended at the end. Anything else (gaps
                // present/changed, route shrank, route replaced with an
                // unrelated one) falls back to a full rebuild of ONLY the
                // route-related overlays -- live member markers above are
                // never touched by this block either way.
                val appliedRoutePoints = appliedRoutePointsState.value
                val appliedGapIndices = appliedGapIndicesState.value
                val canAppendInPlace = appliedGapIndices.isEmpty() &&
                    routeGapAfterIndices.isEmpty() &&
                    appliedRoutePoints.isNotEmpty() &&
                    routePoints.size > appliedRoutePoints.size &&
                    routeSegmentOverlays.value.size == 1 &&
                    routeStartEndMarkers.value != null &&
                    routePoints.subList(0, appliedRoutePoints.size) == appliedRoutePoints

                if (canAppendInPlace) {
                    // Reuse the one existing segment Polyline and the
                    // invisible hit-area Polyline -- setPoints() replaces
                    // their point buffer in place, no new Paint/Polyline
                    // object is allocated for what is visually just an
                    // extension of the same line.
                    routeSegmentOverlays.value.first().setPoints(routePoints)
                    routeHitAreaOverlay.value?.setPoints(routePoints)
                    routeStartEndMarkers.value?.second?.position = routePoints.last()
                    visualChanged = true
                } else {
                    // Full rebuild -- but only of route overlays, tracked
                    // precisely so this never touches live marker overlays.
                    routeSegmentOverlays.value.forEach { mapView.overlays.remove(it) }
                    routeHitAreaOverlay.value?.let { mapView.overlays.remove(it) }
                    routeStartEndMarkers.value?.let { (start, end) ->
                        mapView.overlays.remove(start)
                        mapView.overlays.remove(end)
                    }
                    routeSegmentOverlays.value = emptyList()
                    routeHitAreaOverlay.value = null
                    routeStartEndMarkers.value = null

                    if (routePoints.isNotEmpty()) {
                        val newSegments = mutableListOf<Polyline>()

                        if (routePoints.size >= 2) {
                            // Splits the route into runs of consecutive
                            // normal segments (one polyline each) and
                            // single-segment gap bridges, keeping this O(n)
                            // and allocation-light even with several gaps.
                            var runStart = 0
                            var idx = 0
                            while (idx < routePoints.size - 1) {
                                if (routeGapAfterIndices.contains(idx)) {
                                    if (idx > runStart) {
                                        val seg = normalRoutePolyline(routePoints.subList(runStart, idx + 1))
                                        newSegments.add(seg)
                                        mapView.overlays.add(seg)
                                    }
                                    val gap = gapRoutePolyline(routePoints[idx], routePoints[idx + 1])
                                    newSegments.add(gap)
                                    mapView.overlays.add(gap)
                                    runStart = idx + 1
                                }
                                idx++
                            }
                            // Flush the trailing run after the last gap.
                            if (runStart < routePoints.size - 1) {
                                val seg = normalRoutePolyline(routePoints.subList(runStart, routePoints.size))
                                newSegments.add(seg)
                                mapView.overlays.add(seg)
                            }

                            // Tap handling lives on a separate, invisible,
                            // wide polyline spanning the whole route instead
                            // of the visible segments -- the visible 6px
                            // line's own tap-hit tolerance would be too
                            // narrow to hit reliably.
                            if (onRoutePointSelected != null) {
                                val hitArea = Polyline().apply {
                                    setPoints(routePoints)
                                    outlinePaint.color = Color.TRANSPARENT
                                    outlinePaint.strokeWidth = hitAreaStrokeWidthPx
                                    // Listener is intentionally NOT wired here
                                    // with a captured `routePoints` -- this
                                    // Polyline object gets reused (via
                                    // setPoints() only) across later
                                    // incremental-append passes, so a
                                    // closure captured at creation time would
                                    // go stale the moment the route grows.
                                    // Refreshed unconditionally below instead
                                    // (same pattern as the live marker click
                                    // listeners above), always closing over
                                    // this update() call's current
                                    // `routePoints`.
                                }
                                routeHitAreaOverlay.value = hitArea
                                mapView.overlays.add(hitArea)
                            }
                        }

                        routeSegmentOverlays.value = newSegments

                        val startPoint = routePoints.first()
                        val endPoint = routePoints.last()
                        val startMarker = Marker(mapView).apply {
                            position = startPoint
                            title = "Start Point"
                            subDescription = "Time of first coordinate"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            startMarkerDrawable?.let { icon = it }
                        }
                        val endMarker = Marker(mapView).apply {
                            position = endPoint
                            title = "End Point"
                            subDescription = "Time of last coordinate"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            endMarkerDrawable?.let { icon = it }
                        }
                        mapView.overlays.add(startMarker)
                        mapView.overlays.add(endMarker)
                        routeStartEndMarkers.value = startMarker to endMarker
                    }
                    visualChanged = true
                }

                appliedRoutePointsState.value = routePoints
                appliedGapIndicesState.value = routeGapAfterIndices
            }

            // Refreshed unconditionally (cheap listener reference swap, no
            // visual effect) so tapping the route always resolves against
            // the CURRENT full `routePoints` -- including on a pass that
            // only extended an existing hit-area Polyline via setPoints()
            // above rather than recreating it. See that creation site's
            // comment for why the listener isn't attached there directly.
            if (onRoutePointSelected != null) {
                routeHitAreaOverlay.value?.setOnClickListener(Polyline.OnClickListener { _, _, eventPos ->
                    nearestRoutePointIndex(routePoints, eventPos)?.let { onRoutePointSelected(it) }
                    true
                })
            }

            // Only re-centers the first time this exact route is shown (an
            // append doesn't change the start point, so this stays a no-op
            // through an incrementally-growing route).
            if (routePoints.isNotEmpty()) {
                val startPoint = routePoints.first()
                if (lastCenteredPolylineStart.value != startPoint) {
                    mapView.controller.animateTo(startPoint)
                    lastCenteredPolylineStart.value = startPoint
                    visualChanged = true
                }
            }

            // Only re-centers if moved meaningfully (~11m at the equator per
            // 0.0001 degrees), so a poll tick with the same position doesn't
            // re-trigger a camera animation or fight manual panning.
            if (center != null && routePoints.isEmpty()) {
                val last = lastCenteredPoint.value
                val movedMeaningfully = last == null ||
                    kotlin.math.abs(last.latitude - center.latitude) > 0.0001 ||
                    kotlin.math.abs(last.longitude - center.longitude) > 0.0001
                if (movedMeaningfully) {
                    mapView.controller.animateTo(center)
                    lastCenteredPoint.value = center
                    visualChanged = true
                }
            }

            if (visualChanged) {
                mapView.invalidate()
            }
        },
        modifier = modifier
    )
}
