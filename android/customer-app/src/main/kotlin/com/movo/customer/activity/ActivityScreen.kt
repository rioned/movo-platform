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
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.RouteCard
import com.movo.design.ShimmerCard
import com.movo.design.StatusPill
import com.movo.design.formatTimestamp
import com.movo.design.serviceLabel

enum class ActivityFilter(val apiValue: String, val title: String) {
    Sent("sent", "Sent"), Received("received", "Received"), All("all", "All")
}

/** Delivery history with proof, status and one tap back into live tracking (spec §6.13). */
@Composable
fun ActivityScreen(api: CustomerApi, onTrack: (String) -> Unit) {
    var filter by remember { mutableStateOf(ActivityFilter.All) }
    var deliveries by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(filter) {
        loading = true; error = null
        runCatching {
            val data = api.get("/api/mobile/v1/customer/deliveries?role=${filter.apiValue}").dataObject()
            val array = data.optJSONArray("deliveries")
            List(array?.length() ?: 0) { array!!.getJSONObject(it).toDelivery() }
        }.onSuccess { deliveries = it }.onFailure { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = MovoSpacing.default)) {
        Text("Activity", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = MovoSpacing.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
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
        Spacer(Modifier.height(MovoSpacing.medium))
        error?.let { MovoBanner(it, MovoTone.Critical); Spacer(Modifier.height(MovoSpacing.small)) }

        when {
            loading -> Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) { repeat(3) { ShimmerCard() } }
            deliveries.isEmpty() -> EmptyState(
                title = if (filter == ActivityFilter.All) "No deliveries yet" else "No ${filter.title.lowercase()} deliveries yet",
                message = "Deliveries you send or receive appear here with their proof of delivery and receipts."
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.small),
                contentPadding = PaddingValues(bottom = MovoSpacing.section)
            ) {
                items(deliveries, key = { it.id }) { delivery ->
                    DeliveryRow(delivery, filter, onTrack)
                }
            }
        }
    }
}

@Composable
internal fun DeliveryRow(delivery: Delivery, filter: ActivityFilter, onTrack: (String) -> Unit) {
    val stage = MovoDeliveryStage.from(delivery.status)
    val relationship = delivery.relationship?.replaceFirstChar { it.uppercase() } ?: when (filter) {
        ActivityFilter.Received -> "Receiver"
        ActivityFilter.Sent -> "Sender"
        ActivityFilter.All -> "Participant"
    }
    MovoCard(Modifier.clickable { onTrack(delivery.id) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    "$relationship • ${serviceLabel(delivery.serviceType)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Order ${delivery.orderNo ?: delivery.id.take(8)}", style = MaterialTheme.typography.titleSmall)
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
