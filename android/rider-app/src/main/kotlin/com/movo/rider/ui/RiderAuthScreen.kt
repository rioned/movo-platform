package com.movo.rider.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

enum class RiderAuthMode { SIGN_IN, REGISTER, VERIFY }

data class RiderAuthRequest(
    val mode: RiderAuthMode,
    val phone: String,
    val password: String,
    val fullName: String = "",
    val nationalId: String = "",
    val licenseNumber: String = "",
    val vehicleType: String = "motorcycle",
    val motorcyclePlate: String = "",
    val carPlate: String = "",
    val carMake: String = "",
    val carModel: String = "",
    val carColor: String = "",
    val otp: String = ""
)

/**
 * Rider onboarding (spec §7.2). Registration collects the identity and vehicle
 * details MOVO must verify before a rider can ever receive a delivery.
 */
@Composable
fun RiderAuthScreen(
    busy: Boolean,
    message: String?,
    verificationPhone: String?,
    onSubmit: (RiderAuthRequest) -> Unit
) {
    var mode by remember { mutableStateOf(RiderAuthMode.SIGN_IN) }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var nationalId by remember { mutableStateOf("") }
    var licenseNumber by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("car") }
    var motorcyclePlate by remember { mutableStateOf("") }
    var carPlate by remember { mutableStateOf("") }
    var carMake by remember { mutableStateOf("") }
    var carModel by remember { mutableStateOf("") }
    var carColor by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(verificationPhone) {
        verificationPhone?.let { phone = it; mode = RiderAuthMode.VERIFY }
    }

    // Hero stays fixed; the form sheet takes the remaining height and scrolls
    // inside itself, so the keyboard never leaves a band of empty background.
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(MovoPalette.ForestDeep, MovoPalette.Forest))
        ).navigationBarsPadding().imePadding()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MovoSpacing.xlarge, vertical = MovoSpacing.xlarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fixed dark-green hero: text is explicitly light in both themes.
            Text("MOVO Driver", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(
                "Drive rides or deliver parcels, on your own schedule",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.88f)
            )
        }

        Surface(
            Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(MovoSpacing.xlarge),
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.medium)
            ) {
                if (mode != RiderAuthMode.VERIFY) {
                    SegmentedChoice(
                        options = listOf(
                            SegmentOption("sign_in", "Sign in", "Approved riders"),
                            SegmentOption("register", "Join MOVO", "New riders")
                        ),
                        selected = if (mode == RiderAuthMode.SIGN_IN) "sign_in" else "register",
                        onSelect = { mode = if (it == "sign_in") RiderAuthMode.SIGN_IN else RiderAuthMode.REGISTER },
                        enabled = !busy
                    )
                }

                Text(
                    when (mode) {
                        RiderAuthMode.SIGN_IN -> "Welcome back"
                        RiderAuthMode.REGISTER -> "Create your rider account"
                        RiderAuthMode.VERIFY -> "Verify your phone"
                    },
                    style = MaterialTheme.typography.headlineSmall
                )

                PhoneField(phone, { phone = it }, enabled = !busy && mode != RiderAuthMode.VERIFY)

                if (mode == RiderAuthMode.REGISTER) {
                    MovoField(fullName, { fullName = it }, "Full legal name", enabled = !busy)
                    MovoField(nationalId, { nationalId = it }, "National ID number", enabled = !busy, supporting = "Checked during MOVO verification")
                    MovoField(licenseNumber, { licenseNumber = it }, "Driving licence number", enabled = !busy)
                    SegmentedChoice(
                        options = listOf(
                            SegmentOption("car", "Drive a car", "Ride-hailing"),
                            SegmentOption("motorcycle", "Ride a motorcycle", "Deliveries")
                        ),
                        selected = vehicleType, onSelect = { vehicleType = it }, enabled = !busy
                    )
                    if (vehicleType == "car") {
                        MovoField(carPlate, { carPlate = it.uppercase() }, "Car plate", enabled = !busy, supporting = "For example AAB 123 MP")
                        MovoField(carMake, { carMake = it }, "Car make (optional)", enabled = !busy)
                        MovoField(carModel, { carModel = it }, "Car model (optional)", enabled = !busy)
                        MovoField(carColor, { carColor = it }, "Car color (optional)", enabled = !busy)
                    } else {
                        MovoField(motorcyclePlate, { motorcyclePlate = it.uppercase() }, "Motorcycle plate", enabled = !busy, supporting = "For example RAB 123 C")
                    }
                }

                if (mode == RiderAuthMode.VERIFY) {
                    OtpField(otp, { otp = it }, enabled = !busy)
                } else {
                    MovoField(
                        value = password, onValueChange = { password = it }, label = "Password", enabled = !busy,
                        supporting = if (mode == RiderAuthMode.REGISTER) "At least 8 characters with a letter and a number" else "Leave blank to receive an SMS code",
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        trailingIcon = { MovoTextAction(if (showPassword) "Hide" else "Show", { showPassword = !showPassword }, enabled = !busy) }
                    )
                }

                message?.let { MovoBanner(it, if (it.contains("failed", true) || it.contains("Invalid", true)) MovoTone.Critical else MovoTone.Info) }

                MovoButton(
                    text = when (mode) {
                        RiderAuthMode.SIGN_IN -> "Sign in"
                        RiderAuthMode.REGISTER -> "Create rider account"
                        RiderAuthMode.VERIFY -> "Verify account"
                    },
                    onClick = {
                        onSubmit(
                            RiderAuthRequest(
                                mode = mode, phone = phone.trim(), password = password, fullName = fullName.trim(),
                                nationalId = nationalId.trim(), licenseNumber = licenseNumber.trim(), vehicleType = vehicleType,
                                motorcyclePlate = motorcyclePlate.trim(), carPlate = carPlate.trim(),
                                carMake = carMake.trim(), carModel = carModel.trim(), carColor = carColor.trim(), otp = otp
                            )
                        )
                    },
                    enabled = !busy && phone.isNotBlank(),
                    loading = busy
                )

                if (mode == RiderAuthMode.VERIFY) {
                    MovoTextAction("Back to sign in", { mode = RiderAuthMode.SIGN_IN }, Modifier.fillMaxWidth(), enabled = !busy)
                }

                Text(
                    "MOVO reviews every rider's identity, licence and motorcycle documents before delivery offers begin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
