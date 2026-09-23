const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { io } = require('socket.io-client');

// Zone routing: the rule the whole platform is built on is "resolve every
// coordinate to a zone at ingest, match a pickup to riders in its own zone first,
// and only then walk outward to the next nearest zone". These tests pin the parts
// of that rule that were silently broken: overlapping zones resolving to whichever
// zone happened to sort first, riders outside every service area being treated as
// ordinary candidates, a flat kilometre radius standing in for zone adjacency, and
// the cached rider zone going stale when the map changed underneath it.

const root = path.resolve(__dirname, '..');
const port = 38950 + Math.floor(Math.random() * 400);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-zone-routing-${process.pid}-${Date.now()}.db`);
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
  const phone = `+25077${String(3000000 + serial).slice(-7)}`;
  const payload = {
    role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1',
    ...(role === 'rider' ? {
      national_id: `1199955${String(serial).padStart(9, '0')}`,
      license_number: `LIC-ZR-${serial}`,
      motorcycle_plate: `RZR${String(serial).padStart(3, '0')}Z`
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
async function zoneAt(token, lat, lng) {
  const result = await request(`/api/mobile/v1/customer/nearby-riders?lat=${lat}&lng=${lng}&radius_km=1`, { token });
  assert.equal(result.response.status, 200, result.json.error);
  return result.json.data.zone;
}

function parkEveryoneOffline() {
  db.prepare("UPDATE riders SET online_status='offline',availability='offline'").run();
}

const sockets = new Set();

function once(socket, event, timeout = 3000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${event}`)), timeout);
    socket.once(event, value => { clearTimeout(timer); resolve(value); });
  });
}

async function connectAuthenticated(token) {
  const socket = io(base, { transports: ['websocket'], forceNew: true, reconnection: false });
  sockets.add(socket);
  await once(socket, 'connect');
  const authenticated = once(socket, 'authenticated');
  socket.emit('authenticate', token);
  await authenticated;
  return socket;
}

