package com.movo.rider.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.movo.design.MotoHero
import com.movo.design.MovoAvatar
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoCard
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.StatusPill

import com.movo.rider.RiderMap
import com.movo.rider.model.ActiveDelivery
import com.movo.rider.model.ActiveRide
import com.movo.rider.model.DeliveryOffer
import com.movo.rider.model.RideOffer
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
    onAcceptRideOffer: (RideOffer) -> Unit,
    onDeclineRideOffer: (RideOffer) -> Unit,
    onRideOfferExpired: () -> Unit,
    onAdvanceRide: (ActiveRide) -> Unit,
    onCancelRide: (ActiveRide) -> Unit,
    onCall: (String) -> Unit,
    onNavigate: (Double?, Double?) -> Unit,
    onAddProof: () -> Unit,
    onReportIssue: () -> Unit,
    onOpenProfile: () -> Unit,
    photo: (@Composable () -> Unit)? = null
) {
    val focus = state.activeDelivery ?: state.activeRide?.let { ride ->
        ActiveDelivery(
            id = ride.id, orderNo = ride.rideNo, status = ride.status, serviceType = "ride",
            pickupAddress = ride.pickupAddress, pickupName = "", pickupPhone = "",
            pickupLat = ride.pickupLat, pickupLng = ride.pickupLng,
            destinationAddress = ride.destinationAddress, destinationName = "", destinationPhone = "",
            destinationLat = ride.destinationLat, destinationLng = ride.destinationLng,
            earnings = ride.totalFare, distanceKm = ride.distanceKm, itemDescription = null, specialInstructions = null
        )
    } ?: state.offer?.let { offer ->
        ActiveDelivery(
            id = offer.deliveryId, orderNo = offer.orderNo, status = "assigned", serviceType = offer.serviceType,
            pickupAddress = offer.pickupAddress, pickupName = "", pickupPhone = "",
            pickupLat = offer.pickupLat, pickupLng = offer.pickupLng,
            destinationAddress = offer.destinationAddress, destinationName = "", destinationPhone = "",
            destinationLat = offer.destinationLat, destinationLng = offer.destinationLng,
            earnings = offer.earnings, distanceKm = offer.distanceKm, itemDescription = null, specialInstructions = null
        )
    } ?: state.rideOffer?.let { offer ->
        ActiveDelivery(
            id = offer.rideId, orderNo = offer.rideNo, status = "assigned", serviceType = "ride",
            pickupAddress = offer.pickupAddress, pickupName = "", pickupPhone = "",
            pickupLat = offer.pickupLat, pickupLng = offer.pickupLng,
            destinationAddress = offer.destinationAddress, destinationName = "", destinationPhone = "",
            destinationLat = offer.destinationLat, destinationLng = offer.destinationLng,
            earnings = offer.earnings, distanceKm = offer.distanceKm, itemDescription = null, specialInstructions = null
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val idlePanelHeight = maxHeight * 0.58f
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

                state.activeRide != null -> ActiveRideSheet(
                    ride = state.activeRide,
                    busy = busy,
                    onAdvance = { onAdvanceRide(state.activeRide) },
                    onNavigate = onNavigate,
                    onCancel = { onCancelRide(state.activeRide) }
                )

                state.offer != null -> OfferSheet(
                    offer = state.offer,
                    busy = busy,
                    onAccept = { onAcceptOffer(state.offer) },
                    onDecline = { onDeclineOffer(state.offer) },
                    onExpired = onOfferExpired
                )

                state.rideOffer != null -> RideOfferSheet(
                    offer = state.rideOffer,
                    busy = busy,
                    onAccept = { onAcceptRideOffer(state.rideOffer) },
                    onDecline = { onDeclineRideOffer(state.rideOffer) },
                    onExpired = onRideOfferExpired
                )

                else -> Column(Modifier.heightIn(max = idlePanelHeight).verticalScroll(rememberScrollState())) {
                    IdleSheet(state, busy, online, onGoOnline, onGoOffline)
                }
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
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open rider account", onClick = onOpenProfile),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp
    ) {
        Row(Modifier.padding(MovoSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
            MovoAvatar(state.profile.name, size = 44.dp, online = state.profile.isOnline, photo = photo)
            Column(Modifier.weight(1f).padding(horizontal = MovoSpacing.medium)) {
                Text("MOVO / RIDER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(state.profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        when {
                            state.activeDelivery != null -> "On a delivery"
                            state.activeRide != null -> "On a ride"
                            state.profile.availability == "online" -> "Online"
                            state.profile.availability == "unavailable" -> "On a break"
                            else -> "Offline"
                        },
                        when {
                            state.activeDelivery != null || state.activeRide != null -> MovoTone.Warning
                            state.profile.availability == "online" -> MovoTone.Positive
                            else -> MovoTone.Neutral
                        }
                    )
                    if (!online) StatusPill("No network", MovoTone.Critical)
                }
            }

        }
    }
}

@Composable
private fun IdleSheet(state: RiderHomeState, busy: Boolean, online: Boolean, onGoOnline: () -> Unit, onGoOffline: () -> Unit) {
    MovoCard(
        Modifier.padding(MovoSpacing.default),
        color = MaterialTheme.colorScheme.surface,
        elevation = 8.dp
    ) {
        if (state.profile.availability == "online") {
            // "Online" alone is not the promise. Dispatch matches riders to a pickup by
            // zone first, so a rider with no fix yet — or one sitting outside every
            // service area — is online and will never be offered anything. Say which.
            val blocked = state.dispatch.blockedExplanation
            if (blocked == null) {
                StatusPill("ONLINE • READY", MovoTone.Positive)
                Spacer(Modifier.height(MovoSpacing.small))
                Text("Waiting for offers", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.dispatch.zoneName
                        ?.let { "You are serving $it. Stay safely parked while waiting." }
                        ?: "You are available for nearby requests. Stay safely parked while waiting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                StatusPill("ONLINE • NOT MATCHED", MovoTone.Warning)
                Spacer(Modifier.height(MovoSpacing.small))
                Text("No zone assigned", style = MaterialTheme.typography.titleLarge)
                Text(
                    blocked,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(MovoSpacing.default))
            com.movo.design.MovoSecondaryButton("Go offline", onGoOffline, enabled = !busy)
        } else {
            MotoHero(
                title = "Ready for your next move?",
                subtitle = "MOTORCYCLE / RWANDA",
                modifier = Modifier.fillMaxWidth(),
                compact = true
            )
            Spacer(Modifier.height(MovoSpacing.small))
            Text("You are offline", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.profile.isApproved) "Go online to receive nearby requests on your motorcycle."
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
            Spacer(Modifier.height(MovoSpacing.small))
            Text("Helmet on. Phone mounted. Ride safely.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
