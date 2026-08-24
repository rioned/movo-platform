package com.movo.customer.ride

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movo.customer.BuildConfig
import com.movo.customer.dataObject
import com.movo.customer.location.CustomerLocation
import com.movo.customer.map.CustomerMap
import com.movo.customer.model.Coordinate
import com.movo.customer.model.Ride
import com.movo.customer.model.toRide
import com.movo.customer.model.toRideDriver
import com.movo.customer.network.CustomerApi
import com.movo.customer.network.CustomerApiException
import com.movo.customer.model.RideDriver
import com.movo.design.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * A single adaptive screen for the whole trip — driver assigned, live tracking,
 * arrival, payment and rating — the same "server is the authority, realtime just
 * triggers a refetch" pattern as [com.movo.customer.tracking.TrackingScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideTrackingScreen(rideId: String, api: CustomerApi, token: String, networkConnected: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    var ride by remember { mutableStateOf<Ride?>(null) }
    var driver by remember { mutableStateOf<RideDriver?>(null) }
    var driverLocation by remember { mutableStateOf<Coordinate?>(null) }
    var loading by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showRating by remember { mutableStateOf(false) }
    var showSos by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val refreshMutex = remember(rideId) { Mutex() }
    val context = LocalContext.current
    val customerLocation = remember { CustomerLocation(context) }

    fun refreshAuthoritative() { scope.launch {
        refreshMutex.withLock {
            runCatching {
                val data = api.get("/api/rides/$rideId/track").dataObject()
                val parsedRide = data.getJSONObject("ride").toRide()
                val parsedDriver = data.optJSONObject("driver")?.toRideDriver()
                val location = data.optJSONObject("driverLocation")
                Triple(parsedRide, parsedDriver, location?.let { Coordinate(it.optDouble("lat"), it.optDouble("lng")) }?.takeIf { it.isFinite })
            }.onSuccess { (r, d, loc) -> ride = r; driver = d; driverLocation = loc; error = null }
                .onFailure { error = it.message ?: "Tracking unavailable" }
            loading = false
        }
    } }

    val realtime = remember(rideId, token) { RideRealtime(token, onReconnect = { refreshAuthoritative() }, onUpdate = { refreshAuthoritative() }, onConnection = { connected = it }) }
    DisposableEffect(realtime, rideId) { realtime.subscribe(rideId); refreshAuthoritative(); onDispose { realtime.disconnect() } }
    LaunchedEffect(rideId, connected, networkConnected) {
        while (isActive) { delay(if (!connected || !networkConnected) 10_000 else 30_000); refreshAuthoritative() }
    }
    BackHandler(onBack = onBack)

    val item = ride
    if (loading && item == null) {
        Column(Modifier.fillMaxSize().padding(MovoSpacing.default), verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
            ShimmerBlock(height = 220.dp); ShimmerCard(); ShimmerCard()
        }
        return
    }
    if (item == null) {
        EmptyState(title = "Ride unavailable", message = error ?: "MOVO could not load this ride.", action = { MovoSecondaryButton("Retry", { refreshAuthoritative() }, Modifier.fillMaxWidth(0.6f)) })
        return
    }

    val stage = MovoRideStage.from(item.status)
    val cancellable = item.status in setOf("searching", "assigned", "driver_en_route")

    if (showRating) RideRatingDialog(rideId, api, onDismiss = { showRating = false }, onConfirmed = { showRating = false; actionMessage = "Thanks for rating your trip"; refreshAuthoritative() }, onError = { error = it })
    if (showSos) RideSosDialog(rideId, api, customerLocation, onDismiss = { showSos = false }, onConfirmed = { showSos = false; actionMessage = "MOVO operations has been alerted" }, onError = { error = it })

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(if (item.rideNo.isBlank()) "Your ride" else "Trip ${item.rideNo}", style = MaterialTheme.typography.titleMedium)
                    Text(formatMoney(item.totalFare, item.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { IconButton(onClick = { refreshAuthoritative() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box {
                CustomerMap(item.pickup, item.destination, assignedRider = driverLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 320.dp))
                StatusPill(
                    text = when { !networkConnected -> "Offline"; !connected -> "Reconnecting"; else -> "Live" },
                    tone = if (!networkConnected || !connected) MovoTone.Warning else MovoTone.Positive,
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
                    RideProgress(stage)
                    Spacer(Modifier.height(MovoSpacing.default))
                    RouteCard(pickup = item.pickupAddress, destination = item.destAddress)
                }

                // Step 8: driver identity — name, rating, car model/color and plate.
                driver?.let { d ->
                    MovoCard {
                        SectionHeader("Your driver")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MovoAvatar(d.name, size = 52.dp, online = stage != MovoRideStage.Completed)
                            Column(Modifier.weight(1f).padding(horizontal = MovoSpacing.medium)) {
                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RatingStars(d.rating ?: 0.0, starSize = 14.dp)
                                    Text("  ${d.vehicle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                        d.plate?.let { plate ->
                            Spacer(Modifier.height(MovoSpacing.small))
                            KeyValueRow("Plate — verify before getting in", plate)
                        }
                        Spacer(Modifier.height(MovoSpacing.medium))
                        d.phone?.let { phone ->
                            MovoSecondaryButton(
                                "Call driver", { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))) },
                                Modifier.fillMaxWidth(), leading = { Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                if (item.status == "arrived_pickup") {
                    MovoBanner("Your driver has arrived. Check the plate matches before getting in — the trip starts once your driver confirms.", MovoTone.Warning)
                }

                // Step 11: share the trip and reach an emergency button while the ride is active.
                if (!stage.isTerminal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                        MovoSecondaryButton(
                            "Share trip", {
                                scope.launch {
                                    runCatching { api.post("/api/rides/$rideId/share", JSONObject()).dataObject().optString("share_path") }
                                        .onSuccess { path ->
                                            val url = BuildConfig.API_BASE_URL.trimEnd('/') + path
                                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Follow my MOVO trip live: $url") }, "Share trip"))
                                        }.onFailure { error = it.message }
                                }
                            }, Modifier.weight(1f), leading = { Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        MovoSecondaryButton(
                            "SOS", { showSos = true }, Modifier.weight(1f), tone = MaterialTheme.colorScheme.error,
                            leading = { Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }

                // Step 12: pay at the end — cash settles automatically; card/mpesa need confirmation here.
                if (item.status == "completed" && item.paymentStatus != "paid") {
                    MovoCard {
                        SectionHeader("Complete payment")
                        Text("Total due: ${formatMoney(item.totalFare, item.currency)} via ${item.paymentMethod?.uppercase()}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(MovoSpacing.default))
                        MovoButton("Pay now", {
                            scope.launch {
                                runCatching { api.post("/api/rides/$rideId/pay", JSONObject().put("method", item.paymentMethod ?: "mpesa")) }
                                    .onSuccess { actionMessage = "Payment confirmed"; refreshAuthoritative() }
                                    .onFailure { error = it.message }
                            }
                        })
                    }
                }

                actionMessage?.let { MovoBanner(it, MovoTone.Positive) }
                error?.let { MovoBanner(it, MovoTone.Critical) }

                if (item.status == "completed" && item.customerRating == null) {
                    MovoButton("Rate your trip", { showRating = true })
                }
                if (item.status == "completed" && item.customerRating != null) {
                    MovoButton("Done", onDone)
                }
                if (cancellable) {
                    MovoSecondaryButton(
                        "Cancel ride", {
                            scope.launch {
                                runCatching { api.put("/api/rides/$rideId/cancel", JSONObject().put("reason", "Cancelled by customer")) }
                                    .onSuccess { actionMessage = "Ride cancelled"; refreshAuthoritative() }
                                    .onFailure { e -> error = if (e is CustomerApiException) e.message else e.message }
                            }
                        }, tone = MaterialTheme.colorScheme.error
                    )
                }
                if (item.status == "cancelled") MovoButton("Back to booking", onDone)
                Spacer(Modifier.height(MovoSpacing.section))
            }
        }
    }
}

@Composable
private fun RideRatingDialog(rideId: String, api: CustomerApi, onDismiss: () -> Unit, onConfirmed: () -> Unit, onError: (String) -> Unit) {
    var score by remember { mutableIntStateOf(5) }
    var review by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Rate your driver") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                Text("How was the trip?", style = MaterialTheme.typography.bodyMedium)
                RatingStars(score.toDouble(), onRate = { score = it }, starSize = 30.dp)
                Text("Score: $score / 5", style = MaterialTheme.typography.bodySmall)
                MovoField(review, { review = it.take(500) }, "Review (optional)", singleLine = false)
            }
        },
        confirmButton = {
            TextButton(enabled = !submitting, onClick = {
                submitting = true
                scope.launch {
                    runCatching { api.post("/api/ratings", JSONObject().put("ride_id", rideId).put("score", score).put("review", review.takeIf(String::isNotBlank))) }
                        .onSuccess { onConfirmed() }.onFailure { onError(it.message ?: "Rating failed"); submitting = false }
                }
            }) { Text(if (submitting) "Submitting…" else "Submit") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Not now") } }
    )
}

@Composable
private fun RideSosDialog(rideId: String, api: CustomerApi, location: CustomerLocation, onDismiss: () -> Unit, onConfirmed: () -> Unit, onError: (String) -> Unit) {
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Emergency SOS", color = MaterialTheme.colorScheme.error) },
        text = { Text("This immediately alerts MOVO operations with your trip and location. Use it only in an emergency.") },
        confirmButton = {
            TextButton(enabled = !submitting, onClick = {
                submitting = true
                location.requestCurrent { result ->
                    val point = result.getOrNull()
                    scope.launch {
                        val body = JSONObject().put("kind", "sos")
                        point?.let { body.put("lat", it.latitude).put("lng", it.longitude) }
                        runCatching { api.post("/api/rides/$rideId/sos", body) }
                            .onSuccess { onConfirmed() }.onFailure { onError(it.message ?: "SOS failed"); submitting = false }
                    }
                }
            }) { Text(if (submitting) "Alerting…" else "Send SOS", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") } }
    )
}
