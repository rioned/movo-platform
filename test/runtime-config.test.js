const test = require('node:test');
const assert = require('node:assert/strict');

test('production runtime configuration requires a JWT secret and parses pilot settings', () => {
  const { loadRuntimeConfig } = require('../src/config/runtime');
  assert.throws(() => loadRuntimeConfig({ NODE_ENV: 'production' }), /JWT_SECRET must be set/);
  const config = loadRuntimeConfig({
    NODE_ENV: 'test', JWT_SECRET: 'test-secret',
    ALLOWED_ORIGINS: 'http://localhost:3000,https://pilot.movo.rw',
    DISPATCH_OFFER_TIMEOUT_SEC: '25', DISPATCH_RADIUS_KM: '4',
    DB_PATH: '/tmp/movo-test.db'
  });
  assert.equal(config.dbPath, '/tmp/movo-test.db');
  assert.deepEqual(config.allowedOrigins, ['http://localhost:3000', 'https://pilot.movo.rw']);
  assert.equal(config.dispatch.offerTimeoutSeconds, 25);
  assert.equal(config.dispatch.initialRadiusKm, 4);
});
