const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

/**
 * MOVO sells two products on one dispatch engine. These tests pin the places
 * where a ride must behave differently from a delivery — the fare, the absent
 * handover code, the untexted passenger, the rider's product filter — and, just
 * as importantly, pin that the delivery product did not change underneath it.
 */

const root = path.resolve(__dirname, '..');
const port = 35200 + Math.floor(Math.random() * 400);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-ride-mode-${process.pid}-${Date.now()}.db`);
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

async function register(role, overrides = {}) {
  serial += 1;
  const phone = overrides.phone || `+25079${String(2000000 + serial).slice(-7)}`;
  const payload = {
    role,
    phone,
    full_name: `${role} ${serial}`,
    password: 'Passw0rd!',
    ...(role === 'rider' ? { national_id: `22999800${String(serial).padStart(8, '0')}`, license_number: `LIC-R${serial}`, motorcycle_plate: `RBC${String(serial).padStart(3, '0')}D` } : {}),
    ...overrides
  };
  const registration = await request('/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token, name: payload.full_name };
}

function setRider(id, { approval = 'approved', online = 'online', lat = -1.9441, lng = 30.0619, rides = 1, deliveries = 1 } = {}) {
  db.prepare(`UPDATE riders SET approval_status=?,online_status=?,availability=?,current_lat=?,current_lng=?,
    accepts_rides=?,accepts_deliveries=?,last_location_update=datetime('now') WHERE user_id=?`)
    .run(approval, online, online, lat, lng, rides, deliveries, id);
}

const ROUTE = {
  pickup_address: 'Kacyiru', pickup_lat: -1.9441, pickup_lng: 30.0619,
  dest_address: 'Kigali Heights', dest_lat: -1.9367, dest_lng: 30.0867
};

function rideBody(overrides = {}) {
  return { service_mode: 'ride', ...ROUTE, ...overrides };
}

function deliveryBody(overrides = {}) {
  return {
    service_type: 'parcel', ...ROUTE,
    pickup_name: 'Sender', pickup_phone: '+250788111111',
    dest_name: 'Receiver', dest_phone: '0782222222',
    ...overrides
  };
}

/** Drives an accepted job to the given status through the rider's own endpoints. */
async function advanceTo(riderToken, id, target) {
  const steps = [
    ['going-pickup', 'going_pickup'],
    ['arrive-pickup', 'arrived_pickup'],
    ['verify-pickup', 'picked_up'],
    ['in-transit', 'in_transit'],
    ['arrive-dest', 'arrived_dest']
  ];
  for (const [action, status] of steps) {
    const body = action === 'verify-pickup'
      ? { otp: db.prepare('SELECT pickup_otp FROM deliveries WHERE id=?').get(id).pickup_otp }
      : {};
    const result = await request(`/api/deliveries/${id}/${action}`, { token: riderToken, method: 'PUT', body });
    assert.equal(result.response.status, 200, `${action}: ${result.json.error}`);
    if (status === target) return;
  }
}

test.before(async () => {
  server = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: { ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath, JWT_SECRET: 'ride-mode-test-secret', OTP_TEST_MODE: 'true' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for ride mode test server')), 10000);
    server.stdout.on('data', chunk => {
      if (chunk.toString().includes('Server running')) { clearTimeout(timeout); resolve(); }
    });
    server.stderr.on('data', chunk => process.stderr.write(chunk));
    server.once('error', reject);
    server.once('exit', code => reject(new Error(`Ride mode test server exited early with ${code}`)));
  });
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('a ride is quoted on the moto tariff and priced apart from a parcel on the same route', async () => {
  const passenger = await register('customer');

  const ride = await request('/api/deliveries/price', { token: passenger.token, method: 'POST', body: { ...ROUTE, service_mode: 'ride' } });
  assert.equal(ride.response.status, 200, ride.json.error);
  assert.equal(ride.json.data.serviceMode, 'ride');
  assert.equal(ride.json.data.serviceType, 'ride');

  const parcel = await request('/api/deliveries/price', { token: passenger.token, method: 'POST', body: { ...ROUTE, service_type: 'parcel' } });
  assert.equal(parcel.response.status, 200, parcel.json.error);
  assert.equal(parcel.json.data.serviceMode, 'delivery');

  assert.ok(ride.json.data.customerPrice > 0, 'a ride must carry a fare');
  assert.notEqual(ride.json.data.customerPrice, parcel.json.data.customerPrice,
    'the ride tariff and the parcel tariff are different products and must not collapse to one number');
  // The platform still takes its cut on a ride, and the rider is paid the rest.
  assert.equal(ride.json.data.customerPrice, ride.json.data.riderEarnings + ride.json.data.platformFee);

  const fares = await request('/api/mobile/v1/customer/fares', { token: passenger.token, method: 'POST', body: ROUTE });
  assert.equal(fares.response.status, 200, fares.json.error);
  assert.deepEqual(
    fares.json.data.fares.map(fare => fare.service_type).sort(),
    ['document', 'parcel', 'ride'],
    'the comparison endpoint must quote every product MOVO sells'
  );
});

test('a ride books without recipient details and issues a boarding code but no handover code', async () => {
  const passenger = await register('customer');
  const rider = await register('rider');
  setRider(rider.id);

  // No dest_name/dest_phone: the passenger is their own recipient.
  const created = await request('/api/deliveries', {
    token: passenger.token, method: 'POST', body: rideBody({ preferred_rider_id: rider.id })
  });
  assert.equal(created.response.status, 201, created.json.error);
  const ride = created.json.data.delivery;
  assert.equal(ride.service_mode, 'ride');
  assert.equal(ride.service_type, 'ride');

  const stored = db.prepare('SELECT * FROM deliveries WHERE id=?').get(ride.id);
  assert.equal(stored.dest_phone, passenger.phone, 'the drop-off contact defaults to the passenger');
  assert.equal(stored.dest_name, passenger.name);
  assert.ok(stored.pickup_otp, 'a ride still verifies boarding with a code');
  assert.equal(stored.delivery_otp, null, 'a ride has no handover code — the passenger arrives with the rider');
  assert.equal(stored.passenger_count, 1);
});

test('a rider completes a ride without a handover code, while a delivery still demands one', async () => {
  const passenger = await register('customer');
  const rideRider = await register('rider');
  setRider(rideRider.id);

  const ride = (await request('/api/deliveries', {
    token: passenger.token, method: 'POST', body: rideBody({ preferred_rider_id: rideRider.id })
  })).json.data.delivery;
  await request(`/api/deliveries/${ride.id}/accept`, { token: rideRider.token, method: 'PUT' });
  await advanceTo(rideRider.token, ride.id, 'arrived_dest');

  // No OTP supplied at all — the passenger stepped off the bike.
  const finished = await request(`/api/deliveries/${ride.id}/complete`, { token: rideRider.token, method: 'PUT', body: {} });
  assert.equal(finished.response.status, 200, finished.json.error);
  assert.match(finished.json.data.pod_reference, /^TRIP-/, 'a completed ride is receipted as a trip, not a proof of delivery');
  assert.equal(db.prepare('SELECT status FROM deliveries WHERE id=?').get(ride.id).status, 'delivered');

  // The delivery product is untouched: an empty code is still rejected.
  const sender = await register('customer');
  const parcelRider = await register('rider');
  setRider(parcelRider.id);
  const parcel = (await request('/api/deliveries', {
    token: sender.token, method: 'POST', body: deliveryBody({ preferred_rider_id: parcelRider.id })
  })).json.data.delivery;
  await request(`/api/deliveries/${parcel.id}/accept`, { token: parcelRider.token, method: 'PUT' });
  await advanceTo(parcelRider.token, parcel.id, 'arrived_dest');

  const rejected = await request(`/api/deliveries/${parcel.id}/complete`, { token: parcelRider.token, method: 'PUT', body: {} });
  assert.equal(rejected.response.status, 400, 'a delivery must still verify the recipient');
  assert.equal(rejected.json.code, 'invalid_otp');

  const handoverCode = db.prepare('SELECT delivery_otp FROM deliveries WHERE id=?').get(parcel.id).delivery_otp;
  const accepted = await request(`/api/deliveries/${parcel.id}/complete`, { token: parcelRider.token, method: 'PUT', body: { otp: handoverCode } });
  assert.equal(accepted.response.status, 200, accepted.json.error);
  assert.match(accepted.json.data.pod_reference, /^POD-/);
});

test('dispatch only offers a rider the products they have turned on', async () => {
  const passenger = await register('customer');
  const deliveryOnly = await register('rider');
  setRider(deliveryOnly.id, { rides: 0, deliveries: 1 });

  const nearbyForRides = await request(`/api/mobile/v1/customer/nearby-riders?lat=${ROUTE.pickup_lat}&lng=${ROUTE.pickup_lng}&mode=ride`, { token: passenger.token });
  assert.equal(nearbyForRides.response.status, 200, nearbyForRides.json.error);
  assert.ok(
    !nearbyForRides.json.data.riders.some(rider => rider.id === deliveryOnly.id),
    'a rider who does not take passengers must not be offered as one'
  );

  const nearbyForDeliveries = await request(`/api/mobile/v1/customer/nearby-riders?lat=${ROUTE.pickup_lat}&lng=${ROUTE.pickup_lng}&mode=delivery`, { token: passenger.token });
  assert.ok(
    nearbyForDeliveries.json.data.riders.some(rider => rider.id === deliveryOnly.id),
    'the same rider is still available for the product they do work'
  );

  // Selecting them for a ride is refused rather than silently dispatched.
  const refused = await request('/api/deliveries', {
    token: passenger.token, method: 'POST', body: rideBody({ preferred_rider_id: deliveryOnly.id })
  });
  assert.equal(refused.response.status, 409);
  assert.equal(refused.json.code, 'rider_unavailable');
});

test('a rider edits their service mix but cannot switch every product off', async () => {
  const rider = await register('rider');
  setRider(rider.id);

  const ridesOnly = await request('/api/rider/services', {
    token: rider.token, method: 'PUT', body: { accepts_rides: true, accepts_deliveries: false }
  });
  assert.equal(ridesOnly.response.status, 200, ridesOnly.json.error);
  assert.equal(ridesOnly.json.data.accepts_deliveries, 0);

  const stored = db.prepare('SELECT accepts_rides,accepts_deliveries FROM riders WHERE user_id=?').get(rider.id);
  assert.deepEqual(stored, { accepts_rides: 1, accepts_deliveries: 0 });

  const emptied = await request('/api/rider/services', {
    token: rider.token, method: 'PUT', body: { accepts_rides: false, accepts_deliveries: false }
  });
  assert.equal(emptied.response.status, 400, 'a rider accepting nothing would sit online receiving no work');
  assert.equal(emptied.json.code, 'no_service_selected');

  // The rejected write must not have partially applied.
  assert.deepEqual(
    db.prepare('SELECT accepts_rides,accepts_deliveries FROM riders WHERE user_id=?').get(rider.id),
    { accepts_rides: 1, accepts_deliveries: 0 }
  );

  const home = await request('/api/mobile/v1/rider/home', { token: rider.token });
  assert.equal(home.json.data.accepts_rides, 1);
  assert.equal(home.json.data.accepts_deliveries, 0);
});

test('the rider offer feed and earnings distinguish the two products', async () => {
  const passenger = await register('customer');
  const rider = await register('rider');
  setRider(rider.id);

  const ride = (await request('/api/deliveries', {
    token: passenger.token, method: 'POST', body: rideBody({ preferred_rider_id: rider.id, passenger_count: 2, has_luggage: true })
  })).json.data.delivery;

  const offers = await request('/api/mobile/v1/rider/offers', { token: rider.token });
  assert.equal(offers.response.status, 200, offers.json.error);
  const offered = offers.json.data.offers.find(entry => entry.id === ride.id);
  assert.ok(offered, 'the ride must reach the rider offer feed');
  assert.equal(offered.service_mode, 'ride', 'the rider must know a passenger is waiting, not a parcel');
  assert.equal(offered.passenger_count, 2);
  assert.equal(offered.has_luggage, 1);

  await request(`/api/deliveries/${ride.id}/accept`, { token: rider.token, method: 'PUT' });
  await advanceTo(rider.token, ride.id, 'arrived_dest');
  await request(`/api/deliveries/${ride.id}/complete`, { token: rider.token, method: 'PUT', body: {} });

  const earnings = await request('/api/rider/earnings?period=today', { token: rider.token });
  assert.equal(earnings.response.status, 200, earnings.json.error);
  assert.equal(earnings.json.data.by_mode.ride.count, 1);
  assert.equal(earnings.json.data.by_mode.delivery.count, 0);
  assert.ok(earnings.json.data.by_mode.ride.total_earnings > 0);
});

test('an unknown service mode is refused and existing delivery clients keep working unchanged', async () => {
  const customer = await register('customer');

  const bogusMode = await request('/api/deliveries/price', {
    token: customer.token, method: 'POST', body: { ...ROUTE, service_mode: 'helicopter' }
  });
  assert.equal(bogusMode.response.status, 400);
  assert.equal(bogusMode.json.code, 'unsupported_value');

  // 'parcel' belongs to delivery, not ride — pairing them must be refused.
  const mismatched = await request('/api/deliveries/price', {
    token: customer.token, method: 'POST', body: { ...ROUTE, service_mode: 'ride', service_type: 'parcel' }
  });
  assert.equal(mismatched.response.status, 400);
  assert.equal(mismatched.json.code, 'unsupported_value');

  // A client that has never heard of service_mode still books a delivery.
  const rider = await register('rider');
  setRider(rider.id);
  const legacy = await request('/api/deliveries', {
    token: customer.token, method: 'POST', body: deliveryBody({ service_type: 'document', preferred_rider_id: rider.id })
  });
  assert.equal(legacy.response.status, 201, legacy.json.error);
  assert.equal(legacy.json.data.delivery.service_mode, 'delivery', 'an unspecified mode defaults to the product that already existed');
  assert.equal(legacy.json.data.delivery.service_type, 'document');
  assert.ok(db.prepare('SELECT delivery_otp FROM deliveries WHERE id=?').get(legacy.json.data.delivery.id).delivery_otp);
});
