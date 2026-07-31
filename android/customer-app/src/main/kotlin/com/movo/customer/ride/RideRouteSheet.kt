package com.movo.customer.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.model.RideDraft
import com.movo.customer.send.DiscoveryPhase
import com.movo.customer.send.DiscoverySnapshot
import com.movo.design.MovoAvatar
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoField
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSheet
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.ShimmerBlock
import com.movo.design.StatusPill
import com.movo.design.formatDistance

/**
 * The ride equivalent of hailing: where you are, where you are going, and how many
 * riders are actually near enough to come. Availability is stated rather than
 * implied — a passenger deciding whether to walk deserves to know either way.
 */
@Composable
fun RideRouteSheet(
    draft: RideDraft,
    snapshot: DiscoverySnapshot,
    online: Boolean,
    error: String?,
    onPickupAddressChange: (String) -> Unit,
    onDropoffAddressChange: (String) -> Unit,
    onPickDropoff: () -> Unit,
    onContinue: () -> Unit,
    onRetryScan: () -> Unit
) {
    val phase = snapshot.phase
    val ridersNearby = snapshot.riders.size
    val headline = when (phase) {
        DiscoveryPhase.Locating -> "Finding you"
        DiscoveryPhase.ManualPickupRequired -> "Place your pickup"
        DiscoveryPhase.Scanning -> "Looking for riders"
        DiscoveryPhase.Available -> if (ridersNearby == 1) "1 rider nearby" else "$ridersNearby riders nearby"
        DiscoveryPhase.NoRiders -> "No riders near you"
        DiscoveryPhase.Offline -> "Rider availability needs a connection"
        is DiscoveryPhase.Error -> "Could not check availability"
    }

    MovoSheet {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            when (phase) {
                DiscoveryPhase.Available -> StatusPill("Live", MovoTone.Positive)
                DiscoveryPhase.Offline -> StatusPill("Offline", MovoTone.Warning)
                is DiscoveryPhase.Error -> StatusPill("Retry", MovoTone.Critical)
                DiscoveryPhase.Scanning ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                else -> Unit
            }
        }
        Spacer(Modifier.height(MovoSpacing.medium))

        MovoField(
            draft.pickupAddress, onPickupAddressChange, "Pick-up",
            supporting = "Where should your rider meet you?"
        )
        Spacer(Modifier.height(MovoSpacing.small))
        MovoField(
            draft.destinationAddress, onDropoffAddressChange, "Where to?",
            supporting = "Add a landmark if the street is hard to find"
        )
        Spacer(Modifier.height(MovoSpacing.small))

        // Kigali addresses are often landmarks, so the pin — not the text — is
        // what the rider actually navigates to.
        if (draft.destination?.isFinite == true) {
            MovoBanner(
                "Drop-off pinned at ${"%.5f".format(draft.destination.latitude)}, ${"%.5f".format(draft.destination.longitude)}",
                MovoTone.Positive,
                action = { com.movo.design.MovoTextAction("Change", onPickDropoff) }
            )
        } else {
            MovoSecondaryButton("Set drop-off on map", onPickDropoff)
        }

        if (phase == DiscoveryPhase.Scanning || phase == DiscoveryPhase.Locating) {
            Spacer(Modifier.height(MovoSpacing.medium))
            ShimmerBlock(height = 36.dp)
        }
        if (phase == DiscoveryPhase.Available && snapshot.riders.isNotEmpty()) {
            Spacer(Modifier.height(MovoSpacing.medium))
            NearbyRiderPreview(snapshot)
        }

        if (!online) {
            Spacer(Modifier.height(MovoSpacing.small))
            MovoBanner("You are offline. MOVO keeps your trip and books it when the connection returns.", MovoTone.Warning)
        }
        error?.let {
            Spacer(Modifier.height(MovoSpacing.small))
            MovoBanner(it, MovoTone.Critical)
        }

        Spacer(Modifier.height(MovoSpacing.default))
        MovoButton("Continue", onContinue, enabled = draft.isRoutePlaced && online)
        if (phase == DiscoveryPhase.NoRiders || phase == DiscoveryPhase.Offline || phase is DiscoveryPhase.Error) {
            Spacer(Modifier.height(MovoSpacing.small))
            MovoSecondaryButton("Check again", onRetryScan)
        }
    }
}

@Composable
private fun NearbyRiderPreview(snapshot: DiscoverySnapshot) {
    val closest = snapshot.riders.sortedBy { it.distanceKm }.take(4)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
        closest.forEach { rider -> MovoAvatar(rider.name, size = 34.dp, online = true) }
        Column(Modifier.padding(start = MovoSpacing.small)) {
            closest.firstOrNull()?.let { nearest ->
                Text(
                    "Closest ${formatDistance(nearest.distanceKm)} away",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "Verified riders taking passengers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
