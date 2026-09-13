package com.movo.customer.parcel.delivery

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movo.customer.BuildConfig
import com.movo.customer.R
import com.movo.customer.location.CustomerLocation
import com.movo.customer.parcel.account.AccountOptions
import com.movo.customer.parcel.account.AccountPreferences
import com.movo.customer.parcel.domain.ParcelPlace
import com.movo.customer.parcel.ui.MovoGreen
import com.movo.design.maps.MapTileSources
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

val LocalSavedHome = staticCompositionLocalOf<ParcelPlace?> { null }

/**
 * Fits the given points on screen. Mirrors CustomerMap's fitToPoints (same
 * osmdroid pitfalls apply: zoomToBoundingBox needs a laid-out view and a
 * non-degenerate box), duplicated here rather than shared because it is a
 * few lines of view-lifecycle glue, not a reusable service worth a new module.
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
            controller.setZoom(15.0); controller.setCenter(points.first())
        } else {
            runCatching { zoomToBoundingBox(BoundingBox(north, east, south, west), false, paddingPx) }
                .onFailure { controller.setZoom(13.0); controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2)) }
        }
    }
    if (width > 0 && height > 0) apply.run() else post(apply)
}

/**
 * Delivery-flow map. Deliberately renders on osmdroid (free OpenStreetMap
 * tiles, MapTiler-hosted tiles as an optional paid upgrade if a key is
 * configured) instead of the Google Maps SDK — map *display* has no need for
 * a metered Google Maps bill when osmdroid draws the same pins and route
 * overview for free. Google's paid APIs are reserved for what osmdroid can't
 * do at all: rich place-name autocomplete (see [HybridPlacesSearch]).
 */
@Composable
fun DeliveryMap(places: List<ParcelPlace>, modifier: Modifier = Modifier, onPin: ((ParcelPlace) -> Unit)? = null) {
    val context = LocalContext.current
    val savedHome = LocalSavedHome.current
    val settings = remember { AccountPreferences(context) }
    val options by settings.options.collectAsState(initial = AccountOptions())
    var message by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewState = remember { mutableStateOf<MapView?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) CustomerLocation(context).requestCurrent { result ->
            result.onSuccess { p -> mapViewState.value?.let { it.controller.setZoom(16.0); it.controller.setCenter(GeoPoint(p.latitude, p.longitude)) } }
                .onFailure { message = it.message }
        } else message = "Allow location or choose a pin manually."
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> mapViewState.value?.onResume(); Lifecycle.Event.ON_PAUSE -> mapViewState.value?.onPause(); else -> Unit }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); mapViewState.value?.onPause() }
    }

    Column {
        Box(modifier.fillMaxWidth().height(250.dp).clipToBounds()) {
            AndroidView(factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    mapViewState.value = this
                    setTileSource(BuildConfig.MAPTILER_API_KEY.takeIf(String::isNotBlank)?.let(MapTileSources::maptiler) ?: TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true); controller.setZoom(13.0)
                    controller.setCenter(places.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(-1.9441, 30.0619))
                    overlays.add(CopyrightOverlay(ctx)); onResume()
                }
            }, modifier = Modifier.matchParentSize(), update = { map ->
                mapViewState.value = map
                map.overlays.removeAll { it is Marker || it is MapEventsOverlay || it is Polyline }
                map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                        onPin?.invoke(ParcelPlace("pin:${point.latitude},${point.longitude}", "Map pin", "${point.latitude}, ${point.longitude}", point.latitude, point.longitude))
                        return true
                    }
                    override fun longPressHelper(point: GeoPoint): Boolean {
                        onPin?.invoke(ParcelPlace("pin:${point.latitude},${point.longitude}", "Map pin", "${point.latitude}, ${point.longitude}", point.latitude, point.longitude))
                        return true
                    }
                }))
                val pickupPin = ContextCompat.getDrawable(map.context, R.drawable.ic_pin_pickup)
                val destinationPin = ContextCompat.getDrawable(map.context, R.drawable.ic_pin_destination)
                fun marker(place: ParcelPlace, icon: Drawable?) {
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(place.latitude, place.longitude); title = place.label; snippet = place.address
                        icon?.let { this.icon = it }; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); infoWindow = null
                    })
                }
                places.forEachIndexed { index, place ->
                    marker(place, when { index == 0 -> pickupPin; index == places.lastIndex && places.size > 1 -> destinationPin; else -> null })
                }
                if (places.size > 1) {
                    map.overlays.add(Polyline(map).apply {
                        setPoints(places.filter { it.id != "rider" }.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = android.graphics.Color.parseColor("#1FAE59"); outlinePaint.strokeWidth = 6f
                    })
                }
                map.fitToPoints(places.map { GeoPoint(it.latitude, it.longitude) })
                map.overlays.filterIsInstance<CopyrightOverlay>().firstOrNull()?.let { attribution -> map.overlays.remove(attribution); map.overlays.add(attribution) }
                map.invalidate()
            }, onRelease = { it.onPause(); it.onDetach(); if (mapViewState.value === it) mapViewState.value = null })
            RecenterButton(savedHome, permission, { message = it })
        }
        if (places.size > 1) Text("Route overview — connecting pins, not turn-by-turn directions", style = MaterialTheme.typography.labelSmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.RecenterButton(savedHome: ParcelPlace?, permission: androidx.activity.result.ActivityResultLauncher<Array<String>>, onMessage: (String) -> Unit) {
    Surface(shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(52.dp)
        .combinedClickable(onClickLabel = "Recenter on my location", onLongClickLabel = "Go to saved Home",
            onClick = { permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) },
            onLongClick = { if (savedHome == null) onMessage("Save a Home address first.") })
    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MyLocation, "Recenter; hold for Home", tint = MovoGreen) } }
}

