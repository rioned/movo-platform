package com.movo.customer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.movo.customer.activity.ActivityScreen
import com.movo.customer.auth.*
import com.movo.customer.connectivity.ConnectivityObserver
import com.movo.customer.model.CustomerProfile
import com.movo.customer.model.toProfile
import com.movo.customer.network.CustomerApi
import com.movo.customer.network.CustomerApiException
import com.movo.customer.profile.ProfileScreen
import com.movo.customer.receive.ReceiveScreen
import com.movo.customer.ride.RideBookingScreen
import com.movo.customer.ride.RideTrackingScreen
import com.movo.customer.send.SendScreen
import com.movo.customer.session.CustomerSession
import com.movo.customer.tracking.TrackingScreen
import com.movo.design.MovoBanner
import com.movo.design.MovoPalette
import com.movo.design.MovoSpacing
import com.movo.design.MovoTheme
import com.movo.design.MovoTone
import kotlinx.coroutines.launch
import org.json.JSONObject

// MOVO brand tokens — the canonical values live in the shared :design module
// (com.movo.design.MovoPalette) and are mirrored here for quick reference.
private val RouteWhite = Color(0xFFFCFCFA)
private val MovoForest = Color(0xFF086B4D)
private val SignalGreen = Color(0xFF19A974)
private val RoadInk = Color(0xFF151817)
private val MotoAmber = Color(0xFFF5A623)

enum class CustomerDestination(val label: String, val icon: ImageVector) {
    Ride("Ride", Icons.Filled.LocationOn),
    Send("Send", Icons.AutoMirrored.Filled.Send),
    Receive("Receive", Icons.Filled.MailOutline),
    Activity("Activity", Icons.AutoMirrored.Filled.List),
    Profile("Account", Icons.Filled.Person),
    Tracking("Tracking", Icons.AutoMirrored.Filled.Send),
    RideTracking("Ride tracking", Icons.Filled.LocationOn)
}

class MainActivity : ComponentActivity() {
    private lateinit var session: CustomerSession
    private lateinit var api: CustomerApi
    private lateinit var connectivity: ConnectivityObserver
    private var profile by mutableStateOf<CustomerProfile?>(null)
    private var restoring by mutableStateOf(true)
    private var authLoading by mutableStateOf(false)
    private var authError by mutableStateOf<String?>(null)
    private var pendingVerificationPhone by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = CustomerSession(this); api = CustomerApi(session::token); connectivity = ConnectivityObserver(this)
        restoreSession()
        setContent { MovoTheme { AppContent() } }
    }

    private fun restoreSession() {
        val cached = session.profile()?.takeIf { it.role == "customer" }
        if (session.token() == null) { if (session.profile() != null) session.clear(); restoring = false; return }
        profile = cached
        lifecycleScope.launch {
            runCatching { api.get("/api/auth/me").dataObject().toProfile() }
                .onSuccess {
                    if (it.role != "customer") { session.clear(); profile = null; authError = "This app requires a customer account." }
                    else { profile = it; session.updateProfile(it) }
                }.onFailure { failure ->
                    val unauthorized = failure is CustomerApiException && (failure.status == 401 || failure.status == 403)
                    if (unauthorized) { session.clear(); profile = null }
                    else if (cached == null) authError = "You appear to be offline. Reconnect to restore your session."
                }
            restoring = false
        }
    }

    private fun authenticate(request: AuthRequest) {
        authLoading = true; authError = null
        lifecycleScope.launch {
            runCatching {
                val response = when (request.mode) {
                    AuthMode.LOGIN -> api.post("/api/auth/login", JSONObject().put("phone", request.phone).put("password", request.password.takeIf(String::isNotBlank)))
                    AuthMode.REGISTER -> api.post("/api/auth/register", JSONObject().put("phone", request.phone).put("full_name", request.fullName).put("email", request.email.takeIf(String::isNotBlank)).put("password", request.password).put("role", "customer"))
                    AuthMode.VERIFICATION -> api.post("/api/auth/verify-otp", JSONObject().put("phone", request.phone).put("otp", request.otp))
                }.dataObject()
                val token = response.optString("token"); val user = response.optJSONObject("user")
                if (token.isBlank() || user == null) throw VerificationRequired(request.phone)
                user.toProfile().also {
                    if (it.role != "customer") throw IllegalArgumentException("This app requires a customer account.")
                    session.save(token, it)
                }
            }.onSuccess { profile = it; pendingVerificationPhone = null }
                .onFailure { failure ->
                    if (failure is VerificationRequired) {
                        pendingVerificationPhone = failure.phone
                        authError = "We sent a verification code to ${failure.phone}."
                    } else {
                        authError = failure.message ?: "Authentication failed"
                    }
                }
            authLoading = false
        }
    }

    @Composable private fun AppContent() {
        val online by connectivity.connected.collectAsState(initial = false)
        when {
            restoring -> SplashScreen()
            profile == null -> AuthScreen(
                isLoading = authLoading,
                error = authError,
                verificationPhone = pendingVerificationPhone,
                onSubmit = ::authenticate
            )
            else -> CustomerShell(profile!!, api, session, online) { profile = null }
        }
    }
}

