'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Covers the server-side location-suggestion endpoints (GET /api/places/search,
// GET /api/places/reverse) and their exposure via GET /api/config. Runs against
// PLACES_PROVIDER=sandbox so the suite never depends on reaching a real Google/
// OSM/MapTiler endpoint over the network — src/services/geocoding.test.js (via
// test/geocoding-service.test.js) covers the actual provider-mixing logic with
// a faked fetch.

const root = path.resolve(__dirname, '..');
let serial = 0;

async function request(base, route, { token, method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(`${base}${route}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...headers
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await response.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { success: false, error: text.slice(0, 160) }; }
  return { response, json };
}

async function startServer(env, listenPort) {
  const child = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: { ...process.env, NODE_ENV: 'test', PORT: String(listenPort), ...env },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for server')), 15000);
    child.stdout.on('data', chunk => {
      if (chunk.toString().includes('Server running')) { clearTimeout(timeout); resolve(); }
    });
    child.stderr.on('data', chunk => process.stderr.write(chunk));
    child.once('error', reject);
    child.once('exit', code => reject(new Error(`Server exited early with ${code}`)));
  });
  return child;
}

async function register(base, role, overrides = {}) {
  serial += 1;
  const phone = overrides.phone || `+25078${String(6000000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? { national_id: `1199966${String(serial).padStart(9, '0')}`, license_number: `LIC-P-${serial}`, motorcycle_plate: `RAP${String(serial).padStart(3, '0')}C` } : {}),
    ...overrides
  };
  const registration = await request(base, '/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request(base, '/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

async function withServer(env, fn) {
  const port = 39700 + Math.floor(Math.random() * 300);
  const base = `http://127.0.0.1:${port}`;
  const dbPath = path.join(os.tmpdir(), `movo-places-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}.db`);
  const server = await startServer({
    DB_PATH: dbPath, JWT_SECRET: 'places-test-secret', OTP_TEST_MODE: 'true', RATE_LIMIT_ENABLED: 'false',
    PLACES_PROVIDER: 'sandbox', ...env
  }, port);
  const db = new Database(dbPath);
  try {
    await fn({ base, db });
  } finally {
    db.close();
    server.kill();
    for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
  }
}

test('GET /api/config exposes the active places provider', async () => {
  await withServer({ PLACES_PROVIDER: 'osm' }, async ({ base }) => {
    const config = await request(base, '/api/config');
    assert.equal(config.response.status, 200, config.json.error);
    assert.equal(config.json.data.places_provider, 'osm');
  });
});

test('GET /api/places/search requires auth and returns an empty list for a blank query in sandbox mode', async () => {
  await withServer({}, async ({ base }) => {
    const unauthenticated = await request(base, '/api/places/search?q=Kigali');
    assert.equal(unauthenticated.response.status, 401);

    const customer = await register(base, 'customer');
    const blank = await request(base, '/api/places/search?q=', { token: customer.token });
    assert.equal(blank.response.status, 200, blank.json.error);
    assert.deepEqual(blank.json.data, []);

    const sandboxed = await request(base, '/api/places/search?q=Kigali+Heights', { token: customer.token });
    assert.equal(sandboxed.response.status, 200, sandboxed.json.error);
    assert.deepEqual(sandboxed.json.data, [], 'PLACES_PROVIDER=sandbox must never resolve suggestions');
  });
});

test('GET /api/places/reverse requires valid coordinates and auth', async () => {
  await withServer({}, async ({ base }) => {
    const customer = await register(base, 'customer');
    const missing = await request(base, '/api/places/reverse?lat=notanumber&lng=30', { token: customer.token });
    assert.equal(missing.response.status, 400);

    const ok = await request(base, '/api/places/reverse?lat=-1.9441&lng=30.0619', { token: customer.token });
    assert.equal(ok.response.status, 200, ok.json.error);
    assert.equal(ok.json.data.address, null, 'PLACES_PROVIDER=sandbox must never resolve an address');
  });
});
