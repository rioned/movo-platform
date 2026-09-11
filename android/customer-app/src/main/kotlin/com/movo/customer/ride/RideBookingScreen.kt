package com.movo.customer.ride

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.movo.customer.analytics.CustomerAnalytics
import com.movo.customer.dataObject
import com.movo.customer.location.CustomerLocation
import com.movo.customer.map.CustomerMap
import com.movo.customer.model.Coordinate
import com.movo.customer.model.toRideType
import com.movo.customer.network.CustomerApi
import com.movo.design.*
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class RideBookingStage { Pickup, Destination, ChooseType }

/**
 * Map-first motorcycle booking: choose an exact pickup and destination, then
 * show genuine motorcycle quotes from the provider. Confirming starts automatic
 * dispatch — the same pattern used for delivery: the app searches, it does not ask
 * the rider to pick one driver.
 */
@Composable
fun RideBookingScreen(api: CustomerApi, onRideCreated: (String) -> Unit) {
    var stage by rememberSaveable { mutableStateOf(RideBookingStage.Pickup) }
    var pickup by remember { mutableStateOf<Coordinate?>(null) }
    var pickupAddress by rememberSaveable { mutableStateOf("Current location") }
    var destination by remember { mutableStateOf<Coordinate?>(null) }
    var destinationAddress by rememberSaveable { mutableStateOf("") }
    var paymentMethod by rememberSaveable { mutableStateOf("cash") }
    var rideTypes by remember { mutableStateOf<List<com.movo.customer.model.RideType>>(emptyList()) }
    var selectedRideTypeId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val location = remember { CustomerLocation(context) }
    val analytics = remember { CustomerAnalytics(api) }

    fun locate() = location.requestCurrent { result ->
        result.onSuccess { pickup = it }.onFailure { error = it.message }
    }
    val permission = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
        else error = "Location permission denied. Tap the map to place your pickup pin."
    }
    fun requestLocation() {
        showRationale = activity != null && listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (!showRationale) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { if (pickup == null) requestLocation() }

    BackHandler(stage != RideBookingStage.Pickup) {
        if (!loading && !confirming) {
            error = null
            stage = if (stage == RideBookingStage.ChooseType) RideBookingStage.Destination else RideBookingStage.Pickup
        }
    }

    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false }, title = { Text("Use your location for pickup?") },
        text = { Text("MOVO uses your location only to set the pickup pin. You can always drag it on the map instead.") },
        confirmButton = { TextButton(onClick = { showRationale = false; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Use map instead") } }
    )

    when (stage) {
        RideBookingStage.Pickup -> Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CustomerMap(pickup = pickup, destination = null, modifier = Modifier.fillMaxSize(), showPickupHalo = true) { point -> pickup = point }
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(MovoSpacing.default),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp
                ) {
                    Text("01 / PICKUP  •  Tap to place your pin", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.medium))
                }
                FloatingActionButton(
                    onClick = ::requestLocation, modifier = Modifier.align(Alignment.BottomEnd).padding(MovoSpacing.default),
                    containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Filled.LocationOn, contentDescription = "Use my current location") }
            }
            MovoSheet {
                Text("Let's get you moving", style = MaterialTheme.typography.headlineMedium)
                Text("A motorcycle ride, from your doorstep.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(MovoSpacing.medium))
                MovoField(pickupAddress, { pickupAddress = it }, "Pickup landmark", supporting = "For example, Kigali Convention Centre. Tap the map to set the exact point.")
                error?.let { MovoBanner(it, MovoTone.Warning) }
                Spacer(Modifier.height(MovoSpacing.default))
                MovoButton("Where to?", { error = null; stage = RideBookingStage.Destination }, enabled = pickup?.isFinite == true && pickupAddress.isNotBlank())
            }
        }

        RideBookingStage.Destination -> Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CustomerMap(pickup = pickup, destination = destination, modifier = Modifier.fillMaxSize()) { point ->
                    if (!loading) {
                        destination = point
                        if (destinationAddress.isBlank()) destinationAddress = "Drop-off location"
                    }
                }
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(MovoSpacing.default),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp
                ) {
                    Text("02 / DESTINATION  •  Tap where you're going", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.medium))
                }
            }
            MovoSheet {
                Text("Where are you headed?", style = MaterialTheme.typography.headlineSmall)
                Text("From $pickupAddress", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(MovoSpacing.medium))
                MovoField(destinationAddress, { destinationAddress = it }, "Destination landmark", supporting = "Place a destination pin on the map, then add a landmark.", enabled = !loading)
                Spacer(Modifier.height(MovoSpacing.default))
                MovoButton(
                    "See motorcycle fare", {
                        loading = true; error = null
                        scope.launch {
                            runCatching {
                                val p = pickup!!; val d = destination!!
                                val estimate = api.post(
                                    "/api/rides/estimate",
                                    JSONObject().put("pickup_lat", p.latitude).put("pickup_lng", p.longitude)
                                        .put("dest_lat", d.latitude).put("dest_lng", d.longitude)
                                ).dataObject()
                                val estimates = estimate.getJSONArray("estimates")
                                List(estimates.length()) { estimates.getJSONObject(it).toRideType() }
                                    .filter { it.isMotorcycle }
                                    .filter { it.id.isNotBlank() }
                            }.onSuccess { types ->
                                rideTypes = types
                                selectedRideTypeId = types.firstOrNull()?.id
                                analytics.log(AnalyticsEvent.QUOTE_VIEWED, mapOf("ride_type_count" to types.size.toString()))
                                stage = RideBookingStage.ChooseType
                            }.onFailure { error = it.message }
                            loading = false
                        }
                    },
                    enabled = destination?.isFinite == true && destinationAddress.isNotBlank() && !loading,
                    loading = loading
                )
                error?.let { MovoBanner(it, MovoTone.Critical) }
                MovoTextAction("Change pickup", { error = null; stage = RideBookingStage.Pickup }, Modifier.fillMaxWidth(), enabled = !loading)
            }
        }

        RideBookingStage.ChooseType -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MovoSpacing.default)) {
            MotoHero(title = "Small ride. Big city.", subtitle = "03 / REVIEW YOUR MOTORCYCLE RIDE", compact = true)
            Spacer(Modifier.height(MovoSpacing.default))
            MovoCard {
                Text("YOUR ROUTE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(MovoSpacing.small))
                Text(pickupAddress, style = MaterialTheme.typography.titleMedium)
                Text("↓", color = MaterialTheme.colorScheme.primary)
                Text(destinationAddress, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(MovoSpacing.default))
            if (rideTypes.isEmpty()) {
                MovoBanner("Motorcycle rides unavailable. The provider has no motorcycle fare for this route right now. Try again later or change your route.", MovoTone.Warning)
            }
            Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                rideTypes.forEach { type ->
                    val selected = type.id == selectedRideTypeId
                    MovoCard(
                        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = !confirming, onClick = { selectedRideTypeId = type.id }),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(type.name, style = MaterialTheme.typography.titleMedium)
                                type.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text("Motorcycle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatMoney(type.fare, type.currency), style = MaterialTheme.typography.titleLarge)
                                Text("${formatMinutes(type.estimatedMinutes)} trip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(MovoSpacing.default))
            Text("Payment", style = MaterialTheme.typography.labelMedium)
            SegmentedChoice(
                options = listOf(SegmentOption("cash", "Cash"), SegmentOption("card", "Card")),
                selected = paymentMethod, onSelect = { paymentMethod = it }, enabled = !confirming && rideTypes.isNotEmpty()
            )
            error?.let { MovoBanner(it, MovoTone.Critical) }
            Spacer(Modifier.height(MovoSpacing.default))
            MovoButton(
                if (confirming) "Requesting your moto…" else "Request motorcycle", {
                    val selectedType = rideTypes.firstOrNull { it.id == selectedRideTypeId && it.isMotorcycle } ?: return@MovoButton
                    val p = pickup ?: return@MovoButton
                    val d = destination ?: return@MovoButton
                    confirming = true; error = null
                    scope.launch {
                        runCatching {
                            api.post(
                                "/api/rides",
                                JSONObject().put("pickup_address", pickupAddress).put("pickup_lat", p.latitude).put("pickup_lng", p.longitude)
                                    .put("dest_address", destinationAddress).put("dest_lat", d.latitude).put("dest_lng", d.longitude)
                                    .put("ride_type_id", selectedType.id).put("payment_method", paymentMethod)
                            ).dataObject().getJSONObject("ride").getString("id")
                        }.onSuccess { id ->
                            confirming = false
                            analytics.log(AnalyticsEvent.RIDE_REQUESTED)
                            onRideCreated(id)
                        }.onFailure { error = it.message; confirming = false }
                    }
                },
                enabled = !confirming && !loading && rideTypes.any { it.id == selectedRideTypeId && it.isMotorcycle },
                loading = confirming
            )
            MovoTextAction("Change route", { error = null; stage = RideBookingStage.Destination }, Modifier.fillMaxWidth(), enabled = !confirming)
        }
    }
}
