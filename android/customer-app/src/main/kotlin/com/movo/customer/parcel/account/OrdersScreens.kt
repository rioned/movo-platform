package com.movo.customer.parcel.account

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.parcel.ui.*

@Composable fun OrdersScreen(orders: List<AccountOrder>, loading: Boolean, error: String?, onRefresh: () -> Unit, onOrder: (String) -> Unit, onBack: () -> Unit, language: String = "en") {
    var filter by rememberSaveable { mutableStateOf("active") }
    Page(accountText(language, "Orders", "Ibyoherejwe", "Commandes"), onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("active" to "Ongoing", "completed" to "Completed", "cancelled" to "Cancelled").forEach { (id, label) -> FilterChip(filter == id, { filter = id }, label = { Text(label) }) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val shown = orders.filter { when (filter) { "completed" -> it.status == "delivered"; "cancelled" -> it.status in setOf("cancelled", "failed"); else -> it.status !in setOf("delivered", "cancelled", "failed") } }
        if (shown.isEmpty()) EmptyState("No ${filter.replace("active", "ongoing")} deliveries yet. Your next parcel journey will appear here.")
        shown.forEach { order -> Card(onClick = { onOrder(order.id) }) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${order.pickup} → ${order.destination}", style = MaterialTheme.typography.titleMedium)
            Text(order.createdAt, color = TextSecondary)
            Text("${order.price} · ${order.status.replace('_', ' ')}", color = MovoGreen)
        } } }
        error?.let { Text(it, color = TextSecondary) }
        OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
    }
}

@Composable fun OrderDetailScreen(order: AccountOrder, onTrack: () -> Unit, onSupport: () -> Unit, onRate: () -> Unit, onBack: () -> Unit, language: String = "en", onRepeat: () -> Unit = {}) {
    Page("Delivery receipt", onBack) {
        DetailLine("Order", order.id); DetailLine("Status", order.status.replace('_', ' ')); DetailLine("Date", order.createdAt)
        AccountCard { DetailLine("Pickup", order.pickup); DetailLine("Drop-off", order.destination); DetailLine("Recipient", "${order.recipient} · ${order.phone}"); DetailLine("Delivery fee", order.price) }
        if (order.riderName.isNotBlank()) DetailLine("Rider", order.riderName)
        Text("The live API provides the total delivery charge. An itemized base/distance/size receipt is not exposed here.", color = TextSecondary)
        if (order.status !in setOf("delivered", "cancelled", "failed")) PrimaryButton("Track delivery", onClick = onTrack)
        if (AccountRules.canRate(order.status, order.rating)) PrimaryButton("Rate Rider", onClick = onRate)
        OutlinedButton(onClick = onRepeat, modifier = Modifier.fillMaxWidth()) { Text("Repeat delivery") }
        MenuRow("Get help with this delivery", onClick = onSupport)
    }
}

@Composable fun CompletedRatingScreen(order: AccountOrder, onSubmit: suspend (Int, String) -> Result<Unit>, onDone: () -> Unit, onBack: () -> Unit, language: String = "en", proofCode: String? = null) {
    var score by rememberSaveable(order.id) { mutableIntStateOf(0) }
    var review by rememberSaveable(order.id) { mutableStateOf("") }
    val mutation = rememberAccountMutation()
    Page("Delivered!", onBack) {
        Text("Your package has arrived", style = MaterialTheme.typography.headlineLarge, color = MovoGreen)
        DetailLine("Recipient", order.recipient); DetailLine("Delivery fee", order.price)
        proofCode?.let { DetailLine("Recipient confirmation code", it) }
        if (proofCode == null) Text("Proof of delivery can be requested from support.", color = TextSecondary)
        Text("How was your Rider?", style = MaterialTheme.typography.titleLarge)
        Row { (1..5).forEach { star -> IconButton(onClick = { score = star }, enabled = order.rating == null && !mutation.busy) { Icon(Icons.Default.Star, "$star stars", tint = if (star <= (order.rating ?: score)) MovoGreen else DisabledText) } } }
        AccountField(review, { review = it.take(1000) }, "Optional feedback", multiline = true)
        Text("Tips are not collected in this app yet. No extra charge will be made.", color = TextSecondary)
        MutationNotice(mutation, language, "Thank you — your rating was saved.")
        if (order.rating == null && !mutation.succeeded) PrimaryButton("Submit rating", !mutation.busy && AccountRules.validRating(score, review)) { mutation.run({ onSubmit(score, review) }) }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Back to Home") }
    }
}