data class PlaceSuggestion(val id: String, val title: String, val subtitle: String)

/**
 * Strong location suggestions by mixing two free/cheap sources instead of
 * paying for Google Places on every keystroke:
 *  - Google Places autocomplete, only used when a googleMapsApiKey is
 *    configured — good for named businesses/landmarks, but metered.
 *  - OpenStreetMap/MapTiler geocoding via [com.movo.design.maps.MapServices],
 *    the same MAP_PROVIDER=osm path documented on the server side. Nominatim
 *    is free; MapTiler geocoding is used instead when MAPTILER_API_KEY is
 *    set (the same key already paid for map tiles), so no extra Google spend
 *    is required for the app to suggest addresses at all.
 * Results are merged and deduplicated by rounded coordinate so the customer
 * sees one ranked list, not two competing ones.
 */
class HybridPlacesSearch(context: Context) {
    private val googleClient = if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) null else {
        if (!com.google.android.libraries.places.api.Places.isInitialized()) com.google.android.libraries.places.api.Places.initialize(context.applicationContext, BuildConfig.GOOGLE_MAPS_API_KEY)
        com.google.android.libraries.places.api.Places.createClient(context.applicationContext)
    }
    private val geocoding = com.movo.design.maps.MapServices.geocoding(com.movo.design.maps.MapProvider.OSM, BuildConfig.MAPTILER_API_KEY)

    suspend fun search(query: String): Result<List<ParcelPlace>> {
        if (query.isBlank()) return Result.success(emptyList())
        return runCatching {
            val google = googlePlaces(query)
            val osm = osmPlaces(query)
            (google + osm).distinctBy { "%.4f,%.4f".format(it.latitude, it.longitude) }.take(8)
        }
    }

    private suspend fun googlePlaces(query: String): List<ParcelPlace> {
        val api = googleClient ?: return emptyList()
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                api.findAutocompletePredictions(
                    com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest.builder().setCountries("RW").setQuery(query).build()
                ).addOnSuccessListener { result ->
                    val predictions = result.autocompletePredictions.take(5)
                    if (predictions.isEmpty()) { if (continuation.isActive) continuation.resumeWith(Result.success(emptyList())); return@addOnSuccessListener }
                    val resolved = mutableListOf<ParcelPlace>()
                    var pending = predictions.size
                    predictions.forEach { prediction ->
                        api.fetchPlace(
                            com.google.android.libraries.places.api.net.FetchPlaceRequest.newInstance(
                                prediction.placeId,
                                listOf(com.google.android.libraries.places.api.model.Place.Field.ID, com.google.android.libraries.places.api.model.Place.Field.NAME, com.google.android.libraries.places.api.model.Place.Field.ADDRESS, com.google.android.libraries.places.api.model.Place.Field.LAT_LNG)
                            )
                        ).addOnSuccessListener { fetched ->
                            val place = fetched.place; val location = place.latLng
                            if (location != null) resolved += ParcelPlace(prediction.placeId, place.name ?: prediction.getPrimaryText(null).toString(), place.address ?: prediction.getSecondaryText(null).toString(), location.latitude, location.longitude)
                            pending--; if (pending == 0 && continuation.isActive) continuation.resumeWith(Result.success(resolved.toList()))
                        }.addOnFailureListener {
                            pending--; if (pending == 0 && continuation.isActive) continuation.resumeWith(Result.success(resolved.toList()))
                        }
                    }
                }.addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.success(emptyList())) }
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun osmPlaces(query: String): List<ParcelPlace> =
        geocoding.search(query).map { result ->
            val title = result.displayName.substringBefore(",").ifBlank { result.displayName }
            ParcelPlace("osm:${result.latitude},${result.longitude}", title, result.displayName, result.latitude, result.longitude)
        }
}
