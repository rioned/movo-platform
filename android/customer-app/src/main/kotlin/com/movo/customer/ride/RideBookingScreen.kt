package com.movo.customer.ride

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.movo.customer.dataObject
import com.movo.customer.location.CustomerLocation
import com.movo.customer.map.CustomerMap
import com.movo.customer.model.Coordinate
import com.movo.customer.model.CustomerProfile
import com.movo.customer.model.toRideType
import com.movo.customer.network.CustomerApi
import com.movo.design.*
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class RideBookingStage { Pickup, Destination, ChooseType }

/**
 * The Yango-style booking journey (spec steps 3-7): locate the rider, let them drag
 * the pin to the exact pickup point, place a destination, then show every ride
 * category with its price and ETA before confirming. Confirming starts automatic
 * dispatch — the same pattern used for delivery: the app searches, it does not ask
 * the rider to pick one driver.
 */
@Composable
fun RideBookingScreen(api: CustomerApi, profile: CustomerProfile, online: Boolean, onRideCreated: (String) -> Unit) {
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

    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false }, title = { Text("Use your location for pickup?") },
        text = { Text("MOVO uses your location only to set the pickup pin. You can always drag it on the map instead.") },
        confirmButton = { TextButton(onClick = { showRationale = false; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Use map instead") } }
    )

    when (stage) {
        RideBookingStage.Pickup -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CustomerMap(pickup = pickup, destination = null, modifier = Modifier.fillMaxSize(), showPickupHalo = true) { point -> pickup = point }
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(MovoSpacing.default),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp
                ) {
                    Text("Tap the map to adjust your exact pickup point", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small))
                }
                FloatingActionButton(
                    onClick = ::requestLocation, modifier = Modifier.align(Alignment.BottomEnd).padding(MovoSpacing.default),
                    containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Filled.LocationOn, contentDescription = "Use my current location") }
            }
            MovoSheet {
                Text(if (pickup?.isFinite == true) "Pickup pin placed" else "Locating…", style = MaterialTheme.typography.titleMedium)
                MovoField(pickupAddress, { pickupAddress = it }, "Pickup label (e.g. Praça dos Trabalhadores)")
                error?.let { MovoBanner(it, MovoTone.Warning) }
                Spacer(Modifier.height(MovoSpacing.default))
                MovoButton("Confirm pickup", { stage = RideBookingStage.Destination }, enabled = pickup?.isFinite == true && pickupAddress.isNotBlank())
            }
        }

        RideBookingStage.Destination -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CustomerMap(pickup = pickup, destination = destination, modifier = Modifier.fillMaxSize()) { point -> destination = point }
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(MovoSpacing.default),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp
                ) {
                    Text("Tap where you're going", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small))
                }
            }
            MovoSheet {
                Text(if (destination?.isFinite == true) "Destination pin placed" else "No destination yet", style = MaterialTheme.typography.titleMedium)
                MovoField(destinationAddress, { destinationAddress = it }, "Destination (type or describe where you're going)")
                Spacer(Modifier.height(MovoSpacing.default))
                MovoButton(
                    "Find ride options", {
                        loading = true; error = null
                        scope.launch {
                            runCatching {
                                api.get("/api/ride-types").dataObject()
                                val p = pickup!!; val d = destination!!
                                val estimate = api.post(
                                    "/api/rides/estimate",
                                    JSONObject().put("pickup_lat", p.latitude).put("pickup_lng", p.longitude)
                                        .put("dest_lat", d.latitude).put("dest_lng", d.longitude)
                                ).dataObject()
                                val estimates = estimate.getJSONArray("estimates")
                                List(estimates.length()) { estimates.getJSONObject(it).toRideType() }
                            }.onSuccess { types ->
                                rideTypes = types
                                selectedRideTypeId = types.firstOrNull { it.key == "standard" }?.id ?: types.firstOrNull()?.id
                                stage = RideBookingStage.ChooseType
                            }.onFailure { error = it.message }
                            loading = false
                        }
                    },
                    enabled = destination?.isFinite == true && destinationAddress.isNotBlank() && !loading
                )
                error?.let { MovoBanner(it, MovoTone.Critical) }
                MovoTextAction("Back", { stage = RideBookingStage.Pickup }, Modifier.fillMaxWidth())
            }
        }

        RideBookingStage.ChooseType -> Column(Modifier.fillMaxSize().padding(MovoSpacing.default)) {
            Text("Choose a ride", style = MaterialTheme.typography.headlineSmall)
            Text("Every category shows its price and arrival time up front.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(MovoSpacing.default))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                items(rideTypes) { type ->
                    val selected = type.id == selectedRideTypeId
                    MovoCard(
                        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = { selectedRideTypeId = type.id }),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(type.name, style = MaterialTheme.typography.titleMedium)
                                type.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text("${type.capacity} seats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatMoney(type.fare, type.currency), style = MaterialTheme.typography.titleLarge)
                                Text("~${formatMinutes(type.estimatedMinutes)} away", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(MovoSpacing.default))
            Text("Payment", style = MaterialTheme.typography.labelMedium)
            SegmentedChoice(
                options = listOf(SegmentOption("cash", "Cash"), SegmentOption("mpesa", "M-Pesa"), SegmentOption("card", "Card")),
                selected = paymentMethod, onSelect = { paymentMethod = it }
            )
            error?.let { MovoBanner(it, MovoTone.Critical) }
            Spacer(Modifier.height(MovoSpacing.default))
            MovoButton(
                if (confirming) "Confirming…" else "Confirm ride", {
                    val rideTypeId = selectedRideTypeId ?: return@MovoButton
                    val p = pickup ?: return@MovoButton
                    val d = destination ?: return@MovoButton
                    confirming = true; error = null
                    scope.launch {
                        runCatching {
                            api.post(
                                "/api/rides",
                                JSONObject().put("pickup_address", pickupAddress).put("pickup_lat", p.latitude).put("pickup_lng", p.longitude)
                                    .put("dest_address", destinationAddress).put("dest_lat", d.latitude).put("dest_lng", d.longitude)
                                    .put("ride_type_id", rideTypeId).put("payment_method", paymentMethod)
                            ).dataObject().getJSONObject("ride").getString("id")
                        }.onSuccess { id -> confirming = false; onRideCreated(id) }
                            .onFailure { error = it.message; confirming = false }
                    }
                },
                enabled = !confirming && !loading && selectedRideTypeId != null
            )
            MovoTextAction("Back", { stage = RideBookingStage.Destination }, Modifier.fillMaxWidth())
        }
    }
}
