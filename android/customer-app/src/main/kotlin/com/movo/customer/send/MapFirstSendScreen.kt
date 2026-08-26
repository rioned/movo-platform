package com.movo.customer.send

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.movo.customer.analytics.CustomerAnalytics
import com.movo.customer.dataObject
import com.movo.customer.location.CustomerLocation
import com.movo.customer.map.CustomerMap
import com.movo.customer.model.*
import com.movo.customer.network.CustomerApi
import com.movo.customer.session.CustomerSession
import com.movo.design.AnalyticsEvent
import com.movo.design.maps.LatLng
import com.movo.design.maps.MapProvider
import com.movo.design.maps.MapServices
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoSpacing
import com.movo.design.PriceSummary
import com.movo.design.StatusPill
import com.movo.design.MovoTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private enum class SendStage { Discovery, PickDestination, RequestDetails, ConfirmRequest, Waiting }

/**
 * The map-first booking journey: see how many riders are actually near, describe
 * the delivery, approve the price, then request it. Dispatch is blind and
 * zone-based (spec §12) — the customer never browses or picks a specific rider,
 * MOVO's backend matches the nearest eligible one automatically. Draft state is
 * persisted at every step so a dropped connection or a killed process never costs
 * the customer their input.
 */
@Composable
fun MapFirstSendScreen(
    api: CustomerApi,
    profile: CustomerProfile,
    session: CustomerSession,
    online: Boolean,
    onTracking: (String) -> Unit
) {
    val restored = remember { session.restoreJourney() }
    var draft by remember { mutableStateOf(restored?.draft ?: SendDraft(senderName = profile.name, senderPhone = profile.phone, pickupAddress = "Current pickup")) }
    var quote by remember { mutableStateOf(restored?.quote) }
    val creationKey = remember { restored?.creationIdempotencyKey ?: UUID.randomUUID().toString() }
    var existingDeliveryId by remember { mutableStateOf(restored?.deliveryId) }
    var stage by remember { mutableStateOf(SendStage.Discovery) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val location = remember { CustomerLocation(context) }
    val controller = remember { RiderDiscoveryController(customerNearbyRiderSource(api)) }
    val analytics = remember { CustomerAnalytics(api) }
    // Defaults to MapProvider.OSM, matching this app's only supported tile backend
    // today; a future MAP_PROVIDER=sandbox toggle read from the server's config
    // would flow into this same call, not a new one (spec §63).
    val geocodingService = remember { MapServices.geocoding(MapProvider.OSM) }
    val snapshot by controller.snapshot.collectAsState()

    fun persist() = session.saveJourney(SendJourney(draft, quote, existingDeliveryId, creationKey))
    fun locate() = location.requestCurrent { result ->
        result.onSuccess { coordinate ->
            draft = draft.copy(pickup = coordinate); persist()
            // Only replace the placeholder — never overwrite an address the customer already typed.
            if (draft.pickupAddress.isBlank() || draft.pickupAddress == "Current pickup") {
                scope.launch {
                    geocodingService.reverseGeocode(LatLng(coordinate.latitude, coordinate.longitude))?.let { address ->
                        draft = draft.copy(pickupAddress = address); persist()
                    }
                }
            }
        }.onFailure { error = it.message }
    }
    val permission = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
        else error = "Location permission denied. You can still select pickup on the map."
    }
    fun requestLocation() {
        showRationale = activity != null && listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (!showRationale) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(Unit) { if (draft.pickup == null) requestLocation() }
    // Debounced, off the main thread: the journey is written on every keystroke and
    // encrypted storage is far too slow to touch from the UI thread.
    LaunchedEffect(draft, quote, existingDeliveryId) {
        delay(400)
        withContext(Dispatchers.IO) { persist() }
    }

    // Key scanning to pickup/online/stage to avoid duplicate scans from recomposition
    LaunchedEffect(draft.pickup, online, stage) {
        val pickup = draft.pickup
        if (stage == SendStage.Discovery && pickup?.isFinite == true) {
            controller.invalidate(pickup)
            controller.scan(pickup, online)
        }
    }

    // On pickup change: clear quote, invalidate old results immediately, return Discovery, rescan
    LaunchedEffect(draft.pickup) {
        val pickup = draft.pickup
        if (pickup?.isFinite == true) {
            quote = null
            stage = SendStage.Discovery
        }
    }

    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false }, title = { Text("Use your location for pickup?") },
        text = { Text("MOVO uses precise location when available, or approximate location otherwise, only while you choose a pickup. You can always place the pin manually.") },
        confirmButton = { TextButton(onClick = { showRationale = false; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Use map instead") } }
    )

    when (stage) {
        SendStage.Discovery -> {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    // Map dominates above the sheet; bounded above navigation
                    CustomerMap(
                        pickup = draft.pickup,
                        destination = draft.destination,
                        modifier = Modifier.fillMaxSize(),
                        discoveryActive = true,
                        showPickupHalo = true
                    ) { point ->
                        draft = draft.copy(pickup = point)
                        quote = null
                        controller.invalidate(point)
                    }
                    Row(
                        Modifier.align(Alignment.TopStart).padding(MovoSpacing.default),
                        horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                            Text(
                                "Send a parcel or document",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small)
                            )
                        }
                        if (!online) StatusPill("Offline", MovoTone.Warning)
                    }
                    FloatingActionButton(
                        onClick = ::requestLocation,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(MovoSpacing.default),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) { Icon(Icons.Filled.LocationOn, contentDescription = "Use my current location") }
                }
                DiscoverySheet(
                    snapshot = snapshot,
                    onContinue = {
                        if (snapshot.canContinue()) {
                            stage = SendStage.RequestDetails
                        }
                    },
                    onAdjustPickup = { draft.pickup?.let { controller.invalidate(it) } },
                    onRetry = {
                        draft.pickup?.let { pickup ->
                            scope.launch { controller.scan(pickup, online) }
                        }
                    }
                )
            }
        }

        SendStage.PickDestination -> {
            // A dedicated full-screen pick keeps the destination pin deliberate:
            // a mis-tapped destination is a delivery to the wrong side of Kigali.
            Column(Modifier.fillMaxSize()) {
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
                            "Tap or long-press where the rider should deliver",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small)
                        )
                    }
                }
                com.movo.design.MovoSheet {
                    Text(
                        if (draft.destination?.isFinite == true) "Destination pin placed" else "No destination pin yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        draft.destination?.let { "%.5f, %.5f".format(it.latitude, it.longitude) }
                            ?: "The rider navigates to this exact point.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(MovoSpacing.default))
                    com.movo.design.MovoButton(
                        "Confirm destination",
                        { stage = SendStage.RequestDetails },
                        enabled = draft.destination?.isFinite == true
                    )
                    com.movo.design.MovoTextAction("Back to details", { stage = SendStage.RequestDetails }, Modifier.fillMaxWidth())
                }
            }
        }

        SendStage.RequestDetails -> {
            RequestDetailsSheet(
                draft = draft,
                online = online,
                loading = loading,
                error = error,
                onBack = { stage = SendStage.Discovery },
                onPickDestination = { stage = SendStage.PickDestination },
                onGetQuote = {
                    val pickup = draft.pickup
                    val destination = draft.destination
                    if (pickup == null || !pickup.isFinite) {
                        error = "Select valid pickup and destination coordinates"
                        return@RequestDetailsSheet
                    }
                    if (destination == null || !destination.isFinite) {
                        error = "Tap the map to place the destination pin before pricing"
                        return@RequestDetailsSheet
                    }
                    if (listOf(draft.pickupAddress, draft.destinationAddress, draft.senderName, draft.senderPhone, draft.receiverName, draft.receiverPhone).any(String::isBlank)) {
                        error = "Complete sender, receiver, pickup, and destination details"
                        return@RequestDetailsSheet
                    }
                    loading = true; error = null
                    scope.launch {
                        runCatching {
                            api.post("/api/deliveries/price", JSONObject()
                                .put("pickup_lat", pickup.latitude).put("pickup_lng", pickup.longitude)
                                .put("dest_lat", destination.latitude).put("dest_lng", destination.longitude)
                                .put("service_type", draft.itemType)).dataObject()
                        }.onSuccess { data ->
                            quote = Quote(
                                data.optDouble("customerPrice", data.optDouble("totalCharge")),
                                data.optDouble("distance_km"),
                                data.optInt("estimatedMinutes", data.optInt("estimated_min"))
                            )
                            analytics.log(AnalyticsEvent.QUOTE_VIEWED, mapOf("service_type" to draft.itemType))
                            persist(); stage = SendStage.ConfirmRequest
                        }.onFailure { error = it.message }
                        loading = false
                    }
                },
                onUpdatePickupAddress = { draft = draft.copy(pickupAddress = it) },
                onUpdateDestinationAddress = { draft = draft.copy(destinationAddress = it) },
                onUpdateSenderName = { draft = draft.copy(senderName = it) },
                onUpdateSenderPhone = { draft = draft.copy(senderPhone = it) },
                onUpdateReceiverName = { draft = draft.copy(receiverName = it) },
                onUpdateReceiverPhone = { draft = draft.copy(receiverPhone = it) },
                onUpdateItemType = { draft = draft.copy(itemType = it) },
                onUpdateItemDescription = { draft = draft.copy(itemDescription = it) },
                onUpdateDeliveryInstructions = { draft = draft.copy(deliveryInstructions = it) },
                onUpdatePaymentMethod = { draft = draft.copy(paymentMethod = it) }
            )
        }

        SendStage.ConfirmRequest -> {
            val currentQuote = quote
            val resumeId = existingDeliveryId
            if (resumeId != null) {
                // Already created — e.g. the app was killed right after the POST
                // succeeded. Never re-request; just resume tracking.
                LaunchedEffect(resumeId) { onTracking(resumeId) }
            } else if (currentQuote != null) {
                ConfirmRequestSheet(
                    quote = currentQuote,
                    submitting = submitting,
                    error = error,
                    onBack = { quote = null; stage = SendStage.RequestDetails },
                    onConfirm = {
                        val pickup = draft.pickup
                        val destination = draft.destination
                        if (pickup == null || !pickup.isFinite || destination == null || !destination.isFinite) {
                            error = "Select valid pickup and destination coordinates"
                            return@ConfirmRequestSheet
                        }
                        submitting = true; error = null
                        scope.launch {
                            runCatching {
                                val body = JSONObject().put("service_type", draft.itemType).put("pickup_address", draft.pickupAddress)
                                    .put("pickup_lat", pickup.latitude).put("pickup_lng", pickup.longitude).put("pickup_name", draft.senderName)
                                    .put("pickup_phone", draft.senderPhone).put("dest_address", draft.destinationAddress)
                                    .put("dest_lat", destination.latitude).put("dest_lng", destination.longitude).put("dest_name", draft.receiverName)
                                    .put("dest_phone", draft.receiverPhone).put("item_description", draft.itemDescription)
                                    .put("special_instructions", draft.deliveryInstructions).put("payment_method", draft.paymentMethod)
                                api.post("/api/deliveries", body, creationKey).dataObject().optJSONObject("delivery")
                                    ?: throw IllegalStateException("Delivery response missing")
                            }.onSuccess { delivery ->
                                val id = delivery.optString("id")
                                if (id.isBlank()) {
                                    error = "Server did not return the delivery ID"
                                    submitting = false
                                } else {
                                    withContext(Dispatchers.IO) {
                                        session.saveJourney(SendJourney(draft, quote, id, creationKey))
                                    }
                                    submitting = false
                                    existingDeliveryId = id
                                    analytics.log(AnalyticsEvent.DELIVERY_CONFIRMED, mapOf("service_type" to draft.itemType))
                                    stage = SendStage.Waiting
                                    onTracking(id)
                                }
                            }.onFailure { e ->
                                error = e.message ?: "Unable to request a delivery"
                                submitting = false
                            }
                        }
                    }
                )
            }
        }

        SendStage.Waiting -> {
            // Reserved for post-create rider waiting state; tracking takes over.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(MovoSpacing.default))
                    Text("Opening live tracking…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * The last step before dispatch: approve the price, then request it. There is no
 * rider to choose here — dispatch is blind and zone-based (spec §12), so MOVO's
 * backend matches the nearest eligible rider automatically once the request lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmRequestSheet(
    quote: Quote,
    submitting: Boolean,
    error: String?,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Confirm your delivery", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !submitting) { Icon(Icons.Filled.ArrowBack, "Back to details") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(MovoSpacing.default),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.default)
        ) {
            PriceSummary(total = quote.price, distanceKm = quote.distanceKm, etaMinutes = quote.etaMinutes)
            Text(
                "MOVO will match you with the nearest available rider — dispatch is automatic, so there's no rider to choose.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let { MovoBanner(it, MovoTone.Critical) }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
            Column(Modifier.padding(MovoSpacing.default).navigationBarsPadding()) {
                MovoButton(
                    text = "Request delivery",
                    onClick = onConfirm,
                    enabled = !submitting,
                    loading = submitting
                )
            }
        }
    }
}
