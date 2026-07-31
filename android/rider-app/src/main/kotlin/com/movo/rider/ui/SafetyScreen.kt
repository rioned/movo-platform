package com.movo.rider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoButtonTone
import com.movo.design.MovoCard
import com.movo.design.MovoField
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.SectionHeader

/** Incident categories operations can act on immediately (spec §7.10). */
val INCIDENT_KINDS = listOf(
    "accident" to "Accident",
    "unsafe_item" to "Unsafe item",
    "suspicious_customer" to "Suspicious customer",
    "theft" to "Theft",
    "vehicle_breakdown" to "Breakdown",
    "harassment" to "Harassment",
    "other" to "Something else"
)

/**
 * Rider safety centre. SOS is one tap and needs no typing, because a rider in
 * trouble should not have to fill in a form first.
 */
@Composable
fun SafetyScreen(
    busy: Boolean,
    activeDeliveryOrder: String?,
    onReport: (kind: String, description: String) -> Unit,
    onCallSupport: () -> Unit
) {
    var kind by remember { mutableStateOf("accident") }
    var description by remember { mutableStateOf("") }
    var sosConfirmed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MovoSpacing.default)) {
        Text("Safety", style = MaterialTheme.typography.headlineMedium)
        Text(
            "MOVO operations monitors these reports and responds directly.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(MovoSpacing.medium))

        MovoCard(color = MaterialTheme.colorScheme.errorContainer, elevation = 0.dp) {
            SectionHeader("Emergency")
            Text(
                "Send an SOS with your live location. Use this if you feel unsafe, are threatened, or are in an accident.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(MovoSpacing.medium))
            if (sosConfirmed) {
                MovoBanner("SOS sent. MOVO operations has your location and is calling you.", MovoTone.Critical)
            } else {
                MovoButton(
                    "Send SOS to MOVO",
                    { onReport("sos", "Rider emergency SOS"); sosConfirmed = true },
                    tone = MovoButtonTone.Danger,
                    enabled = !busy
                )
            }
            Spacer(Modifier.height(MovoSpacing.small))
            MovoSecondaryButton("Call MOVO support", onCallSupport, tone = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        MovoCard {
            SectionHeader("Report an incident")
            activeDeliveryOrder?.let {
                Text("Linked to delivery $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(MovoSpacing.small))
            }
            INCIDENT_KINDS.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                    row.forEach { (value, label) ->
                        MovoSecondaryButton(
                            label,
                            { kind = value },
                            Modifier.weight(1f),
                            tone = if (kind == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(MovoSpacing.small))
            }
            MovoField(description, { description = it.take(1000) }, "What happened?", singleLine = false)
            Spacer(Modifier.height(MovoSpacing.medium))
            MovoButton(
                "Send report",
                { onReport(kind, description); description = "" },
                enabled = !busy && description.isNotBlank()
            )
        }
        Spacer(Modifier.height(MovoSpacing.section))
    }
}
