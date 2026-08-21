const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const port = 36000 + Math.floor(Math.random() * 500);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-production-${process.pid}-${Date.now()}.db`);
let db;
let server;
let serial = 0;

async function request(route, { token, method = 'GET', body, headers = {} } = {}) {
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

async function register(role, overrides = {}) {
  serial += 1;
  const phone = overrides.phone || `+25078${String(3000000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? { national_id: `1199988${String(serial).padStart(9, '0')}`, license_number: `LIC-P-${serial}`, motorcycle_plate: `RAP${String(serial).padStart(3, '0')}C` } : {}),
    ...(role === 'business' ? { company_name: `Bulk Logistics ${serial}` } : {}),
    ...overrides
  };
  const registration = await request('/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

async function adminToken() {
  const login = await request('/api/auth/login', { method: 'POST', body: { phone: '+250780000000', password: 'Admin@2026' } });
  assert.equal(login.response.status, 200, login.json.error);
  return login.json.data.token;
}

const PICKUP = { lat: -1.9441, lng: 30.0619 };
const DEST = { lat: -1.9367, lng: 30.0867 };

function deliveryBody(overrides = {}) {
  return {
    service_type: 'parcel',
    pickup_address: 'Kacyiru Convention Centre', pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng,
    pickup_name: 'Sender', pickup_phone: '+250788111111',
    dest_address: 'Kigali Heights', dest_lat: DEST.lat, dest_lng: DEST.lng,
    dest_name: 'Receiver', dest_phone: '+250788222222',
    item_description: 'Signed contract', payment_method: 'mobile_money',
    ...overrides
  };
}

/** Registers an approved rider that is online with a fresh location near the pickup. */
async function onlineRider(admin) {
  const rider = await register('rider');
  const approval = await request(`/api/admin/riders/${rider.id}/approve`, { method: 'PUT', token: admin, body: { action: 'approve' } });
  assert.equal(approval.response.status, 200, approval.json.error);
  const status = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  assert.equal(status.response.status, 200, status.json.error);
  const location = await request('/api/rider/location', { method: 'PUT', token: rider.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  assert.equal(location.response.status, 200, location.json.error);
  return rider;
}

test.before(async () => {
  server = await startServer({
    DB_PATH: dbPath, JWT_SECRET: 'production-platform-test-secret', OTP_TEST_MODE: 'true', RATE_LIMIT_ENABLED: 'false',
    ADMIN_SEED_PASSWORD: 'Admin@2026'
  }, port);
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('every response carries hardened security headers and a correlation id', async () => {
  const { response } = await request('/health');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-security-policy') || '', /default-src 'self'/);
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(response.headers.get('x-frame-options'), 'DENY');
  assert.match(response.headers.get('referrer-policy') || '', /strict-origin/);
  assert.ok(response.headers.get('x-request-id'), 'a request id must be returned for tracing');
});

test('readiness and metrics expose dependency and marketplace health', async () => {
  const ready = await request('/ready');
  assert.equal(ready.response.status, 200, JSON.stringify(ready.json));
  assert.equal(ready.json.status, 'ready');

  const metrics = await fetch(`${base}/metrics`);
  assert.equal(metrics.status, 200);
  const body = await metrics.text();
  assert.match(body, /movo_deliveries_active/);
  assert.match(body, /movo_riders_online/);
  assert.match(body, /movo_http_requests_total/);
});

test('unknown API routes and malformed payloads fail with machine-readable codes', async () => {
  const missing = await request('/api/does-not-exist');
  assert.equal(missing.response.status, 404);
  assert.equal(missing.json.code, 'route_not_found');

  const customer = await register('customer');
  const badCoordinates = await request('/api/deliveries', {
    method: 'POST', token: customer.token, body: deliveryBody({ pickup_lat: 999 })
  });
  assert.equal(badCoordinates.response.status, 400);
  assert.equal(badCoordinates.json.code, 'invalid_coordinate');

  const badPhone = await request('/api/deliveries', {
    method: 'POST', token: customer.token, body: deliveryBody({ dest_phone: '12345' })
  });
  assert.equal(badPhone.response.status, 400);
  assert.equal(badPhone.json.code, 'invalid_phone');
});

test('weak passwords are rejected at registration', async () => {
  const weak = await request('/api/auth/register', {
    method: 'POST',
    body: { role: 'customer', phone: '+250788909090', full_name: 'Weak Password', password: 'passwordd' }
  });
  assert.equal(weak.response.status, 400);
  assert.equal(weak.json.code, 'weak_password');
});

test('a completed delivery produces proof of delivery and a digital receipt', async () => {
  const admin = await adminToken();
  const customer = await register('customer');
  const rider = await onlineRider(admin);

  const created = await request('/api/deliveries', { method: 'POST', token: customer.token, body: deliveryBody() });
  assert.equal(created.response.status, 201, created.json.error);
  const deliveryId = created.json.data.delivery.id;

  const accepted = await request(`/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: rider.token, body: {} });
  assert.equal(accepted.response.status, 200, accepted.json.error);

  const otps = db.prepare('SELECT pickup_otp, delivery_otp FROM deliveries WHERE id=?').get(deliveryId);
  for (const [step, body] of [
    ['going-pickup', {}], ['arrive-pickup', {}], ['verify-pickup', { otp: otps.pickup_otp }],
    ['in-transit', {}], ['arrive-dest', {}]
  ]) {
    const transition = await request(`/api/deliveries/${deliveryId}/${step}`, { method: 'PUT', token: rider.token, body });
    assert.equal(transition.response.status, 200, `${step}: ${transition.json.error}`);
  }

  const completed = await request(`/api/deliveries/${deliveryId}/complete`, {
    method: 'PUT', token: rider.token, body: { otp: otps.delivery_otp, recipient_name: 'Receiver' }
  });
  assert.equal(completed.response.status, 200, completed.json.error);
  assert.match(completed.json.data.pod_reference, /^POD-/);

  const pod = await request(`/api/deliveries/${deliveryId}/pod`, { token: customer.token });
  assert.equal(pod.response.status, 200, pod.json.error);
  assert.equal(pod.json.data.delivery.otp_verified, true);
  assert.equal(pod.json.data.pickup.otp_verified, true);
  assert.equal(pod.json.data.delivery.recipient, 'Receiver');
  assert.ok(pod.json.data.rider.name);

  const receipt = await request(`/api/deliveries/${deliveryId}/receipt`, { token: customer.token });
  assert.equal(receipt.response.status, 200, receipt.json.error);
  assert.equal(receipt.json.data.amounts.currency, 'RWF');
  assert.ok(receipt.json.data.payments.some(payment => payment.type === 'rider_payout'));
  assert.ok(receipt.json.data.payments.some(payment => payment.type === 'platform_fee'));

  const stranger = await register('customer');
  const denied = await request(`/api/deliveries/${deliveryId}/pod`, { token: stranger.token });
  assert.equal(denied.response.status, 404, 'proof of delivery must stay private to the delivery participants');
});

test('riders manage availability states and cannot go offline mid-delivery', async () => {
  const admin = await adminToken();
  const rider = await onlineRider(admin);

  const unavailable = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'unavailable' } });
  assert.equal(unavailable.response.status, 200, unavailable.json.error);
  assert.equal(unavailable.json.data.accepting_offers, false);

  const invalid = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'vacation' } });
  assert.equal(invalid.response.status, 400);
  assert.equal(invalid.json.code, 'unsupported_value');

  await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  const customer = await register('customer');
  const created = await request('/api/deliveries', { method: 'POST', token: customer.token, body: deliveryBody() });
  assert.equal(created.response.status, 201, created.json.error);
  await request(`/api/deliveries/${created.json.data.delivery.id}/accept`, { method: 'PUT', token: rider.token, body: {} });

  const blocked = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'offline' } });
  assert.equal(blocked.response.status, 409);
  assert.equal(blocked.json.code, 'active_delivery');
});

test('rider SOS raises an incident, a support ticket and an operations alert', async () => {
  const admin = await adminToken();
  const rider = await onlineRider(admin);

  const sos = await request('/api/rider/incidents', {
    method: 'POST', token: rider.token,
    body: { kind: 'sos', description: 'Threatened at destination', lat: PICKUP.lat, lng: PICKUP.lng }
  });
  assert.equal(sos.response.status, 201, sos.json.error);
  assert.equal(sos.json.data.severity, 'critical');
  assert.ok(sos.json.data.ticket_id);

  const incidents = await request('/api/admin/incidents?status=open', { token: admin });
  assert.equal(incidents.response.status, 200, incidents.json.error);
  const raised = incidents.json.data.find(incident => incident.id === sos.json.data.id);
  assert.ok(raised, 'operations must see the incident');
  assert.equal(raised.severity, 'critical');

  const resolved = await request(`/api/admin/incidents/${sos.json.data.id}`, {
    method: 'PUT', token: admin, body: { status: 'resolved', resolution: 'Rider safe, police notified' }
  });
  assert.equal(resolved.response.status, 200, resolved.json.error);
  assert.equal(db.prepare('SELECT status FROM incidents WHERE id=?').get(sos.json.data.id).status, 'resolved');

  const riderView = await request('/api/rider/incidents', { token: rider.token });
  assert.equal(riderView.json.data.length, 1);
});

test('operations sees the KPI pack and an audit trail of administrative actions', async () => {
  const admin = await adminToken();
  const kpis = await request('/api/admin/kpis?days=30', { token: admin });
  assert.equal(kpis.response.status, 200, kpis.json.error);
  const data = kpis.json.data;
  for (const section of ['customers', 'deliveries', 'riders', 'financial', 'operations']) {
    assert.ok(data[section], `KPI pack must include ${section}`);
  }
  assert.ok(data.deliveries.completion_rate >= 0 && data.deliveries.completion_rate <= 100);
  assert.equal(data.financial.currency, 'RWF');

  const audit = await request('/api/admin/audit?entity=rider&limit=5', { token: admin });
  assert.equal(audit.response.status, 200, audit.json.error);
  assert.ok(audit.json.data.entries.length > 0, 'rider approvals must be recorded');
  assert.ok(audit.json.data.entries.every(entry => entry.entity === 'rider'));
});

test('businesses upload deliveries in bulk and get per-row outcomes', async () => {
  const business = await register('business');
  const bulk = await request('/api/business/deliveries/bulk', {
    method: 'POST', token: business.token,
    body: {
      deliveries: [
        deliveryBody({ business_ref: 'INV-1001' }),
        deliveryBody({ business_ref: 'INV-1002', service_type: 'document' }),
        deliveryBody({ business_ref: 'INV-1003', dest_lat: 400 })
      ]
    }
  });
  assert.equal(bulk.response.status, 201, bulk.json.error);
  assert.equal(bulk.json.data.summary.created, 2);
  assert.equal(bulk.json.data.summary.rejected, 1);
  assert.equal(bulk.json.data.rejected[0].reference, 'INV-1003');
  assert.equal(bulk.json.data.rejected[0].code, 'invalid_coordinate');
});

test('scheduled deliveries wait for their window instead of dispatching immediately', async () => {
  const business = await register('business');
  const scheduledFor = new Date(Date.now() + 6 * 60 * 60 * 1000).toISOString();
  const created = await request('/api/deliveries', {
    method: 'POST', token: business.token, body: deliveryBody({ scheduled_for: scheduledFor })
  });
  assert.equal(created.response.status, 201, created.json.error);
  assert.equal(created.json.data.delivery.status, 'scheduled');
  assert.equal(created.json.data.message, 'Delivery scheduled');
  const stored = db.prepare('SELECT status, scheduled_for FROM deliveries WHERE id=?').get(created.json.data.delivery.id);
  assert.equal(stored.status, 'scheduled');
  assert.ok(stored.scheduled_for);
});

test('cancelling after a rider is assigned applies the configured cancellation fee', async () => {
  const admin = await adminToken();
  const customer = await register('customer');
  const rider = await onlineRider(admin);
  const created = await request('/api/deliveries', { method: 'POST', token: customer.token, body: deliveryBody() });
  const deliveryId = created.json.data.delivery.id;
  await request(`/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: rider.token, body: {} });

  const cancelled = await request(`/api/deliveries/${deliveryId}/cancel`, {
    method: 'PUT', token: customer.token, body: { reason: 'Recipient unavailable' }
  });
  assert.equal(cancelled.response.status, 200, cancelled.json.error);
  assert.equal(cancelled.json.data.cancellation_fee, 500);
  assert.equal(db.prepare('SELECT online_status FROM riders WHERE user_id=?').get(rider.id).online_status, 'online');

  const receipt = await request(`/api/deliveries/${deliveryId}/receipt`, { token: customer.token });
  assert.equal(receipt.json.data.amounts.cancellation_fee, 500);
  assert.equal(receipt.json.data.amounts.total, 500);
});

test('operations can reassign a stalled delivery to another approved rider', async () => {
  const admin = await adminToken();
  const customer = await register('customer');
  const first = await onlineRider(admin);
  const second = await onlineRider(admin);
  const created = await request('/api/deliveries', { method: 'POST', token: customer.token, body: deliveryBody() });
  const deliveryId = created.json.data.delivery.id;
  await request(`/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: first.token, body: {} });

  const reassigned = await request(`/api/admin/deliveries/${deliveryId}/reassign`, {
    method: 'PUT', token: admin, body: { rider_id: second.id }
  });
  assert.equal(reassigned.response.status, 200, reassigned.json.error);
  assert.equal(db.prepare('SELECT rider_id FROM deliveries WHERE id=?').get(deliveryId).rider_id, second.id);
  assert.equal(db.prepare('SELECT online_status FROM riders WHERE user_id=?').get(first.id).online_status, 'online');
});

test('rate limiting and account lockout defend the credential endpoints', async () => {
  const lockoutPort = port + 1;
  const lockoutDb = path.join(os.tmpdir(), `movo-lockout-${process.pid}-${Date.now()}.db`);
  const guarded = await startServer({
    DB_PATH: lockoutDb, JWT_SECRET: 'lockout-test-secret', OTP_TEST_MODE: 'true',
    RATE_LIMIT_ENABLED: 'true', RATE_LIMIT_AUTH_MAX: '12', RATE_LIMIT_WINDOW_MS: '60000',
    MAX_LOGIN_ATTEMPTS: '3', LOCKOUT_MINUTES: '15'
  }, lockoutPort);

  const call = async (route, body) => {
    const response = await fetch(`http://127.0.0.1:${lockoutPort}${route}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    return { status: response.status, json: await response.json() };
  };

  try {
    const phone = '+250788777777';
    const registration = await call('/api/auth/register', { role: 'customer', phone, full_name: 'Locked Out', password: 'Passw0rd1' });
    assert.equal(registration.status, 200, registration.json.error);
    await call('/api/auth/verify-otp', { phone, otp: registration.json.data.otp });

    const first = await call('/api/auth/login', { phone, password: 'WrongPass1' });
    assert.equal(first.status, 401);
    assert.equal(first.json.code, 'invalid_credentials');
    await call('/api/auth/login', { phone, password: 'WrongPass1' });
    const locked = await call('/api/auth/login', { phone, password: 'WrongPass1' });
    assert.equal(locked.status, 423);
    assert.equal(locked.json.code, 'account_locked');

    const correctButLocked = await call('/api/auth/login', { phone, password: 'Passw0rd1' });
    assert.equal(correctButLocked.status, 423, 'a locked account stays locked even with the right password');

    let limited = null;
    for (let attempt = 0; attempt < 20 && !limited; attempt += 1) {
      const response = await call('/api/auth/login', { phone: '+250788111000', password: 'Passw0rd1' });
      if (response.status === 429) limited = response;
    }
    assert.ok(limited, 'the auth endpoints must rate limit repeated attempts');
    assert.equal(limited.json.code, 'auth_rate_limited');
  } finally {
    guarded.kill();
    for (const file of [lockoutDb, `${lockoutDb}-shm`, `${lockoutDb}-wal`]) fs.rmSync(file, { force: true });
  }
});
