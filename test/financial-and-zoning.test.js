const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Covers the two areas of the MOVO MVP master prompt this session targeted:
// zoning correctness (§12, Test 7 — out-of-zone pickup) and the financial
// architecture (§7A — payout obligations, idempotency, reconciliation).

const root = path.resolve(__dirname, '..');
const port = 38500 + Math.floor(Math.random() * 400);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-financial-zoning-${process.pid}-${Date.now()}.db`);
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
  const phone = overrides.phone || `+25078${String(4000000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? { national_id: `1199977${String(serial).padStart(9, '0')}`, license_number: `LIC-Z-${serial}`, motorcycle_plate: `RAZ${String(serial).padStart(3, '0')}C` } : {}),
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
// Far outside every seeded Kigali zone (seeded zones top out around a 5km radius),
// and distinct from the polygon-zone test's probe point below so the two tests
// don't accidentally create a zone that covers the other's "out of area" point.
const OUT_OF_AREA = { lat: 2.0, lng: 32.0 };

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

async function completeDelivery(admin) {
  const customer = await register('customer');
  const rider = await onlineRider(admin);
  const created = await request('/api/deliveries', { method: 'POST', token: customer.token, body: deliveryBody() });
  assert.equal(created.response.status, 201, created.json.error);
  const deliveryId = created.json.data.delivery.id;
  const accepted = await request(`/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: rider.token, body: {} });
  assert.equal(accepted.response.status, 200, accepted.json.error);
  const otps = db.prepare('SELECT pickup_otp, delivery_otp, order_no FROM deliveries WHERE id=?').get(deliveryId);
  for (const [step, body] of [
    ['going-pickup', {}], ['arrive-pickup', {}], ['verify-pickup', { otp: otps.pickup_otp }],
    ['in-transit', {}], ['arrive-dest', {}]
  ]) {
    const transition = await request(`/api/deliveries/${deliveryId}/${step}`, { method: 'PUT', token: rider.token, body });
    assert.equal(transition.response.status, 200, `${step}: ${transition.json.error}`);
  }
  return { customer, rider, deliveryId, otps };
}

test.before(async () => {
  server = await startServer({
    DB_PATH: dbPath, JWT_SECRET: 'financial-zoning-test-secret', OTP_TEST_MODE: 'true', RATE_LIMIT_ENABLED: 'false',
    ADMIN_SEED_PASSWORD: 'Admin@2026'
  }, port);
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('a pickup outside every service zone is rejected, not mapped to the nearest zone (spec Test 7)', async () => {
  const customer = await register('customer');

  const priced = await request('/api/deliveries/price', {
    method: 'POST', token: customer.token,
    body: { pickup_lat: OUT_OF_AREA.lat, pickup_lng: OUT_OF_AREA.lng, dest_lat: DEST.lat, dest_lng: DEST.lng, service_type: 'parcel' }
  });
  assert.equal(priced.response.status, 422);
  assert.equal(priced.json.code, 'out_of_service_area');

  const created = await request('/api/deliveries', {
    method: 'POST', token: customer.token,
    body: deliveryBody({ pickup_lat: OUT_OF_AREA.lat, pickup_lng: OUT_OF_AREA.lng })
  });
  assert.equal(created.response.status, 422);
  assert.equal(created.json.code, 'out_of_service_area');

  const withinArea = await request('/api/deliveries/price', {
    method: 'POST', token: customer.token,
    body: { pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng, dest_lat: DEST.lat, dest_lng: DEST.lng, service_type: 'parcel' }
  });
  assert.equal(withinArea.response.status, 200, withinArea.json.error);
});

test('a polygon zone boundary overrides the circular radius for point resolution', async () => {
  const admin = await adminToken();
  // A small square polygon around a point that sits outside every seeded zone's
  // radius, proving the boundary — not the center+radius fallback — decides membership.
  const probe = { lat: -1.5000, lng: 29.5000 };
  const square = {
    type: 'Polygon',
    coordinates: [[
      [probe.lng - 0.01, probe.lat - 0.01], [probe.lng + 0.01, probe.lat - 0.01],
      [probe.lng + 0.01, probe.lat + 0.01], [probe.lng - 0.01, probe.lat + 0.01],
      [probe.lng - 0.01, probe.lat - 0.01]
    ]]
  };
  const created = await request('/api/admin/zones', {
    method: 'POST', token: admin,
    body: { name: 'Test Polygon Zone', center_lat: probe.lat, center_lng: probe.lng, radius_km: 0.001, base_price_parcel: 1500, base_price_document: 1000, boundary_geojson: square }
  });
  assert.equal(created.response.status, 201, created.json.error);

  const customer = await register('customer');
  const priced = await request('/api/deliveries/price', {
    method: 'POST', token: customer.token,
    body: { pickup_lat: probe.lat + 0.002, pickup_lng: probe.lng, dest_lat: PICKUP.lat, dest_lng: PICKUP.lng, service_type: 'parcel' }
  });
  // Would be out_of_service_area under the old nearest-zone fallback / a 1m radius;
  // the polygon boundary makes it resolvable (route_unsupported means the zone
  // resolved but has no configured pricing pair yet, which is an acceptable outcome
  // here — the point is that it is not out_of_service_area).
  assert.notEqual(priced.json.code, 'out_of_service_area', priced.json.error);
});

test('editing a zone already used by a delivery bumps its version and is audit logged (spec §50)', async () => {
  const admin = await adminToken();
  await completeDelivery(admin);
  const zone = db.prepare("SELECT * FROM delivery_zones WHERE name='City Center / Kacyiru'").get();
  assert.ok(zone, 'seeded zone should exist');
  const usedByDelivery = db.prepare('SELECT COUNT(*) as c FROM deliveries WHERE origin_zone=?').get(zone.name).c;
  assert.ok(usedByDelivery > 0);

  const updated = await request(`/api/admin/zones/${zone.id}`, {
    method: 'PUT', token: admin, body: { radius_km: zone.radius_km + 1 }
  });
  assert.equal(updated.response.status, 200, updated.json.error);
  assert.equal(updated.json.data.version, zone.version + 1);

  const auditEntry = db.prepare("SELECT * FROM audit_log WHERE entity='zone' AND entity_id=? AND action='zone_geometry_changed' ORDER BY created_at DESC LIMIT 1").get(zone.id);
  assert.ok(auditEntry, 'geometry change must be audit logged');
  const details = JSON.parse(auditEntry.details);
  assert.ok(details.deliveries_already_using_zone > 0);

  // A non-geometry edit (price/name/active flag) must not bump the version.
  const zoneAfter = db.prepare('SELECT version FROM delivery_zones WHERE id=?').get(zone.id);
  const priceOnly = await request(`/api/admin/zones/${zone.id}`, { method: 'PUT', token: admin, body: { base_price_parcel: zone.base_price_parcel + 100 } });
  assert.equal(priceOnly.response.status, 200, priceOnly.json.error);
  assert.equal(priceOnly.json.data.version, zoneAfter.version);
});

test('POD success creates exactly one payout obligation which settles through the mock provider (spec Test 11)', async () => {
  const admin = await adminToken();
  const { rider, deliveryId, otps } = await completeDelivery(admin);

  const completed = await request(`/api/deliveries/${deliveryId}/complete`, {
    method: 'PUT', token: rider.token, body: { otp: otps.delivery_otp, recipient_name: 'Receiver' }
  });
  assert.equal(completed.response.status, 200, completed.json.error);
  assert.equal(completed.json.data.payout.reference, `MOVO-PAYOUT-${otps.order_no}`);

  const payoutRows = db.prepare('SELECT * FROM payouts WHERE delivery_id=?').all(deliveryId);
  assert.equal(payoutRows.length, 1, 'exactly one payout obligation must exist per delivery');
  assert.equal(payoutRows[0].status, 'COMPLETED');
  assert.equal(payoutRows[0].reference, `MOVO-PAYOUT-${otps.order_no}`);

  const earnings = await request('/api/rider/earnings', { token: rider.token });
  assert.equal(earnings.response.status, 200, earnings.json.error);
  const line = earnings.json.data.recent.find(item => item.id === deliveryId);
  assert.ok(line, 'the completed delivery should appear in rider earnings');
  assert.equal(line.payout_status, 'COMPLETED');
});

test('a duplicate completion request is rejected and never produces a second payout (spec Test 14)', async () => {
  const admin = await adminToken();
  const { rider, deliveryId, otps } = await completeDelivery(admin);

  const [first, second] = await Promise.all([
    request(`/api/deliveries/${deliveryId}/complete`, { method: 'PUT', token: rider.token, body: { otp: otps.delivery_otp } }),
    request(`/api/deliveries/${deliveryId}/complete`, { method: 'PUT', token: rider.token, body: { otp: otps.delivery_otp } })
  ]);
  const statuses = [first.response.status, second.response.status].sort((a, b) => a - b);
  assert.equal(statuses[0], 200, 'exactly one of the two concurrent completions should succeed');
  // The loser can observe either the initial stage guard (404 — it no longer finds
  // the delivery in arrived_dest) or the CAS guard inside the transaction (409),
  // depending on scheduling; either way it must not proceed to settle a second time.
  assert.ok([404, 409].includes(statuses[1]), `unexpected status for the losing request: ${statuses[1]}`);

  const payoutRows = db.prepare('SELECT * FROM payouts WHERE delivery_id=?').all(deliveryId);
  assert.equal(payoutRows.length, 1, 'exactly one payout obligation, even under a racing duplicate request');

  const riderPayoutPayments = db.prepare("SELECT * FROM payments WHERE delivery_id=? AND type='rider_payout'").all(deliveryId);
  assert.equal(riderPayoutPayments.length, 1, 'exactly one rider_payout ledger entry');
});

test('operations can list and reconcile payouts by status (spec §7A.9)', async () => {
  const admin = await adminToken();
  const { rider, deliveryId, otps } = await completeDelivery(admin);
  const completed = await request(`/api/deliveries/${deliveryId}/complete`, {
    method: 'PUT', token: rider.token, body: { otp: otps.delivery_otp }
  });
  assert.equal(completed.response.status, 200, completed.json.error);

  const list = await request('/api/admin/payouts?status=COMPLETED', { token: admin });
  assert.equal(list.response.status, 200, list.json.error);
  assert.ok(Array.isArray(list.json.data));
  assert.ok(list.json.data.some(payout => payout.delivery_id === deliveryId));

  const finances = await request('/api/admin/finances', { token: admin });
  assert.equal(finances.response.status, 200, finances.json.error);
  assert.ok('pendingPayouts' in finances.json.data);
  assert.ok(Array.isArray(finances.json.data.payoutsByStatus));
});

test('the customer never sees the rider payout on a completed delivery (spec Test 12)', async () => {
  const admin = await adminToken();
  const { customer, deliveryId } = await completeDelivery(admin);
  const view = await request(`/api/deliveries/${deliveryId}`, { token: customer.token });
  assert.equal(view.response.status, 200, view.json.error);
  assert.equal(view.json.data.payout, undefined, 'customer response must not include a payout object');
  assert.equal(view.json.data.delivery.rider_earnings, undefined, 'customer delivery view must not include rider_earnings');
  assert.equal(view.json.data.delivery.platform_fee, undefined, 'customer delivery view must not include platform_fee');
});

test('a customer price quote never includes the rider payout breakdown', async () => {
  const customer = await register('customer');
  const priced = await request('/api/deliveries/price', {
    method: 'POST', token: customer.token,
    body: { pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng, dest_lat: DEST.lat, dest_lng: DEST.lng, service_type: 'parcel' }
  });
  assert.equal(priced.response.status, 200, priced.json.error);
  assert.equal(priced.json.data.riderEarnings, undefined);
  assert.equal(priced.json.data.platformFee, undefined);
  assert.ok(priced.json.data.customerPrice > 0);

  const admin = await adminToken();
  const adminPriced = await request('/api/deliveries/price', {
    method: 'POST', token: admin,
    body: { pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng, dest_lat: DEST.lat, dest_lng: DEST.lng, service_type: 'parcel' }
  });
  assert.equal(adminPriced.response.status, 200, adminPriced.json.error);
  assert.ok(adminPriced.json.data.riderEarnings > 0, 'the admin pricing simulator (spec §52) keeps the full breakdown');
});

test('nearby-riders reports in_service_area so the client can distinguish "no riders now" from "MOVO does not operate here" (spec §12)', async () => {
  const customer = await register('customer');

  const inArea = await request(`/api/mobile/v1/customer/nearby-riders?lat=${PICKUP.lat}&lng=${PICKUP.lng}&radius_km=5`, { token: customer.token });
  assert.equal(inArea.response.status, 200, inArea.json.error);
  assert.equal(inArea.json.data.in_service_area, true);
  assert.ok(inArea.json.data.zone?.name);

  const outOfArea = await request(`/api/mobile/v1/customer/nearby-riders?lat=${OUT_OF_AREA.lat}&lng=${OUT_OF_AREA.lng}&radius_km=5`, { token: customer.token });
  assert.equal(outOfArea.response.status, 200, outOfArea.json.error);
  assert.equal(outOfArea.json.data.in_service_area, false);
  assert.equal(outOfArea.json.data.zone, null);
  assert.deepEqual(outOfArea.json.data.riders, []);
});
