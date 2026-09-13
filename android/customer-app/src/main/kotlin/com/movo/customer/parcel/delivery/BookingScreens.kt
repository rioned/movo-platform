package com.movo.customer.parcel.delivery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.movo.customer.parcel.domain.*
import com.movo.customer.parcel.ui.*

@Composable internal fun ModeLabel(isDemo: Boolean) {
    if (isDemo) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text("DEMO • No real booking or payment", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }
}
@Composable internal fun Failure(error: String?) { if (error != null) Text(error, color = MaterialTheme.colorScheme.error) }
@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
}

@Composable
fun HomeDashboard(isDemo: Boolean, pickup: ParcelPlace?, onMenu: () -> Unit, onSend: () -> Unit, onActivity: () -> Unit, onSavedPlaces: () -> Unit) {
    Page("MOVO", onMenu) {
        ModeLabel(isDemo)
        ParcelHero("Your city.\nDelivered.", "Documents, parcels and thoughtful surprises. Send across Kigali by moto.", eyebrow = "SMALL PARCELS. BIG CONNECTIONS.")
        PrimaryButton("What are you sending?", onClick = onSend)
        Text("Your delivery hub", style = MaterialTheme.typography.titleLarge)
        MenuRow("Your deliveries", "Track a delivery or view activity", onActivity)
        MenuRow("Saved places", "Home, work and more", onSavedPlaces)
        Text("Start where you are", style = MaterialTheme.typography.titleLarge)
        MenuRow(pickup?.label ?: "Choose your current location", pickup?.address ?: "Kigali, Rwanda", onSend)
        DeliveryMap(listOfNotNull(pickup))
    }
}

