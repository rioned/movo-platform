package com.movo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Pickup → destination summary with the connector line customers recognise from
 * on-demand apps. Kigali addresses are often landmark descriptions, so both lines
 * wrap to two lines instead of truncating to nothing useful.
 */
@Composable
fun RouteCard(
    pickup: String,
    destination: String,
    modifier: Modifier = Modifier,
    pickupCaption: String? = null,
    destinationCaption: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.padding(top = 6.dp, end = MovoSpacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Box(
                Modifier.width(2.dp).height(34.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                Modifier.size(11.dp).clip(RoundedCornerShape(2.dp))
                    .background(MovoPalette.Amber)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
            RouteEndpoint("Pickup", pickup, pickupCaption)
            RouteEndpoint("Destination", destination, destinationCaption)
        }
        trailing?.let { Spacer(Modifier.width(MovoSpacing.small)); it() }
    }
}

@Composable
private fun RouteEndpoint(label: String, address: String, caption: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            address.ifBlank { "Not set" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        caption?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * Horizontal progress across the delivery lifecycle. Completed checkpoints stay
 * filled so the customer can see how far a delivery has travelled at a glance.
 */
@Composable
fun DeliveryProgress(
    stage: MovoDeliveryStage,
    modifier: Modifier = Modifier,
    mode: MovoServiceMode = MovoServiceMode.Delivery
) {
    val steps = trackedSteps(mode)
    val current = stage.trackedStep
    val failed = stage == MovoDeliveryStage.Cancelled || stage == MovoDeliveryStage.Failed
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MovoSpacing.tiny)) {
        steps.forEachIndexed { index, label ->
            val reached = index <= current
            val color = when {
                failed && index == current -> MovoTheme.status.critical
                reached -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(color))
                Spacer(Modifier.height(MovoSpacing.tiny))
                // Seven columns on a small phone leave ~50 dp per label, which
                // clipped words like "Travelling" to "Travelli". Wrapping to a
                // second line keeps every checkpoint readable in both products.
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Vertical event history — the audit trail a customer or agent reads top-down. */
@Composable
fun DeliveryTimeline(events: List<TimelineEntry>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        events.forEachIndexed { index, event ->
            Row(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(end = MovoSpacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    )
                    if (index != events.lastIndex) {
                        Box(Modifier.width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                Column(Modifier.weight(1f).padding(bottom = MovoSpacing.small)) {
                    Text(event.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    event.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

data class TimelineEntry(val title: String, val subtitle: String?)

/**
 * The verification code shown to a sender or recipient. Digits are spaced and
 * oversized because the code is read aloud or across a handover (spec §6.9/§6.10).
 */
@Composable
fun OtpCallout(title: String, code: String, caption: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MovoShapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(MovoSpacing.default), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                code.toCharArray().joinToString("  "),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Price summary with the transparent breakdown the platform promises (§6.6). */
@Composable
fun PriceSummary(
    total: Double,
    modifier: Modifier = Modifier,
    distanceKm: Double? = null,
    etaMinutes: Int? = null,
    platformFee: Double? = null,
    zoneLabel: String? = null
) {
    MovoCard(modifier, color = MaterialTheme.colorScheme.primaryContainer, elevation = 0.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Delivery price", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(formatRwf(total), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(horizontalAlignment = Alignment.End) {
                etaMinutes?.let { Text(formatMinutes(it), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                distanceKm?.let { Text(formatDistance(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        if (platformFee != null || zoneLabel != null) {
            Spacer(Modifier.height(MovoSpacing.small))
            zoneLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            platformFee?.let {
                Text(
                    "Includes ${formatRwf(it)} MOVO platform fee",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
