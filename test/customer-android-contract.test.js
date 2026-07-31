const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const customer = path.join(root, 'android/customer-app');
const read = relative => fs.readFileSync(path.join(customer, relative), 'utf8');

function source(relative, patterns) {
  const body = read(relative);
  for (const pattern of patterns) assert.match(body, pattern, `${relative} must match ${pattern}`);
  return body;
}

test('Task 4 customer foundation has native map, location, realtime, and networking contracts', () => {
  source('build.gradle.kts', [
    /play-services-location:21\.3\.0/, /lifecycle-runtime-ktx:2\.8\.7/,
    /kotlinx-coroutines-android:1\.9\.0/, /socket\.io-client:2\.1\.1/,
    /osmdroid-android:6\.1\.18/, /coil-compose:2\.7\.0/
  ]);
  source('src/main/AndroidManifest.xml', [
    /android\.permission\.INTERNET/, /android\.permission\.ACCESS_FINE_LOCATION/,
    /android\.permission\.ACCESS_COARSE_LOCATION/
  ]);
  source('src/main/kotlin/com/movo/customer/network/CustomerApi.kt', [
    /class CustomerApiException/, /suspend fun get\(/, /suspend fun post\(/, /suspend fun put\(/,
    /connectTimeout = 15_000/, /readTimeout = 15_000/, /JSONObject\(body\)/,
    /optString\("error"/, /take\(240\)/, /Authorization/, /Bearer/
  ]);
  source('src/main/kotlin/com/movo/customer/session/CustomerSession.kt', [
    /class CustomerSession/, /fun token\(\)/, /fun save\(/, /fun clear\(\)/,
    /EncryptedSharedPreferences/, /MasterKey/, /profileName/, /profilePhone/
  ]);
  source('src/main/kotlin/com/movo/customer/realtime/CustomerRealtime.kt', [
    /class CustomerRealtime/, /fun subscribe\(deliveryId:/, /subscribe_delivery/,
    /delivery_update/, /rider_location/, /EVENT_RECONNECT/, /onReconnect/, /disconnect\(\)/
  ]);
  source('src/main/kotlin/com/movo/customer/location/CustomerLocation.kt', [
    /class CustomerLocation/, /fun requestCurrent\(/, /FusedLocationProviderClient/,
    /ACCESS_FINE_LOCATION/, /getCurrentLocation/
  ]);
  source('src/main/kotlin/com/movo/customer/map/CustomerMap.kt', [
    /fun CustomerMap\(/, /AndroidView/, /MapView/, /CopyrightOverlay/,
    /MapEventsOverlay/, /longPressHelper/, /Marker/, /motorcycle/i, /BoundingBox/
  ]);
  source('src/main/kotlin/com/movo/customer/model/CustomerModels.kt', [
    /data class Coordinate/, /data class CustomerProfile/, /data class NearbyRider/,
    /data class Delivery/, /data class TrackingSnapshot/, /data class SendDraft/
  ]);
});

test('Task 5 auth coordinator restores session and exposes four customer workspaces', () => {
  source('src/main/kotlin/com/movo/customer/auth/AuthScreen.kt', [
    /enum class AuthMode/, /PhoneField/, /Sign in/, /Create account/,
    /OtpField/, /Verify/, /full_name/, /optional/i, /isLoading/
  ]);
  source('src/main/kotlin/com/movo/customer/profile/ProfileScreen.kt', [
    /fun ProfileScreen\(/, /Sign out/, /Connected|Reconnecting/, /BackHandler/, /Close/,
    /Kinyarwanda/, /\/api\/tickets/
  ]);
  source('src/main/kotlin/com/movo/customer/MainActivity.kt', [
    /enum class CustomerDestination/, /Send/, /Receive/, /Activity/, /Profile/, /Tracking/,
    /\/api\/auth\/me/, /CustomerSession/, /AuthScreen/, /NavigationBar/,
    /BackHandler/, /session\.clear\(\)/
  ]);
});

test('Task 6 send flow quotes finite coordinates and selects exactly one rider idempotently', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /fun MapFirstSendScreen\(/, /requestCurrent/, /RequestPermission/,
    /Pickup/, /Destination/, /Sender/, /Receiver/,
    /\/api\/deliveries\/price/, /isFinite/, /Quote/, /price|totalCharge/
  ]);
  source('src/main/kotlin/com/movo/customer/send/RiderSelectionScreen.kt', [
    /fun RiderSelectionScreen\(/, /preferred_rider_id/,
    /Idempotency|idempotencyKey/, /\/api\/deliveries/, /select-rider/,
    /awaiting_rider_selection/, /declined/, /expired/, /submitting/
  ]);
  source('src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt', [/parcel/, /document/]);
  source('src/main/kotlin/com/movo/customer/MainActivity.kt', [/SendScreen/, /onTracking/]);
});

test('Task 7 receive, activity, and tracking use HTTP authority after every realtime signal', () => {
  source('src/main/kotlin/com/movo/customer/receive/ReceiveScreen.kt', [
    /fun ReceiveScreen\(/, /\/api\/mobile\/v1\/customer\/home/, /activeReceived/,
    /received/i, /ShimmerCard/, /No deliveries/, /onTrack/
  ]);
  source('src/main/kotlin/com/movo/customer/activity/ActivityScreen.kt', [
    /fun ActivityScreen\(/, /role=(sent|\$\{filter)/, /Sent/, /Received/, /All/,
    /sender/i, /receiver/i, /onTrack/
  ]);
  source('src/main/kotlin/com/movo/customer/tracking/TrackingScreen.kt', [
    /fun TrackingScreen\(/, /\/api\/deliveries\/\$deliveryId\/track/,
    /CustomerRealtime/, /subscribe\(deliveryId\)/, /onReconnect/, /onUpdate/,
    /CustomerMap/, /riderLocation/, /fresh|stale/i, /timeline|events/i,
    /ACTION_DIAL/, /google\.com\/maps\/dir/, /deliveryOtp|delivery_otp/,
    /Cancel delivery/, /Rate delivery/, /Support/, /disconnect\(\)/
  ]);
  source('src/main/kotlin/com/movo/customer/MainActivity.kt', [
    /ReceiveScreen/, /ActivityScreen/, /TrackingScreen/,
    /activeSent/, /activeReceived/, /\/api\/mobile\/v1\/customer\/home/
  ]);
});

test('remediation preserves the existing selected delivery and durable request state', () => {
  source('src/main/kotlin/com/movo/customer/session/CustomerSession.kt', [
    /saveJourney/, /restoreJourney/, /clearJourney/, /creationIdempotencyKey/,
    /replacementIdempotencyKey/, /deliveryId/, /SendDraft/, /Quote/
  ]);
  source('src/main/kotlin/com/movo/customer/send/RiderSelectionScreen.kt', [
    /existingDeliveryId/, /selectReplacement\(/, /api\.put\("\/api\/deliveries\/\$existingDeliveryId\/select-rider"/,
    /submitting/, /riderId/
  ]);
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /restoreJourney/, /RequestMultiplePermissions/, /shouldShowRequestPermissionRationale/,
    /pickup_lat/, /service_type/, /api\.post\("\/api\/deliveries"/, /saveJourney/
  ]);
source("src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt", [/Delivery instructions/, /Cash/, /Mobile money/]);
});

test('remediation enforces customer sessions and models authoritative relationships and vehicles', () => {
  source('src/main/kotlin/com/movo/customer/model/CustomerModels.kt', [
    /val role: String/, /relationship/, /orderNo/, /vehicleMake/, /vehicleModel/,
    /vehiclePlate/, /vehicleColor/, /pickup_name/, /serverTime/, /createdAt/
  ]);
  source('src/main/kotlin/com/movo/customer/MainActivity.kt', [
    /(it|profile)\.role != "customer"/, /CustomerApiException/, /status == 401/, /session\.profile\(\)/,
    /ConnectivityObserver/, /collectAsState/
  ]);
  source('src/main/kotlin/com/movo/customer/activity/ActivityScreen.kt', [/delivery\.relationship/, /orderNo/]);
  source('src/main/kotlin/com/movo/customer/receive/ReceiveScreen.kt', [/orderNo/, /Assigned to/, /delivery\.rider/]);
});

test('remediation uses lifecycle-safe motorcycle maps and subscribed network state', () => {
  source('src/main/kotlin/com/movo/customer/map/CustomerMap.kt', [
    /R\.drawable\.ic_movo_motorcycle/, /LifecycleEventObserver/, /ON_RESUME/, /ON_PAUSE/,
    /Configuration\.getInstance\(\)\.userAgentValue/, /onRelease/
  ]);
  source('src/main/res/drawable/ic_movo_motorcycle.xml', [/<vector/, /<path/]);
  source('src/main/kotlin/com/movo/customer/connectivity/ConnectivityObserver.kt', [
    /registerDefaultNetworkCallback/, /unregisterNetworkCallback/, /callbackFlow/, /awaitClose/
  ]);
  source('src/main/kotlin/com/movo/customer/realtime/CustomerRealtime.kt', [/Handler\(Looper\.getMainLooper\(\)\)/, /post/]);
});

test('remediation serializes authoritative tracking and wires rating support and valid cancellation', () => {
  source('src/main/kotlin/com/movo/customer/tracking/TrackingScreen.kt', [
    /Mutex/, /withLock/, /while \(isActive\)/, /delay\(/, /relationship/,
    /optString\("status"/, /created_at/, /\/api\/ratings/, /score/, /review/,
    /\/api\/tickets/, /category/, /subject/, /description/, /priority/,
    /setOf\("created", "searching", "assigned"\)/
  ]);
  const tracking = read('src/main/kotlin/com/movo/customer/tracking/TrackingScreen.kt');
  assert.doesNotMatch(tracking, /onClick\s*=\s*\{\s*\}/);
  assert.doesNotMatch(tracking, /takeLast\(9\)/);
  assert.doesNotMatch(tracking, /\*\*\*\*/);
});

test('remediation keeps customer screens usable on narrow layouts', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [/FlowRow|Column/, /fillMaxWidth/]);
  source('src/main/kotlin/com/movo/customer/tracking/TrackingScreen.kt', [/verticalScroll\(rememberScrollState\(\)\)/, /fillMaxWidth\(\)/]);
  source('src/main/kotlin/com/movo/customer/auth/AuthScreen.kt', [/imePadding/, /navigationBarsPadding/]);
});

  test('pickup and destination selectors stay above the embedded map on a narrow device', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /Column\(Modifier\.fillMaxSize\(\)\)/, /CustomerMap/, /weight\(1f\)/, /DiscoverySheet/
  ]);
  source('src/main/kotlin/com/movo/customer/map/CustomerMap.kt', [/modifier = modifier\.clipToBounds\(\)/]);
});

test('Refreshes riders after quote and before exclusive rider choice', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /onRefreshRiders|refreshAvailability/, /refreshing/, /preferred_rider_id/,
    /409|conflict/, /idempotency|replacementIdempotencyKey/
  ]);
  source('src/main/kotlin/com/movo/customer/send/RiderSelectionScreen.kt', [
    /riders:/, /refreshing:/, /submitting:/, /onRefreshRiders/, /onSelectRider/,
    /No riders available now/, /PriceSummary/, /RatingStars/
  ]);
});

test('map-first discovery sheet exposes honest gated rider availability', () => {
  source('src/main/kotlin/com/movo/customer/send/DiscoverySheet.kt', [
    /Finding your pickup/, /Finding riders near you/, /No riders near this pickup/,
    /Rider availability needs a connection/, /Scan again/, /Adjust pickup/,
    /snapshot.canContinue/, /riders nearby/, /CircularProgressIndicator/,
    /ValueAnimator\.areAnimatorsEnabled/, /reducedMotion/
  ]);
  source('src/main/kotlin/com/movo/customer/map/CustomerMap.kt', [
    /clipToBounds/, /ic_movo_motorcycle/, /discoveryActive/, /showPickupHalo/,
    /withInfiniteAnimationFrameMillis/, /PULSE_DURATION_MS/, /ValueAnimator[.]areAnimatorsEnabled/
  ]);
});

test('Send opens with rider discovery before request details', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /RiderDiscoveryController/, /customerNearbyRiderSource/, /DiscoverySheet/, /CustomerMap/,
    /snapshot[.]canContinue[(][)]/, /RequestDetailsSheet/, /invalidate/, /SendStage/,
    /pickup_lat/, /pickup_lng/, /dest_lat/, /dest_lng/, /service_type/
  ]);
  source('src/main/kotlin/com/movo/customer/send/SendScreen.kt', [/MapFirstSendScreen/]);
  source('src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt', [
    /Destination/, /Sender/, /Receiver/, /parcel/, /document/,
    /Delivery instructions/, /Cash/, /Mobile money/, /Get quote/
  ]);
  const requestDetails = read('src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt');
  assert.doesNotMatch(requestDetails, /api[.]/);
  const sendScreen = read('src/main/kotlin/com/movo/customer/send/SendScreen.kt');
  assert.match(sendScreen, /fun SendScreen[(][^)]*online[^)]*[)]/);
});

test('the customer can place a destination pin the rider will actually navigate to', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /PickDestination/, /Confirm destination/, /copy\(destination = point\)/,
    /destination\?\.isFinite == true/
  ]);
  source('src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt', [
    /onPickDestination/, /Set destination on map/, /Destination pin placed/
  ]);
});

