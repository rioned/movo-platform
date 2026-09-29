const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Dynamic zoning: zone assignment follows riders and customers as they move. Every
// GPS fix re-zones its sender on arrival, and a background sweep (every 5 minutes
// in production, every second here) re-places everyone from their latest position.
// A rider who accepts a job is marked busy and holds no zone until the job ends,
// however many fixes they send meanwhile; ending the job re-zones them.
//
// Fixture: two disjoint zones far from the seeded Kigali map, X (lng 20.00) and
// Y (lng 20.10), 1.2 km radius each.

const root = path.resolve(__dirname, '..');
const port = 39850 + Math.floor(Math.random() * 400);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-zone-dynamic-${process.pid}-${Date.now()}.db`);
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

async function register(role) {
  serial += 1;
  const phone = `+25077${String(6000000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? {
      national_id: `1199955${String(serial).padStart(9, '0')}`,
      license_number: `LIC-ZD-${serial}`,
      motorcycle_plate: `RZD${String(serial).padStart(3, '0')}Z`
    } : {})
  };
  const registration = await request('/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

async function adminToken() {
  const admin = db.prepare("SELECT phone FROM users WHERE role='admin' LIMIT 1").get();
  const login = await request('/api/auth/login', { method: 'POST', body: { phone: admin.phone, password: 'Admin@2026' } });
  assert.equal(login.response.status, 200, login.json.error);
  return login.json.data.token;
}

// Brings an approved rider online and parks them at a coordinate.
async function riderAt(lat, lng) {
  const rider = await register('rider');
  db.prepare("UPDATE riders SET approval_status='approved' WHERE user_id=?").run(rider.id);
  const status = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  assert.equal(status.response.status, 200, status.json.error);
  const posted = await request('/api/rider/location', { method: 'PUT', token: rider.token, body: { lat, lng } });
  assert.equal(posted.response.status, 200, posted.json.error);
  return { ...rider, zone: posted.json.data.zone };
}

// The zone a coordinate resolves to, read through the customer-facing endpoint.
function parkEveryoneOffline() {
  db.prepare("UPDATE riders SET online_status='offline',availability='offline'").run();
}
test.before(async () => {
  server = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: {
      ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath,
      JWT_SECRET: 'zone-dynamic-test-secret', OTP_TEST_MODE: 'true', ZONE_REFRESH_INTERVAL_SEC: '1',
      RATE_LIMIT_ENABLED: 'false', ADMIN_SEED_PASSWORD: 'Admin@2026'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for server')), 15000);
    server.stdout.on('data', chunk => {
      if (chunk.toString().includes('Server running')) { clearTimeout(timeout); resolve(); }
    });
    server.stderr.on('data', chunk => process.stderr.write(chunk));
    server.once('error', reject);
  });
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});


const X = { lat: 0, lng: 20.00 };
const Y = { lat: 0, lng: 20.10 };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function installZones() {
  const insert = db.prepare(`INSERT OR REPLACE INTO delivery_zones
    (id,name,center_lat,center_lng,radius_km,base_price_parcel,base_price_document,per_km_rate,sort_order,is_active)
    VALUES (?,?,0,?,1.2,1500,1000,200,?,1)`);
  insert.run('zd-x', 'Zone X', X.lng, 200);
  insert.run('zd-y', 'Zone Y', Y.lng, 201);
  const price = db.prepare(`INSERT OR REPLACE INTO zone_pricing (id,origin_zone_id,dest_zone_id,parcel_price,document_price,estimated_min)
    VALUES (?,?,?,1500,1000,10)`);
  for (const o of ['zd-x', 'zd-y']) for (const d of ['zd-x', 'zd-y']) price.run(`zd-${o}-${d}`, o, d);
}

const riderRow = id => db.prepare('SELECT online_status,current_zone_id,current_lat,current_lng FROM riders WHERE user_id=?').get(id);

async function moveRider(rider, point) {
  const posted = await request('/api/rider/location', { method: 'PUT', token: rider.token, body: point });
  assert.equal(posted.response.status, 200, posted.json.error);
  return posted.json.data.zone;
}

async function acceptedDelivery(rider) {
  const customer = await register('customer');
  const created = await request('/api/deliveries', {
    method: 'POST', token: customer.token,
    body: {
      service_type: 'parcel', pickup_address: 'X', pickup_lat: X.lat, pickup_lng: X.lng,
      dest_address: 'Y', dest_lat: Y.lat, dest_lng: Y.lng, pickup_name: 'Sender', pickup_phone: '+250780001111',
      dest_name: 'Receiver', dest_phone: '+250780002222', item_description: 'Book', payment_method: 'cash'
    }
  });
  assert.equal(created.response.status, 201, JSON.stringify(created.json));
  const id = created.json.data.delivery.id;
  const accepted = await request(`/api/deliveries/${id}/accept`, { method: 'PUT', token: rider.token });
  assert.equal(accepted.response.status, 200, accepted.json.error);
  return { id, customer };
}

test('a rider who moves is re-zoned in real time on every fix', async () => {
  installZones();
  parkEveryoneOffline();
  const rider = await riderAt(X.lat, X.lng);
  assert.equal(rider.zone.id, 'zd-x');
  assert.equal((await moveRider(rider, Y)).id, 'zd-y');
  assert.equal(riderRow(rider.id).current_zone_id, 'zd-y');
  assert.equal((await moveRider(rider, X)).id, 'zd-x');
});

