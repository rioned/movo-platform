const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { io } = require('socket.io-client');

const root = path.resolve(__dirname, '..');
const port = 34000 + Math.floor(Math.random() * 1000);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-customer-mobile-${process.pid}-${Date.now()}.db`);
let db;
let server;
let serial = 0;
const sockets = new Set();

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
  try { json = JSON.parse(text); } catch { json = { success: false, error: text.slice(0, 120) }; }
  return { response, json };
}

async function register(role, overrides = {}) {
  serial += 1;
  const phone = overrides.phone || `+25078${String(1000000 + serial).slice(-7)}`;
  const payload = {
    role,
    phone,
    full_name: `${role} ${serial}`,
    password: 'Passw0rd!',
    ...(role === 'rider' ? { national_id: `11999800${String(serial).padStart(8, '0')}`, license_number: `LIC-${serial}`, motorcycle_plate: `RAB${String(serial).padStart(3, '0')}C` } : {}),
    ...overrides
  };
  const registration = await request('/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

function deliveryBody(overrides = {}) {
  return {
    service_type: 'parcel',
    pickup_address: 'Kacyiru', pickup_lat: -1.9441, pickup_lng: 30.0619,
    pickup_name: 'Sender', pickup_phone: '+250788111111',
    dest_address: 'Kigali Heights', dest_lat: -1.9367, dest_lng: 30.0867,
    dest_name: 'Receiver', dest_phone: '0782222222',
    ...overrides
  };
}

function setRider(id, { approval = 'approved', online = 'online', lat = -1.9441, lng = 30.0619, ageSeconds = 0 } = {}) {
  db.prepare(`UPDATE riders SET approval_status=?,online_status=?,current_lat=?,current_lng=?,
    last_location_update=datetime('now',?) WHERE user_id=?`).run(approval, online, lat, lng, `-${ageSeconds} seconds`, id);
}

function once(socket, event, timeout = 2000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${event}`)), timeout);
    socket.once(event, value => { clearTimeout(timer); resolve(value); });
  });
}

function expectNoEvent(socket, event, duration = 250) {
  return new Promise((resolve, reject) => {
    const handler = value => reject(new Error(`Unexpected ${event}: ${JSON.stringify(value)}`));
    socket.once(event, handler);
    setTimeout(() => { socket.off(event, handler); resolve(); }, duration);
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
    env: { ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath, JWT_SECRET: 'customer-mobile-test-secret', OTP_TEST_MODE: 'true' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for customer mobile test server')), 10000);
    server.stdout.on('data', chunk => {
      if (chunk.toString().includes('Server running')) { clearTimeout(timeout); resolve(); }
    });
    server.stderr.on('data', chunk => process.stderr.write(chunk));
    server.once('error', reject);
    server.once('exit', code => reject(new Error(`Customer mobile test server exited early with ${code}`)));
  });
  db = new Database(dbPath);
});

