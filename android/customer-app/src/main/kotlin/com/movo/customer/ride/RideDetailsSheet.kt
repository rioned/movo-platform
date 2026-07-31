package com.movo.customer.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.model.RideDraft
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoCard
import com.movo.design.MovoField
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.PhoneField
import com.movo.design.SectionHeader
import com.movo.design.SegmentOption
import com.movo.design.SegmentedChoice

/**
 * Everything MOVO needs before quoting a ride — which is far less than a delivery
 * needs. The passenger is already known from their account, so this asks about the
 * trip itself and nothing else. Four short cards, not a wall of inputs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailsSheet(
    draft: RideDraft,
    online: Boolean,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onGetFare: () -> Unit,
    onUpdatePassengerName: (String) -> Unit,
    onUpdatePassengerPhone: (String) -> Unit,
    onUpdatePassengerCount: (Int) -> Unit,
    onUpdateLuggage: (Boolean) -> Unit,
    onUpdateNotes: (String) -> Unit,
    onUpdatePaymentMethod: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Trip details", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to the map") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MovoSpacing.default),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.medium)
        ) {
            MovoCard {
                SectionHeader("Who is travelling?")
                MovoField(draft.passengerName, onUpdatePassengerName, "Passenger name")
                Spacer(Modifier.height(MovoSpacing.small))
                PhoneField(
                    draft.passengerPhone, onUpdatePassengerPhone,
                    label = "Passenger phone",
                    supporting = "Your rider calls this number if they cannot find you"
                )
            }

            MovoCard {
                SectionHeader("Seats")
                // A motorcycle seats the rider plus one; the server enforces the
                // same ceiling, so this is a guard rail rather than the only check.
                SegmentedChoice(
                    options = listOf(
                        SegmentOption("1", "Just me", "1 passenger"),
                        SegmentOption("2", "Two of us", "Where permitted")
                    ),
                    selected = draft.passengerCount.toString(),
                    onSelect = { onUpdatePassengerCount(it.toIntOrNull()?.coerceIn(1, 2) ?: 1) }
                )
                Spacer(Modifier.height(MovoSpacing.medium))
                LuggageToggle(draft.hasLuggage, onUpdateLuggage)
            }

            MovoCard {
                SectionHeader("Anything your rider should know?")
                MovoField(
                    draft.notes, onUpdateNotes, "Notes for your rider",
                    singleLine = false,
                    supporting = "Which gate to wait at, what you are wearing, a landmark"
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

            if (!online) MovoBanner("You are offline. MOVO will price this trip once you reconnect.", MovoTone.Warning)
            error?.let { MovoBanner(it, MovoTone.Critical) }
            Spacer(Modifier.height(MovoSpacing.small))
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
            Column(Modifier.padding(MovoSpacing.default).navigationBarsPadding().imePadding()) {
                MovoButton(text = "See fare", onClick = onGetFare, loading = loading, enabled = online)
            }
        }
    }
}

@Composable
private fun LuggageToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("I have a bag", style = MaterialTheme.typography.titleSmall)
            Text(
                "Lets your rider know to expect luggage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
