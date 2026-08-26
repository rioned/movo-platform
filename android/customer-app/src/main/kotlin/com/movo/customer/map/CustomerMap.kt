package com.movo.customer.map

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movo.customer.R
import com.movo.customer.model.Coordinate
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private const val SETTLED_HALO_RADIUS_METERS = 100.0
private const val ACTIVE_INNER_HALO_RADIUS_METERS = 90.0
private const val ACTIVE_OUTER_HALO_RADIUS_METERS = 180.0
private const val PULSE_DURATION_MS = 2000L
private const val HALO_BASE_ALPHA = 20
private const val HALO_PEAK_ALPHA = 50

/**
 * Frames the given points safely.
 *
 * osmdroid's `zoomToBoundingBox` computes against the view's pixel size, and it
 * spins on the main thread when the map has not been laid out yet or when the box
 * has no area (identical pickup and destination). Both cases happen in normal use
 * — a restored journey renders before first layout — so they are handled here
 * rather than being allowed to freeze the app.
 */
private fun MapView.fitToPoints(points: List<GeoPoint>, paddingPx: Int = 80) {
    if (points.isEmpty()) return
    val north = points.maxOf { it.latitude }
    val south = points.minOf { it.latitude }
    val east = points.maxOf { it.longitude }
    val west = points.minOf { it.longitude }
    val degenerate = (north - south) < 1e-5 && (east - west) < 1e-5

    val apply = Runnable {
        if (width <= 0 || height <= 0) return@Runnable
        if (points.size == 1 || degenerate) {
            controller.setZoom(16.0)
            controller.setCenter(points.first())
        } else {
            runCatching { zoomToBoundingBox(BoundingBox(north, east, south, west), false, paddingPx) }
                .onFailure {
                    controller.setZoom(14.0)
                    controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
                }
        }
    }
    if (width > 0 && height > 0) apply.run() else post(apply)
}

private class PickupHaloOverlay(map: MapView, center: GeoPoint, radiusMeters: Double, alpha: Int) : Polygon(map) {
    init {
        points = pointsAsCircle(center, radiusMeters)
        fillPaint.color = Color.argb(alpha, 25, 169, 116)
        outlinePaint.color = Color.argb((alpha * 2).coerceAtMost(255), 8, 107, 77)
        outlinePaint.strokeWidth = 2f
    }
}

@Composable
fun CustomerMap(
    pickup: Coordinate?, destination: Coordinate?,
    assignedRider: Coordinate? = null, modifier: Modifier = Modifier,
    discoveryActive: Boolean = false, showPickupHalo: Boolean = false,
    onCoordinateSelected: (Coordinate) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewState = remember { mutableStateOf<MapView?>(null) }
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }

    // Pulse animation: update existing halo overlay paint directly (no full rebuild)
    LaunchedEffect(discoveryActive, reducedMotion, showPickupHalo) {
        if (!discoveryActive || !showPickupHalo || reducedMotion) {
            // Settle to static values when not pulsing
            val map = mapViewState.value ?: return@LaunchedEffect
            val halos = map.overlays.filterIsInstance<PickupHaloOverlay>()
            for (halo in halos) {
                halo.fillPaint.alpha = HALO_BASE_ALPHA + 18
            }
            map.invalidate()
            return@LaunchedEffect
        }
        while (true) {
            withInfiniteAnimationFrameMillis { frameTime ->
                val t = (frameTime % PULSE_DURATION_MS) / PULSE_DURATION_MS.toFloat()
                val progress = if (t < 0.5f) t * 2f else (1f - (t - 0.5f) * 2f)
                val alpha = (HALO_BASE_ALPHA + (progress * (HALO_PEAK_ALPHA - HALO_BASE_ALPHA))).toInt().coerceIn(0, 255)
                val map = mapViewState.value ?: return@withInfiniteAnimationFrameMillis
                val halos = map.overlays.filterIsInstance<PickupHaloOverlay>()
                for (halo in halos) {
                    halo.fillPaint.alpha = alpha
                }
                map.invalidate()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> mapViewState.value?.onResume(); Lifecycle.Event.ON_PAUSE -> mapViewState.value?.onPause(); else -> Unit }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); mapViewState.value?.onPause() }
    }
    AndroidView(modifier = modifier.clipToBounds(), factory = { context ->
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            mapViewState.value = this; setMultiTouchControls(true); controller.setZoom(14.0)
            controller.setCenter(GeoPoint(-1.9441, 30.0619)); overlays.add(CopyrightOverlay(context)); onResume()
        }
    }, update = { map ->
        mapViewState.value = map
        map.overlays.removeAll { it is Marker || it is MapEventsOverlay || it is PickupHaloOverlay }
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean { onCoordinateSelected(Coordinate(point.latitude, point.longitude)); return true }
            override fun longPressHelper(point: GeoPoint): Boolean { onCoordinateSelected(Coordinate(point.latitude, point.longitude)); return true }
        }))
        val points = mutableListOf<GeoPoint>()
        fun marker(coordinate: Coordinate, title: String, icon: Drawable?, anchorY: Float = Marker.ANCHOR_BOTTOM) {
            val point = GeoPoint(coordinate.latitude, coordinate.longitude); points += point
            map.overlays.add(Marker(map).apply {
                position = point; this.title = title; this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, anchorY)
                // No info window: a tap on the map places a pin, it must not open an
                // empty callout bubble over the city.
                infoWindow = null
            })
        }
        val pickupPin = ContextCompat.getDrawable(map.context, R.drawable.ic_pin_pickup)
        val destinationPin = ContextCompat.getDrawable(map.context, R.drawable.ic_pin_destination)
        val motorcycleIcon = ContextCompat.getDrawable(map.context, R.drawable.ic_movo_motorcycle)
        pickup?.let { coordinate ->
            if (showPickupHalo && coordinate.isFinite) {
                val center = GeoPoint(coordinate.latitude, coordinate.longitude)
                if (discoveryActive) {
                    map.overlays.add(PickupHaloOverlay(map, center, ACTIVE_OUTER_HALO_RADIUS_METERS, HALO_BASE_ALPHA + 4))
                    map.overlays.add(PickupHaloOverlay(map, center, ACTIVE_INNER_HALO_RADIUS_METERS, HALO_BASE_ALPHA + 18))
                } else {
                    map.overlays.add(PickupHaloOverlay(map, center, SETTLED_HALO_RADIUS_METERS, HALO_BASE_ALPHA + 22))
                }
            }
            marker(coordinate, "Pickup", pickupPin)
        }
        destination?.let { marker(it, "Destination", destinationPin) }
        assignedRider?.takeIf { it.isFinite }?.let { marker(it, "Assigned rider motorcycle", motorcycleIcon, Marker.ANCHOR_CENTER) }
        map.fitToPoints(points)
        map.overlays.filterIsInstance<CopyrightOverlay>().firstOrNull()?.let { attribution ->
            map.overlays.remove(attribution)
            map.overlays.add(attribution)
        }
        map.invalidate()
    }, onRelease = { it.onPause(); it.onDetach(); if (mapViewState.value === it) mapViewState.value = null })
}
