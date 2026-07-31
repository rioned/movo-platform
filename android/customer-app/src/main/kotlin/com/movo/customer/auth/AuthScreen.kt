package com.movo.customer.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.movo.design.MovoBanner
import com.movo.design.MovoButton
import com.movo.design.MovoField
import com.movo.design.MovoPalette
import com.movo.design.MovoSpacing
import com.movo.design.MovoTextAction
import com.movo.design.MovoTone
import com.movo.design.OtpField
import com.movo.design.PhoneField
import com.movo.design.SegmentOption
import com.movo.design.SegmentedChoice

enum class AuthMode { LOGIN, REGISTER, VERIFICATION }
data class AuthRequest(val mode: AuthMode, val phone: String, val password: String, val fullName: String, val email: String, val otp: String)

/**
 * Onboarding for customers: phone-first, with password or one-time code
 * (spec §6.2). The form stays on one screen so a new customer can be delivering
 * within a minute of installing the app.
 */
@Composable
fun AuthScreen(
    isLoading: Boolean,
    error: String?,
    initialPhone: String = "",
    verificationPhone: String? = null,
    onSubmit: (AuthRequest) -> Unit
) {
    var mode by remember { mutableStateOf(AuthMode.LOGIN) }
    var phone by remember { mutableStateOf(initialPhone) }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // A verification code request from the server takes the customer straight
    // to the code step instead of leaving them on a form that already succeeded.
    LaunchedEffect(verificationPhone) {
        verificationPhone?.let { phone = it; mode = AuthMode.VERIFICATION }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
        ) {
            AuthHero()
            Surface(
                modifier = Modifier.fillMaxWidth().offset(y = (-24).dp),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp
            ) {
                Column(Modifier.padding(MovoSpacing.xlarge), verticalArrangement = Arrangement.spacedBy(MovoSpacing.medium)) {
                    if (mode != AuthMode.VERIFICATION) {
                        SegmentedChoice(
                            options = listOf(
                                SegmentOption("login", "Sign in", "Existing account"),
                                SegmentOption("register", "Create account", "New to MOVO")
                            ),
                            selected = if (mode == AuthMode.LOGIN) "login" else "register",
                            onSelect = { mode = if (it == "login") AuthMode.LOGIN else AuthMode.REGISTER },
                            enabled = !isLoading
                        )
                    }

                    Text(
                        when (mode) {
                            AuthMode.LOGIN -> "Welcome back"
                            AuthMode.REGISTER -> "Create your customer account"
                            AuthMode.VERIFICATION -> "Verify your phone"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        when (mode) {
                            AuthMode.LOGIN -> "Sign in with your phone number to ride or send across Kigali."
                            AuthMode.REGISTER -> "We use your phone number to confirm deliveries and reach you about a parcel."
                            AuthMode.VERIFICATION -> "Enter the six-digit verification code we sent by SMS."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    PhoneField(phone, { phone = it }, enabled = !isLoading && mode != AuthMode.VERIFICATION)

                    if (mode == AuthMode.REGISTER) {
                        MovoField(
                            value = fullName, onValueChange = { fullName = it }, label = "Full name",
                            enabled = !isLoading, supporting = "The name your recipients will see (full_name)",
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) }
                        )
                        MovoField(
                            value = email, onValueChange = { email = it }, label = "Email (optional)",
                            enabled = !isLoading, supporting = "Used for receipts and proof of delivery",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
                        )
                    }

                    if (mode != AuthMode.VERIFICATION) {
                        MovoField(
                            value = password, onValueChange = { password = it },
                            label = if (mode == AuthMode.LOGIN) "Password" else "Password",
                            enabled = !isLoading,
                            supporting = if (mode == AuthMode.LOGIN) "Leave blank to receive a one-time code by SMS"
                            else "At least 8 characters with a letter and a number",
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                MovoTextAction(if (showPassword) "Hide" else "Show", { showPassword = !showPassword }, enabled = !isLoading)
                            }
                        )
                    }

                    if (mode == AuthMode.VERIFICATION) OtpField(otp, { otp = it }, enabled = !isLoading)

                    error?.let {
                        MovoBanner(it, tone = if (it.startsWith("We sent")) MovoTone.Info else MovoTone.Critical)
                    }

                    MovoButton(
                        text = when (mode) {
                            AuthMode.VERIFICATION -> "Verify"
                            AuthMode.REGISTER -> "Create account"
                            AuthMode.LOGIN -> "Sign in"
                        },
                        onClick = { onSubmit(AuthRequest(mode, phone.trim(), password, fullName.trim(), email.trim(), otp)) },
                        enabled = phone.isNotBlank() && (mode != AuthMode.VERIFICATION || otp.length >= 4),
                        loading = isLoading
                    )

                    if (mode == AuthMode.VERIFICATION) {
                        MovoTextAction("Back to sign in", { mode = AuthMode.LOGIN; otp = "" }, Modifier.fillMaxWidth(), enabled = !isLoading)
                    } else {
                        MovoTextAction("I already have a verification code", { mode = AuthMode.VERIFICATION }, Modifier.fillMaxWidth(), enabled = !isLoading)
                    }

                    Text(
                        "By continuing you agree to MOVO's delivery terms. Verified riders, transparent pricing, proof of delivery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthHero() {
    // The hero gradient is always deep forest green, in either theme, so its text
    // is explicitly light — theme `onPrimary` would be dark green here in dark mode.
    Box(
        Modifier.fillMaxWidth().height(260.dp).background(
            Brush.verticalGradient(listOf(MovoPalette.ForestDeep, MovoPalette.Forest, MovoPalette.Signal))
        )
    ) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = MovoSpacing.xlarge, end = MovoSpacing.xlarge, bottom = MovoSpacing.section),
            verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)
        ) {
            Text("MOVO", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(
                "Ride and send with confidence",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Text(
                "Moto rides and same-day parcel delivery across Kigali, handled by verified riders.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.88f)
            )
        }
    }
}
