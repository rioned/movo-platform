package com.movo.customer.tracking

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movo.customer.dataObject
import com.movo.customer.map.CustomerMap
import com.movo.customer.model.*
import com.movo.customer.network.CustomerApi
import com.movo.customer.realtime.CustomerRealtime
import com.movo.customer.session.CustomerSession
import com.movo.design.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Live delivery tracking (spec §6.8). The screen always renders the last state
 * the server confirmed: realtime events only trigger an authoritative refetch,
 * so a stale socket can never show a customer a delivery stage that never happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingScreen(
    deliveryId: String, api: CustomerApi, token: String, session: CustomerSession,
    networkConnected: Boolean, onBack: () -> Unit, onReselect: () -> Unit
) {
    var delivery by remember { mutableStateOf<Delivery?>(null) }
    var riderLocation by remember { mutableStateOf<Coordinate?>(null) }
    var locationTime by remember { mutableStateOf<String?>(null) }
    var serverTime by remember { mutableStateOf<String?>(null) }
    var timeline by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRating by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val refreshMutex = remember(deliveryId) { Mutex() }
    val context = LocalContext.current

    fun refreshAuthoritative() { scope.launch {
        refreshMutex.withLock {
            runCatching {
                val data = api.get("/api/deliveries/$deliveryId/track").dataObject()
                val parsed = data.getJSONObject("delivery").toDelivery()
                if (parsed.id != deliveryId) throw IllegalStateException("Tracking response order mismatch")
                val relationship = data.optString("relationship").takeIf(String::isNotBlank) ?: parsed.relationship
                val riderJson = data.optJSONObject("rider")
                val withAuthority = parsed.copy(relationship = relationship, rider = riderJson?.toRider() ?: parsed.rider)
                val location = data.optJSONObject("riderLocation")
                val events: JSONArray? = data.optJSONArray("events")
                TrackingResult(
                    withAuthority,
                    location?.let { Coordinate(it.optDouble("lat"), it.optDouble("lng")) }?.takeIf { it.isFinite },
                    location?.optString("created_at")?.takeIf(String::isNotBlank) ?: location?.optString("updated_at")?.takeIf(String::isNotBlank),
                    data.optString("serverTime").takeIf(String::isNotBlank),
                    List(events?.length() ?: 0) { index ->
                        val event = events!!.getJSONObject(index)
                        TimelineEvent(event.optString("status", "Update"), event.optString("note").takeIf(String::isNotBlank), event.optString("created_at").takeIf(String::isNotBlank))
                    }
                )
            }.onSuccess { result ->
                delivery = result.delivery; riderLocation = result.riderLocation; locationTime = result.locationAt
                serverTime = result.serverTime; timeline = result.events; error = null
                if (result.delivery.status in setOf("delivered", "cancelled", "failed")) session.clearJourney()
            }.onFailure { error = it.message ?: "Tracking unavailable" }
            loading = false
        }
    } }

    val realtime = remember(deliveryId, token) {
        CustomerRealtime(token, onReconnect = { refreshAuthoritative() }, onUpdate = { refreshAuthoritative() }, onConnection = { connected = it })
    }
    DisposableEffect(realtime, deliveryId) { realtime.subscribe(deliveryId); refreshAuthoritative(); onDispose { realtime.disconnect() } }
    LaunchedEffect(deliveryId, connected, networkConnected) {
        while (isActive) {
            delay(if (!connected || !networkConnected) 15_000 else 60_000)
            refreshAuthoritative()
        }
    }
    BackHandler(onBack = onBack)

    val item = delivery
    if (loading && item == null) {
        Column(Modifier.fillMaxSize().padding(MovoSpacing.default), verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
            ShimmerBlock(height = 220.dp)
            ShimmerCard()
            ShimmerCard()
        }
        return
    }
    if (item == null) {
        EmptyState(
            title = "Delivery unavailable",
            message = error ?: "MOVO could not load this delivery.",
            action = { MovoSecondaryButton("Retry", { refreshAuthoritative() }, Modifier.fillMaxWidth(0.6f)) }
        )
        return
    }

    val stale = locationTime?.let { locationAt ->
        runCatching { Duration.between(parseInstant(locationAt), serverTime?.let(::parseInstant) ?: Instant.now()).seconds > 120 }.getOrDefault(true)
    } ?: true
    val stage = MovoDeliveryStage.from(item.status)
    val relationship = item.relationship?.lowercase()
    val isSender = relationship == "sender"
    val isReceiver = relationship == "receiver"
    val cancellableByCustomer = isSender && item.status in setOf("created", "searching", "assigned")

    if (showRating) RatingDialog(deliveryId, api, onDismiss = { showRating = false }, onConfirmed = { showRating = false; actionMessage = "Rating submitted"; refreshAuthoritative() }, onError = { error = it })
    if (showSupport) SupportDialog(deliveryId, api, onDismiss = { showSupport = false }, onConfirmed = { showSupport = false; actionMessage = "Support ticket created" }, onError = { error = it })

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Order ${item.orderNo ?: item.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${serviceLabel(item.serviceType)} • ${relationship?.replaceFirstChar { it.uppercase() } ?: "Participant"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { IconButton(onClick = { refreshAuthoritative() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box {
                CustomerMap(
                    item.pickup, item.destination, assignedRider = riderLocation,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 320.dp)
                )
                StatusPill(
                    text = when {
                        !networkConnected -> "Offline"
                        !connected -> "Reconnecting"
                        stale -> "Location delayed"
                        else -> "Live"
                    },
                    tone = when {
                        !networkConnected || !connected -> MovoTone.Warning
                        stale -> MovoTone.Warning
                        else -> MovoTone.Positive
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(MovoSpacing.medium)
                )
            }

            Column(Modifier.padding(MovoSpacing.default), verticalArrangement = Arrangement.spacedBy(MovoSpacing.medium)) {
                MovoCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stage.customerLabel, style = MaterialTheme.typography.titleLarge)
                        StatusPill(stage.shortLabel, stage.tone)
                    }
                    Spacer(Modifier.height(MovoSpacing.medium))
                    DeliveryProgress(stage)
                    Spacer(Modifier.height(MovoSpacing.default))
                    RouteCard(pickup = item.pickupAddress, destination = item.destinationAddress)
                }

                item.rider?.let { rider ->
                    MovoCard {
                        SectionHeader("Your rider")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MovoAvatar(rider.name, size = 52.dp, online = !stale)
                            Column(Modifier.weight(1f).padding(horizontal = MovoSpacing.medium)) {
                                Text(rider.name, style = MaterialTheme.typography.titleMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RatingStars(rider.rating ?: 0.0, starSize = 14.dp)
                                    Text(
                                        "  ${rider.vehicle}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(MovoSpacing.medium))
                        Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                            rider.phone?.let { phone ->
                                MovoSecondaryButton(
                                    "Call rider",
                                    { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))) },
                                    Modifier.weight(1f),
                                    leading = { Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                            MovoSecondaryButton(
                                "Navigate",
                                {
                                    item.destination?.let { point ->
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${point.latitude},${point.longitude}&travelmode=driving")))
                                    }
                                },
                                Modifier.weight(1f),
                                leading = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                if (isSender) item.pickupOtp?.let {
                    OtpCallout("Pickup code", it, "Give this code to the rider only when handing over your item.")
                }
                if (isReceiver && item.status == "arrived_dest") item.deliveryOtp?.let {
                    OtpCallout("Handover code", it, "Share this code with the rider once you have received your item.")
                }

                item.totalCharge?.let { total ->
                    PriceSummary(total = total, distanceKm = item.distanceKm, zoneLabel = item.paymentMethod?.replace('_', ' ')?.replaceFirstChar { it.uppercase() })
                }

                MovoCard {
                    SectionHeader("Timeline")
                    if (timeline.isEmpty()) {
                        Text("Waiting for the first confirmed event", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DeliveryTimeline(
                            timeline.asReversed().map { event ->
                                TimelineEntry(
                                    title = event.note ?: MovoDeliveryStage.from(event.status).customerLabel,
                                    subtitle = event.createdAt?.let(::formatTimestamp)
                                )
                            }
                        )
                    }
                }

                actionMessage?.let { MovoBanner(it, MovoTone.Positive) }
                error?.let { MovoBanner(it, MovoTone.Critical) }

                if (item.status == "awaiting_rider_selection" && isSender) {
                    MovoButton("Select another rider", onReselect)
                }
                if (item.status == "delivered" && isSender) {
                    MovoButton("Rate delivery", { showRating = true })
                }
                if (cancellableByCustomer) {
                    MovoSecondaryButton(
                        "Cancel delivery",
                        {
                            scope.launch {
                                runCatching { api.put("/api/deliveries/$deliveryId/cancel", JSONObject().put("reason", "Cancelled by customer")) }
                                    .onSuccess { actionMessage = "Delivery cancelled"; refreshAuthoritative() }
                                    .onFailure { error = it.message }
                            }
                        },
                        tone = MaterialTheme.colorScheme.error
                    )
                }
                MovoTextAction("Support", { showSupport = true }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(MovoSpacing.section))
            }
        }
    }
}

@Composable
private fun RatingDialog(deliveryId: String, api: CustomerApi, onDismiss: () -> Unit, onConfirmed: () -> Unit, onError: (String) -> Unit) {
    var score by remember { mutableIntStateOf(5) }
    var review by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Rate this delivery") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                Text("How was the rider's professionalism, speed and handling?", style = MaterialTheme.typography.bodyMedium)
                RatingStars(score.toDouble(), onRate = { score = it }, starSize = 30.dp)
                Text("Score: $score / 5", style = MaterialTheme.typography.bodySmall)
                MovoField(review, { review = it.take(500) }, "Review (optional)", singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    submitting = true
                    scope.launch {
                        runCatching { api.post("/api/ratings", JSONObject().put("delivery_id", deliveryId).put("score", score).put("review", review.takeIf(String::isNotBlank))) }
                            .onSuccess { onConfirmed() }
                            .onFailure { onError(it.message ?: "Rating failed"); submitting = false }
                    }
                }
            ) { Text(if (submitting) "Submitting…" else "Submit") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Not now") } }
    )
}

@Composable
private fun SupportDialog(deliveryId: String, api: CustomerApi, onDismiss: () -> Unit, onConfirmed: () -> Unit, onError: (String) -> Unit) {
    var category by remember { mutableStateOf("delivery") }
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("medium") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Report a problem") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                SegmentedChoice(
                    options = listOf(
                        SegmentOption("delivery", "Delivery"),
                        SegmentOption("payment", "Payment"),
                        SegmentOption("rider", "Rider")
                    ),
                    selected = category, onSelect = { category = it }
                )
                MovoField(subject, { subject = it.take(120) }, "Subject")
                MovoField(description, { description = it.take(1000) }, "Description", singleLine = false)
                Text("Priority", style = MaterialTheme.typography.labelMedium)
                SegmentedChoice(
                    options = listOf(SegmentOption("low", "Low"), SegmentOption("medium", "Medium"), SegmentOption("high", "High")),
                    selected = priority, onSelect = { priority = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && category.isNotBlank() && subject.isNotBlank() && description.isNotBlank(),
                onClick = {
                    submitting = true
                    scope.launch {
                        runCatching {
                            api.post("/api/tickets", JSONObject().put("delivery_id", deliveryId).put("category", category).put("subject", subject).put("description", description).put("priority", priority))
                        }.onSuccess { onConfirmed() }
                            .onFailure { onError(it.message ?: "Ticket failed"); submitting = false }
                    }
                }
            ) { Text(if (submitting) "Sending…" else "Create ticket") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun parseInstant(value: String): Instant = Instant.parse(value.replace(' ', 'T').let { if (it.endsWith("Z")) it else "${it}Z" })
private data class TrackingResult(val delivery: Delivery, val riderLocation: Coordinate?, val locationAt: String?, val serverTime: String?, val events: List<TimelineEvent>)
