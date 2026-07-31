package com.movo.customer.send

import android.animation.ValueAnimator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.design.MovoAvatar
import com.movo.design.MovoButton
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSheet
import com.movo.design.MovoSpacing
import com.movo.design.MovoTextAction
import com.movo.design.MovoTone
import com.movo.design.ShimmerBlock
import com.movo.design.StatusPill
import com.movo.design.formatDistance

private const val MAX_DISCOVERY_ERROR_LENGTH = 240

/**
 * The first thing a customer sees: how many verified riders are actually near
 * the pickup right now. Availability is never implied — every phase states what
 * MOVO knows and what the customer can do next.
 */
@Composable
fun DiscoverySheet(
    snapshot: DiscoverySnapshot,
    onContinue: () -> Unit,
    onAdjustPickup: () -> Unit,
    onRetry: () -> Unit
) {
    val phase = snapshot.phase
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    val title = when (phase) {
        DiscoveryPhase.Locating -> "Finding your pickup"
        DiscoveryPhase.ManualPickupRequired -> "Set your pickup on the map"
        DiscoveryPhase.Scanning -> "Finding riders near you"
        DiscoveryPhase.Available -> if (snapshot.riders.size == 1) "1 rider nearby" else "${snapshot.riders.size} riders nearby"
        DiscoveryPhase.NoRiders -> "No riders near this pickup"
        DiscoveryPhase.Offline -> "Rider availability needs a connection"
        is DiscoveryPhase.Error -> phase.message.trim().take(MAX_DISCOVERY_ERROR_LENGTH).ifBlank { "Unable to scan for riders" }
    }
    val subtitle = when (phase) {
        DiscoveryPhase.Available -> "Live availability from the current MOVO scan."
        DiscoveryPhase.NoRiders -> "Try this pickup again or choose another pickup point."
        DiscoveryPhase.Offline -> "Reconnect, then scan this pickup again."
        is DiscoveryPhase.Error -> "Your pickup is saved. You can retry the availability scan."
        DiscoveryPhase.ManualPickupRequired -> "Tap or long-press the map to place your pickup."
        else -> "MOVO is checking current rider availability."
    }

    MovoSheet {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            when (phase) {
                DiscoveryPhase.Available -> StatusPill("Live", MovoTone.Positive)
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
            DiscoveryPhase.Available -> NearbyRiderStrip(snapshot)
            else -> Unit
        }

        Spacer(Modifier.height(MovoSpacing.default))
        MovoButton(
            text = if (snapshot.canContinue()) "Continue" else "Continue",
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

/** Compact preview of who is actually available, closest rider first. */
@Composable
private fun NearbyRiderStrip(snapshot: DiscoverySnapshot) {
    val closest = snapshot.riders.sortedBy { it.distanceKm }.take(4)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
        closest.forEach { rider -> MovoAvatar(rider.name, size = 36.dp, online = true) }
        Column(Modifier.padding(start = MovoSpacing.small)) {
            closest.firstOrNull()?.let { nearest ->
                Text(
                    "Closest ${formatDistance(nearest.distanceKm)} away",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "Verified riders with live location",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
