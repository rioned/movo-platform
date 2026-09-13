package com.movo.customer.parcel

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.*
import com.movo.customer.connectivity.ConnectivityObserver
import com.movo.customer.location.CustomerLocation
import com.movo.customer.parcel.account.*
import com.movo.customer.parcel.auth.*
import com.movo.customer.parcel.delivery.*
import com.movo.customer.parcel.domain.*
import com.movo.customer.parcel.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private fun ParcelDelivery.account() = AccountOrder(id, status, pickup.address, destination.address, recipientName, recipientPhone, createdAt, "RWF ${"%.0f".format(amount)}", riderName.orEmpty(), riderPhone.orEmpty(), rating)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ParcelApp(viewModel: ParcelViewModel = hiltViewModel(), initialOrderId: String? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val connectivity = remember { ConnectivityObserver(context) }
    val online by connectivity.connected.collectAsStateWithLifecycle(initialValue = true)
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    var payment by rememberSaveable { mutableStateOf(false) }
    var paymentContinue by rememberSaveable { mutableStateOf(false) }
    var cancelConfirm by rememberSaveable { mutableStateOf(false) }
    var addressPurpose by rememberSaveable { mutableStateOf("delivery") }
    var addressLabel by rememberSaveable { mutableStateOf("Home") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    fun go(route: String) { viewModel.dismissError(); nav.navigate(route) { launchSingleTop = true } }
    fun home() { nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true } }
    val back: () -> Unit = { if (!nav.popBackStack()) home() }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) CustomerLocation(context).requestCurrent { result -> result.onSuccess { point -> viewModel.setLocation(ParcelPlace("gps", "Your location", "GPS pickup · ${point.latitude}, ${point.longitude}", point.latitude, point.longitude)) }.onFailure { viewModel.report(it.message ?: "Location unavailable") } }
        else viewModel.report("Location permission declined. Search for your pickup instead.")
    }
    val notificationsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(state.ready) {
        if (state.ready && current == "splash") go(if (state.profile != null) "home" else if (state.onboarded) "phone" else "onboarding")
    }
    LaunchedEffect(state.profile, initialOrderId) {
        if (state.profile != null && initialOrderId != null) viewModel.openOrder(initialOrderId) { go("detail") }
        else if (state.ready && state.profile == null && current !in setOf("splash", "onboarding", "phone", "otp")) nav.navigate("phone") { popUpTo(nav.graph.id) }
    }
    LaunchedEffect(current, state.order?.id) {
        if (current in setOf("finding", "tracking")) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { viewModel.pollTracking(); delay(5_000) }
        }
    }
    LaunchedEffect(state.order?.status, current) {
        val order = state.order ?: return@LaunchedEffect
        if (current == "finding" && order.riderName != null) go("tracking")
        if (current in setOf("finding", "tracking") && order.status == "delivered") go("completed")
    }

    CompositionLocalProvider(LocalSavedHome provides state.addresses.firstOrNull { it.label.equals("Home", true) }?.place) {
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = current == "home", drawerContent = {
        ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
          Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.padding(vertical = 20.dp)) {
                ParcelHero("MOVO", "Your package. Across Kigali.", eyebrow = "YOUR DELIVERY SPACE", compact = true)
            }
            listOf("profile" to "Profile", "orders" to "Orders", "recipients" to "Recipient details", "support" to "Support", "settings" to "Settings", "notifications" to "Notifications").forEach { (route, title) ->
                NavigationDrawerItem(label = { Text(title) }, icon = {
                    Icon(when (route) {
                        "profile" -> Icons.Default.Person
                        "orders" -> Icons.Default.Inventory2
                        "recipients" -> Icons.Default.Group
                        "support" -> Icons.Default.SupportAgent
                        "settings" -> Icons.Default.Settings
                        else -> Icons.Default.Notifications
                    }, null)
                }, selected = current == route, onClick = { scope.launch { drawer.close() }; go(route) })
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            NavigationDrawerItem(label = { Text("Logout") }, icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) }, selected = false, onClick = { scope.launch { drawer.close() }; viewModel.signOut { go("phone") } })
          }
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            if (state.demo) Surface(color = MaterialTheme.colorScheme.primaryContainer) { Text("DEMO · Simulated deliveries · No charges", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(10.dp)) }
            if (!online && !state.demo) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text("You're offline. Saved addresses remain available; reconnect and retry.", modifier = Modifier.padding(12.dp)) }
            if (state.error != null) Surface(color = MaterialTheme.colorScheme.errorContainer) { Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(state.error!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = viewModel::retry) { Text("Retry") }
                TextButton(onClick = viewModel::dismissError) { Text("Close") }
            } }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
                NavHost(nav, startDestination = "splash") {
                    composable("splash") { ParcelSplash() }
                    composable("onboarding") { OnboardingScreen { viewModel.completeOnboarding { go("phone") } } }
                    composable("phone") { PhoneScreen(state.busy, { go("onboarding") }, { phone, name -> viewModel.requestOtp(phone, name) { go("otp") } }, { viewModel.enterDemo { home() } }) }
                    composable("otp") { OtpScreen(state.phone, state.challenge?.demoCode, state.resendAt, state.busy, back, viewModel::resendOtp) { viewModel.verify(it) { go("setup") } } }
                    composable("setup") { ProfileSetupScreen(state.profile?.name.orEmpty(), state.busy, back) { viewModel.setup(it) { home() } } }
                    composable("home") {
                        LaunchedEffect(Unit) { viewModel.refreshHome() }
                        HomeDashboard(state.demo, state.lastLocation ?: state.draft.pickup, { scope.launch { drawer.open() } }, { viewModel.newDelivery { addressPurpose = "delivery"; go("pickup") } }, { go("orders") }, { go("addresses") })
                    }
                    listOf("pickup", "dropoff", "stop", "save-place").forEach { route -> composable(route) {
                        var query by rememberSaveable { mutableStateOf("") }
                        var pinMode by rememberSaveable { mutableStateOf(false) }
                        var pin by remember { mutableStateOf<ParcelPlace?>(null) }
                        LaunchedEffect(query) { viewModel.search(query) }
                        // Strong default suggestions when the field is empty: saved places
                        // first (the customer explicitly chose these), then GPS pickup,
                        // then recently used destinations — so there's always something
                        // useful to tap before typing a single character.
                        val candidates = if (query.isBlank()) {
                            state.addresses.map { it.place } + listOfNotNull(state.lastLocation?.takeIf { route == "pickup" }) + state.orders.take(5).map { it.destination }
                        } else state.results
                        Column {
                            if (route == "pickup") TextButton(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Your location — use GPS pickup") }
                            AddressSearchScreen(if (route == "pickup") "Pickup address" else if (route == "save-place") "Save $addressLabel" else "Drop-off address", query, candidates.distinctBy { it.id }, state.searching, state.error, pinMode, pin, { query = it }, { place ->
                                viewModel.validatePlace(place) {
                                    when (route) {
                                        "pickup" -> { viewModel.editDraft(state.draft.copy(pickup = place)); go("dropoff") }
                                        "dropoff" -> { viewModel.editDraft(state.draft.copy(destination = place)); go("route") }
                                        "stop" -> { viewModel.editDraft(state.draft.copy(extraStops = state.draft.extraStops + place)); go("route") }
                                        else -> scope.launch { viewModel.saveAddress(SavedParcelAddress(label = addressLabel, place = place)).onSuccess { go("addresses") }.onFailure { viewModel.report(it.message ?: "Address not saved") } }
                                    }
                                }
                            }, { pinMode = it }, { pin = it }, back)
                        }
                    } }
                    composable("route") { RouteScreen(state.draft, state.demo, viewModel::editDraft, { go("pickup") }, { go("dropoff") }, { go("stop") }, { go("package") }, back) }
                    composable("package") { PackageScreen(state.draft, state.demo, viewModel::editDraft, { go("recipient") }, back) }
                    composable("recipient") { RecipientScreen(state.draft, state.demo, state.recipients, viewModel::editDraft, { r -> scope.launch { viewModel.saveRecipient(r) } }, { go("recipients") }, { paymentContinue = true; payment = true }, back) }
                    composable("review") { LaunchedEffect(Unit) { viewModel.quote() }; ReviewScreen(state.draft, state.estimate, state.demo, state.busy, state.error, { paymentContinue = false; payment = true }, viewModel::quote, { viewModel.confirm { go("finding") } }, back) }
                    composable("finding") { FindingScreen(state.order, state.demo, state.error, viewModel::refreshTracking, { cancelConfirm = true }, { home() }) }
                    composable("tracking") { TrackingScreen(state.tracking, state.busy, state.error, viewModel::refreshTracking, { cancelConfirm = true }, { phone -> context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }, { go("safety") }, { home() }) }
                    composable("completed") { state.order?.let { order -> CompletedRatingScreen(order.account(), viewModel::rate, { home() }, back, state.language, order.deliveryCode) } ?: EmptyState("Select a completed delivery from Orders.") }
                    composable("orders") { LaunchedEffect(Unit) { viewModel.refreshHome() }; OrdersScreen(state.orders.map { it.account() }, state.busy, state.error, viewModel::refreshHome, { id -> viewModel.openOrder(id) { go("detail") } }, back, state.language) }
                    composable("detail") { state.order?.let { order -> OrderDetailScreen(order.account(), { go(if (order.riderName == null) "finding" else "tracking") }, { go("support") }, { go("completed") }, back, state.language, { viewModel.repeatOrder { go("pickup") } }) } ?: EmptyState("Choose an order to view its receipt.") }
                    composable("profile") { ProfileLanding(state.profile, state.draft.paymentMethod, { route -> if (route == "payment") { paymentContinue = false; payment = true } else go(route) }, { viewModel.signOut { go("phone") } }, back) }
                    composable("edit-profile") { EditProfileScreen(AccountProfile(state.profile?.name.orEmpty(), state.profile?.phone.orEmpty(), state.profile?.email.orEmpty()), viewModel::updateProfile, back, state.language) }
                    composable("settings") { SettingsScreen(state.language, viewModel::setLanguage, back) { if (android.os.Build.VERSION.SDK_INT >= 33) notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) } }
                    composable("addresses") { Page("Saved addresses", back) {
                        if (state.addresses.isEmpty()) EmptyState("Save Home, Work or a favorite delivery address.")
                        state.addresses.forEach { address -> MenuRow(address.label, address.place.address) { viewModel.newDelivery { viewModel.editDraft(state.draft.copy(destination = address.place)); go("pickup") } }; TextButton(onClick = { scope.launch { viewModel.deleteAddress(address.id).onFailure { viewModel.report(it.message.orEmpty()) } } }) { Text("Delete ${address.label}", color = ErrorRed) } }
                        OutlinedTextField(addressLabel, { addressLabel = it.take(40) }, label = { Text("Label: Home, Work or custom") }, modifier = Modifier.fillMaxWidth())
                        PrimaryButton("Add address", addressLabel.isNotBlank()) { go("save-place") }
                    } }
                    composable("recipients") { RecipientsScreen(state.recipients, viewModel::saveRecipient, viewModel::deleteRecipient, back, state.language) }
                    composable("support") { SupportScreen(viewModel::ticket, back, state.language) }
                    composable("safety") { SafetyScreen({ go("support") }, back, state.language) }
                    composable("information") { InformationScreen(back, state.language) }
                    composable("notifications") { LaunchedEffect(Unit) { viewModel.notifications() }; NotificationsScreen(state.notices, state.busy, state.error, viewModel::notifications, viewModel::markRead, back, state.language) }
                    composable("discounts") { Page("Discounts", back) { var code by rememberSaveable { mutableStateOf("") }; OutlinedTextField(code, { code = it.take(40) }, label = { Text("Enter promo code") }); Text("Promo-code validation is not available on the current API. No discount will be applied.", color = TextSecondary); PrimaryButton("Apply code", false) {} } }
                    composable("improve") { Page("Improve maps", back) { Text("Add an unlisted place as a saved address, then submit its coordinates to support for review."); PrimaryButton("Choose a place") { go("save-place") }; MenuRow("Submit map correction", "Include its label and coordinates", { go("support") }) } }
                }
            }
        }
    }
    }
    if (payment) PaymentSheet(state.draft.paymentMethod, listOf(PaymentOption("mtn_momo", "MTN MoMo", "Pay with mobile money", state.demo), PaymentOption("airtel_money", "Airtel Money", "Pay with mobile money", state.demo), PaymentOption("cash", "Cash")), { viewModel.editDraft(state.draft.copy(paymentMethod = it)) }, {
        payment = false
        if (paymentContinue && state.draft.paymentMethod.isNotBlank()) { paymentContinue = false; go("review") }
    })
    if (cancelConfirm) AlertDialog(onDismissRequest = { cancelConfirm = false }, title = { Text("Cancel this delivery?") }, text = { Text("Your Rider will be notified. You can book again later.") }, confirmButton = { TextButton(onClick = { cancelConfirm = false; viewModel.cancel { go("orders") } }) { Text("Cancel delivery", color = ErrorRed) } }, dismissButton = { TextButton(onClick = { cancelConfirm = false }) { Text("Keep delivery") } })
}

