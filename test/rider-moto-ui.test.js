const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = name => fs.readFileSync(path.join(__dirname, '../android/rider-app/src/main/kotlin/com/movo/rider', name), 'utf8');

test('rider onboarding is motorcycle-only and retains identity and OTP workflows', () => {
  const auth = source('ui/RiderAuthScreen.kt');
  assert.match(auth, /MotoHero\(/);
  assert.match(auth, /vehicleType = "motorcycle"/);
  assert.doesNotMatch(auth, /SegmentOption\("car"|mutableStateOf\("car"\)|MovoField\(car/);
  for (const token of ['OtpField(', 'PhoneField(', 'nationalId.trim()', 'licenseNumber.trim()', 'onSubmit(']) assert.ok(auth.includes(token), token);
});

test('map-first home limits moto hero to offline idle and keeps safety gates', () => {
  const home = source('ui/RiderHomeScreen.kt');
  const idle = home.slice(home.indexOf('private fun IdleSheet'));
  assert.ok(idle.includes('MotoHero('), 'offline idle needs the compact moto hero');
  assert.ok(idle.indexOf('MotoHero(') > idle.indexOf('} else {'));
  assert.ok(!home.slice(0, home.indexOf('private fun IdleSheet')).includes('MotoHero('));
  for (const token of ['RiderMap(', 'ActiveDeliverySheet(', 'ActiveRideSheet(', 'OfferSheet(', 'RideOfferSheet(', 'onReportIssue = onReportIssue', 'state.profile.isApproved && online && !busy', 'Helmet on. Phone mounted. Ride safely.']) assert.ok(home.includes(token), token);
  assert.ok(home.includes('maxHeight * 0.58f'), 'idle panel must preserve map space');
});

test('earnings uses a scrollable ledger with real values and accessible performance grid', () => {
  const earnings = source('ui/EarningsScreen.kt');
  assert.ok(earnings.includes('item {'), 'summary must scroll with the ledger');
  assert.equal((earnings.match(/LazyColumn\(/g) || []).length, 1);
  assert.ok(earnings.includes('Your work, in numbers'));
  assert.ok(earnings.includes('summary?.period == period'), 'never label stale totals as a new period');
  for (const token of ['onPeriodChange(it)', 'formatRwf(', 'entry.route', 'formatTimestamp(it)', 'stats.acceptanceRate', 'stats.cancellationRate', 'Modifier.weight(1f)']) assert.ok(earnings.includes(token), token);
});

test('account offers motorcycle details only and navigation retains safety access', () => {
  const profile = source('ui/RiderProfileScreen.kt');
  assert.ok(!profile.includes('SectionHeader("Car")'), 'car editor is not part of the Rwanda moto app');
  for (const token of ['onSaveMotorcycle(', 'onUploadDocument(kind)', 'Verification documents', 'onSignOut']) assert.ok(profile.includes(token), token);
  const activity = source('MainActivity.kt');
  assert.ok(activity.includes('Home("Map"'));
  assert.ok(activity.includes('Safety("Safety"'));
  assert.ok(activity.includes('onReportIssue = { tab = RiderTab.Safety }'));
});
