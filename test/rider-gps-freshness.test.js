const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const rider = name => fs.readFileSync(path.join(__dirname, '../android/rider-app/src/main/kotlin/com/movo/rider', name), 'utf8');

test('REST fixes retain measurement time and every send rejects stale GPS even when forced', () => {
  const service = rider('RiderLocationService.kt');
  assert.match(service, /put\("recorded_at",\s*Instant\.ofEpochMilli\(location\.time\)\.toString\(\)\)/);
  const send = service.slice(service.indexOf('private fun maybeSend'), service.indexOf('private fun isAcceptable'));
  assert.match(send, /if \(!isFresh\(location\)\) return/);
  assert.ok(send.indexOf('!isFresh') < send.indexOf('!force'));
  assert.match(service, /RiderGpsFreshness\.isFresh/);
});

test('online begins GPS acquisition first and denial cannot silently start tracking offline', () => {
  const controller = rider('home/RiderController.kt');
  assert.ok(controller.includes('if (status == "online") prepareOnline()'), 'prepare GPS before availability');
  assert.ok(controller.indexOf('prepareOnline()') < controller.indexOf('gateway.put("/api/rider/status"'));
  const activity = rider('MainActivity.kt');
  assert.ok(activity.includes('prepareOnline ='), 'production must wire acquisition');
  assert.ok(activity.includes('Location permission denied'), 'denial must be visible');
  assert.ok(activity.includes('state.profile.isOnline || hasActiveWork'), 'active jobs keep tracking');
  assert.ok(rider('model/RiderModels.kt').includes('reason == "stale_location"'), 'stale GPS must not show ready');
});

test('stationary riders acquire real new fixes rather than refreshing cached ones', () => {
  const service = rider('RiderLocationService.kt');
  assert.ok(service.includes('client.getCurrentLocation('), 'heartbeat must acquire GPS');
  assert.ok(service.includes('.setMaxUpdateAgeMillis(0)'), 'current request must not use cached GPS');
  assert.ok(service.includes('.setMinUpdateDistanceMeters(0f)'), 'stationary callbacks must arrive');
  assert.ok(service.includes('RiderGpsFreshness.shouldPublish('), 'dedupe must allow later measurements');
});
