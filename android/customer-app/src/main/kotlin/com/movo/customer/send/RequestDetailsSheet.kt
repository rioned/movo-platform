package com.movo.customer.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.model.SendDraft
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoCard
import com.movo.design.MovoField
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSpacing
import com.movo.design.MovoTextAction
import com.movo.design.MovoTone
import com.movo.design.PhoneField
import com.movo.design.SectionHeader
import com.movo.design.SegmentOption
import com.movo.design.SegmentedChoice

/**
 * Everything MOVO needs before quoting a price (spec §6.4), grouped into short
 * cards so the form never feels like a single wall of inputs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailsSheet(
    draft: SendDraft,
    online: Boolean,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onGetQuote: () -> Unit,
    onPickDestination: () -> Unit,
    onUpdatePickupAddress: (String) -> Unit,
    onUpdateDestinationAddress: (String) -> Unit,
    onUpdateSenderName: (String) -> Unit,
    onUpdateSenderPhone: (String) -> Unit,
    onUpdateReceiverName: (String) -> Unit,
    onUpdateReceiverPhone: (String) -> Unit,
    onUpdateItemType: (String) -> Unit,
    onUpdateItemDescription: (String) -> Unit,
    onUpdateDeliveryInstructions: (String) -> Unit,
    onUpdatePaymentMethod: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Delivery details", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to discovery") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MovoSpacing.default),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.medium)
        ) {
            SectionHeader("What are you sending?")
            SegmentedChoice(
                options = listOf(
                    SegmentOption("parcel", "Parcel", "Boxes, gifts, goods"),
                    SegmentOption("document", "Document", "Contracts, files, IDs")
                ),
                selected = draft.itemType,
                onSelect = onUpdateItemType
            )

            MovoCard {
                SectionHeader("Route")
                MovoField(draft.pickupAddress, onUpdatePickupAddress, "Pickup address", supporting = "Add a landmark if the street is hard to find")
                Spacer(Modifier.height(MovoSpacing.small))
                MovoField(draft.destinationAddress, onUpdateDestinationAddress, "Destination address", supporting = "Where should the rider hand it over?")
                Spacer(Modifier.height(MovoSpacing.small))
                // Kigali addresses are often landmarks, so the map pin — not the text —
                // is what the rider actually navigates to.
                if (draft.destination?.isFinite == true) {
                    MovoBanner(
                        "Destination pin placed at ${"%.5f".format(draft.destination.latitude)}, ${"%.5f".format(draft.destination.longitude)}",
                        MovoTone.Positive,
                        action = { MovoTextAction("Change", onPickDestination) }
                    )
                } else {
                    MovoBanner("Place the destination pin so your rider can navigate to it.", MovoTone.Warning)
                    Spacer(Modifier.height(MovoSpacing.small))
                    MovoSecondaryButton("Set destination on map", onPickDestination)
                }
            }

            MovoCard {
                SectionHeader("Sender")
                MovoField(draft.senderName, onUpdateSenderName, "Sender name")
                Spacer(Modifier.height(MovoSpacing.small))
                PhoneField(draft.senderPhone, onUpdateSenderPhone, label = "Sender phone", supporting = "The rider calls this number at pickup")
            }

            MovoCard {
                SectionHeader("Receiver")
                MovoField(draft.receiverName, onUpdateReceiverName, "Receiver name")
                Spacer(Modifier.height(MovoSpacing.small))
                PhoneField(draft.receiverPhone, onUpdateReceiverPhone, label = "Receiver phone", supporting = "They receive tracking updates and the handover code")
            }

            MovoCard {
                SectionHeader("Item and instructions")
                MovoField(draft.itemDescription, onUpdateItemDescription, "Item description", supporting = "Helps the rider carry it safely")
                Spacer(Modifier.height(MovoSpacing.small))
                MovoField(
                    draft.deliveryInstructions, onUpdateDeliveryInstructions, "Delivery instructions",
                    singleLine = false, supporting = "Gate colour, floor, who to ask for"
                )
            }

            MovoCard {
                SectionHeader("Payment")
                SegmentedChoice(
                    options = listOf(
                        SegmentOption("mobile_money", "Mobile money", "MTN / Airtel"),
                        SegmentOption("cash", "Cash", "Pay the rider")
                    ),
                    selected = draft.paymentMethod,
                    onSelect = onUpdatePaymentMethod
                )
            }

            if (!online) MovoBanner("You are offline. MOVO will price this request once you reconnect.", MovoTone.Warning)
            error?.let { MovoBanner(it, MovoTone.Critical) }
            Spacer(Modifier.height(MovoSpacing.small))
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
            Column(Modifier.padding(MovoSpacing.default).navigationBarsPadding().imePadding()) {
                MovoButton(text = "Get quote", onClick = onGetQuote, loading = loading, enabled = online)
            }
        }
    }
}