test.after(() => {
  for (const socket of sockets) socket.disconnect();
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('delivery realtime room authorizes sender and receiver, rejects unrelated customers, and accepts locations only from assigned rider', async () => {
  const sender = await register('customer');
  const receiver = await register('customer');
  const unrelated = await register('customer');
  const assigned = await register('rider');
  const otherRider = await register('rider');
  setRider(assigned.id);
  setRider(otherRider.id, { lat: -1.9443, lng: 30.0621 });

  const created = await request('/api/deliveries', {
    token: sender.token,
    method: 'POST',
    body: deliveryBody({ dest_phone: receiver.phone, preferred_rider_id: assigned.id })
  });
  assert.equal(created.response.status, 201, created.json.error);
  const id = created.json.data.delivery.id;

  const [senderSocket, senderSecondSocket, receiverSocket, unrelatedSocket] = await Promise.all([
    connectAuthenticated(sender.token), connectAuthenticated(sender.token), connectAuthenticated(receiver.token), connectAuthenticated(unrelated.token)
  ]);
  for (const socket of [senderSocket, senderSecondSocket, receiverSocket]) {
    const subscribed = once(socket, 'delivery_subscribed');
    socket.emit('subscribe_delivery', { delivery_id: id });
    assert.equal((await subscribed).delivery_id, id);
  }
  const denied = once(unrelatedSocket, 'authentication_error');
  unrelatedSocket.emit('subscribe_delivery', { delivery_id: id });
  await denied;

  const senderAssigned = once(senderSocket, 'delivery_update');
  const senderSecondAssigned = once(senderSecondSocket, 'delivery_update');
  const receiverAssigned = once(receiverSocket, 'delivery_update');
  const accepted = await request(`/api/deliveries/${id}/accept`, { token: assigned.token, method: 'PUT' });
  assert.equal(accepted.response.status, 200, accepted.json.error);
  for (const update of await Promise.all([senderAssigned, senderSecondAssigned, receiverAssigned])) assert.equal(update.status, 'assigned');

  const [assignedSocket, otherRiderSocket] = await Promise.all([connectAuthenticated(assigned.token), connectAuthenticated(otherRider.token)]);
  const noUnassignedLocation = expectNoEvent(receiverSocket, 'rider_location');
  otherRiderSocket.emit('rider_location', { delivery_id: id, lat: -1.95, lng: 30.07 });
  await noUnassignedLocation;
  const noInvalidLocation = expectNoEvent(receiverSocket, 'rider_location');
  assignedSocket.emit('rider_location', { delivery_id: id, lat: 999, lng: 999 });
  await noInvalidLocation;

  const senderLocation = once(senderSocket, 'rider_location');
  const receiverLocation = once(receiverSocket, 'rider_location');
  const unrelatedGetsNothing = expectNoEvent(unrelatedSocket, 'rider_location');
  assignedSocket.emit('rider_location', { delivery_id: id, lat: -1.945, lng: 30.063 });
  const locations = await Promise.all([senderLocation, receiverLocation]);
  assert.deepEqual(locations[0], { delivery_id: id, lat: -1.945, lng: 30.063 });
  assert.deepEqual(locations[1], locations[0]);
  await unrelatedGetsNothing;
  assert.equal(db.prepare('SELECT rider_id,delivery_id FROM rider_locations WHERE delivery_id=? ORDER BY created_at DESC LIMIT 1').get(id).rider_id, assigned.id);

  db.prepare("INSERT INTO rider_locations (id,rider_id,delivery_id,lat,lng,created_at) VALUES (?,?,?,?,?,datetime('now','+1 second'))")
    .run(`other-${id}`, assigned.id, 'different-delivery', -1.99, 30.11);
  const tracked = await request(`/api/deliveries/${id}/track`, { token: receiver.token });
  assert.equal(tracked.response.status, 200, tracked.json.error);
  assert.deepEqual(
    { lat: tracked.json.data.riderLocation.lat, lng: tracked.json.data.riderLocation.lng },
    { lat: -1.945, lng: 30.063 },
    'tracking must not leak the rider sample from another delivery'
  );
  assert.equal(tracked.json.data.relationship, 'receiver');
  assert.ok(tracked.json.data.serverTime);
});

test('delivery creation idempotency is actor scoped and rejects a changed body for the same key', async () => {
  const first = await register('customer');
  const second = await register('customer');
  const key = `customer-create-${Date.now()}`;
  const body = deliveryBody();
  const created = await request('/api/deliveries', { token: first.token, method: 'POST', body, headers: { 'Idempotency-Key': key } });
  assert.equal(created.response.status, 201, created.json.error);
  const replay = await request('/api/deliveries', { token: first.token, method: 'POST', body, headers: { 'Idempotency-Key': key } });
  assert.equal(replay.response.status, 200, replay.json.error);
  assert.equal(replay.json.data.delivery.id, created.json.data.delivery.id);
  const conflict = await request('/api/deliveries', {
    token: first.token, method: 'POST', body: { ...body, dest_address: 'Different destination' }, headers: { 'Idempotency-Key': key }
  });
  assert.equal(conflict.response.status, 409);
  const otherActor = await request('/api/deliveries', { token: second.token, method: 'POST', body, headers: { 'Idempotency-Key': key } });
  assert.equal(otherActor.response.status, 201, otherActor.json.error);
  assert.notEqual(otherActor.json.data.delivery.id, created.json.data.delivery.id);
});

test('ineligible selected rider is rejected without creating a delivery', async () => {
  const sender = await register('customer');
  const offline = await register('rider');
  setRider(offline.id, { online: 'offline' });
  const before = db.prepare('SELECT COUNT(*) AS count FROM deliveries WHERE customer_id=?').get(sender.id).count;
  const result = await request('/api/deliveries', {
    token: sender.token, method: 'POST', body: deliveryBody({ preferred_rider_id: offline.id })
  });
  assert.equal(result.response.status, 409);
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM deliveries WHERE customer_id=?').get(sender.id).count, before);
});

test('selected dispatch offers only the chosen eligible rider and supports decline then sender reselection', async () => {
  const sender = await register('customer');
  const first = await register('rider');
  const second = await register('rider');
  setRider(first.id);
  setRider(second.id, { lat: -1.9443, lng: 30.0621 });

  const created = await request('/api/deliveries', {
    token: sender.token, method: 'POST', body: deliveryBody({ preferred_rider_id: first.id })
  });
  assert.equal(created.response.status, 201, created.json.error);
  const delivery = created.json.data.delivery;
  assert.equal(delivery.preferred_rider_id, first.id);
  assert.equal(delivery.dispatch_mode, 'selected');
  assert.equal(delivery.status, 'searching');
  let offers = db.prepare('SELECT rider_id,status FROM delivery_offers WHERE delivery_id=? ORDER BY created_at').all(delivery.id);
  assert.deepEqual(offers, [{ rider_id: first.id, status: 'offered' }]);

  const offer = db.prepare("SELECT id FROM delivery_offers WHERE delivery_id=? AND rider_id=? AND status='offered'").get(delivery.id, first.id);
  const declined = await request(`/api/mobile/v1/rider/offers/${offer.id}/decline`, { token: first.token, method: 'PUT' });
  assert.equal(declined.response.status, 200, declined.json.error);
  assert.equal(db.prepare('SELECT status FROM deliveries WHERE id=?').get(delivery.id).status, 'awaiting_rider_selection');

  const selected = await request(`/api/deliveries/${delivery.id}/select-rider`, {
    token: sender.token, method: 'PUT', body: { preferred_rider_id: second.id }
  });
  assert.equal(selected.response.status, 200, selected.json.error);
  assert.equal(selected.json.data.delivery.preferred_rider_id, second.id);
  offers = db.prepare('SELECT rider_id,status FROM delivery_offers WHERE delivery_id=? ORDER BY created_at').all(delivery.id);
  assert.deepEqual(offers, [
    { rider_id: first.id, status: 'declined' },
    { rider_id: second.id, status: 'offered' }
  ]);
});

test('persisted selected offers expire through request-time cleanup after a restart gap', async () => {
  const sender = await register('customer');
  const rider = await register('rider');
  setRider(rider.id);
  const created = await request('/api/deliveries', {
    token: sender.token,
    method: 'POST',
    body: deliveryBody({ preferred_rider_id: rider.id })
  });
  assert.equal(created.response.status, 201, created.json.error);
  const deliveryId = created.json.data.delivery.id;
  db.prepare("UPDATE delivery_offers SET expires_at=datetime('now','-1 minute') WHERE delivery_id=? AND status='offered'").run(deliveryId);

  const home = await request('/api/mobile/v1/customer/home', { token: sender.token });
  assert.equal(home.response.status, 200, home.json.error);
  const delivery = home.json.data.activeSent.find(item => item.id === deliveryId);
  assert.equal(delivery.status, 'awaiting_rider_selection');
  assert.equal(db.prepare('SELECT status FROM delivery_offers WHERE delivery_id=?').get(deliveryId).status, 'expired');
});

test('nearby riders include only fresh approved online idle riders within radius in distance order', async () => {
  db.prepare("UPDATE riders SET online_status='offline'").run();
  const customer = await register('customer');
  const near = await register('rider');
  const farther = await register('rider');
  const offline = await register('rider');
  const pending = await register('rider');
  const busy = await register('rider');
  const stale = await register('rider');
  const invalid = await register('rider');
  const distant = await register('rider');

  setRider(near.id, { lat: -1.9442, lng: 30.0620 });
  db.prepare("UPDATE riders SET motorcycle_color='Emerald' WHERE user_id=?").run(near.id);
  setRider(farther.id, { lat: -1.9500, lng: 30.0680 });
  setRider(offline.id, { online: 'offline' });
  setRider(pending.id, { approval: 'pending' });
  setRider(busy.id, { online: 'busy' });
  setRider(stale.id, { ageSeconds: 121 });
  setRider(invalid.id, { lat: 999, lng: 999 });
  setRider(distant.id, { lat: -2.10, lng: 30.20 });

  const result = await request('/api/mobile/v1/customer/nearby-riders?lat=-1.9441&lng=30.0619&radius_km=5', { token: customer.token });
  assert.equal(result.response.status, 200, result.json.error);
  assert.deepEqual(result.json.data.riders.map(rider => rider.id), [near.id, farther.id]);
  assert.ok(result.json.data.riders[0].distance_km <= result.json.data.riders[1].distance_km);
  assert.equal(result.json.data.riders[0].phone, undefined);
  assert.equal(result.json.data.riders[0].motorcycle_color, 'Emerald');
  assert.ok(result.json.data.riders[0].last_location_update);
});

test('phone-matched receiver sees a local-format delivery while an unrelated customer cannot enumerate or open it', async () => {
  const sender = await register('customer', { phone: '+250788111111' });
  const receiver = await register('customer', { phone: '250784444444' });
  const unrelated = await register('customer', { phone: '0783333333' });

  const created = await request('/api/deliveries', { token: sender.token, method: 'POST', body: deliveryBody({ dest_phone: '0784444444' }) });
  assert.equal(created.response.status, 201, created.json.error);
  const id = created.json.data.delivery.id;

  const received = await request('/api/mobile/v1/customer/deliveries?role=received', { token: receiver.token });
  assert.equal(received.response.status, 200, received.json.error);
  assert.deepEqual(received.json.data.deliveries.map(item => item.id), [id]);
  assert.equal(received.json.data.deliveries[0].relationship, 'receiver');
  assert.equal(received.json.data.deliveries[0].pickup_otp, undefined);
  assert.equal(received.json.data.deliveries[0].delivery_otp, undefined);
  assert.ok(received.json.data.serverTime);

  const home = await request('/api/mobile/v1/customer/home', { token: receiver.token });
  assert.equal(home.response.status, 200, home.json.error);
  assert.equal(home.json.data.activeReceived[0].id, id);
  assert.equal(home.json.data.activeReceived[0].relationship, 'receiver');
  assert.deepEqual(home.json.data.profile, {
    id: receiver.id,
    phone: '+250784444444',
    full_name: home.json.data.profile.full_name,
    email: null,
    avatar: null,
    role: 'customer'
  });

  const all = await request('/api/mobile/v1/customer/deliveries?role=all', { token: unrelated.token });
  assert.equal(all.response.status, 200, all.json.error);
  assert.deepEqual(all.json.data.deliveries, []);
  const denied = await request(`/api/deliveries/${id}/track`, { token: unrelated.token });
  assert.equal(denied.response.status, 404);
  const receiverTrack = await request(`/api/deliveries/${id}/track`, { token: receiver.token });
  assert.equal(receiverTrack.response.status, 200, receiverTrack.json.error);

  const sent = await request('/api/mobile/v1/customer/deliveries?role=sent', { token: sender.token });
  assert.equal(sent.json.data.deliveries[0].pickup_otp.length, 4);
  assert.equal(sent.json.data.deliveries[0].delivery_otp, undefined);
  assert.equal(sent.json.data.deliveries[0].relationship, 'sender');
});

test('receiver history is SQL-scoped and is not displaced by newer deliveries belonging to other customers', async () => {
  const sender = await register('customer');
  const receiver = await register('customer');
  const unrelated = await register('customer');
  const created = await request('/api/deliveries', {
    token: sender.token,
    method: 'POST',
    body: deliveryBody({ dest_phone: receiver.phone })
  });
  assert.equal(created.response.status, 201, created.json.error);
  const targetId = created.json.data.delivery.id;
  db.prepare("UPDATE deliveries SET created_at=datetime('now','-2 days') WHERE id=?").run(targetId);

  const template = db.prepare('SELECT * FROM deliveries WHERE id=?').get(targetId);
  const columns = Object.keys(template);
  const insert = db.prepare(`INSERT INTO deliveries (${columns.join(',')}) VALUES (${columns.map(() => '?').join(',')})`);
  const addNoise = db.transaction(() => {
    for (let index = 0; index < 205; index += 1) {
      const row = {
        ...template,
        id: `noise-${targetId}-${index}`,
        order_no: `NOISE-${targetId.slice(0, 8)}-${index}`,
        customer_id: unrelated.id,
        dest_phone: '+250700000000',
        idempotency_actor_id: null,
        idempotency_key: null,
        idempotency_hash: null,
        created_at: new Date(Date.now() + index * 1000).toISOString()
      };
      insert.run(...columns.map(column => row[column]));
    }
  });
  addNoise();

  const history = await request('/api/mobile/v1/customer/deliveries?role=received', { token: receiver.token });
  assert.equal(history.response.status, 200, history.json.error);
  assert.ok(history.json.data.deliveries.some(delivery => delivery.id === targetId));
});

test('customer authentication canonicalizes Rwanda phone variants and prevents duplicate canonical accounts', async () => {
  const local = await register('customer', { phone: '0785555555' });
  const me = await request('/api/auth/me', { token: local.token });
  assert.equal(me.response.status, 200, me.json.error);
  assert.equal(me.json.data.phone, '+250785555555');

  const login = await request('/api/auth/login', {
    method: 'POST',
    body: { phone: '+250785555555', password: 'Passw0rd!' }
  });
  assert.equal(login.response.status, 200, login.json.error);
  assert.equal(login.json.data.user.id, local.id);

  const duplicate = await request('/api/auth/register', {
    method: 'POST',
    body: { role: 'customer', phone: '250785555555', full_name: 'Duplicate', password: 'Passw0rd!' }
  });
  assert.equal(duplicate.response.status, 409);
  assert.equal(duplicate.json.code, 'phone_taken');
});
