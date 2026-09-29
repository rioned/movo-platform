const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Zone neighbours: every GPS fix — rider or customer — is placed in a service zone
// (the one containing it, or the nearest one when it sits just off an edge), and
// rider search walks outward across the zone graph ring by ring: the pickup's own
// zone, then the zones next door, then the zones next door to those.
//
// The fixture is a synthetic line of zones far from the seeded Kigali map:
//   A (lng 10.00) — B (10.03) — C (10.06)          D (10.20, detached)
// Each has a 1.2 km radius, so A–B and B–C sit 0.94 km apart (neighbours under the
// default 1 km neighbour gap) while A–C are 4.3 km apart (not neighbours).

const root = path.resolve(__dirname, '..');
const port = 39400 + Math.floor(Math.random() * 400);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-zone-neighbours-${process.pid}-${Date.now()}.db`);
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
  const phone = `+25077${String(5000000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? {
      national_id: `1199955${String(serial).padStart(9, '0')}`,
      license_number: `LIC-ZN-${serial}`,
      motorcycle_plate: `RZN${String(serial).padStart(3, '0')}Z`
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
      JWT_SECRET: 'zone-neighbours-test-secret', OTP_TEST_MODE: 'true',
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

const KM_PER_DEG = 111.32;
const ZONES = [['zn-a', 'Line A', 10.00], ['zn-b', 'Line B', 10.03], ['zn-c', 'Line C', 10.06], ['zn-d', 'Line D', 10.20]];
const at = lng => ({ lat: 0, lng });

function installLineZones() {
  const insert = db.prepare(`INSERT OR REPLACE INTO delivery_zones
    (id,name,center_lat,center_lng,radius_km,base_price_parcel,base_price_document,per_km_rate,sort_order,is_active)
    VALUES (?,?,0,?,1.2,1500,1000,200,?,1)`);
  ZONES.forEach(([id, name, lng], index) => insert.run(id, name, lng, 100 + index));
  const price = db.prepare(`INSERT OR REPLACE INTO zone_pricing (id,origin_zone_id,dest_zone_id,parcel_price,document_price,estimated_min)
    VALUES (?,?,?,1500,1000,10)`);
  for (const [origin] of ZONES) for (const [dest] of ZONES) price.run(`zn-${origin}-${dest}`, origin, dest);
}

function setConfig(t, key, value) {
  const previous = db.prepare('SELECT value FROM pricing_config WHERE key=?').get(key)?.value;
  db.prepare('INSERT OR REPLACE INTO pricing_config (key,value) VALUES (?,?)').run(key, String(value));
  t.after(() => {
    if (previous === undefined) db.prepare('DELETE FROM pricing_config WHERE key=?').run(key);
    else db.prepare('INSERT OR REPLACE INTO pricing_config (key,value) VALUES (?,?)').run(key, previous);
  });
}

async function nearby(customer, point) {
  const query = point ? `?lat=${point.lat}&lng=${point.lng}&radius_km=5` : '';
  const result = await request(`/api/mobile/v1/customer/nearby-riders${query}`, { token: customer.token });
  assert.equal(result.response.status, 200, result.json.error);
  return result.json.data;
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

test('a customer GPS fix is placed in the zone that contains it', async () => {
  installLineZones();
  const customer = await register('customer');
  const placed = await request('/api/mobile/v1/customer/location', { method: 'PUT', token: customer.token, body: at(10.03) });
  assert.equal(placed.response.status, 200, placed.json.error);
  assert.equal(placed.json.data.zone.id, 'zn-b');
  assert.equal(placed.json.data.zone.placement, 'inside');
  assert.equal(placed.json.data.zone.in_service_area, true);

  const stored = await request('/api/mobile/v1/customer/location', { token: customer.token });
  assert.equal(stored.response.status, 200, stored.json.error);
  assert.equal(stored.json.data.zone.id, 'zn-b', 'the placement is persisted, not just echoed back');
});

test('a fix just off a zone edge is placed in the nearest zone; a fix far from every zone is not', async () => {
  installLineZones();
  const customer = await register('customer');
  // 0.3 km west of A's edge — inside the 0.5 km snap tolerance.
  const nearEdge = at(10.00 - 1.5 / KM_PER_DEG);
  const snapped = await request('/api/mobile/v1/customer/location', { method: 'PUT', token: customer.token, body: nearEdge });
  assert.equal(snapped.json.data.zone.id, 'zn-a');
  assert.equal(snapped.json.data.zone.placement, 'nearest');
  assert.ok(snapped.json.data.zone.distance_km > 0.25 && snapped.json.data.zone.distance_km < 0.35);

  // 0.8 km west of A's edge — beyond the tolerance, so genuinely out of area.
  const farOff = at(10.00 - 2.0 / KM_PER_DEG);
  const outside = await request('/api/mobile/v1/customer/location', { method: 'PUT', token: customer.token, body: farOff });
  assert.equal(outside.json.data.zone.id, null);
  assert.equal(outside.json.data.zone.in_service_area, false);

  // Riders are placed by exactly the same rule.
  parkEveryoneOffline();
  const rider = await riderAt(nearEdge.lat, nearEdge.lng);
  assert.equal(rider.zone.id, 'zn-a');
  assert.equal(rider.zone.placement, 'nearest');
  const listed = await nearby(customer, at(10.00));
  assert.deepEqual(listed.riders.map(r => r.id), [rider.id], 'a rider snapped into the pickup zone is a same-zone candidate');
  assert.equal(listed.riders[0].same_zone, true);
});

test('rider search walks own zone, then neighbours, then neighbours of neighbours', async () => {
  installLineZones();
  parkEveryoneOffline();
  const customer = await register('customer');

  // Only zone C has a rider: A is empty, its neighbour B is empty, so the search
  // reaches C as ring 2 (a neighbour of a neighbour).
  const inC = await riderAt(0, 10.06);
  let found = await nearby(customer, at(10.00));
  assert.equal(found.zone.id, 'zn-a');
  assert.equal(found.search_ring, 2);
  assert.deepEqual(found.riders.map(r => r.id), [inC.id]);
  assert.deepEqual(found.zones_searched.map(z => [z.id, z.ring]), [['zn-a', 0], ['zn-b', 1], ['zn-c', 2]]);

  // A rider appears in the neighbouring zone B: the search stops at ring 1 and no
  // longer reaches out to C.
  const inB = await riderAt(0, 10.03);
  found = await nearby(customer, at(10.00));
  assert.equal(found.search_ring, 1);
  assert.deepEqual(found.riders.map(r => r.id), [inB.id]);

  // A rider in the pickup's own zone: only they are shown.
  const inA = await riderAt(0, 10.005);
  found = await nearby(customer, at(10.00));
  assert.equal(found.search_ring, 0);
  assert.deepEqual(found.riders.map(r => r.id), [inA.id]);
  assert.equal(found.in_zone_rider_count, 1);
});

test('the search depth caps how many neighbour rings are walked', async (t) => {
  installLineZones();
  parkEveryoneOffline();
  setConfig(t, 'rider_search_max_zone_depth', 1);
  const customer = await register('customer');
  await riderAt(0, 10.06);   // zone C: ring 2, beyond a depth of 1
  const found = await nearby(customer, at(10.00));
  assert.equal(found.rider_count, 0);
  assert.equal(found.search_ring, 1);
  assert.deepEqual(found.zones_searched.map(z => z.id), ['zn-a', 'zn-b']);
});

test('a detached zone is reached only after every connected ring is exhausted', async (t) => {
  installLineZones();
  parkEveryoneOffline();
  setConfig(t, 'rider_search_max_zone_depth', 3);
  const customer = await register('customer');
  const inD = await riderAt(0, 10.20);
  const found = await nearby(customer, at(10.00));
  assert.equal(found.search_ring, 3, 'D is not a neighbour of anything, so it follows C');
  assert.deepEqual(found.riders.map(r => r.id), [inD.id]);
});

test('with no coordinates in the request, the search starts from the customer\'s GPS zone', async () => {
  installLineZones();
  parkEveryoneOffline();
  const customer = await register('customer');
  await request('/api/mobile/v1/customer/location', { method: 'PUT', token: customer.token, body: at(10.06) });
  const inC = await riderAt(0, 10.06);
  const found = await nearby(customer, null);
  assert.equal(found.zone.id, 'zn-c');
  assert.equal(found.search_ring, 0);
  assert.deepEqual(found.riders.map(r => r.id), [inC.id]);
});

test('admin zone listing shows each zone\'s neighbours', async () => {
  installLineZones();
  const admin = db.prepare("SELECT phone FROM users WHERE role='admin' LIMIT 1").get();
  const login = await request('/api/auth/login', { method: 'POST', body: { phone: admin.phone, password: 'Admin@2026' } });
  const zones = await request('/api/admin/zones', { token: login.json.data.token });
  assert.equal(zones.response.status, 200, zones.json.error);
  const byId = new Map(zones.json.data.map(zone => [zone.id, zone]));
  assert.deepEqual(byId.get('zn-a').neighbour_zone_ids, ['zn-b']);
  assert.deepEqual(new Set(byId.get('zn-b').neighbour_zone_ids), new Set(['zn-a', 'zn-c']));
  assert.deepEqual(byId.get('zn-d').neighbour_zone_ids, []);
  // Seeded Kigali: Kanombe and Masaka overlap, so they are neighbours.
  assert.ok(byId.get('z8').neighbour_zone_ids.includes('z9'));
});

for (const kind of ['delivery', 'ride']) test(`${kind} dispatch reaches a neighbour-of-neighbour zone one ring per wave`, async () => {
  installLineZones();
  parkEveryoneOffline();
  const customer = await register('customer');
  const inC = await riderAt(0, 10.06);
  const created = await request(kind === 'delivery' ? '/api/deliveries' : '/api/rides', {
    method: 'POST', token: customer.token,
    body: {
      ride_type_id: db.prepare('SELECT id FROM ride_types LIMIT 1').get().id,
      service_type: 'parcel', pickup_address: 'A', pickup_lat: 0, pickup_lng: 10.00,
      dest_address: 'B', dest_lat: 0, dest_lng: 10.03, pickup_name: 'Sender', pickup_phone: '+250780001111',
      dest_name: 'Receiver', dest_phone: '+250780002222', item_description: 'Book', payment_method: 'cash'
    }
  });
  assert.equal(created.response.status, 201, JSON.stringify(created.json));
  const id = created.json.data[kind].id;
  const offers = () => db.prepare(`SELECT ${kind === 'delivery' ? 'rider_id' : 'driver_id'} AS rider_id FROM ${kind}_offers WHERE ${kind}_id=?`).all(id);

  // Wave 0 (A) and wave 1 (B) are empty; each empty wave expands after ~3 s.
  await sleep(2500);
  assert.equal(offers().length, 0, 'nobody in A or B, so nobody is offered yet');
  await sleep(4500);
  assert.deepEqual(offers().map(o => o.rider_id), [inC.id], 'ring 2 (zone C) is reached after two expansions');
  db.prepare(`UPDATE ${kind === 'ride' ? 'rides' : 'deliveries'} SET status='cancelled' WHERE id=?`).run(id);
});
