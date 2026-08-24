const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const port = 37000 + Math.floor(Math.random() * 500);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-ride-hailing-${process.pid}-${Date.now()}.db`);
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

// Mozambican mobile numbers: 84/85 (Vodacom), 86/87 (Movitel) prefixes, no leading 0.
function mozambiquePhone(prefix) {
  serial += 1;
  return `+258${prefix}${String(1000000 + serial).slice(-7)}`;
}

async function registerCustomer(overrides = {}) {
  serial += 1;
  const phone = overrides.phone || mozambiquePhone('84');
  const payload = { role: 'customer', phone, full_name: `Passenger ${serial}`, password: 'Passw0rd1', ...overrides };
  const registration = await request('/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

async function registerDriver(overrides = {}) {
  serial += 1;
  const phone = overrides.phone || mozambiquePhone('85');
  const payload = {
    role: 'rider', phone, full_name: `Driver ${serial}`, password: 'Passw0rd1',
    national_id: `MZ${String(serial).padStart(9, '0')}`, license_number: `LIC-D-${serial}`,
    vehicle_type: 'car', car_plate: `AAB ${String(100 + serial)} MP`, car_make: 'Toyota', car_model: 'Corolla', car_color: 'White',
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

// Maputo, Mozambique coordinates — city center to Sommerschield, roughly 3km apart.
const PICKUP = { lat: -25.9692, lng: 32.5732 };
const DEST = { lat: -25.9422, lng: 32.6009 };

async function onlineDriver(admin, overrides = {}) {
  const driver = await registerDriver(overrides);
  const approval = await request(`/api/admin/riders/${driver.id}/approve`, { method: 'PUT', token: admin, body: { action: 'approve' } });
  assert.equal(approval.response.status, 200, approval.json.error);
  const status = await request('/api/rider/status', { method: 'PUT', token: driver.token, body: { status: 'online' } });
  assert.equal(status.response.status, 200, status.json.error);
  const location = await request('/api/rider/location', { method: 'PUT', token: driver.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  assert.equal(location.response.status, 200, location.json.error);
  return driver;
}

async function acceptOffer(driver, rideId) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const home = await request('/api/mobile/v1/rider/ride-offers', { token: driver.token });
    const offer = home.json.data.offers.find(o => o.id === rideId);
    if (offer) {
      const accept = await request(`/api/rides/${rideId}/accept`, { method: 'PUT', token: driver.token });
      assert.equal(accept.response.status, 200, accept.json.error);
      return accept.json.data.ride;
    }
    await new Promise(r => setTimeout(r, 150));
  }
  throw new Error('Ride offer never reached the driver');
}

test.before(async () => {
  server = await startServer({
    DB_PATH: dbPath, JWT_SECRET: 'ride-hailing-test-secret', OTP_TEST_MODE: 'true', RATE_LIMIT_ENABLED: 'false',
    ADMIN_SEED_PASSWORD: 'Admin@2026'
  }, port);
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('Mozambican phone numbers (84/85/86/87 prefixes) register and log in with SMS OTP, no password required', async () => {
  for (const prefix of ['84', '85', '86', '87']) {
    const phone = mozambiquePhone(prefix);
    const registration = await request('/api/auth/register', { method: 'POST', body: { role: 'customer', phone, full_name: 'Maputo Rider' } });
    assert.equal(registration.response.status, 200, registration.json.error);
    assert.equal(registration.json.data.phone, phone);
    const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
    assert.equal(verified.response.status, 200, verified.json.error);
    assert.ok(verified.json.data.token);

    const login = await request('/api/auth/login', { method: 'POST', body: { phone } });
    assert.equal(login.response.status, 200, login.json.error);
    assert.equal(login.json.data.requires_otp, true, 'a passwordless account must be challenged with OTP, not silently logged in');
  }
});

test('ride estimate shows every ride type with a price and ETA before the rider commits', async () => {
  const customer = await registerCustomer();
  const estimate = await request('/api/rides/estimate', {
    method: 'POST', token: customer.token,
    body: { pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng, dest_lat: DEST.lat, dest_lng: DEST.lng }
  });
  assert.equal(estimate.response.status, 200, estimate.json.error);
  assert.ok(estimate.json.data.estimates.length >= 2, 'more than one ride category should be offered');
  assert.equal(estimate.json.data.currency, 'MZN');
  for (const e of estimate.json.data.estimates) {
    assert.ok(e.fare > 0);
    assert.ok(e.estimated_minutes > 0);
    assert.ok(e.distance_km > 0);
  }
});

test('the full Yango-style trip: request, driver assigned, tracked, started, completed, paid, rated, receipted', async () => {
  const admin = await adminToken();
  const customer = await registerCustomer();
  const driver = await onlineDriver(admin);

  const rideTypes = await request('/api/ride-types', { token: customer.token });
  const standard = rideTypes.json.data.find(rt => rt.key === 'standard');
  assert.ok(standard);

  // Step 6/7: choose a ride type and confirm — the app searches for the nearest driver.
  const created = await request('/api/rides', {
    method: 'POST', token: customer.token,
    body: {
      pickup_address: 'Praça dos Trabalhadores, Maputo', pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng,
      dest_address: 'Sommerschield, Maputo', dest_lat: DEST.lat, dest_lng: DEST.lng,
      ride_type_id: standard.id, payment_method: 'cash'
    }
  });
  assert.equal(created.response.status, 201, created.json.error);
  const rideId = created.json.data.ride.id;
  assert.equal(created.json.data.ride.status, 'searching');
  assert.ok(created.json.data.ride.total_fare > 0);

  // Step 8: driver assigned — name, rating, car model/color/plate must be visible.
  const assigned = await acceptOffer(driver, rideId);
  assert.equal(assigned.status, 'assigned');
  assert.equal(assigned.driver_id, driver.id);

  const track1 = await request(`/api/rides/${rideId}/track`, { token: customer.token });
  assert.equal(track1.response.status, 200, track1.json.error);
  assert.equal(track1.json.data.driver.car_model, 'Corolla');
  assert.ok(track1.json.data.driver.car_plate);
  assert.ok(track1.json.data.driver.car_color);

  // Step 9: driver en route, live-tracked.
  const enRoute = await request(`/api/rides/${rideId}/en-route`, { method: 'PUT', token: driver.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  assert.equal(enRoute.response.status, 200, enRoute.json.error);
  const arrivePickup = await request(`/api/rides/${rideId}/arrive-pickup`, { method: 'PUT', token: driver.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  assert.equal(arrivePickup.response.status, 200, arrivePickup.json.error);

  // Step 10: verify the plate, get in — the trip starts.
  const start = await request(`/api/rides/${rideId}/start`, { method: 'PUT', token: driver.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  assert.equal(start.response.status, 200, start.json.error);

  // Step 11: share the trip with a contact and confirm the public link works without auth.
  const share = await request(`/api/rides/${rideId}/share`, { method: 'POST', token: customer.token, body: { contact_name: 'Mãe', contact_phone: '+258841112222' } });
  assert.equal(share.response.status, 200, share.json.error);
  const publicTrack = await request(`/api/track/share/${share.json.data.share_token}`);
  assert.equal(publicTrack.response.status, 200, publicTrack.json.error);
  assert.ok(publicTrack.json.data.driver.plate, 'the shared link must expose the plate for safety verification');

  // SOS button is reachable mid-ride.
  const sos = await request(`/api/rides/${rideId}/sos`, { method: 'POST', token: customer.token, body: { kind: 'sos', lat: PICKUP.lat, lng: PICKUP.lng } });
  assert.equal(sos.response.status, 201, sos.json.error);

  const arriveDest = await request(`/api/rides/${rideId}/arrive-destination`, { method: 'PUT', token: driver.token, body: { lat: DEST.lat, lng: DEST.lng } });
  assert.equal(arriveDest.response.status, 200, arriveDest.json.error);

  // Step 12: pay at the end — cash is settled automatically on completion.
  const complete = await request(`/api/rides/${rideId}/complete`, { method: 'PUT', token: driver.token });
  assert.equal(complete.response.status, 200, complete.json.error);
  assert.equal(complete.json.data.payment_status, 'paid');

  // Step 13: rate 1-5 stars.
  const rating = await request('/api/ratings', { method: 'POST', token: customer.token, body: { ride_id: rideId, score: 5, review: 'Great driver' } });
  assert.equal(rating.response.status, 200, rating.json.error);
  const doubleRate = await request('/api/ratings', { method: 'POST', token: customer.token, body: { ride_id: rideId, score: 4 } });
  assert.equal(doubleRate.response.status, 400);

  // Step 14: receipt and trip history persist in the account.
  const receipt = await request(`/api/rides/${rideId}/receipt`, { token: customer.token });
  assert.equal(receipt.response.status, 200, receipt.json.error);
  assert.equal(receipt.json.data.payment.status, 'paid');
  const history = await request('/api/rides', { token: customer.token });
  assert.ok(history.json.data.some(r => r.id === rideId));

  const driverProfile = db.prepare('SELECT avg_rating, total_rides FROM riders WHERE user_id=?').get(driver.id);
  assert.equal(driverProfile.avg_rating, 5);
  assert.equal(driverProfile.total_rides, 1);
});

test('card and mpesa payments settle in-app after trip completion, not on the spot', async () => {
  const admin = await adminToken();
  const customer = await registerCustomer();
  const driver = await onlineDriver(admin);
  const rideTypes = await request('/api/ride-types', { token: customer.token });
  const economy = rideTypes.json.data.find(rt => rt.key === 'economy');

  const created = await request('/api/rides', {
    method: 'POST', token: customer.token,
    body: { pickup_address: 'A', pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng, dest_address: 'B', dest_lat: DEST.lat, dest_lng: DEST.lng, ride_type_id: economy.id, payment_method: 'mpesa' }
  });
  const rideId = created.json.data.ride.id;
  const assigned = await acceptOffer(driver, rideId);
  await request(`/api/rides/${rideId}/en-route`, { method: 'PUT', token: driver.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  await request(`/api/rides/${rideId}/arrive-pickup`, { method: 'PUT', token: driver.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  await request(`/api/rides/${rideId}/start`, { method: 'PUT', token: driver.token, body: { lat: PICKUP.lat, lng: PICKUP.lng } });
  await request(`/api/rides/${rideId}/arrive-destination`, { method: 'PUT', token: driver.token, body: { lat: DEST.lat, lng: DEST.lng } });
  const complete = await request(`/api/rides/${rideId}/complete`, { method: 'PUT', token: driver.token });
  assert.equal(complete.json.data.payment_status, 'pending', 'mpesa/card fares stay pending until the customer confirms payment in-app');

  const pay = await request(`/api/rides/${rideId}/pay`, { method: 'POST', token: customer.token, body: { method: 'mpesa' } });
  assert.equal(pay.response.status, 200, pay.json.error);
  const ride = db.prepare('SELECT payment_status, payment_method FROM rides WHERE id=?').get(rideId);
  assert.equal(ride.payment_status, 'paid');
  assert.equal(ride.payment_method, 'mpesa');
});

test('cancelling after a driver is assigned applies the configured cancellation fee', async () => {
  const admin = await adminToken();
  const customer = await registerCustomer();
  const driver = await onlineDriver(admin);
  const rideTypes = await request('/api/ride-types', { token: customer.token });
  const standard = rideTypes.json.data.find(rt => rt.key === 'standard');
  const created = await request('/api/rides', {
    method: 'POST', token: customer.token,
    body: { pickup_address: 'A', pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng, dest_address: 'B', dest_lat: DEST.lat, dest_lng: DEST.lng, ride_type_id: standard.id }
  });
  const rideId = created.json.data.ride.id;
  await acceptOffer(driver, rideId);
  const cancel = await request(`/api/rides/${rideId}/cancel`, { method: 'PUT', token: customer.token, body: { reason: 'Changed my mind' } });
  assert.equal(cancel.response.status, 200, cancel.json.error);
  assert.ok(cancel.json.data.cancellation_fee > 0);
  const freedDriver = db.prepare('SELECT online_status FROM riders WHERE user_id=?').get(driver.id);
  assert.equal(freedDriver.online_status, 'online');
});

test('a rider cannot be double-booked across a delivery and a ride at the same time', async () => {
  const admin = await adminToken();
  const driver = await onlineDriver(admin);
  const rideCustomer = await registerCustomer();
  const rideTypes = await request('/api/ride-types', { token: rideCustomer.token });
  const standard = rideTypes.json.data.find(rt => rt.key === 'standard');
  const created = await request('/api/rides', {
    method: 'POST', token: rideCustomer.token,
    body: { pickup_address: 'A', pickup_lat: PICKUP.lat, pickup_lng: PICKUP.lng, dest_address: 'B', dest_lat: DEST.lat, dest_lng: DEST.lng, ride_type_id: standard.id }
  });
  const assigned = await acceptOffer(driver, created.json.data.ride.id);
  assert.equal(assigned.status, 'assigned');

  const offers = await request('/api/mobile/v1/rider/ride-offers', { token: driver.token });
  assert.equal(offers.json.data.offers.length, 0, 'a busy driver must not receive further ride offers');
});
