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
    /data class Coordinate/, /data class CustomerProfile/,
    /data class Delivery/, /data class TrackingSnapshot/, /data class SendDraft/
  ]);
});

test('customer auth and parcel navigation replace the legacy passenger shell', () => {
  source('src/main/kotlin/com/movo/customer/auth/AuthScreen.kt', [
    /enum class AuthMode/, /PhoneField/, /Sign in/, /Create account/,
    /OtpField/, /Verify/, /Full name/, /optional/i, /isLoading/
  ]);
  source('src/main/kotlin/com/movo/customer/profile/ProfileScreen.kt', [
    /fun ProfileScreen\(/, /Sign out/, /Connected|Reconnecting/, /BackHandler/, /Close/,
    /Kinyarwanda/, /\/api\/tickets/
  ]);
  source('src/main/kotlin/com/movo/customer/MainActivity.kt', [
    /ParcelApp/, /ParcelTheme/, /AndroidEntryPoint/
  ]);
  source('src/main/kotlin/com/movo/customer/parcel/ParcelApp.kt', [/NavHost/, /PhoneScreen/, /OtpScreen/, /"orders"/, /"profile"/, /"tracking"/]);
  source('src/main/kotlin/com/movo/customer/parcel/data/LiveParcelRepository.kt', [/api\/auth\/me/, /CustomerSession/, /session.clear\(\)/]);
});

test('Task 6 send flow quotes finite coordinates and requests one zone-based dispatch with the customer\'s chosen rider, idempotently', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /fun MapFirstSendScreen\(/, /requestCurrent/, /RequestPermission/,
    /Pickup/, /Destination/, /Sender/, /Receiver/,
    /\/api\/deliveries\/price/, /isFinite/, /Quote/, /price|totalCharge/,
    /fun ConfirmRequestSheet\(/, /Idempotency|idempotencyKey/, /api\.post\("\/api\/deliveries"/,
    /submitting/
  ]);
  // The customer picks the rider they hand the parcel to. The preference rides in the
  // create call, and is omitted entirely when they chose "any available rider" so
  // automatic dispatch is untouched for anyone who skipped the list.
  const sendScreen = read('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt');
  assert.match(sendScreen, /preferredRiderId\?\.let \{ put\("preferred_rider_id", it\) \}/, 'the chosen rider must be sent as a first-refusal preference');
  assert.match(sendScreen, /onSelectRider/, 'the discovery sheet must let the customer choose a rider');
  source('src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt', [/parcel/, /document/]);
  source('src/main/kotlin/com/movo/customer/parcel/ParcelViewModel.kt', [/repository.create\(draft, id\)/, /journey.requestId\(\)/]);
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
  source('src/main/kotlin/com/movo/customer/parcel/ParcelApp.kt', [/OrdersScreen/, /TrackingScreen/, /repeatOnLifecycle/, /pollTracking/]);
  source('src/main/kotlin/com/movo/customer/parcel/data/LiveParcelRepository.kt', [/api\/mobile\/v1\/customer\/deliveries\?role=all/]);
});

test('remediation preserves the already-created delivery and durable request state', () => {
  source('src/main/kotlin/com/movo/customer/session/CustomerSession.kt', [
    /saveJourney/, /restoreJourney/, /clearJourney/, /creationIdempotencyKey/,
    /deliveryId/, /SendDraft/, /Quote/
  ]);
  // There is no rider-replacement flow under blind dispatch: a resumed journey
  // that already produced a delivery id just resumes tracking, never re-requests.
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /existingDeliveryId/, /resumeId/, /onTracking\(resumeId\)/,
    /restoreJourney/, /RequestMultiplePermissions/, /shouldShowRequestPermissionRationale/,
    /pickup_lat/, /service_type/, /api\.post\("\/api\/deliveries"/, /saveJourney/, /submitting/
  ]);
source("src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt", [/Delivery instructions/, /Cash/, /Mobile money/]);
});

