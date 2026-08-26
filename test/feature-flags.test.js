const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Covers the feature-flag plumbing added for spec §77: PAYMENTS_ENABLED,
// POD_PHOTO_ENABLED, SIGNATURE_ENABLED, CHAT_ENABLED, SCHEDULED_DELIVERY_ENABLED
// must exist in runtime config/.env.example and actually gate behavior, not just
// be parsed and ignored.

const root = path.resolve(__dirname, '..');
let serial = 0;

async function request(base, route, { token, method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(`${base}${route}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
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
  const phone = overrides.phone || `+25078${String(5000000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? { national_id: `1199966${String(serial).padStart(9, '0')}`, license_number: `LIC-F-${serial}`, motorcycle_plate: `RAF${String(serial).padStart(3, '0')}C` } : {}),
    ...overrides
  };
  const registration = await request(base, '/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request(base, '/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

async function withServer(env, fn) {
  const port = 39200 + Math.floor(Math.random() * 400);
  const base = `http://127.0.0.1:${port}`;
  const dbPath = path.join(os.tmpdir(), `movo-feature-flags-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}.db`);
  const server = await startServer({
    DB_PATH: dbPath, JWT_SECRET: 'feature-flags-test-secret', OTP_TEST_MODE: 'true', RATE_LIMIT_ENABLED: 'false',
    ADMIN_SEED_PASSWORD: 'Admin@2026', ...env
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

test('GET /api/config exposes feature flags and the map provider, unauthenticated', async () => {
  await withServer({ PAYMENTS_ENABLED: 'true', POD_PHOTO_ENABLED: 'false', SIGNATURE_ENABLED: 'true', CHAT_ENABLED: 'false', SCHEDULED_DELIVERY_ENABLED: 'true' }, async ({ base }) => {
    const config = await request(base, '/api/config');
    assert.equal(config.response.status, 200, config.json.error);
    assert.deepEqual(config.json.data.features, {
      paymentsEnabled: true, podPhotoEnabled: false, signatureEnabled: true,
      chatEnabled: false, scheduledDeliveryEnabled: true
    });
    assert.ok(config.json.data.map_provider);
  });
});

test('POD_PHOTO_ENABLED=false blocks proof uploads before the file is even processed', async () => {
  await withServer({ POD_PHOTO_ENABLED: 'false' }, async ({ base, db }) => {
    const rider = await register(base, 'rider');
    const proof = await request(base, `/api/rider/deliveries/does-not-matter/proof`, { method: 'POST', token: rider.token, body: {} });
    assert.equal(proof.response.status, 403);
    assert.equal(proof.json.code, 'feature_disabled');
    void db;
  });
});

test('SIGNATURE_ENABLED=false completes the delivery but never persists the signature', async () => {
  await withServer({ SIGNATURE_ENABLED: 'false' }, async ({ base, db }) => {
    const admin = (await request(base, '/api/auth/login', { method: 'POST', body: { phone: '+250780000000', password: 'Admin@2026' } })).json.data.token;
    const customer = await register(base, 'customer');
    const rider = await register(base, 'rider');
    await request(base, `/api/admin/riders/${rider.id}/approve`, { method: 'PUT', token: admin, body: { action: 'approve' } });
    await request(base, '/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
    await request(base, '/api/rider/location', { method: 'PUT', token: rider.token, body: { lat: -1.9441, lng: 30.0619 } });

    const created = await request(base, '/api/deliveries', {
      method: 'POST', token: customer.token,
      body: {
        service_type: 'parcel',
        pickup_address: 'Kacyiru Convention Centre', pickup_lat: -1.9441, pickup_lng: 30.0619,
        pickup_name: 'Sender', pickup_phone: '+250788111111',
        dest_address: 'Kigali Heights', dest_lat: -1.9367, dest_lng: 30.0867,
        dest_name: 'Receiver', dest_phone: '+250788222222',
        item_description: 'Signed contract', payment_method: 'mobile_money'
      }
    });
    assert.equal(created.response.status, 201, created.json.error);
    const deliveryId = created.json.data.delivery.id;
    const accepted = await request(base, `/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: rider.token, body: {} });
    assert.equal(accepted.response.status, 200, accepted.json.error);
    const otps = db.prepare('SELECT pickup_otp, delivery_otp FROM deliveries WHERE id=?').get(deliveryId);
    for (const [step, body] of [
      ['going-pickup', {}], ['arrive-pickup', {}], ['verify-pickup', { otp: otps.pickup_otp }],
      ['in-transit', {}], ['arrive-dest', {}]
    ]) {
      const transition = await request(base, `/api/deliveries/${deliveryId}/${step}`, { method: 'PUT', token: rider.token, body });
      assert.equal(transition.response.status, 200, `${step}: ${transition.json.error}`);
    }
    const completed = await request(base, `/api/deliveries/${deliveryId}/complete`, {
      method: 'PUT', token: rider.token, body: { otp: otps.delivery_otp, signature: 'Jane Doe' }
    });
    assert.equal(completed.response.status, 200, completed.json.error);
    const notes = db.prepare('SELECT delivery_notes FROM deliveries WHERE id=?').get(deliveryId).delivery_notes;
    assert.ok(!notes || !notes.includes('signed:'), 'a disabled signature feature must not persist the signature');
  });
});

test('SCHEDULED_DELIVERY_ENABLED=false rejects a scheduled delivery request', async () => {
  await withServer({ SCHEDULED_DELIVERY_ENABLED: 'false' }, async ({ base }) => {
    const customer = await register(base, 'customer');
    const future = new Date(Date.now() + 3600_000).toISOString();
    const created = await request(base, '/api/deliveries', {
      method: 'POST', token: customer.token,
      body: {
        service_type: 'parcel',
        pickup_address: 'Kacyiru Convention Centre', pickup_lat: -1.9441, pickup_lng: 30.0619,
        pickup_name: 'Sender', pickup_phone: '+250788111111',
        dest_address: 'Kigali Heights', dest_lat: -1.9367, dest_lng: 30.0867,
        dest_name: 'Receiver', dest_phone: '+250788222222',
        item_description: 'Signed contract', payment_method: 'mobile_money',
        scheduled_for: future
      }
    });
    assert.equal(created.response.status, 403);
    assert.equal(created.json.code, 'feature_disabled');
  });
});

test('PAYMENTS_ENABLED=false blocks new ride requests', async () => {
  await withServer({ PAYMENTS_ENABLED: 'false' }, async ({ base, db }) => {
    const customer = await register(base, 'customer');
    const rideType = db.prepare('SELECT id FROM ride_types LIMIT 1').get();
    const ride = await request(base, '/api/rides', {
      method: 'POST', token: customer.token,
      body: {
        pickup_address: 'Maputo Central', pickup_lat: -25.9655, pickup_lng: 32.5832,
        dest_address: 'Polana', dest_lat: -25.9553, dest_lng: 32.6046,
        ride_type_id: rideType.id, payment_method: 'cash'
      }
    });
    assert.equal(ride.response.status, 503);
    assert.equal(ride.json.code, 'payments_disabled');
  });
});
