package com.movo.rider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.design.MovoButton
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSheet
import com.movo.design.MovoSpacing
import com.movo.design.MovoTextAction
import com.movo.design.RideProgress
import com.movo.design.MovoRideStage
import com.movo.design.RouteCard
import com.movo.design.formatDistance
import com.movo.design.formatMoney
import com.movo.rider.model.ActiveRide
import com.movo.rider.model.nextRideAction

/**
 * The driver's working surface for an accepted ride: one next action, navigation
 * one tap away, and the fare — with no verification code, since the passenger
 * confirms the plate visually rather than exchanging a handover code.
 */
@Composable
fun ActiveRideSheet(ride: ActiveRide, busy: Boolean, onAdvance: () -> Unit, onNavigate: (Double?, Double?) -> Unit, onCancel: () -> Unit) {
    val stage = MovoRideStage.from(ride.status)
    val action = nextRideAction(ride.status)
    val headingToPickup = stage.trackedStep <= 2

    MovoSheet {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stage.driverLabel, style = MaterialTheme.typography.titleLarge)
                    Text(ride.rideNo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMoney(ride.totalFare, ride.currency), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(formatDistance(ride.distanceKm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(MovoSpacing.medium))
            RideProgress(stage)
            Spacer(Modifier.height(MovoSpacing.default))

            RouteCard(pickup = ride.pickupAddress, destination = ride.destinationAddress, pickupCaption = "Passenger pickup", destinationCaption = "Drop-off")

            Spacer(Modifier.height(MovoSpacing.medium))
            MovoSecondaryButton(
                if (headingToPickup) "Navigate to pickup" else "Navigate to destination",
                { if (headingToPickup) onNavigate(ride.pickupLat, ride.pickupLng) else onNavigate(ride.destinationLat, ride.destinationLng) },
                leading = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )

            if (ride.status == "arrived_pickup") {
                Spacer(Modifier.height(MovoSpacing.small))
                Text("Wait for the passenger to verify your plate before starting the trip.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(MovoSpacing.medium))
            action?.let { MovoButton(it.label, onAdvance, loading = busy, enabled = !busy) }
            if (ride.status in setOf("assigned", "driver_en_route")) {
                MovoTextAction("Cancel this ride", onCancel, Modifier.fillMaxWidth())
            }
        }
    }
}
