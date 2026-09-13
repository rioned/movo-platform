const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../android/customer-app/src/main/kotlin/com/movo/customer');
const source = file => fs.readFileSync(path.join(root, file), 'utf8');

test('customer entry presents Rwanda motorcycle identity and retains authentication', () => {
  const auth = source('auth/AuthScreen.kt');
  assert.match(auth, /MotoHero\(/);
  assert.match(auth, /Kigali/);
  assert.doesNotMatch(auth, /Maputo|Mozambique/);
  for (const flow of ['AuthMode.LOGIN', 'AuthMode.REGISTER', 'AuthMode.VERIFICATION', 'onSubmit(AuthRequest(', 'OtpField(', 'PhoneField(']) assert.ok(auth.includes(flow), flow);
});

test('booking offers only provider motorcycle quotes and fails closed without them', () => {
  const booking = source('ride/RideBookingScreen.kt');
  assert.ok(booking.includes('.filter { it.isMotorcycle }'), 'filter before displaying quotes');
  assert.ok(booking.includes('rideTypes.firstOrNull { it.id == selectedRideTypeId && it.isMotorcycle }'), 'revalidate selection before POST');
  assert.ok(booking.includes('Motorcycle rides unavailable'), 'honest empty state');
  assert.ok(booking.includes('.put("ride_type_id", selectedType.id)'), 'server-provided ID');
  assert.ok(!booking.includes('.put("vehicle_type"'), 'do not invent unsupported request fields');
  assert.ok(booking.includes('Kigali Convention Centre'));
  assert.ok(booking.includes('MotoHero('));
  assert.ok(booking.includes('BackHandler('));
  assert.ok(!/standard|economy|comfort|Praça/.test(booking));
  for (const route of ['/api/rides/estimate', '/api/rides']) assert.ok(booking.includes(route));
  const models = source('model/RideModels.kt');
  assert.ok(models.includes('val isMotorcycle: Boolean'));
  assert.ok(models.includes('setOf("moto", "motorcycle")'));
  assert.ok(!models.includes('optString("currency", "RWF")'), 'never fabricate Rwanda currency');
});

test('customer navigation and delivery discovery carry the moto identity without bypassing live gates', () => {
  const shell = source('parcel/ParcelApp.kt');
  assert.ok(source('MainActivity.kt').includes('ParcelApp('));
  assert.ok(source('parcel/delivery/BookingScreens.kt').includes('TwoWheeler'));
  assert.ok(source('parcel/ui/ParcelTheme.kt').includes('MovoGreen'));
  for (const destination of ['home', 'pickup', 'orders', 'profile', 'tracking']) assert.ok(shell.includes(`"${destination}"`));
  assert.doesNotMatch(shell, /RideBookingScreen|RideTrackingScreen/);
  const send = source('send/MapFirstSendScreen.kt');
  assert.ok(send.includes('Parcels & documents • Kigali'));
  assert.ok(send.includes('api.post("/api/deliveries", body, creationKey)'));
  const discovery = source('send/DiscoverySheet.kt');
  assert.ok(discovery.includes('SEND BY MOTO'));
  assert.ok(discovery.includes('enabled = snapshot.canContinue()'));
  assert.ok(discovery.includes('snapshot.riderCount'));
  const api = source('network/CustomerApi.kt');
  assert.ok(api.includes('BuildConfig.API_BASE_URL'));
});

test('the destination cannot move while its fare is being fetched', () => {
  const booking = source('ride/RideBookingScreen.kt');
  assert.ok(/if \(!loading\) \{\s*destination = point/.test(booking));
});