test('a customer who moves is re-zoned in real time on every fix', async () => {
  installZones();
  const customer = await register('customer');
  const first = await request('/api/mobile/v1/customer/location', { method: 'PUT', token: customer.token, body: X });
  assert.equal(first.json.data.zone.id, 'zd-x');
  const second = await request('/api/mobile/v1/customer/location', { method: 'PUT', token: customer.token, body: Y });
  assert.equal(second.json.data.zone.id, 'zd-y');
  const stored = await request('/api/mobile/v1/customer/location', { token: customer.token });
  assert.equal(stored.json.data.zone.id, 'zd-y');
});

test('the periodic sweep re-places riders and customers from their latest position', async () => {
  installZones();
  parkEveryoneOffline();
  const rider = await riderAt(X.lat, X.lng);
  const customer = await register('customer');
  await request('/api/mobile/v1/customer/location', { method: 'PUT', token: customer.token, body: X });

  // Positions change underneath the cached zones (as a zone edit or a missed
  // ingest would leave them); the sweep must catch up without any new fix.
  db.prepare("UPDATE riders SET current_lat=?,current_lng=? WHERE user_id=?").run(Y.lat, Y.lng, rider.id);
  db.prepare("UPDATE customer_positions SET lat=?,lng=? WHERE user_id=?").run(Y.lat, Y.lng, customer.id);
  await sleep(2500);
  assert.equal(riderRow(rider.id).current_zone_id, 'zd-y');
  assert.equal(db.prepare('SELECT zone_id FROM customer_positions WHERE user_id=?').get(customer.id).zone_id, 'zd-y');
});

test('accepting a job marks the rider busy and unzoned until the job finishes', async () => {
  installZones();
  parkEveryoneOffline();
  const rider = await riderAt(X.lat, X.lng);
  const { id } = await acceptedDelivery(rider);

  let row = riderRow(rider.id);
  assert.equal(row.online_status, 'busy');
  assert.equal(row.current_zone_id, null, 'a rider on a job holds no zone');

  // Fixes during the job are recorded for tracking, but assign no zone.
  const during = await moveRider(rider, Y);
  assert.equal(during.id, null);
  assert.equal(during.placement, 'on_job');
  row = riderRow(rider.id);
  assert.equal(row.current_zone_id, null);
  assert.equal(row.current_lng, Y.lng, 'the position itself is still tracked');

  // The periodic sweep does not re-zone a busy rider either.
  await sleep(2500);
  assert.equal(riderRow(rider.id).current_zone_id, null);

  // Rider home states why: on a job.
  const home = await request('/api/mobile/v1/rider/home', { token: rider.token });
  assert.equal(home.json.data.reason, 'on_job');
  assert.equal(home.json.data.dispatchable, false);

  // Nobody searching either zone sees the busy rider.
  const customer = await register('customer');
  for (const point of [X, Y]) {
    const seen = await request(`/api/mobile/v1/customer/nearby-riders?lat=${point.lat}&lng=${point.lng}`, { token: customer.token });
    assert.ok(!seen.json.data.riders.some(r => r.id === rider.id));
  }

  // Finishing the job (closed from operations here) returns them to the pool,
  // zoned where they now stand — Y, not the X they started in.
  const admin = db.prepare("SELECT phone FROM users WHERE role='admin' LIMIT 1").get();
  const login = await request('/api/auth/login', { method: 'POST', body: { phone: admin.phone, password: 'Admin@2026' } });
  const closed = await request(`/api/admin/deliveries/${id}`, { method: 'PUT', token: login.json.data.token, body: { status: 'cancelled' } });
  assert.equal(closed.response.status, 200, closed.json.error);
  row = riderRow(rider.id);
  assert.equal(row.online_status, 'online');
  assert.equal(row.current_zone_id, 'zd-y');
});

test('a rider cannot re-zone themselves by toggling to busy', async () => {
  installZones();
  parkEveryoneOffline();
  const rider = await riderAt(X.lat, X.lng);
  const busy = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'busy' } });
  assert.equal(busy.response.status, 200, busy.json.error);
  assert.equal(busy.json.data.zone.id, null);
  assert.equal(riderRow(rider.id).current_zone_id, null);
  const online = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  assert.equal(online.json.data.zone.id, 'zd-x');
});

test('ride accept marks the driver busy and unzoned, and completion re-zones them', async () => {
  installZones();
  parkEveryoneOffline();
  const driver = await riderAt(X.lat, X.lng);
  const customer = await register('customer');
  const created = await request('/api/rides', {
    method: 'POST', token: customer.token,
    body: {
      ride_type_id: db.prepare('SELECT id FROM ride_types LIMIT 1').get().id,
      pickup_address: 'X', pickup_lat: X.lat, pickup_lng: X.lng, dest_address: 'Y', dest_lat: Y.lat, dest_lng: Y.lng, payment_method: 'cash'
    }
  });
  assert.equal(created.response.status, 201, JSON.stringify(created.json));
  const rideId = created.json.data.ride.id;
  const accepted = await request(`/api/rides/${rideId}/accept`, { method: 'PUT', token: driver.token });
  assert.equal(accepted.response.status, 200, accepted.json.error);
  assert.equal(riderRow(driver.id).online_status, 'busy');
  assert.equal(riderRow(driver.id).current_zone_id, null);
  await moveRider(driver, Y);
  assert.equal(riderRow(driver.id).current_zone_id, null);

  const admin = db.prepare("SELECT phone FROM users WHERE role='admin' LIMIT 1").get();
  const login = await request('/api/auth/login', { method: 'POST', body: { phone: admin.phone, password: 'Admin@2026' } });
  const closed = await request(`/api/admin/rides/${rideId}`, { method: 'PUT', token: login.json.data.token, body: { status: 'completed' } });
  assert.equal(closed.response.status, 200, closed.json.error);
  assert.equal(riderRow(driver.id).online_status, 'online');
  assert.equal(riderRow(driver.id).current_zone_id, 'zd-y');
});
