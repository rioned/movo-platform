package com.movo.customer.receive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.movo.design.MovoAvatar
import com.movo.design.MovoBanner
import com.movo.design.MovoCard
import com.movo.design.MovoDeliveryStage
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.RouteCard
import com.movo.design.ShimmerCard
import com.movo.design.StatusPill
import com.movo.design.serviceLabel
import kotlinx.coroutines.launch

/**
 * Inbound deliveries. Anything addressed to the customer's verified phone number
 * appears here automatically — no code to enter, no link to open.
 */
@Composable
fun ReceiveScreen(api: CustomerApi, onTrack: (String) -> Unit) {
    var deliveries by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        loading = true; error = null
        scope.launch {
            runCatching {
                val home = api.get("/api/mobile/v1/customer/home").dataObject()
                val activeReceived = home.optJSONArray("activeReceived")
                val recentReceived = home.optJSONArray("recentReceived")
                buildList {
                    for (array in listOf(activeReceived, recentReceived)) {
                        for (i in 0 until (array?.length() ?: 0)) add(array!!.getJSONObject(i).toDelivery())
                    }
                }
            }.onSuccess { deliveries = it }.onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = MovoSpacing.default)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = MovoSpacing.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Receive", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Deliveries are matched automatically to your verified phone number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = ::refresh, enabled = !loading) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
        error?.let { MovoBanner(it, MovoTone.Critical); Spacer(Modifier.height(MovoSpacing.small)) }

        when {
            loading -> Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) { repeat(2) { ShimmerCard() } }
            deliveries.isEmpty() -> EmptyState(
                title = "No deliveries are addressed to you yet",
                message = "When someone sends you a parcel or document, it appears here with live tracking and your handover code."
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.small),
                contentPadding = PaddingValues(bottom = MovoSpacing.section)
            ) {
                items(deliveries, key = { it.id }) { delivery ->
                    val stage = MovoDeliveryStage.from(delivery.status)
                    MovoCard(Modifier.clickable { onTrack(delivery.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(
                                    "Receiver • ${serviceLabel(delivery.serviceType)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("Order ${delivery.orderNo ?: delivery.id.take(8)}", style = MaterialTheme.typography.titleSmall)
                            }
                            StatusPill(stage.shortLabel, stage.tone)
                        }
                        Spacer(Modifier.height(MovoSpacing.medium))
                        RouteCard(pickup = delivery.pickupAddress, destination = delivery.destinationAddress)
                        Spacer(Modifier.height(MovoSpacing.medium))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MovoAvatar(delivery.rider?.name ?: "MOVO", size = 32.dp)
                            Column(Modifier.padding(start = MovoSpacing.small)) {
                                Text(
                                    delivery.rider?.let { "Assigned to ${it.name}" }
                                        ?: if (delivery.riderId != null) "Assigned to a rider" else "Assignment pending",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    delivery.rider?.vehicle ?: "Sender: ${delivery.senderName ?: "MOVO customer"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
