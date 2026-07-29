package com.movo.rider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Render a drawable to a bitmap at a given size. */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
  val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  drawable.setBounds(0, 0, sizePx, sizePx)
  drawable.draw(canvas)
  return bmp
}

/** Zoom the map so all given points are visible with padding. */
private fun MapView.zoomToFitPoints(points: List<GeoPoint>) {
  when {
    points.isEmpty() -> {
      controller.setZoom(12.0)
      controller.setCenter(GeoPoint(-1.9441, 30.0619))
    }
    points.size == 1 -> {
      controller.setZoom(16.0)
      controller.setCenter(points[0])
    }
    else -> {
      val north = points.maxOf { it.latitude }
      val south = points.minOf { it.latitude }
      val east = points.maxOf { it.longitude }
      val west = points.minOf { it.longitude }
      try {
        zoomToBoundingBox(BoundingBox(north, east, south, west).increaseByScale(1.5f), true)
      } catch (_: Exception) {
        controller.setZoom(14.0)
        controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
      }
    }
  }
}

@Composable
fun RiderMap(context: Context, pickupLat: Double?, pickupLng: Double?, destLat: Double?, destLng: Double?, modifier: Modifier = Modifier) {
  val riderLocation = remember { mutableStateOf<GeoPoint?>(null) }

  AndroidView(modifier = modifier, factory = {
    Configuration.getInstance().userAgentValue = context.packageName
    MapView(it).apply {
      setLayerType(View.LAYER_TYPE_SOFTWARE, null)
      setTileSource(TileSourceFactory.MAPNIK)
      setMultiTouchControls(true)

      // Build the motorcycle icon bitmap once
      val d = ContextCompat.getDrawable(context, R.drawable.ic_rider_motorcycle)
      val iconSize = (48 * context.resources.displayMetrics.density).toInt()
      val iconBmp = d?.let { drawableToBitmap(it, iconSize) }
      val iconDrawable = iconBmp?.let { BitmapDrawable(context.resources, it) }

      // Rider marker (shown as soon as we have a location)
      val riderMarker = Marker(this).apply {
        title = "You"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        if (iconDrawable != null) icon = iconDrawable
      }

      val listener = LocationListener { location: Location ->
        val riderPoint = GeoPoint(location.latitude, location.longitude)
        riderLocation.value = riderPoint

        // Update rider marker position
        riderMarker.position = riderPoint
        if (riderMarker !in overlays) overlays.add(riderMarker)

        // Build list of ALL known points and zoom to fit them
        val pts = mutableListOf(riderPoint)
        if (pickupLat != null && pickupLng != null) pts.add(GeoPoint(pickupLat, pickupLng))
        if (destLat != null && destLng != null) pts.add(GeoPoint(destLat, destLng))
        zoomToFitPoints(pts)
        invalidate()
      }
      try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Get last known from any provider
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
          ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
          ?: manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
          ?.let(listener::onLocationChanged)
        // Listen to both providers
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000, 0f, listener)
        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000, 0f, listener)
      } catch (_: SecurityException) { }
    }
  }, update = { map ->
    // Remove old customer/destination markers and polylines (rider marker stays)
    map.overlays.removeAll {
      (it is Marker && (it.title == "Customer pickup" || it.title == "Destination")) || it is Polyline
    }

    val allPoints = mutableListOf<GeoPoint>()
    riderLocation.value?.let(allPoints::add)

    if (pickupLat != null && pickupLng != null) {
      val pickup = GeoPoint(pickupLat, pickupLng)
      allPoints.add(pickup)
      Marker(map).apply {
        position = pickup
        title = "Customer pickup"
        snippet = "Pickup location"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
      }.also(map.overlays::add)
    }
    if (destLat != null && destLng != null) {
      val dest = GeoPoint(destLat, destLng)
      allPoints.add(dest)
      Marker(map).apply {
        position = dest
        title = "Destination"
        snippet = "Drop-off location"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
      }.also(map.overlays::add)
    }
    if (pickupLat != null && pickupLng != null && destLat != null && destLng != null) {
      Polyline().apply {
        setPoints(listOf(GeoPoint(pickupLat, pickupLng), GeoPoint(destLat, destLng)))
      }.also(map.overlays::add)
    }

    map.zoomToFitPoints(allPoints)
    map.invalidate()
  })
}
