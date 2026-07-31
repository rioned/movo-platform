package com.movo.customer.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.dataObject
import com.movo.customer.model.CustomerProfile
import com.movo.customer.network.CustomerApi
import com.movo.design.MovoAvatar
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoButtonTone
import com.movo.design.MovoCard
import com.movo.design.MovoField
import com.movo.design.MovoSecondaryButton
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.SectionHeader
import com.movo.design.SegmentOption
import com.movo.design.SegmentedChoice
import com.movo.design.StatTile
import com.movo.design.StatusPill
import com.movo.design.formatRwf
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Account home: identity, delivery totals, language preference, support and a
 * clean way out. Everything a customer needs after the delivery is done.
 */
@Composable
fun ProfileScreen(
    profile: CustomerProfile,
    api: CustomerApi,
    connected: Boolean,
    onClose: () -> Unit,
    onSignOut: () -> Unit
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    var deliveries by remember { mutableStateOf(0) }
    var spend by remember { mutableStateOf(0.0) }
    var language by remember { mutableStateOf("en") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSupport by remember { mutableStateOf(false) }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        runCatching {
            val payload = api.get("/api/mobile/v1/customer/deliveries?role=sent").dataObject()
            val array = payload.optJSONArray("deliveries")
            var total = 0.0
            var completed = 0
            for (index in 0 until (array?.length() ?: 0)) {
                val delivery = array!!.getJSONObject(index)
                if (delivery.optString("status") == "delivered") {
                    completed += 1
                    total += delivery.optDouble("total_charge", 0.0)
                }
            }
            completed to total
        }.onSuccess { (completed, total) -> deliveries = completed; spend = total }
    }

    if (showSupport) SupportDialog(
        api = api,
        onDismiss = { showSupport = false },
        onSent = { showSupport = false; status = "Support ticket created. MOVO will contact you." },
        onError = { error = it }
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MovoSpacing.default)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Account", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        Spacer(Modifier.height(MovoSpacing.medium))

        MovoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MovoAvatar(profile.name, size = 64.dp, online = connected)
                Column(Modifier.padding(start = MovoSpacing.default).weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" ${profile.phone}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    profile.email?.takeIf { it.isNotBlank() }?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(MovoSpacing.medium))
            StatusPill(if (connected) "Connected" else "Reconnecting", if (connected) MovoTone.Positive else MovoTone.Warning)
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        MovoCard {
            SectionHeader("Your MOVO activity")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatTile("Delivered", "$deliveries")
                StatTile("Total spend", formatRwf(spend))
                StatTile("Account", "Customer")
            }
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        MovoCard {
            SectionHeader("Language")
            Text(
                "MOVO speaks the language you prefer for notifications and delivery updates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(MovoSpacing.small))
            SegmentedChoice(
                options = listOf(SegmentOption("en", "English"), SegmentOption("rw", "Kinyarwanda")),
                selected = language,
                onSelect = { next ->
                    language = next
                    scope.launch {
                        runCatching { api.put("/api/profile", JSONObject().put("language", next)) }
                            .onSuccess { status = if (next == "rw") "Ururimi rwahinduwe" else "Language updated" }
                            .onFailure { error = it.message }
                    }
                },
                enabled = connected
            )
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        MovoCard {
            SectionHeader("Help and safety")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "  Every MOVO rider is identity-verified and every delivery is confirmed with a handover code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(MovoSpacing.medium))
            MovoSecondaryButton("Contact MOVO support", { showSupport = true }, enabled = connected)
        }

        status?.let { Spacer(Modifier.height(MovoSpacing.medium)); MovoBanner(it, MovoTone.Positive) }
        error?.let { Spacer(Modifier.height(MovoSpacing.medium)); MovoBanner(it, MovoTone.Critical) }

        Spacer(Modifier.height(MovoSpacing.large))
        MovoButton("Sign out", onSignOut, tone = MovoButtonTone.Danger)
        Spacer(Modifier.height(MovoSpacing.medium))
        Text(
            "MOVO — Rwanda's Trusted Digital Logistics Platform",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(MovoSpacing.section))
    }
}

@Composable
private fun SupportDialog(api: CustomerApi, onDismiss: () -> Unit, onSent: () -> Unit, onError: (String) -> Unit) {
    var category by remember { mutableStateOf("delivery") }
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Contact support") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                SegmentedChoice(
                    options = listOf(
                        SegmentOption("delivery", "Delivery"),
                        SegmentOption("payment", "Payment"),
                        SegmentOption("other", "Other")
                    ),
                    selected = category,
                    onSelect = { category = it }
                )
                MovoField(subject, { subject = it.take(120) }, "Subject")
                MovoField(description, { description = it.take(1000) }, "How can we help?", singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && subject.isNotBlank() && description.isNotBlank(),
                onClick = {
                    submitting = true
                    scope.launch {
                        runCatching {
                            api.post("/api/tickets", JSONObject().put("category", category).put("subject", subject).put("description", description).put("priority", "medium"))
                        }.onSuccess { onSent() }
                            .onFailure { onError(it.message ?: "Unable to create the ticket"); submitting = false }
                    }
                }
            ) { Text(if (submitting) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") } }
    )
}
