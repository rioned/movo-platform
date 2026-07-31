package com.movo.rider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.design.MovoAvatar
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoButtonTone
import com.movo.design.MovoCard
import com.movo.design.MovoField
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoServiceMode
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.SectionHeader
import com.movo.design.ServiceModeBadge
import com.movo.design.SegmentOption
import com.movo.design.SegmentedChoice
import com.movo.design.StatTile
import com.movo.design.StatusPill
import com.movo.design.formatRwf
import com.movo.rider.model.RiderProfile

/** Documents MOVO must hold on file before approval (spec §7.2). */
val RIDER_DOCUMENTS = listOf(
    "profile" to "Profile photo",
    "id_front" to "National ID (front)",
    "id_back" to "National ID (back)",
    "license" to "Riding licence",
    "motorcycle" to "Motorcycle photo"
)

/**
 * Rider account: verification state, vehicle details that customers see, document
 * uploads, availability control and sign-out.
 */
@Composable
fun RiderProfileScreen(
    profile: RiderProfile,
    pendingSync: Int,
    photo: (@Composable () -> Unit)? = null,
    onUploadDocument: (String) -> Unit,
    onSaveMotorcycle: (RiderProfile) -> Unit,
    onAvailabilityChange: (String) -> Unit,
    onSaveServices: (acceptsRides: Boolean, acceptsDeliveries: Boolean) -> Unit,
    onSignOut: () -> Unit
) {
    var plate by remember(profile.motorcyclePlate) { mutableStateOf(profile.motorcyclePlate) }
    var make by remember(profile.motorcycleMake) { mutableStateOf(profile.motorcycleMake) }
    var colour by remember(profile.motorcycleColor) { mutableStateOf(profile.motorcycleColor) }
    var type by remember(profile.motorcycleType) { mutableStateOf(profile.motorcycleType.ifBlank { "fuel" }) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MovoSpacing.default)) {
        Text("Account", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(MovoSpacing.medium))

        MovoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MovoAvatar(profile.name, size = 64.dp, online = profile.isOnline, photo = photo)
                Column(Modifier.padding(start = MovoSpacing.default).weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        profile.motorcyclePlate.ifBlank { "Motorcycle not set" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(MovoSpacing.small))
                    StatusPill(
                        when (profile.approvalStatus) {
                            "approved" -> "Verified rider"
                            "pending" -> "Verification pending"
                            "suspended" -> "Suspended"
                            else -> profile.approvalStatus.replaceFirstChar { it.uppercase() }
                        },
                        when (profile.approvalStatus) {
                            "approved" -> MovoTone.Positive
                            "pending" -> MovoTone.Warning
                            else -> MovoTone.Critical
                        }
                    )
                }
            }
            Spacer(Modifier.height(MovoSpacing.medium))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile("Deliveries", "${profile.totalDeliveries}")
                StatTile("Earned", formatRwf(profile.totalEarnings))
                StatTile("Rating", if (profile.ratingCount > 0) String.format("%.1f", profile.rating) else "—")
            }
        }

        if (pendingSync > 0) {
            Spacer(Modifier.height(MovoSpacing.medium))
            MovoBanner("$pendingSync delivery update(s) saved offline. MOVO syncs them when you reconnect.", MovoTone.Warning)
        }

        if (!profile.isApproved) {
            Spacer(Modifier.height(MovoSpacing.medium))
            MovoBanner(
                "Your account is ${profile.approvalStatus}. Upload the documents below so MOVO operations can verify you.",
                MovoTone.Warning
            )
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        MovoCard {
            SectionHeader("Availability")
            Text(
                "Only riders who are online receive delivery offers. Location sharing runs while you are online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MovoSpacing.small))
            SegmentedChoice(
                options = listOf(
                    SegmentOption("online", "Online"),
                    SegmentOption("unavailable", "On a break"),
                    SegmentOption("offline", "Offline")
                ),
                selected = if (profile.availability == "busy") "online" else profile.availability,
                onSelect = onAvailabilityChange,
                enabled = profile.isApproved
            )
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        ServicesCard(profile, onSaveServices)

        Spacer(Modifier.height(MovoSpacing.medium))
        MovoCard {
            SectionHeader("Motorcycle")
            MovoField(plate, { plate = it.uppercase() }, "Plate number")
            Spacer(Modifier.height(MovoSpacing.small))
            MovoField(make, { make = it }, "Make or brand")
            Spacer(Modifier.height(MovoSpacing.small))
            MovoField(colour, { colour = it }, "Colour")
            Spacer(Modifier.height(MovoSpacing.small))
            SegmentedChoice(
                options = listOf(SegmentOption("fuel", "Fuel"), SegmentOption("electric", "Electric")),
                selected = type,
                onSelect = { type = it }
            )
            Spacer(Modifier.height(MovoSpacing.medium))
            MovoButton(
                "Save motorcycle details",
                { onSaveMotorcycle(profile.copy(motorcyclePlate = plate, motorcycleMake = make, motorcycleColor = colour, motorcycleType = type)) }
            )
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        MovoCard {
            SectionHeader("Verification documents")
            Text(
                "Clear photos speed up approval. MOVO stores them securely and only operations can view them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MovoSpacing.small))
            RIDER_DOCUMENTS.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                    row.forEach { (kind, label) ->
                        MovoSecondaryButton(label, { onUploadDocument(kind) }, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(MovoSpacing.small))
            }
        }

        Spacer(Modifier.height(MovoSpacing.large))
        MovoButton("Sign out", onSignOut, tone = MovoButtonTone.Danger)
        Spacer(Modifier.height(MovoSpacing.section))
    }
}

/**
 * Which MOVO products this rider works.
 *
 * This is a real dispatch filter, not a display preference: a product switched off
 * here stops producing offers entirely, so a rider who does not want passengers
 * never has to decline one and never pays for it in their acceptance rate.
 *
 * The last enabled product cannot be switched off — a rider accepting nothing
 * would sit online receiving silence with no way to work out why — so the toggle
 * is disabled at that point and says so, rather than failing on the server.
 */
@Composable
private fun ServicesCard(
    profile: RiderProfile,
    onSaveServices: (acceptsRides: Boolean, acceptsDeliveries: Boolean) -> Unit
) {
    MovoCard {
        SectionHeader("What you want to carry")
        Text(
            "MOVO only sends you offers for the services you keep on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(MovoSpacing.medium))

        ServiceToggle(
            mode = MovoServiceMode.Ride,
            description = "Carry passengers on your motorcycle",
            checked = profile.acceptsRides,
            // Only blocked when this is the last one left on.
            enabled = profile.acceptsDeliveries,
            onChange = { onSaveServices(it, profile.acceptsDeliveries) }
        )
        Spacer(Modifier.height(MovoSpacing.small))
        ServiceToggle(
            mode = MovoServiceMode.Delivery,
            description = "Carry parcels and documents",
            checked = profile.acceptsDeliveries,
            enabled = profile.acceptsRides,
            onChange = { onSaveServices(profile.acceptsRides, it) }
        )

        if (!(profile.acceptsRides && profile.acceptsDeliveries)) {
            Spacer(Modifier.height(MovoSpacing.small))
            MovoBanner("Keep at least one service on so MOVO can send you work.", MovoTone.Info)
        }
    }
}

@Composable
private fun ServiceToggle(
    mode: MovoServiceMode,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ServiceModeBadge(mode)
        Column(Modifier.weight(1f).padding(horizontal = MovoSpacing.medium)) {
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled || !checked)
    }
}
