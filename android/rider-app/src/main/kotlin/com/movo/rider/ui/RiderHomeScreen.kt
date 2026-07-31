package com.movo.rider.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.movo.design.MovoAvatar
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoCard
import com.movo.design.MovoServiceMode
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.ServiceModeBadge
import com.movo.design.StatusPill
import com.movo.design.formatRwf
import com.movo.rider.RiderMap
import com.movo.rider.model.ActiveDelivery
import com.movo.rider.model.DeliveryOffer
import com.movo.rider.model.RiderHomeState

/**
 * The rider's home: the map fills the screen, and exactly one decision sits on
 * top of it — go online, accept an offer, or finish the delivery in progress.
 */
@Composable
fun RiderHomeScreen(
    activity: Activity,
    state: RiderHomeState,
    busy: Boolean,
    online: Boolean,
    otp: String,
    onOtpChange: (String) -> Unit,
    onGoOnline: () -> Unit,
    onGoOffline: () -> Unit,
    onAcceptOffer: (DeliveryOffer) -> Unit,
    onDeclineOffer: (DeliveryOffer) -> Unit,
    onOfferExpired: () -> Unit,
    onAdvance: (ActiveDelivery) -> Unit,
    onCall: (String) -> Unit,
    onNavigate: (Double?, Double?) -> Unit,
    onAddProof: () -> Unit,
    onReportIssue: () -> Unit,
    onOpenProfile: () -> Unit,
    photo: (@Composable () -> Unit)? = null
) {
    val focus = state.activeDelivery ?: state.offer?.let { offer ->
        ActiveDelivery(
            id = offer.deliveryId, orderNo = offer.orderNo, status = "assigned", serviceType = offer.serviceType,
            pickupAddress = offer.pickupAddress, pickupName = "", pickupPhone = "",
            pickupLat = offer.pickupLat, pickupLng = offer.pickupLng,
            destinationAddress = offer.destinationAddress, destinationName = "", destinationPhone = "",
            destinationLat = offer.destinationLat, destinationLng = offer.destinationLng,
            earnings = offer.earnings, distanceKm = offer.distanceKm, itemDescription = null, specialInstructions = null,
            serviceMode = offer.serviceMode, passengerCount = offer.passengerCount, hasLuggage = offer.hasLuggage
        )
    }

    Box(Modifier.fillMaxSize()) {
        RiderMap(
            activity,
            focus?.pickupLat, focus?.pickupLng,
            focus?.destinationLat, focus?.destinationLng,
            Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)))
        )

        Column(Modifier.fillMaxWidth().padding(MovoSpacing.default).statusBarsPadding()) {
            RiderStatusHeader(state, online, onOpenProfile, photo)
            if (!state.profile.isApproved) {
                Spacer(Modifier.height(MovoSpacing.small))
                MovoBanner(
                    "Your rider account is ${state.profile.approvalStatus}. MOVO must verify your documents before offers begin.",
                    MovoTone.Warning
                )
            }
            if (state.pendingSync > 0) {
                Spacer(Modifier.height(MovoSpacing.small))
                MovoBanner("${state.pendingSync} update(s) waiting to sync", MovoTone.Info)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            when {
                state.activeDelivery != null -> ActiveDeliverySheet(
                    delivery = state.activeDelivery,
                    busy = busy,
                    otp = otp,
                    onOtpChange = onOtpChange,
                    onAdvance = { onAdvance(state.activeDelivery) },
                    onCall = onCall,
                    onNavigate = onNavigate,
                    onAddProof = onAddProof,
                    onReportIssue = onReportIssue
                )

                state.offer != null -> OfferSheet(
                    offer = state.offer,
                    busy = busy,
                    onAccept = { onAcceptOffer(state.offer) },
                    onDecline = { onDeclineOffer(state.offer) },
                    onExpired = onOfferExpired
                )

                else -> IdleSheet(state, busy, online, onGoOnline, onGoOffline)
            }
        }
    }
}

@Composable
private fun RiderStatusHeader(
    state: RiderHomeState,
    online: Boolean,
    onOpenProfile: () -> Unit,
    photo: (@Composable () -> Unit)?
) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onOpenProfile),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(Modifier.padding(MovoSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            MovoAvatar(state.profile.name, size = 48.dp, online = state.profile.isOnline, photo = photo)
            Column(Modifier.weight(1f).padding(horizontal = MovoSpacing.medium)) {
                Text(state.profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        when {
                            state.activeDelivery?.mode?.isRide == true -> "On a trip"
                            state.activeDelivery != null -> "On a delivery"
                            state.profile.availability == "online" -> "Online"
                            state.profile.availability == "unavailable" -> "On a break"
                            else -> "Offline"
                        },
                        when {
                            state.activeDelivery != null -> MovoTone.Warning
                            state.profile.availability == "online" -> MovoTone.Positive
                            else -> MovoTone.Neutral
                        }
                    )
                    if (!online) StatusPill("No network", MovoTone.Critical)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatRwf(state.profile.totalEarnings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("lifetime", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun IdleSheet(state: RiderHomeState, busy: Boolean, online: Boolean, onGoOnline: () -> Unit, onGoOffline: () -> Unit) {
    MovoCard(
        Modifier.padding(MovoSpacing.default),
        color = MaterialTheme.colorScheme.surface
    ) {
        // Naming the products they are actually queued for is what makes a quiet
        // hour readable: no offers because it is quiet, or because rides are off?
        val services = when {
            state.profile.acceptsRides && state.profile.acceptsDeliveries -> "rides and deliveries"
            state.profile.acceptsRides -> "rides"
            else -> "deliveries"
        }
        if (state.profile.availability == "online") {
            Text("Waiting for offers", style = MaterialTheme.typography.titleLarge)
            Text(
                "You are visible to customers near you and queued for $services. Keep the app open so offers arrive instantly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MovoSpacing.medium))
            Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                if (state.profile.acceptsRides) ServiceModeBadge(MovoServiceMode.Ride)
                if (state.profile.acceptsDeliveries) ServiceModeBadge(MovoServiceMode.Delivery)
            }
            Spacer(Modifier.height(MovoSpacing.default))
            com.movo.design.MovoSecondaryButton("Go offline", onGoOffline, enabled = !busy)
        } else {
            Text("You are offline", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.profile.isApproved) "Go online to start receiving MOVO $services near you."
                else "You can go online once MOVO verifies your documents.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MovoSpacing.default))
            MovoButton(
                text = "GO ONLINE",
                onClick = onGoOnline,
                enabled = state.profile.isApproved && online && !busy,
                loading = busy
            )
            if (!online) {
                Spacer(Modifier.height(MovoSpacing.small))
                Text(
                    "Waiting for a network connection…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