@Composable
fun AddressSearchScreen(title: String, query: String, results: List<ParcelPlace>, isLoading: Boolean, error: String?, pinMode: Boolean, pin: ParcelPlace?, onQueryChange: (String) -> Unit, onSelect: (ParcelPlace) -> Unit, onPinMode: (Boolean) -> Unit, onPinChange: (ParcelPlace) -> Unit, onBack: () -> Unit) {
    Page(title, onBack) {
        Field("Search address", query, onQueryChange)
        TextButton(onClick = { onPinMode(!pinMode) }) { Text(if (pinMode) "Search instead" else "Choose a point on the map") }
        if (pinMode) {
            Text("Tap the map to position the pin. Verify the coordinates and add delivery instructions later.")
            DeliveryMap(listOfNotNull(pin), onPin = onPinChange)
            pin?.let { Text(it.address) }
            PrimaryButton("Use this pin", enabled = pin != null, onClick = { pin?.let(onSelect) })
        } else {
            if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Failure(error)
            if (!isLoading && error == null && results.isEmpty()) EmptyState(if (query.isBlank()) "Search for a street, building or landmark." else "No addresses found. Try another search or choose a map pin.")
            results.forEach { p -> MenuRow(p.label, p.address) { onSelect(p) } }
            Text("Search results from OpenStreetMap, MapTiler and Google Places", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteScreen(draft: ParcelDraft, isDemo: Boolean, onDraftChange: (ParcelDraft) -> Unit, onPickup: () -> Unit, onDestination: () -> Unit, onAddStop: () -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    Page("Your delivery route", onBack) {
        ModeLabel(isDemo)
        JourneyStep("01", "Plan the journey", "Choose the pickup, drop-off and the right fit for your parcel.")
        DeliveryMap(listOfNotNull(draft.pickup) + draft.extraStops + listOfNotNull(draft.destination))
        MenuRow("Pickup", draft.pickup?.address ?: "Choose pickup address", onPickup)
        draft.extraStops.forEachIndexed { i, stop ->
            MenuRow("Stop ${i + 1} • Remove", stop.address) { onDraftChange(draft.copy(extraStops = draft.extraStops.filterIndexed { index, _ -> index != i })) }
        }
        MenuRow("Drop-off", draft.destination?.address ?: "Choose destination", onDestination)
        TextButton(onClick = onAddStop, enabled = isDemo) { Text("+ Add a stop") }
        Text("Delivery tier", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("standard", "express", "box").forEach { tier -> FilterChip(selected = draft.tier == tier, onClick = { onDraftChange(draft.copy(tier = tier)) }, label = { Text(when(tier) { "box" -> "Moto + Box"; "express" -> "Express Moto"; else -> "Standard Moto" }, style = MaterialTheme.typography.labelSmall) }, enabled = tier == "standard" || isDemo) }
        }
        Text("Package size", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("small", "medium", "large").forEach { size -> FilterChip(selected = draft.size == size, onClick = { onDraftChange(draft.copy(size = size)) }, label = { Text(size) }, enabled = size == "small" || isDemo) }
        }
        if (!isDemo) Text("Live booking currently supports standard, small parcels and one drop-off. More options are available to explore in demo.")
        PrimaryButton("Package details", enabled = draft.pickup != null && draft.destination != null && draft.pickup != draft.destination, onClick = onContinue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentSheet(selectedId: String, options: List<PaymentOption>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Payment methods", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            options.forEach { option ->
                val selected = selectedId == option.id && option.enabled
                val tint = if (option.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = option.enabled, role = Role.RadioButton, onClick = { onSelect(selectPayment(selectedId, option)) }).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.title, color = tint, style = MaterialTheme.typography.titleMedium)
                        if (option.subtitle.isNotBlank()) Text(option.subtitle, color = tint)
                        if (!option.enabled) Text("Unavailable", color = tint, style = MaterialTheme.typography.labelMedium)
                    }
                    if (selected) Box(Modifier.size(26.dp).background(MovoGreen, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, "Selected", Modifier.size(19.dp), tint = BackgroundDark)
                    } else RadioButton(selected = false, enabled = option.enabled, onClick = null)
                }
                HorizontalDivider()
            }
            PrimaryButton("Done", enabled = canConfirmPayment(selectedId, options), onClick = onDismiss)
        }
    }
}

@Composable
fun ReviewScreen(draft: ParcelDraft, estimate: ParcelEstimate?, isDemo: Boolean, isSubmitting: Boolean, error: String?, onPayment: () -> Unit, onRefreshQuote: () -> Unit, onConfirm: () -> Unit, onBack: () -> Unit) {
    Page("Review delivery", onBack) {
        ModeLabel(isDemo)
        JourneyStep("04", "Ready for the road", "Check the details and delivery fee before confirming.")
        Text("Pickup", style = MaterialTheme.typography.labelLarge); Text(draft.pickup?.address ?: "Missing pickup")
        draft.extraStops.forEach { Text("Stop • ${it.address}") }
        Text("Drop-off", style = MaterialTheme.typography.labelLarge); Text(draft.destination?.address ?: "Missing destination")
        HorizontalDivider()
        Text("${draft.tier.replaceFirstChar { it.uppercase() }} • ${draft.size} • ${draft.category}")
        Text(draft.description)
        Text("Sender: ${draft.senderName} • ${draft.senderPhone}")
        Text("Recipient: ${draft.recipientName} • ${draft.recipientPhone}")
        if (draft.instructions.isNotBlank()) Text("Instructions: ${draft.instructions}")
        if (draft.cashOnDelivery > 0) Text("Cash to collect: ${draft.cashOnDelivery} RWF")
        if (draft.photoUri != null) Text("Package photo attached")
        if (draft.notifySms) Text("Recipient SMS requested")
        MenuRow("Payment", draft.paymentMethod.replaceFirstChar { it.uppercase() }, onPayment)
        if (estimate == null) EmptyState("Get a current quote before confirming. No price has been assumed.")
        else {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DELIVERY ESTIMATE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${estimate.currency} ${"%.0f".format(estimate.amount)}", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${"%.1f".format(estimate.distanceKm)} km • ${estimate.etaMinutes?.let { "$it min estimated" } ?: "Arrival time not supplied"}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Failure(error)
        TextButton(onClick = onRefreshQuote, enabled = !isSubmitting) { Text("Refresh quote") }
        PrimaryButton(if (isSubmitting) "Requesting delivery…" else if (isDemo) "Create demo delivery" else "Confirm delivery", enabled = !isSubmitting && estimate != null && estimate.isDemo == isDemo && draft.pickup != null && draft.destination != null && validRecipient(draft.recipientName, draft.recipientPhone) && validRecipient(draft.senderName, draft.senderPhone) && draft.description.isNotBlank(), onClick = onConfirm)
    }
}
