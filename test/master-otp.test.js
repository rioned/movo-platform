/**
 * The master OTP is the manual-testing escape hatch: one fixed code clears every
 * OTP prompt in the product. These tests run WITHOUT OTP_TEST_MODE so the real
 * verification path executes and the master code is what actually gets it past.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { loadRuntimeConfig, evaluateReadiness } = require('../src/config/runtime');

const root = path.resolve(__dirname, '..');
const port = 37000 + Math.floor(Math.random() * 500);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-master-otp-${process.pid}-${Date.now()}.db`);
const MASTER = '000000';
let db;
let server;
let serial = 0;

async function request(route, { token, method = 'GET', body } = {}) {
  const response = await fetch(`${base}${route}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await response.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { success: false, error: text.slice(0, 160) }; }
  return { response, json };
}

/** Registers an account and verifies it with the master code — never a real OTP. */
async function register(role, overrides = {}) {
  serial += 1;
  const phone = overrides.phone || `+25078${String(5100000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? { national_id: `1199977${String(serial).padStart(9, '0')}`, license_number: `LIC-M-${serial}`, motorcycle_plate: `RAM${String(serial).padStart(3, '0')}D` } : {}),
    ...overrides
  };
  const registration = await request('/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  assert.equal(registration.json.data.otp, undefined, 'the real OTP must never be returned outside test mode');
  const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: MASTER } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

const PICKUP = { lat: -1.9441, lng: 30.0619 };
const DEST = { lat: -1.9367, lng: 30.0867 };

function deliveryBody() {
  return {
    service_type: 'parcel',
    pickup_address: 'Kacyiru Convention Centre', pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng,
    pickup_name: 'Sender', pickup_phone: '+250788111111',
    dest_address: 'Kigali Heights', dest_lat: DEST.lat, dest_lng: DEST.lng,
    dest_name: 'Receiver', dest_phone: '+250788222222',
    item_description: 'Signed contract', payment_method: 'mobile_money'
  };
}

async function adminToken() {
  const login = await request('/api/auth/login', { method: 'POST', body: { phone: '+250780000000', password: 'Admin@2026' } });
  assert.equal(login.response.status, 200, login.json.error);
  return login.json.data.token;
}

async function onlineRider(admin) {
  const rider = await register('rider');
  await request(`/api/admin/riders/${rider.id}/approve`, { method: 'PUT', token: admin, body: { action: 'approve' } });
  await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  await request('/api/rider/location', { method: 'PUT', token: rider.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  return rider;
}

test.before(async () => {
  server = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: {
      ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath,
      JWT_SECRET: 'master-otp-test-secret', OTP_TEST_MODE: 'false', MASTER_OTP: MASTER, RATE_LIMIT_ENABLED: 'false'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for master OTP test server')), 15000);
    server.stdout.on('data', chunk => {
      if (chunk.toString().includes('Server running')) { clearTimeout(timeout); resolve(); }
    });
    server.once('error', reject);
    server.once('exit', code => reject(new Error(`Master OTP test server exited early with ${code}`)));
  });
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('the master OTP verifies a freshly registered account without the real code', async () => {
  const customer = await register('customer');
  const me = await request('/api/auth/me', { token: customer.token });
  assert.equal(me.response.status, 200, me.json.error);
  assert.equal(me.json.data.status, 'active');
});

test('the master OTP still logs in after wrong codes exhaust the attempt budget', async () => {
  const customer = await register('customer');

  const login = await request('/api/auth/login', { method: 'POST', body: { phone: customer.phone } });
  assert.equal(login.response.status, 200, login.json.error);
  assert.equal(login.json.data.requires_otp, true);

  for (let attempt = 0; attempt < 6; attempt += 1) {
    const wrong = await request('/api/auth/verify-otp', { method: 'POST', body: { phone: customer.phone, otp: '123123' } });
    assert.notEqual(wrong.response.status, 200, 'a wrong code must never be accepted');
  }

  const master = await request('/api/auth/verify-otp', { method: 'POST', body: { phone: customer.phone, otp: MASTER } });
  assert.equal(master.response.status, 200, master.json.error);
  assert.ok(master.json.data.token);

  const audited = db.prepare("SELECT details FROM audit_log WHERE user_id=? AND action='otp_verified' ORDER BY created_at DESC LIMIT 1").get(customer.id);
  assert.match(audited.details || '', /master_otp/, 'master OTP use must be visible in the audit log');
});

test('the master OTP clears both handover codes on a live delivery', async () => {
  const admin = await adminToken();
  const customer = await register('customer');
  const rider = await onlineRider(admin);

  const created = await request('/api/deliveries', { method: 'POST', token: customer.token, body: deliveryBody() });
  assert.equal(created.response.status, 201, created.json.error);
  const deliveryId = created.json.data.delivery.id;

  const accepted = await request(`/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: rider.token, body: {} });
  assert.equal(accepted.response.status, 200, accepted.json.error);

  // Handover codes are four digits, so both the master code and its four-digit
  // prefix have to pass — the rider app's field accepts either.
  for (const [step, body] of [
    ['going-pickup', {}], ['arrive-pickup', {}], ['verify-pickup', { otp: MASTER }],
    ['in-transit', {}], ['arrive-dest', {}]
  ]) {
    const transition = await request(`/api/deliveries/${deliveryId}/${step}`, { method: 'PUT', token: rider.token, body });
    assert.equal(transition.response.status, 200, `${step}: ${transition.json.error}`);
  }

  const completed = await request(`/api/deliveries/${deliveryId}/complete`, {
    method: 'PUT', token: rider.token, body: { otp: MASTER.slice(0, 4), recipient_name: 'Receiver' }
  });
  assert.equal(completed.response.status, 200, completed.json.error);
  assert.match(completed.json.data.pod_reference, /^POD-/);
});