test.before(async () => {
  server = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: {
      ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath,
      JWT_SECRET: 'zone-routing-test-secret', OTP_TEST_MODE: 'true',
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
  for (const socket of sockets) socket.close();
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('every zone resolves its own center, so overlapping zones do not steal each other\'s points', async () => {
  // The seeded Kigali zones overlap in 26 of their 45 pairs. Resolving a point to
  // the first zone that merely contains it handed 6 of the 10 zone centers to a
  // neighbour — Masaka's own center resolved to Kicukiro — which then quoted the
  // wrong price and searched the wrong rider pool. A point belongs to the zone it
  // sits deepest inside, not to whichever zone sorts first.
  const customer = await register('customer');
  const zones = db.prepare('SELECT id,name,center_lat,center_lng FROM delivery_zones WHERE is_active=1 ORDER BY sort_order').all();
  assert.ok(zones.length >= 10, 'the seeded zone set should be present');

  for (const zone of zones) {
    const resolved = await zoneAt(customer.token, zone.center_lat, zone.center_lng);
    assert.equal(resolved?.id, zone.id, `the center of ${zone.name} must resolve to ${zone.name}, not to an overlapping neighbour`);
  }
});

test('a rider parked outside every service area is not a candidate for a pickup inside one', async () => {
  // Being within N kilometres of a pickup is not the same as serving it. A rider
  // sitting outside every zone has no priced relationship with the pickup's area,
  // so they must not be offered the job however close the straight line looks.
  parkEveryoneOffline();
  const customer = await register('customer');

  // Outside every seeded zone, yet only ~5.5 km from the Kicukiro pickup.
  const outside = { lat: -2.0243, lng: 30.0945 };
  const parked = await riderAt(outside.lat, outside.lng);
  assert.equal(parked.zone.in_service_area, false, 'this fixture point must genuinely be outside every zone');
  assert.equal(parked.zone.id, null);

  const pickup = { lat: -1.9783, lng: 30.1125 };
  const nearby = await request(`/api/mobile/v1/customer/nearby-riders?lat=${pickup.lat}&lng=${pickup.lng}&radius_km=8`, { token: customer.token });
  assert.equal(nearby.response.status, 200, nearby.json.error);
  assert.equal(nearby.json.data.in_service_area, true, 'the pickup itself is inside a zone');
  assert.equal(nearby.json.data.rider_count, 0, 'an out-of-area rider is never a candidate for an in-zone pickup');
});

test('a rider row with no cached zone is still dispatchable from its coordinates', async () => {
  // current_zone_id is a cache written at location ingest. Rows that predate the
  // zone columns (or came from an import) carry NULL until the rider's next GPS
  // ping, and reading NULL as "outside every service area" would silently make
  // every one of those riders undispatchable — a fleet-wide outage, not a filter.
  parkEveryoneOffline();
  const customer = await register('customer');
  const pickup = { lat: -1.9441, lng: 30.0619 };
  const rider = await riderAt(pickup.lat, pickup.lng);

  db.prepare('UPDATE riders SET current_zone_id=NULL,current_zone_name=NULL WHERE user_id=?').run(rider.id);

  const nearby = await request(`/api/mobile/v1/customer/nearby-riders?lat=${pickup.lat}&lng=${pickup.lng}&radius_km=5`, { token: customer.token });
  assert.equal(nearby.response.status, 200, nearby.json.error);
  assert.equal(nearby.json.data.rider_count, 1, 'an uncached rider standing in the pickup zone is still a candidate');
  assert.equal(nearby.json.data.in_zone_rider_count, 1, 'their zone is resolved from their coordinates, not assumed absent');
});

test('a rider in the pickup zone outranks a closer rider from another zone', async () => {
  // The whole point of zoning: the price was quoted for this zone, so the rider who
  // serves it wins even when a rider across the boundary is nearer in a straight line.
  parkEveryoneOffline();
  const customer = await register('customer');
  const pickup = { lat: -1.9583, lng: 30.1400 };   // z8 Kanombe
  const pickupZone = await zoneAt(customer.token, pickup.lat, pickup.lng);

  // In the pickup's own zone, but deliberately the farther of the two (~3.98 km).
  const inZone = await riderAt(-1.9263, 30.1560);
  // In a different zone, deliberately closer to the pickup (~2.05 km).
  const outZone = await riderAt(-1.9703, 30.1260);
  assert.equal(inZone.zone.id, pickupZone.id, 'fixture: this rider shares the pickup zone');
  assert.notEqual(outZone.zone.id, pickupZone.id, 'fixture: this rider is in another zone');

  const nearby = await request(`/api/mobile/v1/customer/nearby-riders?lat=${pickup.lat}&lng=${pickup.lng}&radius_km=10`, { token: customer.token });
  assert.equal(nearby.response.status, 200, nearby.json.error);
  const listed = nearby.json.data.riders;
  assert.equal(listed[0].id, inZone.id, 'the in-zone rider leads even though the other one is closer');
  assert.ok(listed[0].distance_km > listed[1].distance_km, 'fixture: the in-zone rider really is the farther one');
  assert.equal(listed[0].same_zone, true);
  assert.equal(listed[1].same_zone, false);
});

test('an empty pickup zone falls through to the next nearest zone, not to a flat radius', async () => {
  // Dispatch wave 0 asks the pickup's own zone only. When nobody there is free it
  // admits the next-nearest *zone*, so the fallback follows the service-area map
  // rather than a circle drawn on top of it.
  parkEveryoneOffline();
  const customer = await register('customer');
  const pickup = { lat: -1.9583, lng: 30.1400 };   // z8 Kanombe, left empty
  const pickupZone = await zoneAt(customer.token, pickup.lat, pickup.lng);

  const neighbour = await riderAt(-1.9950, 30.1250);  // z9 Masaka, the adjacent zone
  assert.notEqual(neighbour.zone.id, pickupZone.id);
  assert.equal(neighbour.zone.in_service_area, true);

  const created = await request('/api/deliveries', {
    method: 'POST', token: customer.token,
    body: {
      service_type: 'parcel',
      pickup_address: 'Kanombe', pickup_lat: pickup.lat, pickup_lng: pickup.lng,
      pickup_name: 'Sender', pickup_phone: '+250788111111',
      dest_address: 'Kicukiro', dest_lat: -1.9783, dest_lng: 30.1125,
      dest_name: 'Receiver', dest_phone: '+250788222222',
      item_description: 'Contract', payment_method: 'mobile_money'
    }
  });
  assert.equal(created.response.status, 201, created.json.error);
  const deliveryId = created.json.data.delivery.id;

  // Wave 0 is the pickup's own zone, which has nobody in it.
  const waveZero = db.prepare("SELECT rider_id FROM delivery_offers WHERE delivery_id=? AND status='offered'").all(deliveryId);
  assert.equal(waveZero.length, 0, 'the first wave asks only the pickup zone, which is empty');

  // Each empty wave expands one zone tier after ~3s. Ranked by nearest edge,
  // Kicukiro (0.28 km) sits ahead of Masaka (0.41 km), so Masaka is tier 2.
  await new Promise(resolve => setTimeout(resolve, 7000));
  const offered = db.prepare('SELECT rider_id FROM delivery_offers WHERE delivery_id=?').all(deliveryId);
  assert.ok(offered.some(offer => offer.rider_id === neighbour.id),
    'with the home zone empty, the next nearest zone\'s rider must be reached');
});

test('coming online re-resolves the rider zone from their last fix, and going offline clears it', async () => {
  // A rider who goes offline in one zone and comes back after the map changed must
  // not rejoin the old zone's pool. Availability changes are the moment a rider
  // joins or leaves a dispatch pool, so the zone is settled there too.
  parkEveryoneOffline();
  const rider = await riderAt(-1.9441, 30.0619);
  assert.ok(rider.zone.id, 'fixture: the rider starts inside a zone');

  const offline = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'offline' } });
  assert.equal(offline.response.status, 200, offline.json.error);
  assert.equal(offline.json.data.dispatchable, false);
  const whileOffline = db.prepare('SELECT current_zone_id FROM riders WHERE user_id=?').get(rider.id);
  assert.equal(whileOffline.current_zone_id, null, 'an offline rider holds no place in a zone pool');

  const online = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  assert.equal(online.response.status, 200, online.json.error);
  assert.equal(online.json.data.zone.id, rider.zone.id, 'coming back online restores the zone from the last known fix');
  assert.equal(online.json.data.dispatchable, true);
  const whileOnline = db.prepare('SELECT current_zone_id FROM riders WHERE user_id=?').get(rider.id);
  assert.equal(whileOnline.current_zone_id, rider.zone.id);
});

