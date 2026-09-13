package com.movo.customer.parcel.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movo.customer.parcel.ui.*
import kotlinx.coroutines.delay

@Composable fun ParcelSplash() {
    var revealed by remember { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (revealed) 1f else 0f, label = "MOVO reveal")
    LaunchedEffect(Unit) { revealed = true }
    Surface(color = BackgroundDark, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().alpha(opacity), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            ParcelArtwork(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 48.dp))
            Text("MOVO", style = MaterialTheme.typography.displayLarge, color = MovoGreenLight)
            Text("Your package. Across Kigali.", color = androidx.compose.ui.graphics.Color(0xFFB8D3C4))
            Spacer(Modifier.height(32.dp)); CircularProgressIndicator(color = MovoGreen)
        }
    }
}

@Composable fun OnboardingScreen(onFinish: () -> Unit) {
    var slide by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Send anything by moto", "Follow every delivery", "Pay your way")
    val descriptions = listOf("Documents, lunch or a thoughtful parcel. A Rider takes it from you to your recipient.", "From pickup to handover, keep your parcel in sight with real-time updates.", "Choose cash or mobile money. Always review your delivery fee before you confirm.")
    val icons = listOf(Icons.Default.Inventory2, Icons.Default.MyLocation, Icons.Default.Payments)
    Page("Welcome to MOVO", { if (slide > 0) slide-- }) {
        ParcelHero(titles[slide], descriptions[slide], eyebrow = "WELCOME TO MOVO • ${slide + 1} / 3")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icons.forEachIndexed { index, icon ->
                Surface(color = if (index == slide) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape, modifier = Modifier.weight(1f)) {
                    Icon(icon, null, tint = if (index == slide) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp).size(24.dp))
                }
            }
        }
        PrimaryButton(if (slide == 2) "Get started" else "Continue") { if (slide == 2) onFinish() else slide++ }
        TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip introduction") }
    }
}

@Composable fun PhoneScreen(busy: Boolean, onBack: () -> Unit, onRequest: (String, String?) -> Unit, onDemo: () -> Unit) {
    var digits by rememberSaveable { mutableStateOf("") }
    var registering by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    Page("Your phone number", onBack) {
        ParcelHero("Deliver across Kigali", "A little parcel. A personal connection.", eyebrow = "YOUR NEXT DELIVERY STARTS HERE", compact = true)
        Text("We'll send a six-digit verification code. No password needed.", color = TextSecondary)
        OutlinedTextField(digits, { digits = it.filter(Char::isDigit).take(9) }, prefix = { Text("+250 ") }, label = { Text("Mobile number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CircleShape)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(registering, { registering = it }); Text("I'm new to MOVO")
        }
        if (registering) OutlinedTextField(name, { name = it.take(100) }, label = { Text("Full name") }, modifier = Modifier.fillMaxWidth())
        PrimaryButton(if (busy) "Sending…" else "Send verification code", !busy && rwandaPhone(digits) != null && (!registering || name.isNotBlank())) {
            rwandaPhone(digits)?.let { onRequest(it, name.takeIf { registering }) }
        }
        Text("By continuing you agree to MOVO's terms and privacy policy.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        HorizontalDivider(color = Divider)
        Text("Want to explore first? Demo deliveries never reach a real Rider or charge money.", color = TextSecondary)
        OutlinedButton(onClick = onDemo, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Explore demo") }
    }
}

@Composable fun OtpScreen(phone: String, demoCode: String?, resendAt: Long, busy: Boolean, onBack: () -> Unit, onResend: () -> Unit, onVerify: (String) -> Unit) {
    var code by rememberSaveable(phone) { mutableStateOf("") }
    var remaining by remember { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(resendAt) {
        do {
            remaining = ((resendAt - android.os.SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
            if (remaining > 0) delay(1000)
        } while (remaining > 0)
    }
    Page("Verify your number", onBack) {
        Text("Enter the code sent to $phone", style = MaterialTheme.typography.headlineSmall)
        if (demoCode != null) Text("DEMO ONLY · code $demoCode", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(code, { code = pastedOtp(it) ?: it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(), shape = CircleShape)
        TextButton(onClick = { clipboard.getText()?.text?.let { pastedOtp(it) }?.let { code = it } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste code") }
        PrimaryButton(if (busy) "Verifying…" else "Verify", !busy && code.length == 6) { onVerify(code) }
        TextButton(onClick = onResend, enabled = !busy && remaining == 0, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (remaining > 0) "Resend in ${remaining}s" else "Resend code") }
    }
}

@Composable fun ProfileSetupScreen(initialName: String, busy: Boolean, onBack: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photo = it?.toString() }
    Page("Make it yours", onBack) {
        Text("Phone verified", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        photo?.let { AsyncImage(it, "Profile photo preview", Modifier.size(88.dp).clip(CircleShape)) }
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose optional photo") }
        if (photo != null) Text("Preview only: profile photo upload is not available on the current API.", color = TextSecondary)
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Full name") }, modifier = Modifier.fillMaxWidth())
        PrimaryButton(if (busy) "Saving…" else "Start sending", !busy && name.trim().length >= 2) { onSave(name.trim()) }
    }
}