test('a wrong handover code is still rejected while the master OTP is enabled', async () => {
  const admin = await adminToken();
  const customer = await register('customer');
  const rider = await onlineRider(admin);

  const created = await request('/api/deliveries', { method: 'POST', token: customer.token, body: deliveryBody() });
  const deliveryId = created.json.data.delivery.id;
  await request(`/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: rider.token, body: {} });
  await request(`/api/deliveries/${deliveryId}/going-pickup`, { method: 'PUT', token: rider.token, body: {} });
  await request(`/api/deliveries/${deliveryId}/arrive-pickup`, { method: 'PUT', token: rider.token, body: {} });

  const { pickup_otp: pickupOtp } = db.prepare('SELECT pickup_otp FROM deliveries WHERE id=?').get(deliveryId);
  const wrongCode = String((Number(pickupOtp) + 1) % 10000).padStart(4, '0');
  const rejected = await request(`/api/deliveries/${deliveryId}/verify-pickup`, { method: 'PUT', token: rider.token, body: { otp: wrongCode } });
  assert.equal(rejected.response.status, 400, 'only the real code or the master code may verify a pickup');
});

test('the master OTP defaults on outside production and is impossible inside it', () => {
  const development = loadRuntimeConfig({ NODE_ENV: 'development' });
  assert.equal(development.masterOtp, '000000');

  const custom = loadRuntimeConfig({ NODE_ENV: 'development', MASTER_OTP: '4242' });
  assert.equal(custom.masterOtp, '4242');

  const disabled = loadRuntimeConfig({ NODE_ENV: 'development', MASTER_OTP: '' });
  assert.equal(disabled.masterOtp, null);

  assert.throws(() => loadRuntimeConfig({ NODE_ENV: 'development', MASTER_OTP: 'letters' }), /MASTER_OTP must be four to six digits/);

  const productionEnv = { NODE_ENV: 'production', JWT_SECRET: 'x'.repeat(32) };
  assert.equal(loadRuntimeConfig(productionEnv).masterOtp, null);
  assert.throws(() => loadRuntimeConfig({ ...productionEnv, MASTER_OTP: MASTER }), /MASTER_OTP must not be set in production/);

  const readiness = evaluateReadiness(
    { ...loadRuntimeConfig(productionEnv), masterOtp: MASTER },
    { database: true }
  );
  assert.equal(readiness.ready, false);
  assert.ok(readiness.failures.includes('master OTP is enabled'));
});