test('a rider who has never sent a fix is online but unzoned, and not dispatchable', async () => {
  // Online with no position is a real state (app opened, GPS not yet acquired). It
  // must be visible to the rider app rather than looking like a working rider who
  // mysteriously receives no offers.
  const rider = await register('rider');
  db.prepare("UPDATE riders SET approval_status='approved' WHERE user_id=?").run(rider.id);
  const status = await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  assert.equal(status.response.status, 200, status.json.error);
  assert.equal(status.json.data.online_status, 'online');
  assert.equal(status.json.data.zone.in_service_area, false);
  assert.equal(status.json.data.zone.id, null);
  assert.equal(status.json.data.dispatchable, false, 'no fix yet means no zone, and no zone means no offers');
});

test('an admin zone change re-resolves the cached rider zones instead of leaving them stale', async () => {
  // The rider zone is a cache. When an admin moves, redraws or deactivates a zone,
  // dispatch would otherwise keep matching against the old map until every rider
  // happened to send their next GPS ping.
  parkEveryoneOffline();
  const admin = await adminToken();
  const customer = await register('customer');
  const point = { lat: -1.9441, lng: 30.0619 };
  const rider = await riderAt(point.lat, point.lng);
  const originalZoneId = rider.zone.id;
  assert.ok(originalZoneId);

  const deactivated = await request(`/api/admin/zones/${originalZoneId}`, {
    method: 'PUT', token: admin, body: { is_active: 0 }
  });
  assert.equal(deactivated.response.status, 200, deactivated.json.error);
  assert.ok(deactivated.json.data.riders_rezoned >= 1, 'the zone change must report the riders it re-resolved');

  const cached = db.prepare('SELECT current_zone_id FROM riders WHERE user_id=?').get(rider.id);
  assert.notEqual(cached.current_zone_id, originalZoneId, 'the cached zone must not survive its zone being deactivated');

  // The rider is still where they were, so they belong to whichever zone now covers
  // that point — and they must still be discoverable there, in-zone.
  const resolvedNow = await zoneAt(customer.token, point.lat, point.lng);
  assert.equal(cached.current_zone_id, resolvedNow?.id ?? null, 'the cache must agree with a fresh resolution of the same point');

  // Restore the zone so the fixture leaves the map as it found it.
  const restored = await request(`/api/admin/zones/${originalZoneId}`, {
    method: 'PUT', token: admin, body: { is_active: 1 }
  });
  assert.equal(restored.response.status, 200, restored.json.error);
  const rezoned = db.prepare('SELECT current_zone_id FROM riders WHERE user_id=?').get(rider.id);
  assert.equal(rezoned.current_zone_id, originalZoneId, 're-activating the zone puts the rider back in it');
});

