package com.movo.rider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.movo.design.DeliveryProgress
import com.movo.design.MovoButton
import com.movo.design.MovoDeliveryStage
import com.movo.design.MovoField
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSheet
import com.movo.design.MovoSpacing
import com.movo.design.MovoTextAction
import com.movo.design.MovoTone
import com.movo.design.RouteCard
import com.movo.design.StatusPill
import com.movo.design.formatDistance
import com.movo.design.formatRwf
import com.movo.design.serviceLabel
import com.movo.rider.model.ActiveDelivery
import com.movo.rider.model.nextRiderAction

/**
 * The rider's working surface for an accepted delivery (spec §7.6/§7.7): one
 * obvious next action, navigation and calling one tap away, and the verification
 * code entry that gates the two handover points.
 */
@Composable
fun ActiveDeliverySheet(
    delivery: ActiveDelivery,
    busy: Boolean,
    otp: String,
    onOtpChange: (String) -> Unit,
    onAdvance: () -> Unit,
    onCall: (String) -> Unit,
    onNavigate: (Double?, Double?) -> Unit,
    onAddProof: () -> Unit,
    onReportIssue: () -> Unit
) {
    val stage = MovoDeliveryStage.from(delivery.status)
    val action = nextRiderAction(delivery.status)
    val headingToPickup = stage.trackedStep <= 3
    var recipientName by remember(delivery.id) { mutableStateOf(delivery.destinationName) }

    MovoSheet {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stage.riderLabel, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${serviceLabel(delivery.serviceType)} • ${delivery.orderNo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatRwf(delivery.earnings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(formatDistance(delivery.distanceKm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(MovoSpacing.medium))
            DeliveryProgress(stage)
            Spacer(Modifier.height(MovoSpacing.default))

            RouteCard(
                pickup = delivery.pickupAddress,
                destination = delivery.destinationAddress,
                pickupCaption = "Customer pickup • ${listOf(delivery.pickupName, delivery.pickupPhone).filter(String::isNotBlank).joinToString(" · ")}",
                destinationCaption = "Delivery destination • ${listOf(delivery.destinationName, delivery.destinationPhone).filter(String::isNotBlank).joinToString(" · ")}"
            )

            delivery.itemDescription?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(MovoSpacing.small))
                Text("Item: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            delivery.specialInstructions?.takeIf { it.isNotBlank() }?.let {
                Text("Instructions: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(MovoSpacing.medium))
            Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                MovoSecondaryButton(
                    if (headingToPickup) "Navigate to pickup" else "Navigate to destination",
                    {
                        if (headingToPickup) onNavigate(delivery.pickupLat, delivery.pickupLng)
                        else onNavigate(delivery.destinationLat, delivery.destinationLng)
                    },
                    Modifier.weight(1f),
                    leading = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                MovoSecondaryButton(
                    "Call customer",
                    { onCall(if (headingToPickup) delivery.pickupPhone else delivery.destinationPhone) },
                    Modifier.weight(1f),
                    leading = { Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            if (delivery.requiresVerification) {
                Spacer(Modifier.height(MovoSpacing.medium))
                StatusPill(
                    if (delivery.status == "arrived_pickup") "Ask the sender for the pickup code" else "Ask the recipient for the handover code",
                    MovoTone.Warning
                )
                Spacer(Modifier.height(MovoSpacing.small))
                MovoField(
                    value = otp,
                    onValueChange = { onOtpChange(it.filter(Char::isDigit).take(6)) },
                    label = if (delivery.status == "arrived_pickup") "Pickup code" else "Delivery code",
                    keyboardType = KeyboardType.NumberPassword,
                    supporting = "The customer reads this code from their MOVO app"
                )
                if (delivery.status == "arrived_dest") {
                    Spacer(Modifier.height(MovoSpacing.small))
                    MovoField(recipientName, { recipientName = it }, "Who received the item?")
                }
                MovoTextAction("Attach proof photo", onAddProof, Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(MovoSpacing.medium))
            action?.let {
                MovoButton(it.label, onAdvance, loading = busy, enabled = !busy)
            }
            MovoTextAction("Report a problem", onReportIssue, Modifier.fillMaxWidth())
        }
    }
}
