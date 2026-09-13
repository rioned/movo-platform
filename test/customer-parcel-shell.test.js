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