test('a rider streaming position over the socket is zoned exactly like the REST path', async () => {
  // There are three ways a rider position reaches the server: the REST endpoint and
  // two socket channels. Only the REST one used to resolve the zone, so a rider
  // whose app streamed over the socket kept whatever zone they last had — dispatch
  // then matched them against a service area they had long since driven out of.
  parkEveryoneOffline();
  const customer = await register('customer');
  const start = { lat: -1.9441, lng: 30.0619 };   // z1
  const rider = await riderAt(start.lat, start.lng);
  const startZone = rider.zone.id;
  assert.ok(startZone);

  const created = await request('/api/deliveries', {
    method: 'POST', token: customer.token,
    body: {
      service_type: 'parcel',
      pickup_address: 'Kacyiru', pickup_lat: start.lat, pickup_lng: start.lng,
      pickup_name: 'Sender', pickup_phone: '+250788111111',
      dest_address: 'Kicukiro', dest_lat: -1.9783, dest_lng: 30.1125,
      dest_name: 'Receiver', dest_phone: '+250788222222',
      item_description: 'Parcel', payment_method: 'mobile_money'
    }
  });
  assert.equal(created.response.status, 201, created.json.error);
  const deliveryId = created.json.data.delivery.id;
  const accepted = await request(`/api/deliveries/${deliveryId}/accept`, { method: 'PUT', token: rider.token, body: {} });
  assert.equal(accepted.response.status, 200, accepted.json.error);

  // Stream a position in a DIFFERENT zone over the socket only.
  const socket = await connectAuthenticated(rider.token);
  const subscribed = once(socket, 'delivery_subscribed');
  socket.emit('subscribe_delivery', { delivery_id: deliveryId });
  await subscribed;
  const moved = { lat: -1.9783, lng: 30.1125 };   // z4 Kicukiro
  const broadcast = once(socket, 'rider_location');
  socket.emit('rider_location', { delivery_id: deliveryId, lat: moved.lat, lng: moved.lng });
  await broadcast;

  const stored = db.prepare('SELECT current_lat,current_zone_id FROM riders WHERE user_id=?').get(rider.id);
  assert.equal(Math.round(stored.current_lat * 1e4), Math.round(moved.lat * 1e4), 'the socket fix is persisted');
  assert.ok(stored.current_zone_id, 'a socket fix must carry a resolved zone, never a null one');
  assert.notEqual(stored.current_zone_id, startZone, 'the rider moved zones, so the cached zone must move with them');

  const expected = await zoneAt(customer.token, moved.lat, moved.lng);
  assert.equal(stored.current_zone_id, expected.id, 'socket ingest must resolve the same zone the REST path would');

  // The history row carries the zone too, so operations can replay where a rider was.
  const trail = db.prepare('SELECT zone_id FROM rider_locations WHERE rider_id=? ORDER BY rowid DESC LIMIT 1').get(rider.id);
  assert.equal(trail.zone_id, expected.id, 'the location trail records the zone each fix was resolved to');
});

