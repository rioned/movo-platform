package com.movo.customer.send

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.model.NearbyRider
import com.movo.customer.model.Quote
import com.movo.customer.network.CustomerApi
import com.movo.design.EmptyState
import com.movo.design.MovoAvatar
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoServiceMode
import com.movo.design.MovoShapes
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.PriceSummary
import com.movo.design.RatingStars
import com.movo.design.ShimmerCard
import com.movo.design.StatusPill
import com.movo.design.formatDistance
import org.json.JSONObject

/**
 * Price confirmation and exclusive rider choice. The customer approves the fare
 * before the request reaches any rider (spec §6.6) and then picks exactly one
 * verified rider, whose details stay visible while the offer is outstanding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiderSelectionScreen(
    riders: List<NearbyRider>,
    existingDeliveryId: String?,
    onRefreshRiders: () -> Unit,
    refreshing: Boolean,
    submitting: Boolean,
    onSelectRider: (riderId: String) -> Unit,
    idempotencyKey: String,
    replacementIdempotencyKey: String,
    onBack: () -> Unit,
    error: String?,
    quote: Quote? = null,
    mode: MovoServiceMode = MovoServiceMode.Delivery
) {
    var selected by remember { mutableStateOf<NearbyRider?>(null) }
    val replacementStates = setOf("awaiting_rider_selection", "declined", "expired")

    BackHandler(enabled = !submitting, onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    when {
                        existingDeliveryId != null -> "Choose a replacement rider"
                        mode.isRide -> "Choose your rider"
                        else -> "Choose one rider"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
            },
            navigationIcon = { IconButton(onClick = onBack, enabled = !submitting) { Icon(Icons.Filled.ArrowBack, "Back to request") } },
            actions = {
                IconButton(onClick = onRefreshRiders, enabled = !refreshing && !submitting) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh rider availability")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Column(Modifier.padding(horizontal = MovoSpacing.default), verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
            quote?.let {
                PriceSummary(total = it.price, distanceKm = it.distanceKm, etaMinutes = it.etaMinutes)
            }
            if (existingDeliveryId != null) {
                MovoBanner(
                    "Your ${mode.noun} is kept after ${replacementStates.joinToString(" / ").replace('_', ' ')}. Pick another rider to continue.",
                    MovoTone.Info
                )
            }
            error?.let { MovoBanner(it, MovoTone.Critical) }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f)) {
            when {
                refreshing && riders.isEmpty() -> Column(
                    Modifier.padding(MovoSpacing.default),
                    verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)
                ) { repeat(3) { ShimmerCard() } }

                riders.isEmpty() -> EmptyState(
                    title = if (mode.isRide) "No riders free right now" else "No riders available now",
                    message = "Rider availability changes minute by minute in Kigali. Scan again or adjust your pickup point.",
                    action = { MovoSecondaryButton("Retry", onRefreshRiders, Modifier.fillMaxWidth(0.6f)) }
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(MovoSpacing.default),
                    verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)
                ) {
                    items(riders, key = { it.id }) { rider ->
                        RiderCard(
                            rider = rider,
                            selected = selected?.id == rider.id,
                            enabled = !submitting,
                            onSelect = { selected = rider }
                        )
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
            Column(Modifier.padding(MovoSpacing.default).navigationBarsPadding()) {
                MovoButton(
                    text = when {
                        existingDeliveryId != null -> "Request replacement rider"
                        mode.isRide -> "Request this rider"
                        else -> "Request selected rider"
                    },
                    onClick = { selected?.id?.let(onSelectRider) },
                    enabled = selected != null && !submitting,
                    loading = submitting
                )
            }
        }
    }
}

@Composable
private fun RiderCard(rider: NearbyRider, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
        shape = MovoShapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shadowElevation = if (selected) 0.dp else 2.dp
    ) {
        Row(Modifier.padding(MovoSpacing.default), verticalAlignment = Alignment.CenterVertically) {
            MovoAvatar(rider.name, size = 52.dp, online = true)
            Column(Modifier.weight(1f).padding(horizontal = MovoSpacing.medium)) {
                Text(rider.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                    RatingStars(rider.rating, starSize = 14.dp)
                    Text(
                        if (rider.rating > 0) String.format("%.1f", rider.rating) else "New rider",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(rider.vehicle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(MovoSpacing.tiny)) {
                StatusPill(formatDistance(rider.distanceKm), MovoTone.Neutral, showDot = false)
                if (selected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(" Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

suspend fun selectReplacement(api: CustomerApi, existingDeliveryId: String, riderId: String, idempotencyKey: String) =
    api.put("/api/deliveries/$existingDeliveryId/select-rider", JSONObject().put("preferred_rider_id", riderId), idempotencyKey)