/**
 * Branded restore screen — shown only while the cached session is revalidated.
 * The deep forest background is fixed in both themes, so its content is white.
 */
@Composable
private fun SplashScreen() {
    Surface(Modifier.fillMaxSize(), color = MovoPalette.Forest) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("MOVO", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(
                "Ride, or send a parcel",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.88f)
            )
            Spacer(Modifier.height(MovoSpacing.xlarge))
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
    }
}

private class VerificationRequired(val phone: String) : Exception()
internal fun JSONObject.dataObject(): JSONObject = optJSONObject("data") ?: this

@Composable
private fun CustomerShell(profile: CustomerProfile, api: CustomerApi, session: CustomerSession, online: Boolean, onSignedOut: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf(CustomerDestination.Ride) }
    var trackingDeliveryId by rememberSaveable { mutableStateOf<String?>(null) }
    var trackingRideId by rememberSaveable { mutableStateOf<String?>(null) }
    val mainDestinations = listOf(CustomerDestination.Ride, CustomerDestination.Send, CustomerDestination.Receive, CustomerDestination.Activity, CustomerDestination.Profile)
    fun openTracking(id: String) { trackingDeliveryId = id; destination = CustomerDestination.Tracking }
    fun openRideTracking(id: String) { trackingRideId = id; destination = CustomerDestination.RideTracking }
    LaunchedEffect(online) {
        if (!online) return@LaunchedEffect
        runCatching {
            val home = api.get("/api/mobile/v1/customer/home").dataObject()
            val activeSent = home.optJSONArray("activeSent"); val activeReceived = home.optJSONArray("activeReceived")
            activeSent?.optJSONObject(0) ?: activeReceived?.optJSONObject(0)
        }.getOrNull()?.let { active ->
            val id = active.optString("id"); val status = active.optString("status")
            if (status == "awaiting_rider_selection" && session.restoreJourney()?.deliveryId == id) destination = CustomerDestination.Send
            else if (id.isNotBlank()) openTracking(id)
        }
        // A ride survives an app restart the same way a delivery does: find the
        // customer's own non-terminal ride, if any, and resume live tracking on it.
        runCatching {
            val response = api.get("/api/rides")
            response.optJSONArray("data")
        }.getOrNull()?.let { rides ->
            for (index in 0 until rides.length()) {
                val ride = rides.optJSONObject(index) ?: continue
                val status = ride.optString("status")
                if (status !in setOf("completed", "cancelled")) { openRideTracking(ride.optString("id")); break }
            }
        }
    }
    BackHandler(destination != CustomerDestination.Ride) { destination = CustomerDestination.Ride }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (destination != CustomerDestination.Tracking && destination != CustomerDestination.RideTracking) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    mainDestinations.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Connectivity is a first-class state in Kigali: say it plainly (spec §17).
            AnimatedVisibility(visible = !online, enter = fadeIn(), exit = fadeOut()) {
                MovoBanner(
                    "You are offline. MOVO keeps your request and syncs when the connection returns.",
                    tone = MovoTone.Warning,
                    modifier = Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small)
                )
            }
            Box(Modifier.weight(1f)) {
                when (destination) {
                    CustomerDestination.Ride -> RideBookingScreen(api, onRideCreated = ::openRideTracking)
                    CustomerDestination.Send -> SendScreen(api, profile, session, online, onTracking = ::openTracking)
                    CustomerDestination.Receive -> ReceiveScreen(api, onTrack = ::openTracking)
                    CustomerDestination.Activity -> ActivityScreen(api, onTrack = ::openTracking)
                    CustomerDestination.Profile -> ProfileScreen(profile, api, connected = online, onClose = { destination = CustomerDestination.Ride }) { session.clear(); onSignedOut() }
                    CustomerDestination.Tracking -> trackingDeliveryId?.let { id ->
                        TrackingScreen(id, api, session.token().orEmpty(), session, online, onBack = { destination = CustomerDestination.Send }, onReselect = { destination = CustomerDestination.Send })
                    } ?: Placeholder("No delivery selected")
                    CustomerDestination.RideTracking -> trackingRideId?.let { id ->
                        RideTrackingScreen(id, api, session.token().orEmpty(), online, onBack = { destination = CustomerDestination.Ride }, onDone = { trackingRideId = null; destination = CustomerDestination.Ride })
                    } ?: Placeholder("No ride selected")
                }
            }
        }
    }
}

@Composable private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
