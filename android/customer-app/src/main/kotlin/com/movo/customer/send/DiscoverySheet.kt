package com.movo.customer.send

import android.animation.ValueAnimator
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.movo.design.MovoButton
import com.movo.design.MovoCard
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSheet
import com.movo.design.MovoSpacing
import com.movo.design.MovoTextAction
import com.movo.design.MovoTone
import com.movo.design.ShimmerBlock
import com.movo.design.StatusPill
import com.movo.design.formatDistance
import com.movo.design.RatingStars
import com.movo.customer.model.NearbyRider

private const val MAX_DISCOVERY_ERROR_LENGTH = 240

/**
 * How many riders are near the pickup, and who they are. The customer chooses which
 * rider collects their parcel — the choice is passed to dispatch as a preference it
 * honours first, so both "I want that one" and "just send whoever's closest" are
 * one tap apart.
 */
@Composable
fun DiscoverySheet(
    snapshot: DiscoverySnapshot,
    onContinue: () -> Unit,
    onAdjustPickup: () -> Unit,
    onRetry: () -> Unit,
    onSelectRider: (String?) -> Unit
) {
    val phase = snapshot.phase
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    val chosen = snapshot.selectedRider
    val title = when (phase) {
        DiscoveryPhase.Locating -> "Finding your pickup"
        DiscoveryPhase.ManualPickupRequired -> "Set your pickup on the map"
        DiscoveryPhase.Scanning -> "Finding riders near you"
        DiscoveryPhase.Available -> if (snapshot.riderCount == 1) "1 rider nearby" else "${snapshot.riderCount} riders nearby"
        DiscoveryPhase.NoRiders -> "No riders near this pickup"
        DiscoveryPhase.OutOfServiceArea -> "MOVO is not currently available at this location."
        DiscoveryPhase.Offline -> "Rider availability needs a connection"
        is DiscoveryPhase.Error -> phase.message.trim().take(MAX_DISCOVERY_ERROR_LENGTH).ifBlank { "Unable to scan for riders" }
    }
    val subtitle = when (phase) {
        DiscoveryPhase.Available -> if (chosen == null) {
            "Choose a rider below, or let MOVO match you with the nearest one."
        } else {
            "MOVO will offer this delivery to ${chosen.label} first, and falls back to the nearest rider if they don't take it."
        }
        DiscoveryPhase.NoRiders -> "Try this pickup again or choose another pickup point."
        DiscoveryPhase.OutOfServiceArea -> "Choose a pickup point inside MOVO's service area to continue."
        DiscoveryPhase.Offline -> "Reconnect, then scan this pickup again."
        is DiscoveryPhase.Error -> "Your pickup is saved. You can retry the availability scan."
        DiscoveryPhase.ManualPickupRequired -> "Tap or long-press the map to place your pickup."
        else -> "MOVO is checking current rider availability."
    }

    MovoSheet {
        Text("SEND BY MOTO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(MovoSpacing.small))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            when (phase) {
                DiscoveryPhase.Available -> StatusPill("Live", MovoTone.Positive)
                DiscoveryPhase.OutOfServiceArea -> StatusPill("Unavailable", MovoTone.Critical)
                DiscoveryPhase.Offline -> StatusPill("Offline", MovoTone.Warning)
                is DiscoveryPhase.Error -> StatusPill("Retry", MovoTone.Critical)
                DiscoveryPhase.Scanning -> if (reducedMotion) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else StatusPill("Scanning", MovoTone.Info)
                else -> Unit
            }
        }
        Spacer(Modifier.height(MovoSpacing.tiny))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(MovoSpacing.medium))

        when (phase) {
            DiscoveryPhase.Scanning, DiscoveryPhase.Locating -> {
                ShimmerBlock(height = 44.dp)
                Spacer(Modifier.height(MovoSpacing.small))
                ShimmerBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.6f))
            }
            DiscoveryPhase.Available -> RiderChoices(snapshot, chosen, onSelectRider)
            else -> Unit
        }

        Spacer(Modifier.height(MovoSpacing.default))
        MovoButton(
            text = "Add parcel details",
            onClick = onContinue,
            enabled = snapshot.canContinue()
        )
        if (phase == DiscoveryPhase.NoRiders || phase == DiscoveryPhase.Offline || phase is DiscoveryPhase.Error) {
            Spacer(Modifier.height(MovoSpacing.small))
            MovoSecondaryButton("Scan again", onRetry)
        }
        MovoTextAction("Adjust pickup", onAdjustPickup, Modifier.fillMaxWidth())
    }
}

/**
 * The rider list plus the "let MOVO choose" option. Auto is the default so a customer
 * in a hurry keeps the previous one-tap behaviour, and the explicit choice is always
 * one tap further — never forced on someone with no preference.
 */
@Composable
private fun RiderChoices(
    snapshot: DiscoverySnapshot,
    chosen: NearbyRider?,
    onSelectRider: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
        AutoRiderOption(selected = chosen == null, onSelect = { onSelectRider(null) })
        snapshot.riders.forEach { rider ->
            RiderOption(rider = rider, selected = rider.id == chosen?.id, onSelect = { onSelectRider(rider.id) })
        }
    }
}

@Composable
private fun AutoRiderOption(selected: Boolean, onSelect: () -> Unit) {
    ChoiceSurface(selected = selected, onClick = onSelect) {
        Text("Any available rider", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "MOVO offers the job to the nearest rider and keeps expanding until someone accepts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One selectable rider. Shows the plate the customer will look for at the kerb, the
 * rider's standing, and how soon they can reach the pickup — and nothing that would
 * let a stranger contact them before the job is accepted.
 */
@Composable
private fun RiderOption(rider: NearbyRider, selected: Boolean, onSelect: () -> Unit) {
    ChoiceSurface(selected = selected, onClick = onSelect) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                rider.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${rider.etaMinutes} min",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(MovoSpacing.tiny))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
            RatingStars(rider.rating, starSize = 14.dp)
            Text(
                if (rider.ratingCount > 0) "${rider.rating} · ${rider.ratingCount} ratings" else "New rider",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(MovoSpacing.tiny))
        Text(
            buildString {
                append(formatDistance(rider.distanceKm))
                append(" away")
                rider.motorcycleSummary()?.let { append(" · $it") }
                rider.zone?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun NearbyRider.motorcycleSummary(): String? =
    listOfNotNull(color?.takeIf(String::isNotBlank), make?.takeIf(String::isNotBlank), type?.takeIf(String::isNotBlank))
        .joinToString(" ")
        .takeIf(String::isNotBlank)

/**
 * A selectable row. Selection is carried by the fill colour rather than a drawn border
 * so the row keeps MovoCard's own rim and corner radius — the surface stays identical
 * to every other card in the app, only its weight changes.
 */
@Composable
private fun ChoiceSurface(selected: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    MovoCard(
        modifier = Modifier
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) { content() }
}
