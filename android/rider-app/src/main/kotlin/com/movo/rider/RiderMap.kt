package com.movo.rider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import com.movo.design.maps.LatLng
import com.movo.design.maps.MapServices
import com.movo.design.maps.MapProvider
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val PICKUP_MARKER = "Customer pickup"
private const val DESTINATION_MARKER = "Destination"

/** Render a drawable to a bitmap at a given size. */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
  val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  drawable.setBounds(0, 0, sizePx, sizePx)
  drawable.draw(canvas)
  return bmp
}

/**
 * Zoom the map so all given points are visible with padding.
 *
 * osmdroid's `zoomToBoundingBox` works from the view's pixel size and blocks the
 * main thread when the map has not been laid out yet, or when the box has no area
 * (rider sitting on the pickup point). Both are normal here, so the work is
 * deferred until layout and degenerate boxes are centred instead of framed.
 */
private fun MapView.zoomToFitPoints(points: List<GeoPoint>) {
  val apply = Runnable {
    if (width <= 0 || height <= 0) return@Runnable
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
        if ((north - south) < 1e-5 && (east - west) < 1e-5) {
          controller.setZoom(16.0)
          controller.setCenter(points[0])
        } else {
          try {
            zoomToBoundingBox(BoundingBox(north, east, south, west).increaseByScale(1.5f), false)
          } catch (_: Exception) {
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
          }
        }
      }
    }
  }
  if (width > 0 && height > 0) apply.run() else post(apply)
}

/**
 * The rider's working map: their own motorcycle, the pickup and destination pins
 * and the route between them. Location updates and the map view are released with
 * the composable, so backgrounding the app stops the GPS listener.
 */
@Composable
fun RiderMap(context: Context, pickupLat: Double?, pickupLng: Double?, destLat: Double?, destLng: Double?, modifier: Modifier = Modifier) {
  val riderLocation = remember { mutableStateOf<GeoPoint?>(null) }
  val mapViewState = remember { mutableStateOf<MapView?>(null) }
  val locationListenerState = remember { mutableStateOf<LocationListener?>(null) }
  val lifecycleOwner = LocalLifecycleOwner.current
  // Route line between pickup and destination, via the map-service abstraction (spec
  // §63): road-following through OSRM when reachable, degrading to a straight line
  // on any failure. Defaults to MapProvider.OSM, matching this app's only supported
  // tile backend today; a future MAP_PROVIDER=sandbox toggle read from the server's
  // config would flow into this same call, not a new one.
  val routingService = remember { MapServices.routing(MapProvider.OSM) }
  var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

  LaunchedEffect(pickupLat, pickupLng, destLat, destLng) {
    if (pickupLat == null || pickupLng == null || destLat == null || destLng == null) {
      routePoints = emptyList()
      return@LaunchedEffect
    }
    val pickup = GeoPoint(pickupLat, pickupLng)
    val destination = GeoPoint(destLat, destLng)
    routePoints = listOf(pickup, destination) // instant straight line, upgraded below
    val route = routingService.route(LatLng(pickupLat, pickupLng), LatLng(destLat, destLng))
    routePoints = route.points.map { GeoPoint(it.latitude, it.longitude) }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> mapViewState.value?.onResume()
        Lifecycle.Event.ON_PAUSE -> mapViewState.value?.onPause()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      mapViewState.value?.onPause()
    }
  }

  AndroidView(modifier = modifier.clipToBounds(), factory = {
    Configuration.getInstance().userAgentValue = context.packageName
    MapView(it).apply {
      mapViewState.value = this
      setTileSource(TileSourceFactory.MAPNIK)
      setMultiTouchControls(true)
      overlays.add(CopyrightOverlay(context))
      onResume()

      // Build the motorcycle icon bitmap once
      val d = ContextCompat.getDrawable(context, R.drawable.ic_rider_motorcycle)
      val iconSize = (48 * context.resources.displayMetrics.density).toInt()
      val iconBmp = d?.let { drawable -> drawableToBitmap(drawable, iconSize) }
      val iconDrawable = iconBmp?.let { bitmap -> BitmapDrawable(context.resources, bitmap) }

      // Rider marker (shown as soon as we have a location)
      val riderMarker = Marker(this).apply {
        title = "You"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        infoWindow = null
        if (iconDrawable != null) icon = iconDrawable
      }

      val listener = LocationListener { location: Location ->
        val riderPoint = GeoPoint(location.latitude, location.longitude)
        riderLocation.value = riderPoint

        riderMarker.position = riderPoint
        if (riderMarker !in overlays) overlays.add(riderMarker)

        val pts = mutableListOf(riderPoint)
        if (pickupLat != null && pickupLng != null) pts.add(GeoPoint(pickupLat, pickupLng))
        if (destLat != null && destLng != null) pts.add(GeoPoint(destLat, destLng))
        zoomToFitPoints(pts)
        invalidate()
      }
      locationListenerState.value = listener
      try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
          ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
          ?: manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
          ?.let(listener::onLocationChanged)
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000, 0f, listener)
        manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000, 0f, listener)
      } catch (_: SecurityException) { }
    }
  }, update = { map ->
    mapViewState.value = map
    // Remove old customer/destination markers and polylines (rider marker stays)
    map.overlays.removeAll {
      (it is Marker && (it.title == PICKUP_MARKER || it.title == DESTINATION_MARKER)) || it is Polyline
    }

    val allPoints = mutableListOf<GeoPoint>()
    riderLocation.value?.let(allPoints::add)
    val pickupPin = ContextCompat.getDrawable(map.context, R.drawable.ic_pin_pickup)
    val destinationPin = ContextCompat.getDrawable(map.context, R.drawable.ic_pin_destination)

    if (pickupLat != null && pickupLng != null) {
      val pickup = GeoPoint(pickupLat, pickupLng)
      allPoints.add(pickup)
      Marker(map).apply {
        position = pickup
        title = PICKUP_MARKER
        snippet = "Collect the item here"
        icon = pickupPin
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        infoWindow = null
      }.also(map.overlays::add)
    }
    if (destLat != null && destLng != null) {
      val dest = GeoPoint(destLat, destLng)
      allPoints.add(dest)
      Marker(map).apply {
        position = dest
        title = DESTINATION_MARKER
        snippet = "Hand over the item here"
        icon = destinationPin
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        infoWindow = null
      }.also(map.overlays::add)
    }
    if (routePoints.size > 1) {
      Polyline().apply {
        setPoints(routePoints)
        outlinePaint.color = Color.argb(200, 8, 107, 77)
        outlinePaint.strokeWidth = 8f
      }.also(map.overlays::add)
    }

    // Keep the attribution overlay on top, as the tile licence requires.
    map.overlays.filterIsInstance<CopyrightOverlay>().firstOrNull()?.let { attribution ->
      map.overlays.remove(attribution)
      map.overlays.add(attribution)
    }

    map.zoomToFitPoints(allPoints)
    map.invalidate()
  }, onRelease = { map ->
    locationListenerState.value?.let { listener ->
      runCatching {
        (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
      }
    }
    locationListenerState.value = null
    map.onPause()
    map.onDetach()
    if (mapViewState.value === map) mapViewState.value = null
  })
}