test('a rider returned to the pool after a delivery is zoned at the drop-off, not the pickup', async () => {
  // A rider finishes wherever the drop-off was, which is routinely a different zone
  // from the one the job started in. Flipping them back to online without
  // re-resolving would advertise them in a zone they have already left.
  parkEveryoneOffline();
  const customer = await register('customer');
  const pickup = { lat: -1.9441, lng: 30.0619 };   // z1
  const dest = { lat: -1.9783, lng: 30.1125 };     // z4
  const rider = await riderAt(pickup.lat, pickup.lng);
  const pickupZone = rider.zone.id;

  const created = await request('/api/deliveries', {
    method: 'POST', token: customer.token,
    body: {
      service_type: 'parcel',
      pickup_address: 'Kacyiru', pickup_lat: pickup.lat, pickup_lng: pickup.lng,
      pickup_name: 'Sender', pickup_phone: '+250788111111',
      dest_address: 'Kicukiro', dest_lat: dest.lat, dest_lng: dest.lng,
      dest_name: 'Receiver', dest_phone: '+250788222222',
      item_description: 'Parcel', payment_method: 'mobile_money'
    }
  });
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

  // The rider is now standing at the drop-off, in a different zone.
  const atDropoff = await request('/api/rider/location', { method: 'PUT', token: rider.token, body: { lat: dest.lat, lng: dest.lng } });
  assert.equal(atDropoff.response.status, 200, atDropoff.json.error);
  const destZone = atDropoff.json.data.zone.id;
  assert.notEqual(destZone, pickupZone, 'fixture: the drop-off is in a different zone from the pickup');

  // Simulate the cache having gone stale during the job — which is exactly what a
  // mid-job zone edit, or an ingest path that skipped zone resolution, leaves behind.
  // Completion must re-resolve from the rider's actual coordinates rather than
  // trusting whatever the cached value happens to say.
  db.prepare('UPDATE riders SET current_zone_id=?,current_zone_name=? WHERE user_id=?')
    .run(pickupZone, 'stale zone from the pickup', rider.id);

  const completed = await request(`/api/deliveries/${deliveryId}/complete`, {
    method: 'PUT', token: rider.token, body: { otp: otps.delivery_otp }
  });
  assert.equal(completed.response.status, 200, completed.json.error);

  const pooled = db.prepare('SELECT online_status,current_zone_id FROM riders WHERE user_id=?').get(rider.id);
  assert.equal(pooled.online_status, 'online', 'the rider is back in the dispatch pool');
  assert.equal(pooled.current_zone_id, destZone, 'they rejoin the pool in the zone they are actually standing in');

  // And they are genuinely discoverable there, in-zone, rather than back at the pickup.
  const atDest = await request(`/api/mobile/v1/customer/nearby-riders?lat=${dest.lat}&lng=${dest.lng}&radius_km=5`, { token: customer.token });
  assert.equal(atDest.response.status, 200, atDest.json.error);
  assert.equal(atDest.json.data.in_zone_rider_count, 1, 'the finished rider serves the drop-off zone now');
});

