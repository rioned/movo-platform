package com.movo.rider

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.movo.design.MovoBanner
import com.movo.design.MovoSpacing
import com.movo.design.MovoTheme
import com.movo.design.MovoTone
import com.movo.rider.analytics.RiderAnalytics
import com.movo.rider.connectivity.RiderConnectivity
import com.movo.rider.home.RiderController
import com.movo.rider.home.RiderGateway
import com.movo.rider.home.RiderMessage
import com.movo.rider.model.*
import com.movo.rider.network.RiderApi
import com.movo.rider.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class RiderTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Earnings("Earnings", Icons.Filled.List),
    Safety("Safety", Icons.Filled.Warning),
    Account("Account", Icons.Filled.AccountCircle)
}

/**
 * MOVO Rider.
 *
 * The activity owns process-level concerns — permissions, the foreground location
 * service, realtime wiring — while [RiderController] owns the delivery workflow
 * state that the screens render.
 */
class MainActivity : ComponentActivity() {
    private lateinit var api: RiderApi
    private lateinit var controller: RiderController
    private lateinit var connectivity: RiderConnectivity

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            val state = controller.state.value
            startLocationSharingIfOnline(state.activeDelivery != null || state.activeRide != null)
        }
    }

    private var authenticated by mutableStateOf(false)
    private var authBusy by mutableStateOf(false)
    private var authMessage by mutableStateOf<String?>(null)
    private var verificationPhone by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var earnings by mutableStateOf<EarningsSummary?>(null)
    private var performance by mutableStateOf<PerformanceStats?>(null)
    private var earningsLoading by mutableStateOf(false)
    private var earningsError by mutableStateOf<String?>(null)
    private var realtime: RiderRealtime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = RiderApi(this)
        connectivity = RiderConnectivity(this)
        controller = RiderController(
            object : RiderGateway {
                override suspend fun get(path: String) = api.get(path)
                override suspend fun put(path: String, body: JSONObject) = api.put(path, body)
                override suspend fun post(path: String, body: JSONObject) = api.post(path, body)
                override suspend fun syncPending() = api.syncPending()
                override fun pendingCount() = api.pendingCount()
            },
            analytics = RiderAnalytics(api)
        )
        authenticated = api.isAuthenticated
        if (authenticated) refresh()
        setContent { MovoTheme { RiderApp() } }
    }

    override fun onDestroy() {
        realtime?.disconnect()
        super.onDestroy()
    }

    private fun refresh() = lifecycleScope.launch {
        controller.refresh()
        connectRealtime()
    }

    private fun connectRealtime() {
        if (realtime != null) return
        val token = api.token() ?: return
        realtime = RiderRealtime(BuildConfig.API_BASE_URL, token) {
            lifecycleScope.launch { controller.refresh() }
        }.also { it.connect() }
    }

    private fun requestLocationPermission() = locationPermission.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    )

    private fun startLocationSharingIfOnline(hasActiveWork: Boolean = false) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) { requestLocationPermission(); return }
        val intent = Intent(this, RiderLocationService::class.java).putExtra(RiderLocationService.EXTRA_ACTIVE_WORK, hasActiveWork)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationSharing() = stopService(Intent(this, RiderLocationService::class.java))

    private fun authenticate(request: RiderAuthRequest) {
        authBusy = true; authMessage = null
        lifecycleScope.launch {
            runCatching {
                when (request.mode) {
                    RiderAuthMode.SIGN_IN -> api.post(
                        "/api/auth/login",
                        JSONObject().put("phone", request.phone).put("password", request.password.takeIf(String::isNotBlank))
                    )
                    RiderAuthMode.REGISTER -> api.post(
                        "/api/auth/register",
                        JSONObject().put("phone", request.phone).put("full_name", request.fullName)
                            .put("password", request.password).put("role", "rider")
                            .put("national_id", request.nationalId).put("license_number", request.licenseNumber)
                            .put("vehicle_type", request.vehicleType)
                            .apply {
                                if (request.vehicleType == "car") {
                                    put("car_plate", request.carPlate)
                                    request.carMake.takeIf(String::isNotBlank)?.let { put("car_make", it) }
                                    request.carModel.takeIf(String::isNotBlank)?.let { put("car_model", it) }
                                    request.carColor.takeIf(String::isNotBlank)?.let { put("car_color", it) }
                                } else {
                                    put("motorcycle_plate", request.motorcyclePlate)
                                }
                            }
                    )
                    RiderAuthMode.VERIFY -> api.post(
                        "/api/auth/verify-otp",
                        JSONObject().put("phone", request.phone).put("otp", request.otp)
                    )
                }
            }.onSuccess { payload ->
                val token = payload.optString("token")
                when {
                    token.isNotBlank() -> {
                        api.saveToken(token)
                        authenticated = true
                        verificationPhone = null
                        authMessage = null
                        requestLocationPermission()
                        refresh()
                    }
                    else -> {
                        verificationPhone = payload.optString("phone").takeIf(String::isNotBlank) ?: request.phone
                        authMessage = "Enter the verification code sent to ${verificationPhone}."
                    }
                }
            }.onFailure { authMessage = it.message ?: "Authentication failed" }
            authBusy = false
        }
    }

    private fun loadEarnings(period: String) {
        earningsLoading = true; earningsError = null
        lifecycleScope.launch {
            runCatching { api.get("/api/rider/earnings?period=$period").toEarningsSummary(period) }
                .onSuccess { earnings = it }
                .onFailure { earningsError = it.message }
            runCatching { api.get("/api/rider/performance").toPerformanceStats() }
                .onSuccess { performance = it }
            earningsLoading = false
        }
    }

    private fun dial(number: String) {
        if (number.isBlank()) return
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
    }

    private fun navigateTo(lat: Double?, lng: Double?) {
        if (lat == null || lng == null) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving")))
    }

    @Composable
    private fun RiderApp() {
        if (!authenticated) {
            RiderAuthScreen(authBusy, authMessage, verificationPhone, ::authenticate)
            return
        }

        val state by controller.state.collectAsState()
        val message by controller.message.collectAsState()
        val online by connectivity.connected.collectAsState(initial = true)
        var tab by remember { mutableStateOf(RiderTab.Home) }
        var otp by remember { mutableStateOf("") }
        val snackbarHost = remember { SnackbarHostState() }

        val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val kind = pendingDocumentKind
            if (uri != null && kind != null) lifecycleScope.launch {
                runCatching { api.uploadDocument(kind, uri) }
                    .onSuccess { snackbarHost.showSnackbar("${kind.replace('_', ' ')} uploaded for verification") }
                    .onFailure { snackbarHost.showSnackbar(it.message ?: "Upload failed") }
            }
        }
        val proofPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val delivery = state.activeDelivery
            if (uri != null && delivery != null) lifecycleScope.launch {
                val kind = if (delivery.status == "arrived_pickup") "pickup" else "delivery"
                runCatching { api.uploadProof(delivery.id, kind, uri) }
                    .onSuccess { snackbarHost.showSnackbar("Proof photo attached") }
                    .onFailure { snackbarHost.showSnackbar(it.message ?: "Proof upload failed") }
            }
        }

        // Periodic reconciliation: realtime events are a hint, the API is the truth.
        LaunchedEffect(Unit) {
            while (isActive) {
                delay(30_000)
                controller.refresh()
            }
        }
        LaunchedEffect(online) { if (online) controller.refresh() }
        // Drives the location service's tier: tight polling only while a delivery/ride
        // is actually active, loose polling while merely available-and-idle (spec §13.6).
        val hasActiveWork = state.activeDelivery != null || state.activeRide != null
        LaunchedEffect(state.profile.isOnline, hasActiveWork) {
            if (state.profile.isOnline) startLocationSharingIfOnline(hasActiveWork) else stopLocationSharing()
        }
        LaunchedEffect(message) {
            message?.let {
                snackbarHost.showSnackbar(it.text)
                controller.consumeMessage()
            }
        }
        LaunchedEffect(tab) { if (tab == RiderTab.Earnings) loadEarnings("today") }
        BackHandler(enabled = tab != RiderTab.Home) { tab = RiderTab.Home }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    RiderTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
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
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                AnimatedVisibility(visible = !online) {
                    MovoBanner(
                        "No network. Delivery updates are saved on this phone and sent automatically.",
                        MovoTone.Warning,
                        Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.small)
                    )
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        RiderTab.Home -> RiderHomeScreen(
                            activity = this@MainActivity,
                            state = state,
                            busy = busy,
                            online = online,
                            otp = otp,
                            onOtpChange = { otp = it },
                            onGoOnline = {
                                busy = true
                                lifecycleScope.launch { controller.setAvailability("online"); busy = false }
                            },
                            onGoOffline = {
                                busy = true
                                lifecycleScope.launch { controller.setAvailability("offline"); busy = false }
                            },
                            onAcceptOffer = { offer ->
                                busy = true
                                lifecycleScope.launch { controller.acceptOffer(offer); busy = false }
                            },
                            onDeclineOffer = { offer ->
                                busy = true
                                lifecycleScope.launch { controller.declineOffer(offer); busy = false }
                            },
                            onOfferExpired = { lifecycleScope.launch { controller.refresh() } },
                            onAdvance = { delivery ->
                                busy = true
                                lifecycleScope.launch {
                                    if (controller.advance(delivery, otp)) otp = ""
                                    busy = false
                                }
                            },
                            onAcceptRideOffer = { offer ->
                                busy = true
                                lifecycleScope.launch { controller.acceptRideOffer(offer); startLocationSharingIfOnline(); busy = false }
                            },
                            onDeclineRideOffer = { offer ->
                                busy = true
                                lifecycleScope.launch { controller.declineRideOffer(offer); busy = false }
                            },
                            onRideOfferExpired = { lifecycleScope.launch { controller.refresh() } },
                            onAdvanceRide = { ride ->
                                busy = true
                                lifecycleScope.launch { controller.advanceRide(ride); busy = false }
                            },
                            onCancelRide = { ride ->
                                busy = true
                                lifecycleScope.launch { controller.cancelRide(ride); busy = false }
                            },
                            onCall = ::dial,
                            onNavigate = ::navigateTo,
                            onAddProof = { proofPicker.launch("image/*") },
                            onReportIssue = { tab = RiderTab.Safety },
                            onOpenProfile = { tab = RiderTab.Account },
                            photo = riderPhoto(state.profile.photoPath)
                        )

                        RiderTab.Earnings -> EarningsScreen(
                            summary = earnings,
                            performance = performance,
                            loading = earningsLoading,
                            error = earningsError,
                            onPeriodChange = ::loadEarnings
                        )

                        RiderTab.Safety -> SafetyScreen(
                            busy = busy,
                            activeDeliveryOrder = state.activeDelivery?.orderNo,
                            onReport = { kind, description ->
                                busy = true
                                lifecycleScope.launch {
                                    controller.reportIncident(kind, description, null, null, state.activeDelivery?.id)
                                    busy = false
                                }
                            },
                            onCallSupport = { dial("+250780000000") }
                        )

                        RiderTab.Account -> RiderProfileScreen(
                            profile = state.profile,
                            pendingSync = state.pendingSync,
                            photo = riderPhoto(state.profile.photoPath),
                            onUploadDocument = { kind -> pendingDocumentKind = kind; documentPicker.launch("image/*") },
                            onSaveMotorcycle = { updated ->
                                busy = true
                                lifecycleScope.launch { controller.saveMotorcycle(updated); busy = false }
                            },
                            onSaveCar = { updated ->
                                busy = true
                                lifecycleScope.launch { controller.saveCar(updated); busy = false }
                            },
                            onAvailabilityChange = { status ->
                                busy = true
                                lifecycleScope.launch { controller.setAvailability(status); busy = false }
                            },
                            onSignOut = {
                                stopLocationSharing()
                                realtime?.disconnect(); realtime = null
                                api.clear(); authenticated = false
                            }
                        )
                    }
                }
            }
        }
    }

    private var pendingDocumentKind: String? = null

    /** Rider photos are access-controlled, so the token travels with the image request. */
    private fun riderPhoto(path: String?): (@Composable () -> Unit)? {
        val token = api.token() ?: return null
        if (path.isNullOrBlank()) return null
        return {
            AsyncImage(
                model = ImageRequest.Builder(this@MainActivity)
                    .data(BuildConfig.API_BASE_URL + path)
                    .addHeader("Authorization", "Bearer $token")
                    .crossfade(true).build(),
                contentDescription = "Rider photo",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
