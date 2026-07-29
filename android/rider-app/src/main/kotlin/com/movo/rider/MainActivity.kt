package com.movo.rider

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import org.json.JSONObject

// ─── Colour palette (dark green theme) ───────────────────────────────────────
object MovoColor {
  val Green          = Color(0xFF087B54)
  val GreenDark      = Color(0xFF065233)
  val GreenLight     = Color(0xFFE8F5EE)
  val GreenSurface   = Color(0xFFF4F9F6)
  val GreenBg        = Color(0xFFF0F6F3)
  val OnGreen        = Color.White
  val TextPrimary    = Color(0xFF1A1D1C)
  val TextSecondary  = Color(0xFF5A6B63)
  val Surface        = Color.White
  val CardBg         = Color.White
  val Divider        = Color(0xFFE2EAE5)
  val Gold           = Color(0xFFD97706)
  val Online         = Color(0xFF22C55E)
  val Error          = Color(0xFFDC2626)
  val Shadow         = Color(0xFF000000).copy(alpha = 0.08f)
  val OverlayDim     = Color(0xFF000000).copy(alpha = 0.40f)
}

// ─── Reusable components ──────────────────────────────────────────────────────

@Composable
private fun MovoButton(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false, modifier: Modifier = Modifier) {
  Button(
    onClick = onClick, enabled = enabled && !loading,
    modifier = modifier.fillMaxWidth().height(52.dp),
    shape = RoundedCornerShape(16.dp),
    colors = ButtonDefaults.buttonColors(containerColor = MovoColor.Green, disabledContainerColor = MovoColor.Divider)
  ) {
    if (loading) CircularProgressIndicator(color = MovoColor.OnGreen, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    else Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun MovoOutlinedField(value: String, onValueChange: (String) -> Unit, label: String, leadingIcon: @Composable (() -> Unit)? = null, visualTransformation: VisualTransformation = VisualTransformation.None, modifier: Modifier = Modifier) {
  OutlinedTextField(
    value = value, onValueChange = onValueChange,
    label = { Text(label, color = MovoColor.TextSecondary) },
    leadingIcon = leadingIcon,
    visualTransformation = visualTransformation,
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = MovoColor.Green,
      unfocusedBorderColor = MovoColor.Divider,
      focusedContainerColor = MovoColor.GreenSurface,
      unfocusedContainerColor = MovoColor.Surface,
      cursorColor = MovoColor.Green,
      focusedLabelColor = MovoColor.Green,
      unfocusedTextColor = MovoColor.TextPrimary,
    ),
    singleLine = true,
  )
}

@Composable
private fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(99.dp),
    color = color.copy(alpha = 0.15f),
    modifier = modifier
  ) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
      Box(Modifier.size(7.dp).clip(CircleShape).background(color))
      Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
    }
  }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MovoColor.GreenDark)
    Text(label, fontSize = 11.sp, color = MovoColor.TextSecondary)
  }
}

// ─── Step indicator for delivery progress ────────────────────────────────────

private val steps = listOf("Accepted", "Going", "Pickup", "Picked", "Transit", "Arrived", "Done")
private val statusToStep = mapOf(
  "assigned" to 0, "going_pickup" to 1, "arrived_pickup" to 2,
  "picked_up" to 3, "in_transit" to 4, "arrived_dest" to 5, "complete" to 6
)

@Composable
private fun DeliveryStepper(currentStatus: String) {
  val step = statusToStep[currentStatus] ?: 0
  val activeSteps = (step + 1).coerceAtMost(steps.size)
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    steps.take(activeSteps).forEachIndexed { i, label ->
      val done = i < step
      val active = i == step
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Box(
          modifier = Modifier.size(if (active) 32.dp else 28.dp)
            .clip(CircleShape)
            .background(if (done || active) MovoColor.Green else MovoColor.Divider),
          contentAlignment = Alignment.Center
        ) {
          if (done) Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MovoColor.OnGreen)
          else if (active) Box(Modifier.size(10.dp).clip(CircleShape).background(MovoColor.OnGreen))
          else Text("${i + 1}", fontSize = 11.sp, color = MovoColor.TextSecondary)
        }
        Text(label, fontSize = 8.sp, color = if (active || done) MovoColor.Green else MovoColor.TextSecondary, textAlign = TextAlign.Center, maxLines = 1)
      }
    }
  }
}

