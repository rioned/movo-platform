const test = require('node:test');
const assert = require('node:assert/strict');
const { loadRuntimeConfig, evaluateReadiness } = require('../src/config/runtime');

test('production runtime configuration requires a JWT secret and parses pilot settings', () => {
  assert.throws(() => loadRuntimeConfig({ NODE_ENV: 'production' }), /JWT_SECRET must be set/);
  const config = loadRuntimeConfig({
    NODE_ENV: 'test', JWT_SECRET: 'test-secret',
    ALLOWED_ORIGINS: 'http://localhost:3000,https://pilot.movo.rw',
    DISPATCH_OFFER_TIMEOUT_SEC: '25', DISPATCH_RADIUS_KM: '4',
    DB_PATH: '/tmp/movo-test.db', MAP_PROVIDER: 'sandbox', PAYMENT_PROVIDER: 'sandbox', SMS_PROVIDER: 'sandbox'
  });
  assert.equal(config.dbPath, '/tmp/movo-test.db');
  assert.deepEqual(config.allowedOrigins, ['http://localhost:3000', 'https://pilot.movo.rw']);
  assert.equal(config.dispatch.offerTimeoutSeconds, 25);
  assert.equal(config.dispatch.initialRadiusKm, 4);
  assert.equal(config.providers.maps, 'sandbox');
  assert.equal(config.providers.payment, 'sandbox');
  assert.equal(config.providers.sms, 'sandbox');
});

test('test config defaults to an isolated database and readiness reports missing dependencies', () => {
  const config = loadRuntimeConfig({ NODE_ENV: 'test' });
  assert.match(config.dbPath, /movo-test\.db$/);
  assert.notEqual(config.dbPath, '/home/lab/movo-platform/movo.db');
  assert.deepEqual(evaluateReadiness(config, { database: false }), { ready: false, failures: ['database unavailable'] });
  assert.deepEqual(evaluateReadiness(config, { database: true }), { ready: true, failures: [] });
});

test('feature flags default to on except chat, and are parsed from the environment', () => {
  const defaults = loadRuntimeConfig({ NODE_ENV: 'test' });
  assert.deepEqual(defaults.features, {
    paymentsEnabled: true, podPhotoEnabled: true, signatureEnabled: true,
    chatEnabled: false, scheduledDeliveryEnabled: true
  });

  const overridden = loadRuntimeConfig({
    NODE_ENV: 'test', PAYMENTS_ENABLED: 'false', POD_PHOTO_ENABLED: 'false',
    SIGNATURE_ENABLED: 'false', CHAT_ENABLED: 'true', SCHEDULED_DELIVERY_ENABLED: 'false'
  });
  assert.deepEqual(overridden.features, {
    paymentsEnabled: false, podPhotoEnabled: false, signatureEnabled: false,
    chatEnabled: true, scheduledDeliveryEnabled: false
  });
});
