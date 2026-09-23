const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = path.join(__dirname, '../android/customer-app/src/main/kotlin/com/movo/customer');
const read = file => fs.existsSync(path.join(base,file)) ? fs.readFileSync(path.join(base,file),'utf8') : '';

test('customer launcher exclusively hosts parcel navigation, not passenger booking', () => {
  const main = read('MainActivity.kt');
  assert.match(main, /ParcelApp\(/);
  assert.doesNotMatch(main, /RideBookingScreen|RideTrackingScreen|CustomerDestination\.Ride/);
});
test('parcel shell exposes every booking and account destination', () => {
  const app = read('parcel/ParcelApp.kt');
  for (const route of ['splash','onboarding','phone','otp','setup','home','pickup','dropoff','route','package','recipient','review','finding','tracking','completed','orders','detail','profile','addresses','settings','support','safety','information','notifications','discounts','improve']) {
    assert.ok(app.includes('"'+route+'"'), 'Missing destination '+route);
  }
  assert.match(app, /NavHost/);
});

// The launcher hosts ParcelApp, so the rider chooser has to live in THIS flow. An
// earlier version of the feature was built into MapFirstSendScreen, which the app
// never navigates to — the customer saw no riders at all. These assertions pin the
// chooser to the screen the app actually shows.
test('the live parcel booking flow lets the customer see and choose a nearby rider', () => {
  const viewModel = read('parcel/ParcelViewModel.kt');
  // The nearby-riders call must keep the rider list, not just read in_service_area.
  assert.match(viewModel, /nearbyRiders/, 'parcel state must carry the nearby riders');
  assert.match(viewModel, /fun chooseRider\(/, 'the customer must be able to choose a rider');
  assert.match(viewModel, /fun refreshNearbyRiders\(/, 'the list must be refreshable at review time');
  assert.match(viewModel, /toNearbyRiders\(\)/, 'the riders array must be parsed from the response');
  // A choice that has gone offline must be released rather than sent and rejected.
  assert.match(viewModel, /preferredRiderId = null, preferredRiderLabel = null/, 'a stale rider choice must be dropped on refresh');

  const review = read('parcel/delivery/BookingScreens.kt');
  assert.match(review, /Choose your rider/, 'the review screen must show the chooser');
  assert.match(review, /Any available rider/, 'automatic dispatch must stay one tap away');
  assert.ok(/fun ReviewScreen\([^)]*nearbyRiders/s.test(review), 'ReviewScreen must receive the rider list');
  // A rider row is a chooser entry, never a contact card.
  assert.doesNotMatch(review, /rider\.phone|riderPhone/, 'a nearby rider must not be contactable before accepting');

  const app = read('parcel/ParcelApp.kt');
  assert.match(app, /viewModel::chooseRider/, 'the review route must wire the chooser');
  assert.match(app, /refreshNearbyRiders\(\)/, 'opening review must refresh the rider list');

  const wire = read('parcel/data/ParcelWire.kt');
  assert.match(wire, /preferred_rider_id/, 'the booking payload must carry the chosen rider');
  assert.match(wire, /d\.preferredRiderId\?\.let/, 'the field must be omitted when no rider was chosen');

  const models = read('parcel/domain/ParcelModels.kt');
  assert.match(models, /class NearbyParcelRider/, 'the chooser needs a rider model');
  assert.match(models, /val preferredRiderId: String\? = null/, 'the draft must carry the choice');
});