test('rider home states the rider\'s dispatch standing, not just that they are online', async () => {
  // A rider who is online but unzoned receives nothing, and the old payload gave the
  // app no way to tell that apart from a normal quiet spell. The rider app showed
  // "ONLINE - READY / Waiting for offers" to someone who would wait forever.
  parkEveryoneOffline();
  const rider = await register('rider');
  db.prepare("UPDATE riders SET approval_status='approved' WHERE user_id=?").run(rider.id);

  // Online, but no GPS fix has ever arrived.
  await request('/api/rider/status', { method: 'PUT', token: rider.token, body: { status: 'online' } });
  const noFix = await request('/api/mobile/v1/rider/home', { token: rider.token });
  assert.equal(noFix.response.status, 200, noFix.json.error);
  assert.equal(noFix.json.data.online_status, 'online');
  assert.equal(noFix.json.data.dispatchable, false, 'online without a zone is not dispatchable');
  assert.equal(noFix.json.data.reason, 'no_location_fix');
  assert.equal(noFix.json.data.zone.in_service_area, false);

  // A fix inside a zone makes them genuinely dispatchable, and names the zone.
  const inside = await request('/api/rider/location', { method: 'PUT', token: rider.token, body: { lat: -1.9441, lng: 30.0619 } });
  assert.equal(inside.response.status, 200, inside.json.error);
  const ready = await request('/api/mobile/v1/rider/home', { token: rider.token });
  assert.equal(ready.json.data.dispatchable, true);
  assert.equal(ready.json.data.reason, null);
  assert.equal(ready.json.data.zone.in_service_area, true);
  assert.ok(ready.json.data.zone.name, 'the rider is told which zone they are serving');

  // Riding out of every service area is reported as such, not as a silent dead end.
  const outside = await request('/api/rider/location', { method: 'PUT', token: rider.token, body: { lat: -2.0243, lng: 30.0945 } });
  assert.equal(outside.response.status, 200, outside.json.error);
  assert.equal(outside.json.data.zone.in_service_area, false);
  const stranded = await request('/api/mobile/v1/rider/home', { token: rider.token });
  assert.equal(stranded.json.data.dispatchable, false);
  assert.equal(stranded.json.data.reason, 'outside_service_area');
});

test('a polygon zone is ranked by its nearest edge, not its nearest corner', async () => {
  // Real admin-drawn service areas have long edges. Measuring to the nearest vertex
  // instead of the nearest edge overstates the distance badly in the middle of an
  // edge — a rider ~550 m outside an 11 km-wide boundary measures 5.6 km from its
  // closest corner. That 10x error reorders the zone walk, so dispatch skips the
  // neighbour it is actually standing against and offers the job to a farther zone.
  const admin = await adminToken();
  const customer = await register('customer');
  parkEveryoneOffline();

  // A wide rectangle far from the seeded Kigali zones, so nothing else interferes.
  const west = 29.50, east = 29.61, north = -1.50, south = -1.55;
  const wide = await request('/api/admin/zones', {
    method: 'POST', token: admin,
    body: {
      name: 'Wide Edge Zone', center_lat: (north + south) / 2, center_lng: (west + east) / 2,
      radius_km: 0.001, base_price_parcel: 1500, base_price_document: 1000,
      boundary_geojson: {
        type: 'Polygon',
        coordinates: [[[west, north], [east, north], [east, south], [west, south], [west, north]]]
      }
    }
  });
  assert.equal(wide.response.status, 201, wide.json.error);

  // A second, small zone placed so it is FARTHER from the probe than the wide zone's
  // edge (~0.55 km) but NEARER than the wide zone's closest corner (~5.6 km).
  const probe = { lat: north + 0.005, lng: (west + east) / 2 };   // just outside the top edge
  const near = await request('/api/admin/zones', {
    method: 'POST', token: admin,
    body: {
      name: 'Decoy Corner Zone', center_lat: probe.lat + 0.025, center_lng: probe.lng,
      radius_km: 0.5, base_price_parcel: 1500, base_price_document: 1000
    }
  });
  assert.equal(near.response.status, 201, near.json.error);

  // A rider inside the wide polygon, close to the edge the probe sits against.
  const insideWide = await riderAt(north - 0.005, probe.lng);
  assert.equal(insideWide.zone.name, 'Wide Edge Zone', 'fixture: this rider is inside the wide polygon zone');
  // A rider inside the decoy zone, which is farther from the probe than that edge.
  const insideDecoy = await riderAt(probe.lat + 0.025, probe.lng);
  assert.equal(insideDecoy.zone.name, 'Decoy Corner Zone', 'fixture: this rider is inside the decoy zone');

  // The probe point itself is outside both zones, so ranking decides who leads.
  const nearby = await request(`/api/mobile/v1/customer/nearby-riders?lat=${probe.lat}&lng=${probe.lng}&radius_km=20`, { token: customer.token });
  assert.equal(nearby.response.status, 200, nearby.json.error);
  const order = nearby.json.data.riders.map(rider => rider.zone);
  assert.ok(order.length >= 2, 'both riders are candidates');
  assert.equal(order[0], 'Wide Edge Zone',
    'the zone whose EDGE the pickup sits against must rank first, even though its corners are far away');
});

