package com.movo.customer.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.dataObject
import com.movo.customer.model.Delivery
import com.movo.customer.model.toDelivery
import com.movo.customer.network.CustomerApi
import com.movo.design.EmptyState
import com.movo.design.MovoBanner
import com.movo.design.MovoCard
import com.movo.design.MovoDeliveryStage
import com.movo.design.MovoServiceMode
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.RouteCard
import com.movo.design.ServiceModeBadge
import com.movo.design.ShimmerCard
import com.movo.design.StatusPill
import com.movo.design.formatTimestamp
import com.movo.design.serviceLabel

enum class ActivityFilter(val apiValue: String, val title: String) {
    Sent("sent", "Sent"), Received("received", "Received"), All("all", "All")
}

/**
 * History with proof, status and one tap back into live tracking (spec §6.13).
 *
 * Scoped to the product currently on screen: a passenger looking through their
 * trips does not want last week's parcels mixed in. "Everything" is one tap away
 * for the times they do.
 */
@Composable
fun ActivityScreen(api: CustomerApi, mode: MovoServiceMode, onTrack: (String) -> Unit) {
    // Reset per mode: a ride has no sender/received split, so switching product
    // must not carry a "Received" filter across into a list that cannot honour it.
    var filter by remember(mode) { mutableStateOf(ActivityFilter.All) }
    var showAllModes by remember(mode) { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // A ride has no separate receiver, so the sent/received split is meaningless
    // there — the row of chips is simply not offered.
    val relationshipFilters = !mode.isRide

    LaunchedEffect(filter, mode) {
        loading = true; error = null
        runCatching {
            val data = api.get("/api/mobile/v1/customer/deliveries?role=${filter.apiValue}").dataObject()
            val array = data.optJSONArray("deliveries")
            List(array?.length() ?: 0) { array!!.getJSONObject(it).toDelivery() }
        }.onSuccess { jobs = it }.onFailure { error = it.message }
        loading = false
    }

    val visible = remember(jobs, showAllModes, mode) {
        if (showAllModes) jobs else jobs.filter { it.mode == mode }
    }
    val hiddenCount = jobs.size - visible.size

    Column(Modifier.fillMaxSize().padding(horizontal = MovoSpacing.default)) {
        Text(
            if (mode.isRide) "Your trips" else "Activity",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = MovoSpacing.medium)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
            if (relationshipFilters) {
                ActivityFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
            if (showAllModes || hiddenCount > 0) {
                FilterChip(
                    selected = showAllModes,
                    onClick = { showAllModes = !showAllModes },
                    label = { Text(if (showAllModes) "All services" else "Show all ($hiddenCount)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
        Spacer(Modifier.height(MovoSpacing.medium))
        error?.let { MovoBanner(it, MovoTone.Critical); Spacer(Modifier.height(MovoSpacing.small)) }

        when {
            loading -> Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) { repeat(3) { ShimmerCard() } }
            visible.isEmpty() -> EmptyState(
                title = if (mode.isRide) "No trips yet" else "No ${filter.title.lowercase()} deliveries yet",
                message = if (mode.isRide) "Trips you take with MOVO appear here with their route and receipt."
                    else "Deliveries you send or receive appear here with their proof of delivery and receipts."
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.small),
                contentPadding = PaddingValues(bottom = MovoSpacing.section)
            ) {
                items(visible, key = { it.id }) { job ->
                    DeliveryRow(job, filter, onTrack)
                }
            }
        }
    }
}

@Composable
internal fun DeliveryRow(delivery: Delivery, filter: ActivityFilter, onTrack: (String) -> Unit) {
    val stage = MovoDeliveryStage.from(delivery.status)
    val mode = delivery.mode
    // A trip has one participant, so the sender/receiver caption says nothing —
    // the passenger count is the fact worth showing instead.
    val caption = if (mode.isRide) {
        val seats = delivery.passengerCount ?: 1
        if (seats > 1) "$seats passengers" else "Moto ride"
    } else {
        val relationship = delivery.relationship?.replaceFirstChar { it.uppercase() } ?: when (filter) {
            ActivityFilter.Received -> "Receiver"
            ActivityFilter.Sent -> "Sender"
            ActivityFilter.All -> "Participant"
        }
        "$relationship • ${serviceLabel(delivery.serviceType)}"
    }
    MovoCard(Modifier.clickable { onTrack(delivery.id) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                    ServiceModeBadge(mode)
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(MovoSpacing.tiny))
                Text(
                    "${if (mode.isRide) "Trip" else "Order"} ${delivery.orderNo ?: delivery.id.take(8)}",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            StatusPill(stage.shortLabel, stage.tone)
        }
        Spacer(Modifier.height(MovoSpacing.medium))
        RouteCard(
            pickup = delivery.pickupAddress,
            destination = delivery.destinationAddress,
            trailing = { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        )
        delivery.createdAt?.let {
            Spacer(Modifier.height(MovoSpacing.small))
            Text(formatTimestamp(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