// =============================================================================
//  MAIN ACTIVITY
// =============================================================================

class MainActivity : ComponentActivity() {
  private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
  override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { RiderScreen() } }
  private fun requestLocation() = locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))

  @Composable private fun RiderScreen() {
    val api = remember { RiderApi(this) }
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf("Sign in to load approval") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var offerId by remember { mutableStateOf<String?>(null) }
    var offerDeliveryId by remember { mutableStateOf<String?>(null) }
    var offerLabel by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var pickupLat by remember { mutableStateOf<Double?>(null) }
    var pickupLng by remember { mutableStateOf<Double?>(null) }
    var destinationLat by remember { mutableStateOf<Double?>(null) }
    var destinationLng by remember { mutableStateOf<Double?>(null) }
    var registering by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var nationalId by remember { mutableStateOf("") }
    var licenseNumber by remember { mutableStateOf("") }
    var motorcyclePlate by remember { mutableStateOf("") }
    var verificationPhone by remember { mutableStateOf<String?>(null) }
    var otp by remember { mutableStateOf("") }
    var earningsSummary by remember { mutableStateOf("") }
    var activeDeliveryId by remember { mutableStateOf<String?>(null) }
    var activeDeliveryStatus by remember { mutableStateOf("") }
    var deliveryOtp by remember { mutableStateOf("") }
    var documentKind by remember { mutableStateOf<String?>(null) }
    var riderName by remember { mutableStateOf("Rider") }
    var profilePhotoUrl by remember { mutableStateOf<String?>(null) }
    var totalEarnings by remember { mutableStateOf(0.0) }
    var rating by remember { mutableStateOf(0.0) }
    var ratingCount by remember { mutableStateOf(0) }
    var completedDeliveries by remember { mutableStateOf(0) }
    var profileExpanded by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    // ── Motorcycle settings ──
    var motorcycleType by remember { mutableStateOf("") }
    var motorcycleMake by remember { mutableStateOf("") }
    var motorcycleColor by remember { mutableStateOf("") }
    var settingsSaved by remember { mutableStateOf(false) }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      val kind = documentKind
      if (uri != null && kind != null) scope.launch { try { api.uploadDocument(kind, uri); message = "${kind.replace('_', ' ')} uploaded for approval." } catch (e: Exception) { message = e.message ?: "Upload failed" } }
    }
    val proofPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      if (uri != null && activeDeliveryId != null) scope.launch { try { api.uploadProof(activeDeliveryId!!, if (activeDeliveryStatus == "arrived_pickup") "pickup" else "delivery", uri); message = "Proof uploaded." } catch (e: Exception) { message = e.message ?: "Proof upload failed" } }
    }

    fun refresh() = scope.launch {
      try {
        api.syncPending()
        val payload = JSONObject(api.get("/api/mobile/v1/rider/home")).getJSONObject("data")
        approval = payload.optString("approval_status", "pending")
        riderName = payload.optString("full_name", "Rider")
        profilePhotoUrl = payload.optString("profile_photo_url").takeIf { it.isNotBlank() }
        totalEarnings = payload.optDouble("total_earnings", 0.0)
        rating = payload.optDouble("avg_rating", 0.0)
        ratingCount = payload.optInt("rating_count", 0)
        completedDeliveries = payload.optInt("total_deliveries", 0)
        online = payload.optString("online_status") in listOf("online", "busy")
        // Request location permissions so the map can show the rider
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
          this@MainActivity.requestLocation()
        }
        // Motorcycle settings
        motorcyclePlate = payload.optString("motorcycle_plate", "")
        motorcycleMake = payload.optString("motorcycle_make", "")
        motorcycleType = payload.optString("motorcycle_type", "")
        motorcycleColor = payload.optString("motorcycle_color", "")
        if (online && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
          ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, RiderLocationService::class.java))
        val offer = payload.optJSONArray("offers")?.takeIf { it.length() > 0 }?.getJSONObject(0)
        val active = payload.optJSONObject("activeDelivery")
        val mapDelivery = active ?: offer
        pickupLat = mapDelivery?.takeIf { it.has("pickup_lat") }?.optDouble("pickup_lat")
        pickupLng = mapDelivery?.takeIf { it.has("pickup_lng") }?.optDouble("pickup_lng")
        destinationLat = mapDelivery?.takeIf { it.has("dest_lat") }?.optDouble("dest_lat")
        destinationLng = mapDelivery?.takeIf { it.has("dest_lng") }?.optDouble("dest_lng")
        activeDeliveryId = active?.optString("id")
        activeDeliveryStatus = active?.optString("status") ?: ""
        offerId = offer?.optString("offer_id")
        offerDeliveryId = offer?.optString("id")
        customerPhone = offer?.optString("pickup_phone") ?: ""
        offerLabel = offer?.let { "${it.optString("order_no")} · ${it.optDouble("rider_earnings")} RWF\nPickup: ${it.optString("pickup_address")}\nCustomer: ${it.optString("pickup_name")} · ${it.optString("pickup_phone")}\nDestination: ${it.optString("dest_address")}\nexpires ${it.optString("expires_at")}" } ?: ""
        message = if (approval == "approved") if (offer == null) "Ready for delivery offers" else "New delivery offer available" else "Your account is $approval. Approval is required before going online."
      } catch (e: Exception) { message = e.message ?: "Unable to load rider status" }
    }

    fun saveMotorcycleSettings() = scope.launch {
      try {
        settingsSaved = false
        api.put("/api/rider/profile", JSONObject().apply {
          if (motorcycleType.isNotBlank()) put("motorcycle_type", motorcycleType)
          if (motorcycleColor.isNotBlank()) put("motorcycle_color", motorcycleColor)
          if (motorcycleMake.isNotBlank()) put("motorcycle_make", motorcycleMake)
          if (motorcyclePlate.isNotBlank()) put("motorcycle_plate", motorcyclePlate)
        }.toString())
        settingsSaved = true
        message = "Motorcycle settings saved"
        refresh()
      } catch (e: Exception) { message = e.message ?: "Save failed" }
    }

    LaunchedEffect(Unit) { if (api.token() != null) refresh() }
    DisposableEffect(api.token()) {
      val realtime = api.token()?.let { RiderRealtime(BuildConfig.API_BASE_URL, it) { refresh() }.also { it.connect() } }
      onDispose { realtime?.disconnect() }
    }

    val token = api.token()
    val isAuthenticated = token != null

    // ── THEME ──
    MaterialTheme(
      colorScheme = lightColorScheme(
        primary = MovoColor.Green, onPrimary = MovoColor.OnGreen,
        primaryContainer = MovoColor.GreenLight, onPrimaryContainer = MovoColor.GreenDark,
        surface = MovoColor.Surface, onSurface = MovoColor.TextPrimary,
        surfaceVariant = MovoColor.GreenSurface, onSurfaceVariant = MovoColor.TextSecondary,
        background = MovoColor.GreenBg, onBackground = MovoColor.TextPrimary,
      )
    ) {
      Box(Modifier.fillMaxSize().background(MovoColor.GreenBg)) {

        // ── AUTHENTICATED: Map + overlay cards ──
        if (isAuthenticated) {
          RiderMap(this@MainActivity, pickupLat, pickupLng, destinationLat, destinationLng, Modifier.fillMaxSize())

          Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.35f).align(Alignment.TopCenter)
              .background(Brush.verticalGradient(listOf(MovoColor.OverlayDim, Color.Transparent)))
          )

          // ═══ TOP BAR ═══
          Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 48.dp, bottom = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MovoColor.CardBg.copy(alpha = 0.92f),
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
          ) {
            Row(
              Modifier.fillMaxWidth().clickable { profileExpanded = !profileExpanded }.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                  Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = MovoColor.GreenLight,
                    border = androidx.compose.foundation.BorderStroke(2.5.dp, MovoColor.Green),
                  ) {
                    if (profilePhotoUrl != null) {
                      AsyncImage(
                        model = ImageRequest.Builder(this@MainActivity).data(BuildConfig.API_BASE_URL + profilePhotoUrl).addHeader("Authorization", "Bearer $token").crossfade(true).build(),
                        contentDescription = "Profile", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(CircleShape)
                      )
                    } else {
                      Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                        Text(riderName.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MovoColor.GreenDark)
                      }
                    }
                  }
                  if (online) Box(Modifier.size(14.dp).clip(CircleShape).background(MovoColor.Online).align(Alignment.BottomEnd).border(2.dp, MovoColor.CardBg, CircleShape))
                }
                Column {
                  Text(riderName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MovoColor.TextPrimary)
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge(if (online) "Online" else "Offline", if (online) MovoColor.Online else MovoColor.TextSecondary)
                    if (activeDeliveryId != null) StatusBadge("Active", MovoColor.Gold)
                  }
                }
              }
              Text(if (profileExpanded) "▲" else "▼", fontSize = 14.sp, color = MovoColor.TextSecondary, fontWeight = FontWeight.Bold)
            }
          }

          // ═══ BOTTOM AREA ═══
          Box(Modifier.fillMaxSize().align(Alignment.BottomCenter)) {
            Column(
              Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).align(Alignment.BottomCenter),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              if (message.isNotBlank() && !profileExpanded) {
                Surface(shape = RoundedCornerShape(16.dp), color = MovoColor.GreenLight, shadowElevation = 4.dp) {
                  Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 13.sp, color = MovoColor.GreenDark, lineHeight = 18.sp)
                }
              }

              if (offerId != null && offerDeliveryId != null && !profileExpanded) {
                Surface(shape = RoundedCornerShape(24.dp), color = MovoColor.CardBg, shadowElevation = 10.dp, tonalElevation = 3.dp) {
                  Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Box(Modifier.size(10.dp).clip(CircleShape).background(MovoColor.Gold))
                      Spacer(Modifier.width(8.dp))
                      Text("New delivery offer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MovoColor.TextPrimary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(offerLabel, fontSize = 13.sp, color = MovoColor.TextSecondary, lineHeight = 19.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                      OutlinedButton(
                        onClick = { if (customerPhone.isNotBlank()) this@MainActivity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$customerPhone"))) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MovoColor.Green),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, MovoColor.Green)
                      ) { Text("Call", fontWeight = FontWeight.SemiBold) }
                      OutlinedButton(
                        onClick = { scope.launch { try { api.put("/api/mobile/v1/rider/offers/$offerId/decline", "{}"); refresh() } catch (e: Exception) { message = e.message ?: "Decline failed" } } },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MovoColor.Error),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, MovoColor.Error)
                      ) { Text("Decline", fontWeight = FontWeight.SemiBold) }
                      Button(
                        onClick = { scope.launch { try { api.put("/api/deliveries/$offerDeliveryId/accept", "{}"); refresh() } catch (e: Exception) { message = e.message ?: "Offer unavailable" } } },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MovoColor.Green)
                      ) { Text("Accept", fontWeight = FontWeight.SemiBold) }
                    }
                  }
                }
              }

              if (activeDeliveryId != null && !profileExpanded) {
                Surface(shape = RoundedCornerShape(24.dp), color = MovoColor.CardBg, shadowElevation = 10.dp, tonalElevation = 3.dp) {
                  Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      DeliveryStepper(activeDeliveryStatus)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Status: ${activeDeliveryStatus.replace("_", " ").replaceFirstChar { it.uppercase() }}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MovoColor.GreenDark)
                    if (activeDeliveryStatus == "arrived_pickup" || activeDeliveryStatus == "arrived_dest") {
                      Spacer(Modifier.height(12.dp))
                      OutlinedTextField(
                        deliveryOtp, { deliveryOtp = it },
                        label = { Text("${if (activeDeliveryStatus == "arrived_pickup") "Pickup" else "Delivery"} OTP") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MovoColor.Green, unfocusedBorderColor = MovoColor.Divider, focusedContainerColor = MovoColor.GreenSurface, unfocusedContainerColor = MovoColor.Surface, cursorColor = MovoColor.Green),
                        singleLine = true,
                      )
                      TextButton(onClick = { proofPicker.launch("image/*") }) {
                        Text("📷  Add proof photo", color = MovoColor.Green, fontWeight = FontWeight.Medium)
                      }
                    }
                    Spacer(Modifier.height(4.dp))
                    val action = when (activeDeliveryStatus) { "assigned" -> "Start pickup route"; "going_pickup" -> "Arrived at pickup"; "arrived_pickup" -> "Verify pickup"; "picked_up" -> "Start transit"; "in_transit" -> "Arrived at destination"; "arrived_dest" -> "Complete delivery"; else -> null }
                    if (action != null) {
                      MovoButton(text = action, onClick = {
                        scope.launch {
                          try {
                            val path = when (activeDeliveryStatus) { "assigned" -> "going-pickup"; "going_pickup" -> "arrive-pickup"; "arrived_pickup" -> "verify-pickup"; "picked_up" -> "in-transit"; "in_transit" -> "arrive-dest"; "arrived_dest" -> "complete"; else -> "complete" }
                            val body = if (activeDeliveryStatus == "arrived_pickup" || activeDeliveryStatus == "arrived_dest") JSONObject().put("otp", deliveryOtp).toString() else "{}"
                            api.put("/api/deliveries/$activeDeliveryId/$path", body)
                            deliveryOtp = ""; refresh()
                          } catch (e: Exception) { message = e.message ?: "Action failed" }
                        }
                      })
                    }
                  }
                }
              }

              if (offerId == null && activeDeliveryId == null && !profileExpanded && approval == "approved") {
                Surface(shape = RoundedCornerShape(24.dp), color = MovoColor.CardBg, shadowElevation = 6.dp, tonalElevation = 2.dp) {
                  Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📍", fontSize = 32.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Ready for delivery offers", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MovoColor.TextPrimary)
                    Text("Your location is being shared. Offers appear here.", fontSize = 13.sp, color = MovoColor.TextSecondary, textAlign = TextAlign.Center)
                  }
                }
              }
            }
          }

          // ═══ PROFILE OVERLAY ═══
          AnimatedVisibility(
            visible = profileExpanded,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.fillMaxSize()
          ) {
            Box(Modifier.fillMaxSize()) {
              Box(Modifier.fillMaxSize().background(MovoColor.OverlayDim).clickable { profileExpanded = false })
              Surface(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MovoColor.Surface,
                shadowElevation = 16.dp,
              ) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                  Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MovoColor.Divider).align(Alignment.CenterHorizontally))
                  Spacer(Modifier.height(16.dp))

                  // Avatar
                  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Surface(modifier = Modifier.size(80.dp), shape = CircleShape, color = MovoColor.GreenLight, border = androidx.compose.foundation.BorderStroke(3.dp, MovoColor.Green)) {
                      if (profilePhotoUrl != null) {
                        AsyncImage(
                          model = ImageRequest.Builder(this@MainActivity).data(BuildConfig.API_BASE_URL + profilePhotoUrl).addHeader("Authorization", "Bearer $token").crossfade(true).build(),
                          contentDescription = "Profile", contentScale = ContentScale.Crop,
                          modifier = Modifier.size(80.dp).clip(CircleShape)
                        )
                      } else {
                        Box(contentAlignment = Alignment.Center) {
                          Text(riderName.take(1).uppercase(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MovoColor.GreenDark)
                        }
                      }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(riderName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MovoColor.TextPrimary)
                    StatusBadge(if (online) "Online" else "Offline", if (online) MovoColor.Online else MovoColor.TextSecondary)
                  }

                  Spacer(Modifier.height(16.dp))

                  // Stats
                  Surface(shape = RoundedCornerShape(16.dp), color = MovoColor.GreenSurface) {
                    Row(
                      Modifier.fillMaxWidth().padding(vertical = 12.dp),
                      horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                      StatBox("Earnings", "${totalEarnings.toInt()} RWF")
                      Divider(modifier = Modifier.height(36.dp).width(1.dp), color = MovoColor.Divider)
                      StatBox("Rating", "★ ${String.format("%.1f", rating)}")
                      Divider(modifier = Modifier.height(36.dp).width(1.dp), color = MovoColor.Divider)
                      StatBox("Deliveries", "$completedDeliveries")
                    }
                  }

                  Spacer(Modifier.height(12.dp))

                  // Account status
                  if (approval != "approved") {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF3E0)) {
                      Column(Modifier.padding(14.dp)) {
                        Text("Account: $approval", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB45309))
                        Spacer(Modifier.height(8.dp))
                        Text("Upload verification documents below.", fontSize = 12.sp, color = Color(0xFF92400E))
                      }
                    }
                    Spacer(Modifier.height(12.dp))
                  }

                  // ── Motorcycle settings section ──
                  Text("Motorcycle settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MovoColor.TextPrimary)
                  Spacer(Modifier.height(8.dp))

                  // Motorcycle type dropdown (Fuel / Electric)
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val types = listOf("fuel" to "⛽ Fuel", "electric" to "⚡ Electric")
                    types.forEach { (value, label) ->
                      val selected = motorcycleType == value
                      Surface(
                        onClick = { motorcycleType = value },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MovoColor.Green else MovoColor.GreenSurface,
                        modifier = Modifier.weight(1f)
                      ) {
                        Row(
                          Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.Center
                        ) {
                          Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selected) MovoColor.OnGreen else MovoColor.TextPrimary)
                        }
                      }
                    }
                  }
                  Spacer(Modifier.height(8.dp))

                  // Motorcycle color
                  MovoOutlinedField(motorcycleColor, { motorcycleColor = it }, "Motorcycle colour", leadingIcon = { Text("🎨", fontSize = 16.sp) })
                  Spacer(Modifier.height(8.dp))

                  // Motorcycle make (brand)
                  MovoOutlinedField(motorcycleMake, { motorcycleMake = it }, "Motorcycle make (brand)", leadingIcon = { Text("🏍", fontSize = 16.sp) })

                  Spacer(Modifier.height(12.dp))

                  // Save motorcycle settings
                  MovoButton(text = if (settingsSaved) "✓ Settings saved" else "Save motorcycle settings", onClick = { saveMotorcycleSettings() })

                  Spacer(Modifier.height(16.dp))

                  // ── Document upload ──
                  Text("Verification documents", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MovoColor.TextPrimary)
                  Spacer(Modifier.height(8.dp))
                  val docButtons = listOf("Profile photo" to "profile", "Motorcycle" to "motorcycle", "ID front" to "id_front", "ID back" to "id_back", "Licence" to "license")
                  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    docButtons.chunked(3).forEach { row ->
                      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, kind) ->
                          OutlinedButton(
                            onClick = { documentKind = kind; documentPicker.launch("image/*") },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MovoColor.Green),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MovoColor.Divider),
                          ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                        }
                      }
                    }
                  }

                  Spacer(Modifier.height(16.dp))

                  // ── Online toggle ──
                  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                      Text("Online mode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MovoColor.TextPrimary)
                      Text("Location sharing active while online", fontSize = 11.sp, color = MovoColor.TextSecondary)
                    }
                    Switch(
                      checked = online,
                      onCheckedChange = { scope.launch { try { api.put("/api/rider/status", """{"online":$it}"""); refresh() } catch (e: Exception) { message = e.message ?: "Toggle failed" } } },
                      colors = SwitchDefaults.colors(checkedThumbColor = MovoColor.OnGreen, checkedTrackColor = MovoColor.Green)
                    )
                  }

                  if (earningsSummary.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(earningsSummary, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MovoColor.Gold)
                  }

                  Spacer(Modifier.height(8.dp))
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                      onClick = { scope.launch { try { val e = JSONObject(api.get("/api/rider/earnings?period=week")).getJSONObject("data"); earningsSummary = "This week: ${e.optDouble("total_earnings")} RWF · ${e.optInt("count")} deliveries" } catch (ex: Exception) { earningsSummary = ex.message ?: "Failed" } } },
                      modifier = Modifier.weight(1f).height(44.dp),
                      shape = RoundedCornerShape(14.dp),
                      colors = ButtonDefaults.outlinedButtonColors(contentColor = MovoColor.Green),
                      border = androidx.compose.foundation.BorderStroke(1.dp, MovoColor.Green)
                    ) { Text("Weekly earnings", fontWeight = FontWeight.Medium) }
                    OutlinedButton(
                      onClick = { api.clear(); profileExpanded = false },
                      modifier = Modifier.weight(1f).height(44.dp),
                      shape = RoundedCornerShape(14.dp),
                      colors = ButtonDefaults.outlinedButtonColors(contentColor = MovoColor.Error),
                      border = androidx.compose.foundation.BorderStroke(1.dp, MovoColor.Error)
                    ) { Text("Sign out", fontWeight = FontWeight.Medium) }
                  }
                }
              }
            }
          }
        }

        // ── NOT AUTHENTICATED ──
        if (!isAuthenticated) {
          Box(
            Modifier.fillMaxSize()
              .background(Brush.verticalGradient(listOf(MovoColor.GreenDark, MovoColor.Green)))
          ) {
            Box(
              Modifier.fillMaxSize()
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), radius = 800f))
            )
            Column(
              Modifier.fillMaxSize().padding(24.dp),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.15f),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Text("🏍", fontSize = 44.sp)
                }
              }
              Spacer(Modifier.height(16.dp))
              Text("MOVO Rider", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
              Text("Deliver with confidence", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
              Spacer(Modifier.height(36.dp))

              Surface(
                shape = RoundedCornerShape(28.dp),
                color = MovoColor.CardBg,
                shadowElevation = 16.dp,
              ) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                  if (verificationPhone == null && !registering) {
                    Text("Welcome back", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MovoColor.TextPrimary)
                    Text("Sign in to your rider account", fontSize = 14.sp, color = MovoColor.TextSecondary)
                    Spacer(Modifier.height(20.dp))
                    MovoOutlinedField(phone, { phone = it }, "Phone number", leadingIcon = { Text("📱", fontSize = 16.sp) })
                    Spacer(Modifier.height(8.dp))
                    MovoOutlinedField(password, { password = it }, "Password", leadingIcon = { Text("🔒", fontSize = 16.sp) }, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                      TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show", fontSize = 12.sp, color = MovoColor.Green) }
                    }
                    Spacer(Modifier.height(16.dp))
                    MovoButton("Sign in", { scope.launch { busy = true; try { val r = JSONObject(api.post("/api/auth/login", """{"phone":"$phone","password":"$password"}""")); api.saveToken(r.getJSONObject("data").getString("token")); this@MainActivity.requestLocation(); refresh() } catch (e: Exception) { message = "Sign-in failed: ${e.message}" }; busy = false } }, loading = busy)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { registering = true }, modifier = Modifier.fillMaxWidth()) {
                      Text("New rider? Create an account", color = MovoColor.Green, fontWeight = FontWeight.Medium)
                    }
                  }
                  if (registering && verificationPhone == null) {
                    Text("Create account", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MovoColor.TextPrimary)
                    Text("Join MOVO as a delivery rider", fontSize = 14.sp, color = MovoColor.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    MovoOutlinedField(fullName, { fullName = it }, "Full name", leadingIcon = { Text("👤", fontSize = 16.sp) })
                    Spacer(Modifier.height(6.dp))
                    MovoOutlinedField(phone, { phone = it }, "Phone number", leadingIcon = { Text("📱", fontSize = 16.sp) })
                    Spacer(Modifier.height(6.dp))
                    MovoOutlinedField(password, { password = it }, "Password (8+ characters)", leadingIcon = { Text("🔒", fontSize = 16.sp) }, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation())
                    Spacer(Modifier.height(6.dp))
                    MovoOutlinedField(nationalId, { nationalId = it }, "National ID", leadingIcon = { Text("🪪", fontSize = 16.sp) })
                    Spacer(Modifier.height(6.dp))
                    MovoOutlinedField(licenseNumber, { licenseNumber = it }, "Motorcycle licence", leadingIcon = { Text("📄", fontSize = 16.sp) })
                    Spacer(Modifier.height(6.dp))
                    MovoOutlinedField(motorcyclePlate, { motorcyclePlate = it }, "Motorcycle plate", leadingIcon = { Text("🏍", fontSize = 16.sp) })
                    Spacer(Modifier.height(12.dp))
                    MovoButton("Create rider account", { scope.launch { busy = true; try { val b = JSONObject().put("phone", phone).put("full_name", fullName).put("password", password).put("role", "rider").put("national_id", nationalId).put("license_number", licenseNumber).put("motorcycle_plate", motorcyclePlate).toString(); api.post("/api/auth/register", b); verificationPhone = phone; registering = false; message = "Enter the verification code sent to your phone." } catch (e: Exception) { message = e.message ?: "Registration failed" }; busy = false } }, loading = busy)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { registering = false }, modifier = Modifier.fillMaxWidth()) { Text("Already have an account? Sign in", color = MovoColor.Green, fontWeight = FontWeight.Medium) }
                  }
                  if (verificationPhone != null) {
                    Text("Verify phone", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MovoColor.TextPrimary)
                    Text("Enter the code sent to $verificationPhone", fontSize = 14.sp, color = MovoColor.TextSecondary)
                    Spacer(Modifier.height(20.dp))
                    MovoOutlinedField(otp, { otp = it }, "Six-digit verification code", leadingIcon = { Text("✉️", fontSize = 16.sp) })
                    Spacer(Modifier.height(16.dp))
                    MovoButton("Verify account", { scope.launch { busy = true; try { val r = JSONObject(api.post("/api/auth/verify-otp", JSONObject().put("phone", verificationPhone).put("otp", otp).toString())); api.saveToken(r.getJSONObject("data").getString("token")); verificationPhone = null; refresh() } catch (e: Exception) { message = e.message ?: "Verification failed" }; busy = false } }, loading = busy)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
