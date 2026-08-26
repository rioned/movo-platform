const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const riderRoot = path.join(__dirname, '..', 'android', 'rider-app', 'src', 'main', 'kotlin', 'com', 'movo', 'rider');
const read = relative => fs.readFileSync(path.join(riderRoot, relative), 'utf8');

function source(relative, patterns) {
  const body = read(relative);
  for (const pattern of patterns) assert.match(body, pattern, `${relative} must match ${pattern}`);
  return body;
}

test('the rider offer card shows the decision facts and a bounded acceptance window', () => {
  source('ui/OfferSheet.kt', [
    /fun OfferSheet\(/, /CountdownRing/, /offerSecondsRemaining/, /offerProgress/,
    /Accept delivery/, /Decline/, /RouteCard/, /formatRwf/, /formatDistance/, /onExpired/
  ]);
  // Customer identity must stay hidden until the rider commits to the delivery.
  const offerSheet = read('ui/OfferSheet.kt');
  assert.doesNotMatch(offerSheet, /pickupPhone|destinationPhone|pickupName/);
});

test('the active delivery sheet exposes contact, navigation and verified handover', () => {
  source('ui/ActiveDeliverySheet.kt', [
    /fun ActiveDeliverySheet\(/, /Customer pickup/, /Delivery destination/,
    /Navigate to pickup/, /Navigate to destination/, /Call customer/,
    /Pickup code/, /Delivery code/, /Attach proof photo/, /Report a problem/,
    /DeliveryProgress/, /nextRiderAction/
  ]);
  source('MainActivity.kt', [/google\.com\/maps\/dir\/\?api=1&destination=/, /ACTION_DIAL/]);
});

test('the rider workflow is modelled once and drives every stage transition', () => {
  source('model/RiderModels.kt', [
    /fun nextRiderAction\(/, /"going-pickup"/, /"arrive-pickup"/, /"verify-pickup"/,
    /"in-transit"/, /"arrive-dest"/, /"complete"/, /requiresOtp/,
    /data class ActiveDelivery/, /data class DeliveryOffer/, /data class EarningsSummary/, /data class PerformanceStats/
  ]);
  source('home/RiderController.kt', [
    /class RiderController/, /interface RiderGateway/, /suspend fun refresh\(/,
    /suspend fun acceptOffer\(/, /suspend fun declineOffer\(/, /suspend fun advance\(/,
    /suspend fun reportIncident\(/, /\/api\/mobile\/v1\/rider\/home/, /\/api\/rider\/incidents/
  ]);
});

test('rider home offers one clear action and states availability honestly', () => {
  source('ui/RiderHomeScreen.kt', [
    /fun RiderHomeScreen\(/, /GO ONLINE/, /Go offline/, /Waiting for offers/,
    /No network/, /waiting to sync/, /RiderMap/
  ]);
  source('MainActivity.kt', [
    /RiderTab/, /Earnings/, /Safety/, /Account/, /RiderConnectivity/,
    /RiderLocationService/, /syncPending|pendingCount/
  ]);
});

test('earnings, performance and safety reporting are first-class rider screens', () => {
  source('ui/EarningsScreen.kt', [
    /fun EarningsScreen\(/, /Today/, /This week/, /This month/,
    /Acceptance/, /Cancellation/, /Rating/, /Net earnings/
  ]);
  source('ui/SafetyScreen.kt', [
    /fun SafetyScreen\(/, /Send SOS to MOVO/, /accident/, /unsafe_item/,
    /suspicious_customer/, /Call MOVO support/
  ]);
  source('ui/RiderProfileScreen.kt', [
    /fun RiderProfileScreen\(/, /National ID \(front\)/, /Riding licence/, /Motorcycle photo/,
    /Availability/, /On a break/, /Sign out/
  ]);
});

test('a stationary online rider keeps reporting location and stays discoverable, with tiered cadence (spec §13.6)', () => {
  // Regression: a displacement filter meant a rider waiting at a junction stopped
  // sending updates, went stale server-side and vanished from customer discovery.
  // Tiered tracking now does filter live fixes by distance/accuracy (to save
  // battery while idle), but the heartbeat must force a resend of the last fix
  // bypassing those filters, so a stationary rider never goes stale.
  const service = source('RiderLocationService.kt', [
    /startHeartbeat/, /lastSent/, /\/api\/rider\/location/,
    /setMinUpdateDistanceMeters/, /EXTRA_ACTIVE_WORK/, /ACTIVE_INTERVAL_MS/, /IDLE_INTERVAL_MS/,
    /applyTracking/, /interval_ms/, /min_distance_m/, /min_accuracy_m/
  ]);
  assert.match(service, /force\s*=\s*true/, 'the heartbeat must force a resend that bypasses the distance/accuracy filters');
  assert.match(service, /if \(!force/, 'distance/accuracy filters must be skippable by the forced heartbeat resend');
});

test('the rider map defers framing until layout instead of blocking the UI thread', () => {
  source('RiderMap.kt', [/width <= 0 \|\| height <= 0/, /post\(apply\)/, /infoWindow = null/]);
});

test('rider credentials and queued deliveries are stored encrypted, not in plain preferences', () => {
  source('session/RiderSession.kt', [/EncryptedSharedPreferences/, /MasterKey/, /pendingMutations/]);
  source('network/RiderApi.kt', [
    /class RiderApiException/, /suspend fun syncPending\(/, /enqueue\(/,
    /connectTimeout = 15_000/, /readTimeout = 15_000/, /Authorization/, /Bearer/
  ]);
  const api = read('network/RiderApi.kt');
  assert.doesNotMatch(api, /getSharedPreferences/, 'rider tokens must never fall back to plain preferences');
});

test('rider map routes rely on the shared MapService/RoutingService abstraction, not a hardcoded straight line (spec §63)', () => {
  const map = source('RiderMap.kt', [
    /import com\.movo\.design\.maps\.MapServices/, /import com\.movo\.design\.maps\.MapProvider/,
    /MapServices\.routing\(MapProvider\.OSM\)/, /routePoints/
  ]);
  assert.doesNotMatch(map, /router\.project-osrm\.org/, 'RiderMap must go through RoutingService, not call OSRM directly');
});

test('the rider app fires the named product analytics events (spec §78)', () => {
  source('analytics/RiderAnalytics.kt', [
    /class RiderAnalytics/, /AnalyticsLogger/, /\/api\/analytics\/events/
  ]);
  source('home/RiderController.kt', [
    /AnalyticsEvent\.RIDER_WENT_ONLINE/, /AnalyticsEvent\.RIDER_WENT_OFFLINE/,
    /AnalyticsEvent\.OFFER_ACCEPTED/, /AnalyticsEvent\.OFFER_DECLINED/,
    /AnalyticsEvent\.DELIVERY_COMPLETED/, /AnalyticsEvent\.RIDE_COMPLETED/
  ]);
  source('MainActivity.kt', [/RiderAnalytics\(api\)/]);
});
