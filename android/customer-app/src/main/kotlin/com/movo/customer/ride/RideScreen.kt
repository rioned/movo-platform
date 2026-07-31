package com.movo.customer.ride

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.movo.customer.dataObject
import com.movo.customer.location.CustomerLocation
import com.movo.customer.map.CustomerMap
import com.movo.customer.model.*
import com.movo.customer.network.CustomerApi
import com.movo.customer.network.CustomerApiException
import com.movo.customer.send.RiderDiscoveryController
import com.movo.customer.send.RiderSelectionScreen
import com.movo.customer.send.customerNearbyRiderSource
import com.movo.customer.send.selectReplacement
import com.movo.customer.session.CustomerSession
import com.movo.design.MovoServiceMode
import com.movo.design.MovoSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private enum class RideStage { Route, PickDropoff, Details, FareAndRider, Waiting }

/**
 * Booking a moto ride: see your pickup, say where you are going, agree the fare,
 * then choose the rider who comes for you.
 *
 * The delivery flow's map, discovery scan and rider picker are reused verbatim —
 * they solve the same problems here — while the request itself is a ride, with no
 * sender, receiver or parcel anywhere in it. Draft state is persisted at every
 * step so a dropped connection never costs the passenger their trip.
 */
@Composable
fun RideScreen(
    api: CustomerApi,
    profile: CustomerProfile,
    session: CustomerSession,
    online: Boolean,
    onTracking: (String) -> Unit
) {
    val restored = remember { session.restoreRideJourney() }
    var draft by remember {
        mutableStateOf(
            restored?.draft ?: RideDraft(
                passengerName = profile.name,
                passengerPhone = profile.phone,
                pickupAddress = "My current location"
            )
        )
    }
    var quote by remember { mutableStateOf(restored?.quote) }
    val creationKey = remember { restored?.creationIdempotencyKey ?: UUID.randomUUID().toString() }
    val replacementKey = remember { restored?.replacementIdempotencyKey ?: UUID.randomUUID().toString() }
    var existingRideId by remember { mutableStateOf(restored?.rideId) }
    var stage by remember { mutableStateOf(RideStage.Route) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var riderError by remember { mutableStateOf<String?>(null) }
    var availableRiders by remember { mutableStateOf<List<NearbyRider>>(emptyList()) }
    var refreshingRiders by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val location = remember { CustomerLocation(context) }
    // Ride discovery only counts riders who actually take passengers.
    val rideRiderSource = remember { customerNearbyRiderSource(api, mode = MovoServiceMode.Ride) }
    val controller = remember { RiderDiscoveryController(rideRiderSource) }
    val snapshot by controller.snapshot.collectAsState()

    fun persist() = session.saveRideJourney(RideJourney(draft, quote, existingRideId, creationKey, replacementKey))
    fun locate() = location.requestCurrent { result ->
        result.onSuccess { draft = draft.copy(pickup = it) }.onFailure { error = it.message }
    }
    val permission = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
        else error = "Location permission denied. You can still place your pickup on the map."
    }
    fun requestLocation() {
        showRationale = activity != null && listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (!showRationale) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(Unit) { if (draft.pickup == null) requestLocation() }
    // Debounced and off the main thread: encrypted storage is far too slow to
    // touch from the UI thread on every keystroke.
    LaunchedEffect(draft, quote, existingRideId) {
        delay(400)
        withContext(Dispatchers.IO) { persist() }
    }
    LaunchedEffect(draft.pickup, online, stage) {
        val pickup = draft.pickup
        if (stage == RideStage.Route && pickup?.isFinite == true) {
            controller.invalidate(pickup)
            controller.scan(pickup, online)
        }
    }
    // A moved pickup invalidates the fare that was quoted from the old one.
    LaunchedEffect(draft.pickup, draft.destination) { quote = null }

    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false },
        title = { Text("Use your location for pickup?") },
        text = { Text("MOVO uses your location only while you book a ride, so your rider can find you. You can always place the pin yourself.") },
        confirmButton = {
            TextButton(onClick = {
                showRationale = false
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Use map instead") } }
    )

    when (stage) {
        RideStage.Route -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CustomerMap(
                    pickup = draft.pickup,
                    destination = draft.destination,
                    nearbyMotorcycles = snapshot.riders,
                    modifier = Modifier.fillMaxSize(),
                    discoveryActive = true,
                    showPickupHalo = true
                ) { point ->
                    draft = draft.copy(pickup = point)
                    controller.invalidate(point)
                }
                FloatingActionButton(
                    onClick = ::requestLocation,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(MovoSpacing.default),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Filled.LocationOn, contentDescription = "Use my current location") }
            }
            RideRouteSheet(
                draft = draft,
                snapshot = snapshot,
                online = online,
                error = error,
                onPickupAddressChange = { draft = draft.copy(pickupAddress = it) },
                onDropoffAddressChange = { draft = draft.copy(destinationAddress = it) },
                onPickDropoff = { stage = RideStage.PickDropoff },
                onContinue = { error = null; stage = RideStage.Details },
                onRetryScan = { draft.pickup?.let { pickup -> scope.launch { controller.scan(pickup, online) } } }
            )
        }

        RideStage.PickDropoff -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CustomerMap(
                    pickup = draft.pickup,
                    destination = draft.destination,
                    modifier = Modifier.fillMaxSize()
                ) { point -> draft = draft.copy(destination = point) }
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(MovoSpacing.default),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp
                ) {
                    Text(
                        "Tap or long-press where you want to get off",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small)
                    )
                }
            }
            com.movo.design.MovoSheet {
                Text(
                    if (draft.destination?.isFinite == true) "Drop-off pin placed" else "No drop-off pin yet",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    draft.destination?.let { "%.5f, %.5f".format(it.latitude, it.longitude) }
                        ?: "Your rider navigates to this exact point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(MovoSpacing.default))
                com.movo.design.MovoButton(
                    "Confirm drop-off",
                    { stage = RideStage.Route },
                    enabled = draft.destination?.isFinite == true
                )
                com.movo.design.MovoTextAction("Back", { stage = RideStage.Route }, Modifier.fillMaxWidth())
            }
        }

        RideStage.Details -> RideDetailsSheet(
            draft = draft,
            online = online,
            loading = loading,
            error = error,
            onBack = { stage = RideStage.Route },
            onGetFare = {
                val pickup = draft.pickup
                val dropoff = draft.destination
                if (pickup == null || !pickup.isFinite || dropoff == null || !dropoff.isFinite) {
                    error = "Place your pickup and drop-off on the map first"
                    return@RideDetailsSheet
                }
                if (!draft.isComplete) {
                    error = "Add your name, phone and both addresses"
                    return@RideDetailsSheet
                }
                loading = true; error = null
                scope.launch {
                    runCatching {
                        api.post(
                            "/api/deliveries/price",
                            JSONObject()
                                .put("pickup_lat", pickup.latitude).put("pickup_lng", pickup.longitude)
                                .put("dest_lat", dropoff.latitude).put("dest_lng", dropoff.longitude)
                                .put("service_mode", MovoServiceMode.Ride.apiValue)
                        ).dataObject()
                    }.onSuccess { data ->
                        quote = Quote(
                            data.optDouble("customerPrice", data.optDouble("totalCharge")),
                            data.optDouble("distance_km"),
                            data.optInt("estimatedMinutes", data.optInt("estimated_min"))
                        )
                        persist(); stage = RideStage.FareAndRider
                    }.onFailure { error = it.message }
                    loading = false
                }
            },
            onUpdatePassengerName = { draft = draft.copy(passengerName = it) },
            onUpdatePassengerPhone = { draft = draft.copy(passengerPhone = it) },
            onUpdatePassengerCount = { draft = draft.copy(passengerCount = it) },
            onUpdateLuggage = { draft = draft.copy(hasLuggage = it) },
            onUpdateNotes = { draft = draft.copy(notes = it) },
            onUpdatePaymentMethod = { draft = draft.copy(paymentMethod = it) }
        )

        RideStage.FareAndRider -> {
            val currentQuote = quote
            val pickup = draft.pickup
            if (currentQuote == null || pickup == null) {
                // Defensive: a restored journey could arrive here without a fare.
                LaunchedEffect(Unit) { stage = RideStage.Details }
            } else {
                fun refreshAvailability() {
                    refreshingRiders = true; riderError = null
                    scope.launch {
                        runCatching { rideRiderSource.scan(pickup) }
                            .onSuccess { riders -> availableRiders = riders.filter { it.location.isFinite } }
                            .onFailure { riderError = it.message ?: "Unable to scan for riders" }
                        refreshingRiders = false
                    }
                }
                LaunchedEffect(currentQuote) { refreshAvailability() }

                RiderSelectionScreen(
                    riders = availableRiders,
                    existingDeliveryId = existingRideId,
                    onRefreshRiders = ::refreshAvailability,
                    refreshing = refreshingRiders,
                    submitting = submitting,
                    quote = currentQuote,
                    mode = MovoServiceMode.Ride,
                    onSelectRider = { riderId ->
                        submitting = true; error = null; riderError = null
                        val rideId = existingRideId
                        scope.launch {
                            runCatching {
                                if (rideId == null) {
                                    val body = JSONObject()
                                        .put("service_mode", MovoServiceMode.Ride.apiValue)
                                        .put("pickup_address", draft.pickupAddress)
                                        .put("pickup_lat", pickup.latitude).put("pickup_lng", pickup.longitude)
                                        .put("pickup_name", draft.passengerName).put("pickup_phone", draft.passengerPhone)
                                        .put("dest_address", draft.destinationAddress)
                                        .put("dest_lat", draft.destination!!.latitude).put("dest_lng", draft.destination!!.longitude)
                                        .put("passenger_count", draft.passengerCount)
                                        .put("has_luggage", draft.hasLuggage)
                                        .put("special_instructions", draft.notes)
                                        .put("payment_method", draft.paymentMethod)
                                        .put("preferred_rider_id", riderId)
                                    api.post("/api/deliveries", body, creationKey).dataObject().optJSONObject("delivery")
                                        ?: throw IllegalStateException("Ride response missing")
                                } else {
                                    selectReplacement(api, rideId, riderId, replacementKey).dataObject()
                                        .optJSONObject("delivery") ?: JSONObject().put("id", rideId).put("status", "searching")
                                }
                            }.onSuccess { ride ->
                                val id = ride.optString("id").ifBlank { rideId.orEmpty() }
                                if (id.isBlank()) {
                                    error = "Server did not return the trip ID"
                                    submitting = false
                                } else {
                                    val nextReplacementKey = if (rideId == null) replacementKey else UUID.randomUUID().toString()
                                    withContext(Dispatchers.IO) {
                                        session.saveRideJourney(RideJourney(draft, currentQuote, id, creationKey, nextReplacementKey))
                                    }
                                    submitting = false
                                    existingRideId = id
                                    onTracking(id)
                                    stage = RideStage.Waiting
                                }
                            }.onFailure { failure ->
                                if (failure is CustomerApiException && failure.status == 409) {
                                    riderError = "That rider is no longer available. Choose another."
                                    refreshAvailability()
                                } else {
                                    riderError = failure.message ?: "Unable to request this rider"
                                }
                                submitting = false
                            }
                        }
                    },
                    idempotencyKey = creationKey,
                    replacementIdempotencyKey = replacementKey,
                    onBack = { quote = null; stage = RideStage.Details },
                    error = riderError
                )
            }
        }

        RideStage.Waiting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(MovoSpacing.default))
                Text("Opening live tracking…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
