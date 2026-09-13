package com.movo.customer.parcel.delivery

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movo.customer.parcel.auth.rwandaPhone
import com.movo.customer.parcel.domain.*
import com.movo.customer.parcel.ui.*
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PackageScreen(draft: ParcelDraft, isDemo: Boolean, onDraftChange: (ParcelDraft) -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; onDraftChange(draft.copy(photoUri = uri.toString())) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val file = File(context.cacheDir, "package-${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            onDraftChange(draft.copy(photoUri = Uri.fromFile(file).toString()))
        }
    }
    var codText by rememberSaveable { mutableStateOf(draft.cashOnDelivery.takeIf { it > 0 }?.toInt()?.toString().orEmpty()) }
    var cod by rememberSaveable { mutableStateOf(draft.cashOnDelivery > 0) }
    Page("Package details", onBack) {
        ModeLabel(isDemo)
        Text("What are you sending?", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Document", "Parcel", "Food", "Fragile", "Other").forEach { label ->
                FilterChip(selected = draft.category.equals(label, true), onClick = { onDraftChange(draft.copy(category = label.lowercase(), serviceType = if (label == "Document") "document" else "parcel")) }, label = { Text(label) })
            }
        }
        Text("Package size", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("small", "medium", "large").forEach { size -> FilterChip(draft.size == size, { onDraftChange(draft.copy(size = size)) }, label = { Text(size.replaceFirstChar(Char::uppercase)) }, enabled = isDemo || size == "small") } }
        Field("Short description", draft.description) { onDraftChange(draft.copy(description = it.take(500))) }
        draft.photoUri?.let { AsyncImage(it, "Your package photo", Modifier.fillMaxWidth().height(180.dp)); TextButton(onClick = { onDraftChange(draft.copy(photoUri = null)) }) { Text("Remove photo") } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = isDemo) { Text("Gallery") }
            OutlinedButton(onClick = { camera.launch(null) }, enabled = isDemo) { Text("Camera") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Collect cash on delivery", Modifier.weight(1f)); Switch(cod, { cod = it; onDraftChange(draft.copy(cashOnDelivery = if (it) codText.toDoubleOrNull() ?: 0.0 else 0.0)) }, enabled = isDemo) }
        if (cod) Field("Amount to collect · RWF", codText) { codText = it.filter(Char::isDigit).take(8); onDraftChange(draft.copy(cashOnDelivery = codText.toDoubleOrNull() ?: 0.0)) }
        if (!isDemo) Text("Photo upload, larger sizes and cash collection need backend support. Explore them in demo mode.", color = TextSecondary)
        PrimaryButton("Recipient details", draft.description.isNotBlank() && (!cod || draft.cashOnDelivery > 0), onContinue)
    }
}

@Composable fun RecipientScreen(draft: ParcelDraft, isDemo: Boolean, savedRecipients: List<SavedRecipient>, onDraftChange: (ParcelDraft) -> Unit, onSaveRecipient: (SavedRecipient) -> Unit, onManageRecipients: () -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var contactError by remember { mutableStateOf<String?>(null) }
    var saveThisRecipient by rememberSaveable { mutableStateOf(false) }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri -> runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) { val phone = rwandaPhone(cursor.getString(1)); if (phone == null) contactError = "Choose a Rwanda +250 mobile number." else onDraftChange(draft.copy(recipientName = cursor.getString(0).orEmpty(), recipientPhone = phone)) }
            }
        }.onFailure { contactError = "Unable to read this contact. Enter the details manually." } }
    }
    Page("Recipient details", onBack) {
        Text("Who is receiving the package?", style = MaterialTheme.typography.headlineSmall)
        Text("Sending yourself something, like keys you forgot at home? Save a household member as a recipient and reuse them next time.", color = TextSecondary)
        if (savedRecipients.isNotEmpty()) {
            Text("Saved recipients", style = MaterialTheme.typography.titleMedium)
            savedRecipients.forEach { recipient ->
                MenuRow(recipient.name, listOfNotNull(recipient.phone, recipient.note.takeIf(String::isNotBlank)).joinToString(" · ")) {
                    onDraftChange(draft.copy(recipientName = recipient.name, recipientPhone = recipient.phone, instructions = recipient.note.ifBlank { draft.instructions }))
                }
            }
        }
        TextButton(onClick = onManageRecipients) { Text("Manage saved recipients") }
        Field("Recipient name", draft.recipientName) { onDraftChange(draft.copy(recipientName = it.take(100))) }
        OutlinedTextField(draft.recipientPhone.removePrefix("+250"), { onDraftChange(draft.copy(recipientPhone = "+250" + it.filter(Char::isDigit).take(9))) }, prefix = { Text("+250 ") }, label = { Text("Recipient phone") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { contacts.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }) { Text("Choose from contacts") }
        contactError?.let { Text(it, color = TextSecondary) }
        Field("Delivery note (optional)", draft.instructions) { onDraftChange(draft.copy(instructions = it.take(500))) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Save this recipient for next time", Modifier.weight(1f)); Switch(saveThisRecipient, { saveThisRecipient = it }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Notify recipient by SMS", Modifier.weight(1f)); Switch(draft.notifySms, { onDraftChange(draft.copy(notifySms = it)) }, enabled = isDemo) }
        if (!isDemo) Text("Tracking-link SMS is not yet supported by the live API.", color = TextSecondary)
        PrimaryButton("Choose payment", draft.recipientName.isNotBlank() && rwandaPhone(draft.recipientPhone) != null) {
            if (saveThisRecipient && draft.recipientName.isNotBlank() && rwandaPhone(draft.recipientPhone) != null) onSaveRecipient(SavedRecipient(name = draft.recipientName, phone = draft.recipientPhone, note = draft.instructions))
            onContinue()
        }
    }
}