test('a rider the customer could see and pick is honoured however far away they are', async (t) => {
  // Discovery lists riders out to a 20 km radius, but the dispatch search tops out
  // at rider_search_radius_km + 3 expansions (11 km by default). Ranking the
  // customer's explicit choice against the dispatch radius silently dropped it and
  // offered the parcel to somebody else — precisely the "never told 'your rider'
  // about a stranger" failure the preferred-rider flow exists to prevent. If the
  // picker can show a rider, submitting that rider must reach them.
  const admin = await adminToken();
  parkEveryoneOffline();
  const customer = await register('customer');
  const pickup = { lat: -1.9441, lng: 30.0619 };   // z1 Kacyiru

  // A service zone ~15 km east: inside discovery's 20 km cap, well beyond dispatch's.
  const far = { lat: -1.9441, lng: 30.1970 };
  const farZone = await request('/api/admin/zones', {
    method: 'POST', token: admin,
    body: {
      name: 'Far Pick Zone', center_lat: far.lat, center_lng: far.lng, radius_km: 3,
      base_price_parcel: 1500, base_price_document: 1000, per_km_rate: 200
    }
  });
  assert.equal(farZone.response.status, 201, farZone.json.error);

  // Discovery only walks rider_search_max_zone_depth tiers, so ops must have set the
  // depth to reach this far zone for the customer to see (and pick) the rider at all.
  const previousDepth = db.prepare("SELECT value FROM pricing_config WHERE key='rider_search_max_zone_depth'").get()?.value ?? '3';
  db.prepare('INSERT OR REPLACE INTO pricing_config (key,value) VALUES (?,?)').run('rider_search_max_zone_depth', '20');
  t.after(() => db.prepare('INSERT OR REPLACE INTO pricing_config (key,value) VALUES (?,?)').run('rider_search_max_zone_depth', previousDepth));

  const chosen = await riderAt(far.lat, far.lng);
  const nearer = await riderAt(pickup.lat, pickup.lng);
  assert.equal(chosen.zone.name, 'Far Pick Zone', 'fixture: the chosen rider sits in the far zone');

  // The customer can genuinely see and select this rider on the discovery map.
  const discovery = await request(`/api/mobile/v1/customer/nearby-riders?lat=${pickup.lat}&lng=${pickup.lng}&radius_km=20`, { token: customer.token });
  assert.equal(discovery.response.status, 200, discovery.json.error);
  const offered = discovery.json.data.riders.find(rider => rider.id === chosen.id);
  assert.ok(offered, 'the far rider is presented to the customer as a choice');
  assert.ok(offered.distance_km > 11, 'fixture: they are beyond the dispatch search radius');

  const created = await request('/api/deliveries', {
    method: 'POST', token: customer.token,
    body: {
      service_type: 'parcel',
      pickup_address: 'Kacyiru', pickup_lat: pickup.lat, pickup_lng: pickup.lng,
      pickup_name: 'Sender', pickup_phone: '+250788111111',
      dest_address: 'Remera', dest_lat: -1.9367, dest_lng: 30.0867,
      dest_name: 'Receiver', dest_phone: '+250788222222',
      item_description: 'Parcel', payment_method: 'mobile_money',
      preferred_rider_id: chosen.id
    }
  });
  assert.equal(created.response.status, 201, created.json.error);
  const deliveryId = created.json.data.delivery.id;

  const offers = db.prepare("SELECT rider_id FROM delivery_offers WHERE delivery_id=? AND status='offered'").all(deliveryId);
  assert.equal(offers.length, 1, 'the chosen rider is approached alone, before anyone else');
  assert.equal(offers[0].rider_id, chosen.id, 'the customer\'s pick must be honoured, not silently replaced');
  assert.notEqual(offers[0].rider_id, nearer.id, 'the nearer rider must not jump the customer\'s choice');
});
