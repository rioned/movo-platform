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
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
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
import com.movo.customer.mode.ModeSelectScreen
import com.movo.customer.model.CustomerProfile
import com.movo.customer.model.toProfile
import com.movo.customer.network.CustomerApi
import com.movo.customer.network.CustomerApiException
import com.movo.customer.profile.ProfileScreen
import com.movo.customer.receive.ReceiveScreen
import com.movo.customer.ride.RideScreen
import com.movo.customer.send.SendScreen
import com.movo.customer.session.CustomerSession
import com.movo.customer.tracking.TrackingScreen
import com.movo.design.ModeSwitcher
import com.movo.design.MovoBanner
import com.movo.design.MovoPalette
import com.movo.design.MovoServiceMode
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
    Ride("Ride", Icons.Filled.Person),
    Send("Send", Icons.Filled.Send),
    Receive("Receive", Icons.Filled.MailOutline),
    Activity("Activity", Icons.Filled.List),
    Profile("Account", Icons.Filled.Person),
    Tracking("Tracking", Icons.Filled.Send)
}

/**
 * The tabs a mode offers. Ride has no "Receive" — nobody receives a passenger on
 * your behalf — so the bar reshapes rather than showing a tab that does nothing.
 */
private fun destinationsFor(mode: MovoServiceMode): List<CustomerDestination> = when (mode) {
    MovoServiceMode.Ride -> listOf(CustomerDestination.Ride, CustomerDestination.Activity, CustomerDestination.Profile)
    MovoServiceMode.Delivery -> listOf(CustomerDestination.Send, CustomerDestination.Receive, CustomerDestination.Activity, CustomerDestination.Profile)
}

/** The tab a mode opens on. */
private fun homeFor(mode: MovoServiceMode): CustomerDestination =
    if (mode.isRide) CustomerDestination.Ride else CustomerDestination.Send

class MainActivity : ComponentActivity() {
    private lateinit var session: CustomerSession
    private lateinit var api: CustomerApi
    private lateinit var connectivity: ConnectivityObserver
    private var profile by mutableStateOf<CustomerProfile?>(null)
    private var restoring by mutableStateOf(true)
    private var authLoading by mutableStateOf(false)
    private var authError by mutableStateOf<String?>(null)
    private var pendingVerificationPhone by mutableStateOf<String?>(null)

    /**
     * The product the customer is working in, or null while the chooser is up.
     *
     * A restored session resumes its last product, but a fresh sign-in always
     * lands on the chooser: which product you want is the first thing MOVO asks,
     * not something it assumes on your behalf.
     */
    private var serviceMode by mutableStateOf<MovoServiceMode?>(null)

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
        // Resuming an existing session keeps the product they were last using.
        serviceMode = session.serviceMode()
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
            }.onSuccess {
                profile = it
                pendingVerificationPhone = null
                // A fresh sign-in always shows the chooser, pre-selected with the
                // product this device used last.
                serviceMode = null
            }
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

    private fun signOut() {
        session.clear()
        profile = null
        serviceMode = null
    }

    @Composable private fun AppContent() {
        val online by connectivity.connected.collectAsState(initial = false)
        val currentProfile = profile
        val currentMode = serviceMode
        when {
            restoring -> SplashScreen()
            currentProfile == null -> AuthScreen(
                isLoading = authLoading,
                error = authError,
                verificationPhone = pendingVerificationPhone,
                onSubmit = ::authenticate
            )
            currentMode == null -> ModeSelectScreen(
                customerName = currentProfile.name,
                initialMode = session.serviceMode(),
                onConfirm = { chosen -> session.saveServiceMode(chosen); serviceMode = chosen },
                onSignOut = ::signOut
            )
            else -> CustomerShell(
                profile = currentProfile,
                api = api,
                session = session,
                online = online,
                mode = currentMode,
                onModeChange = { chosen -> session.saveServiceMode(chosen); serviceMode = chosen },
                onSignedOut = ::signOut
            )
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
                "Deliver with Confidence",
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
private fun CustomerShell(
    profile: CustomerProfile,
    api: CustomerApi,
    session: CustomerSession,
    online: Boolean,
    mode: MovoServiceMode,
    onModeChange: (MovoServiceMode) -> Unit,
    onSignedOut: () -> Unit
) {
    val home = homeFor(mode)
    var destination by rememberSaveable(mode) { mutableStateOf(home) }
    var trackingDeliveryId by rememberSaveable { mutableStateOf<String?>(null) }
    val mainDestinations = destinationsFor(mode)
    fun openTracking(id: String) { trackingDeliveryId = id; destination = CustomerDestination.Tracking }
    LaunchedEffect(online, mode) {
        if (!online) return@LaunchedEffect
        runCatching {
            val payload = api.get("/api/mobile/v1/customer/home").dataObject()
            val activeSent = payload.optJSONArray("activeSent"); val activeReceived = payload.optJSONArray("activeReceived")
            // Only resume a job belonging to the product currently on screen —
            // switching to Ride should not reopen tracking for a parcel.
            val candidates = buildList {
                for (index in 0 until (activeSent?.length() ?: 0)) activeSent?.optJSONObject(index)?.let(::add)
                for (index in 0 until (activeReceived?.length() ?: 0)) activeReceived?.optJSONObject(index)?.let(::add)
            }
            candidates.firstOrNull { MovoServiceMode.from(it.optString("service_mode")) == mode }
        }.getOrNull()?.let { active ->
            val id = active.optString("id"); val status = active.optString("status")
            val awaitingThisJourney = if (mode.isRide) session.restoreRideJourney()?.rideId == id
                else session.restoreJourney()?.deliveryId == id
            if (status == "awaiting_rider_selection" && awaitingThisJourney) destination = home
            else if (id.isNotBlank()) openTracking(id)
        }
    }
    BackHandler(destination != home) { destination = home }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (destination != CustomerDestination.Tracking) {
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
            // The product switcher rides above the working area, so changing
            // product is always one tap away and never a hunt through settings.
            if (destination != CustomerDestination.Tracking) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small),
                    horizontalArrangement = Arrangement.Center
                ) {
                    ModeSwitcher(selected = mode, onSelect = onModeChange)
                }
            }
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
                    CustomerDestination.Ride -> RideScreen(api, profile, session, online, onTracking = ::openTracking)
                    CustomerDestination.Send -> SendScreen(api, profile, session, online, onTracking = ::openTracking)
                    CustomerDestination.Receive -> ReceiveScreen(api, onTrack = ::openTracking)
                    CustomerDestination.Activity -> ActivityScreen(api, mode = mode, onTrack = ::openTracking)
                    CustomerDestination.Profile -> ProfileScreen(profile, api, connected = online, onClose = { destination = home }, onSignOut = onSignedOut)
                    CustomerDestination.Tracking -> trackingDeliveryId?.let { id ->
                        TrackingScreen(id, api, session.token().orEmpty(), session, online, onBack = { destination = home }, onReselect = { destination = home })
                    } ?: Placeholder("Nothing selected")
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