test('the map never blocks the UI thread and the journey is never written on it', () => {
  // Regression: zoomToBoundingBox spins in osmdroid when the map has no layout yet
  // or the box has no area, which froze the customer app on launch.
  source('src/main/kotlin/com/movo/customer/map/CustomerMap.kt', [
    /fun MapView\.fitToPoints/, /width <= 0 \|\| height <= 0/, /degenerate/, /post\(apply\)/
  ]);
  const map = read('src/main/kotlin/com/movo/customer/map/CustomerMap.kt');
  assert.doesNotMatch(map, /zoomToBoundingBox\(BoundingBox\.fromGeoPoints/);
  // Encrypted storage is far too slow for the main thread on every keystroke.
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /withContext\(Dispatchers\.IO\)/, /delay\(400\)/
  ]);
  const session = read('src/main/kotlin/com/movo/customer/session/CustomerSession.kt');
  assert.doesNotMatch(session, /\.commit\(\)/, 'session writes must be asynchronous');
});

test('branded surfaces stay legible in dark mode', () => {
  // Regression: the fixed forest gradient with theme `onPrimary` text rendered
  // dark green on dark green when the device was in dark mode.
  source('src/main/kotlin/com/movo/customer/auth/AuthScreen.kt', [/color = Color\.White/]);
  source('src/main/kotlin/com/movo/customer/MainActivity.kt', [/MovoPalette\.Forest/, /color = Color\.White/]);
});

test('MOVO map-first colors and forced-restore rescan', () => {
  source('src/main/kotlin/com/movo/customer/MainActivity.kt', [
    /0xFFFCFCFA/, /0xFF086B4D/, /0xFF19A974/, /0xFF151817/, /0xFFF5A623/,
    /NavigationBarItem|navigationBar.*send/i
  ]);
  // The session is persistence only: it must not reach into rider discovery or
  // selection. It does now persist a ride journey alongside the send journey —
  // "ride" is a domain noun here, not the UI leak this guards against.
  const session = read('src/main/kotlin/com/movo/customer/session/CustomerSession.kt');
  assert.doesNotMatch(session, /nearby|RiderSelection|DiscoverySnapshot|DiscoveryPhase/);
});