@Composable fun FindingScreen(delivery: ParcelDelivery?, isDemo: Boolean, error: String?, onRefresh: () -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "Finding a Rider")
    val pulse by transition.animateFloat(.85f, 1.15f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "Search pulse")
    Page("Finding a Rider", onBack) {
        ModeLabel(isDemo)
        DeliveryMap(listOfNotNull(delivery?.pickup))
        Icon(Icons.Default.Inventory2, "Searching near pickup", Modifier.size(72.dp).scale(if (android.animation.ValueAnimator.areAnimatorsEnabled()) pulse else 1f).align(Alignment.CenterHorizontally), tint = MovoGreen)
        Text("Looking for your nearest available Rider", style = MaterialTheme.typography.headlineSmall)
        Text(delivery?.reference.orEmpty(), color = TextSecondary)
        if (isDemo) Text("Demo dispatch simulation is running. No real Rider is contacted.")
        Failure(error)
        OutlinedButton(onClick = onRefresh) { Text("Refresh status") }
        TextButton(onClick = onCancel, enabled = delivery?.canCancel == true) { Text("Cancel delivery", color = ErrorRed) }
    }
}

@Composable fun TrackingScreen(tracking: ParcelTracking?, isLoading: Boolean, error: String?, onRefresh: () -> Unit, onCancel: () -> Unit, onCall: (String) -> Unit, onSupport: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Page("Live tracking", onBack) {
        val delivery = tracking?.delivery
        if (tracking == null) { EmptyState("Waiting for the latest delivery position."); PrimaryButton("Retry", !isLoading, onRefresh) }
        else {
            ModeLabel(delivery!!.isDemo)
            val rider = if (tracking.riderLatitude != null && tracking.riderLongitude != null) ParcelPlace("rider", "Rider", "Latest Rider position", tracking.riderLatitude, tracking.riderLongitude) else null
            DeliveryMap(listOfNotNull(delivery.pickup, delivery.destination, rider))
            Text(delivery.status.replace('_', ' '), style = MaterialTheme.typography.headlineSmall, color = MovoGreen)
            Text("Last position: ${tracking.updatedAt ?: "not received yet"}", color = TextSecondary)
            Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(delivery.riderName ?: "Rider details pending", style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(delivery.riderPlate, delivery.riderRating?.let { "★ $it" }).joinToString(" · "))
                delivery.riderPhone?.let { phone -> OutlinedButton(onClick = { onCall(phone) }) { Text("Call Rider") } }
                TextButton(onClick = onSupport) { Text("Messaging & support") }
            } }
            val statuses = listOf("created" to "Order placed", "assigned" to "Rider assigned", "picked_up" to "Picked up", "in_transit" to "In transit", "delivered" to "Delivered")
            statuses.forEach { (code, label) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.CheckCircle, label, tint = if (tracking.events.any { it.status == code } || delivery.status == code) MovoGreen else DisabledText)
                Text(label)
            } }
            TextButton(onClick = {
                val text = if (delivery.isDemo) "MOVO demo delivery ${delivery.reference} — this is a simulation, not public tracking." else "MOVO delivery ${delivery.reference}: ${delivery.status.replace('_',' ')}. ${delivery.pickup.address} → ${delivery.destination.address}. Public tracking links are not available yet."
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share delivery status"))
            }) { Text("Share delivery status") }
            if (delivery.canCancel) TextButton(onClick = onCancel) { Text("Cancel delivery", color = ErrorRed) }
            MenuRow("Safety / SOS", "Emergency assistance", onSupport)
            PrimaryButton("Refresh tracking", !isLoading, onRefresh)
        }
        Failure(error)
    }
}