@Composable private fun ProfileLanding(profile: ParcelProfile?, payment: String, onNavigate: (String) -> Unit, onLogout: () -> Unit, onBack: () -> Unit) {
    Page("Profile", onBack) {
        Text(profile?.name ?: "MOVO customer", style = MaterialTheme.typography.headlineLarge)
        Text("✓ Phone verified", color = MaterialTheme.colorScheme.primary); Text(profile?.phone.orEmpty(), color = TextSecondary)
        TextButton(onClick = { onNavigate("edit-profile") }) { Text("Edit profile") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("orders", "support", "addresses", "settings").forEach { route -> TextButton(onClick = { onNavigate(route) }) { Text(route.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall) } } }
        MenuRow("Discounts", "Enter promo code") { onNavigate("discounts") }
        MenuRow("Payment methods", payment.ifBlank { "Choose a default" }) { onNavigate("payment") }
        MenuRow("Recipient details", "Saved people who receive your packages") { onNavigate("recipients") }
        MenuRow("Improve maps", "Add places, fix errors") { onNavigate("improve") }
        MenuRow("Safety", onClick = { onNavigate("safety") })
        MenuRow("Information", onClick = { onNavigate("information") })
        MenuRow("Notifications", onClick = { onNavigate("notifications") })
        TextButton(onClick = onLogout) { Text("Logout") }
    }
}