test('remediation enforces customer sessions and models authoritative relationships and vehicles', () => {
  source('src/main/kotlin/com/movo/customer/model/CustomerModels.kt', [
    /val role: String/, /relationship/, /orderNo/, /vehicleMake/, /vehicleModel/,
    /vehiclePlate/, /vehicleColor/, /pickup_name/, /serverTime/, /createdAt/
  ]);
  source('src/main/kotlin/com/movo/customer/parcel/data/LiveParcelRepository.kt', [/it.role == "customer"/, /e.code\(\) == 401/, /session.clear\(\)/]);
  source('src/main/kotlin/com/movo/customer/parcel/ParcelApp.kt', [/ConnectivityObserver/, /collectAsStateWithLifecycle/]);
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

test('the confirm step offers the chosen rider as a preference and still states the automatic fallback', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /fun ConfirmRequestSheet\(/, /PriceSummary/, /onConfirm/, /submitting/, /idempotencyKey|creationKey/,
    // The fallback is still automatic, so the copy that promises a nearest-rider match
    // must stay true even when a specific rider was chosen.
    /nearest available rider/i
  ]);
  // Choosing a rider is a preference, never a lock: the create call carries the id, and
  // the discovery sheet is what offers the choice.
  const sendScreen = read('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt');
  assert.match(sendScreen, /preferredRiderId/, 'the chosen rider must be carried into the request');
  assert.match(sendScreen, /controller\.select\(/, 'the customer must be able to choose a rider');
});

test('map-first discovery sheet lists riders to choose from and preserves honest gated availability', () => {
  source('src/main/kotlin/com/movo/customer/send/DiscoverySheet.kt', [
    /Finding your pickup/, /Finding riders near you/, /No riders near this pickup/,
    /Rider availability needs a connection/, /Scan again/, /Adjust pickup/,
    /snapshot\.canContinue/, /riders nearby/, /CircularProgressIndicator/,
    /ValueAnimator\.areAnimatorsEnabled/, /reducedMotion/,
    // The chooser itself: every listed rider is pickable, and "any available rider"
    // restores automatic dispatch.
    /Any available rider/, /onSelectRider/, /RiderOption/, /snapshot\.selectedRider\b/,
    /etaMinutes/, /ratingCount|RatingStars/
  ]);
  // A rider row must never expose a way to contact them before they accept the job.
  const sheet = read('src/main/kotlin/com/movo/customer/send/DiscoverySheet.kt');
  assert.doesNotMatch(sheet, /rider\.phone|pickupPhone|contactRider/, 'a nearby rider has no contactable surface before acceptance');
  source('src/main/kotlin/com/movo/customer/map/CustomerMap.kt', [
    /clipToBounds/, /ic_movo_motorcycle/, /discoveryActive/, /showPickupHalo/,
    /withInfiniteAnimationFrameMillis/, /PULSE_DURATION_MS/, /ValueAnimator[.]areAnimatorsEnabled/,
    // Nearby riders are drawn on the map, with the chosen one added last so it wins
    // any overlap with the others.
    /nearbyRiders/, /selectedRiderId/, /Your rider/
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
  source('src/main/kotlin/com/movo/customer/auth/AuthScreen.kt', [/import com\.movo\.design\.MotoHero/, /MotoHero\(/]);
  const hero = fs.readFileSync(path.join(root, 'android/design/src/main/kotlin/com/movo/design/MotoHero.kt'), 'utf8');
  assert.match(hero, /Text\(title, color = Color\.White/);
  assert.match(hero, /Text\(subtitle, color = Color\(0xFFC1D2CB\)/);
  assert.match(hero, /background\(Brush\.linearGradient\(listOf\(Color\(0xFF102C24\), Color\(0xFF081914\)/);
  source('src/main/kotlin/com/movo/customer/parcel/ui/ParcelTheme.kt', [/BackgroundDark/, /TextPrimary/, /containerColor = MovoGreenPressed/]);
});

test('MOVO parcel colors and durable session compatibility', () => {
  source('src/main/kotlin/com/movo/customer/parcel/ui/ParcelTheme.kt', [/0xFF1FAE59/, /0xFF0E1412/, /0xFF182019/, /0xFFF5F7F6/]);
  // The send journey does carry the customer's chosen rider, so this guard is about the
  // parcel session staying free of the *ride-booking* domain (a different product line
  // with its own draft shape) — not about the word "rider" appearing anywhere.
  const session = read('src/main/kotlin/com/movo/customer/session/CustomerSession.kt');
  assert.doesNotMatch(session, /RideBooking|RideModels|ride_offers|RideDraft|DiscoverySnapshot/, 'the parcel session must not absorb ride-booking state');
  assert.match(session, /preferredRiderId/, 'the chosen rider is part of the send journey and must persist with it');
});

test('the customer app resolves the pickup address through the shared GeocodingService abstraction (spec §63)', () => {
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /import com\.movo\.design\.maps\.MapServices/, /import com\.movo\.design\.maps\.MapProvider/,
    /MapServices\.geocoding\(MapProvider\.OSM/, /reverseGeocode\(/
  ]);
});

test('the customer app fires the named product analytics events (spec §78)', () => {
  source('src/main/kotlin/com/movo/customer/analytics/CustomerAnalytics.kt', [
    /class CustomerAnalytics/, /AnalyticsLogger/, /\/api\/analytics\/events/
  ]);
  source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
    /AnalyticsEvent\.QUOTE_VIEWED/, /AnalyticsEvent\.DELIVERY_CONFIRMED/
  ]);
  source('src/main/kotlin/com/movo/customer/ride/RideBookingScreen.kt', [
    /AnalyticsEvent\.QUOTE_VIEWED/, /AnalyticsEvent\.RIDE_REQUESTED/
  ]);
});
