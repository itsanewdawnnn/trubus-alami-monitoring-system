package com.trubus.tams.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single-fix ("one-shot") current-location fetch, independent of
 * [com.trubus.tams.service.MemberLocationService]'s continuous
 * requestLocationUpdates() subscription -- that one belongs to the
 * foreground Service's own tracking lifecycle; this is for callers that just
 * need one coordinate right now and then stop.
 *
 * Originally private to `com.trubus.tams.ui.screens.OutletScreen`'s "Use
 * Current Location" button; extracted here unchanged so
 * [com.trubus.tams.worker.LocationSyncWorker] can reuse the exact same
 * implementation for its stale-fix stopgap instead of duplicating it -- see
 * that class's own doc comment.
 *
 * Returns null on any failure (permission missing, no fix available,
 * cancelled) -- callers must treat this the same as "no fix available right
 * now", never crash.
 *
 * Manually wrapped in [suspendCancellableCoroutine] rather than
 * kotlinx-coroutines-play-services' `Task.await()` extension -- that
 * library isn't a dependency of this app (see build.gradle.kts's own notes
 * on deliberately-omitted dependencies). Same manual-wrapping style
 * [com.trubus.tams.data.geocoding.NominatimSearchService] and
 * [com.trubus.tams.data.geocoding.ReverseGeocodingService] already use for
 * their own single-shot async calls.
 */
object OneShotLocationProvider {
    suspend fun getCurrentLocation(context: Context): Location? {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return null

        return suspendCancellableCoroutine { continuation ->
            val cancellationSource = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellationSource.cancel() }
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationSource.token)
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            } catch (_: SecurityException) {
                // Permission revoked between the check above and this call --
                // narrow enough a race that failing closed (null) is correct.
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
