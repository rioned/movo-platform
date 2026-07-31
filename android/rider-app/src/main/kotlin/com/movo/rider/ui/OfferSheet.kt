package com.movo.rider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.design.CountdownRing
import com.movo.design.MovoButton
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSheet
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.RouteCard
import com.movo.design.StatusPill
import com.movo.design.formatDistance
import com.movo.design.formatRwf
import com.movo.design.serviceLabel
import com.movo.rider.home.offerProgress
import com.movo.rider.home.offerSecondsRemaining
import com.movo.rider.model.DeliveryOffer
import kotlinx.coroutines.delay

/**
 * The offer card a rider decides on in seconds (spec §7.4): earnings, distance,
 * both areas and the time left to accept. Sensitive customer contact details are
 * deliberately absent until the offer is accepted.
 */
@Composable
fun OfferSheet(
    offer: DeliveryOffer,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onExpired: () -> Unit
) {
    var secondsRemaining by remember(offer.offerId) { mutableIntStateOf(offerSecondsRemaining(offer.expiresAt)) }
    val window = remember(offer.offerId) { offerSecondsRemaining(offer.expiresAt).coerceAtLeast(1) }

    LaunchedEffect(offer.offerId) {
        while (true) {
            val remaining = offerSecondsRemaining(offer.expiresAt)
            secondsRemaining = remaining
            if (remaining <= 0) { onExpired(); return@LaunchedEffect }
            delay(1000)
        }
    }

    MovoSheet {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                StatusPill("New delivery offer", MovoTone.Warning)
                Spacer(Modifier.height(MovoSpacing.small))
                Text(formatRwf(offer.earnings), style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${serviceLabel(offer.serviceType)} • ${formatDistance(offer.distanceKm)} • ${offer.orderNo}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CountdownRing(progress = offerProgress(secondsRemaining, window), secondsRemaining = secondsRemaining)
        }

        Spacer(Modifier.height(MovoSpacing.default))
        RouteCard(
            pickup = offer.pickupAddress,
            destination = offer.destinationAddress,
            pickupCaption = "Collect here",
            destinationCaption = "Deliver here"
        )
        offer.specialInstructions?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(MovoSpacing.small))
            Text("Handling note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(MovoSpacing.default))
        MovoButton("Accept delivery", onAccept, enabled = !busy && secondsRemaining > 0, loading = busy)
        Spacer(Modifier.height(MovoSpacing.small))
        MovoSecondaryButton("Decline", onDecline, enabled = !busy, tone = MaterialTheme.colorScheme.error)
    }
}
