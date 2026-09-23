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
const port = 39950 + Math.floor(Math.random() * 400);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-strict-dispatch-${process.pid}-${Date.now()}.db`);
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


const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
for (const kind of ['delivery','ride']) test(`${kind}: exhaust six home riders before expanding, without reoffering declines`, async () => {
  const customer = await setup();
  const home = [];
  for (let i=0;i<6;i++) home.push(await riderAt(0,30));
  const near = await riderAt(0,30.02);
  db.prepare('UPDATE riders SET avg_rating=5 WHERE user_id=?').run(near.id);
  const id = await book(customer,kind);
  assert.equal(offers(kind,id).length,5);
  db.prepare(`UPDATE ${kind}_offers SET status='declined' WHERE ${kind}_id=?`).run(id);
  await sleep(1250);
  const next = offers(kind,id);
  assert.equal(next.length,6,'sixth home candidate must be reached before expansion');
  assert.deepEqual(new Set(next.map(o=>o.rider_id)),new Set(home.map(r=>r.id)));
  await sleep(4300);
  const all = offers(kind,id);
  assert.equal(all.length,7);
  assert.equal(new Set(all.map(o=>o.rider_id)).size,7,'no declined or expired rider is reoffered');
  db.prepare(`UPDATE ${kind === 'ride' ? 'rides' : 'deliveries'} SET status='cancelled' WHERE id=?`).run(id);
});
for (const kind of ['delivery','ride']) test(`${kind}: depth zero terminates after home zone exhaustion`, async () => {
  const customer = await setup(0);
  const id = await book(customer,kind);
  await sleep(100);
  assert.equal(db.prepare(`SELECT status FROM ${kind === 'ride' ? 'rides' : 'deliveries'} WHERE id=?`).get(id).status,kind === 'ride' ? 'cancelled' : 'failed');
});
test('cancelled delivery cannot be offered or failed by an empty-wave timer', async () => {
  const customer = await setup(1);
  const near = await riderAt(0,30.02);
  const id = await book(customer,'delivery');
  db.prepare("UPDATE deliveries SET status='cancelled' WHERE id=?").run(id);
  await sleep(4400);
  assert.equal(offers('delivery',id).length,0);
  assert.equal(db.prepare('SELECT status FROM deliveries WHERE id=?').get(id).status,'cancelled');
});
async function setup(depth = 3) {
  parkEveryoneOffline();
  db.prepare('UPDATE delivery_zones SET is_active=0').run();
  for (const [id,lng] of [['home',30],['near',30.02],['far',30.04]]) {
    db.prepare(`INSERT OR REPLACE INTO delivery_zones (id,name,center_lat,center_lng,radius_km,base_price_parcel,base_price_document,per_km_rate,is_active) VALUES (?,?,0,?,0.5,1500,1000,200,1)`).run(id,id,lng);
  }
  for (const [key,value] of [['rider_search_max_zone_depth',depth],['rider_accept_timeout_sec',1],['ride_driver_accept_timeout_sec',1]])
    db.prepare('INSERT OR REPLACE INTO pricing_config (key,value) VALUES (?,?)').run(key,String(value));
  db.prepare("INSERT OR REPLACE INTO zone_pricing (id,origin_zone_id,dest_zone_id,parcel_price,document_price,estimated_min) VALUES ('strict-route','home','near',1500,1000,10)").run();
  return register('customer');
}
async function book(customer, kind, preferred) {
  const result = await request(kind === 'delivery' ? '/api/deliveries' : '/api/rides', {method:'POST',token:customer.token,body:{
    ride_type_id:db.prepare('SELECT id FROM ride_types LIMIT 1').get().id,
    service_type:'parcel',pickup_address:'Home',pickup_lat:0,pickup_lng:30,
    dest_address:'Near',dest_lat:0,dest_lng:30.02,pickup_name:'Sender',pickup_phone:'+250780001111',
    dest_name:'Receiver',dest_phone:'+250780002222',item_description:'Book',payment_method:'cash',preferred_rider_id:preferred
  }});
  assert.equal(result.response.status,201,JSON.stringify(result.json));
  return result.json.data[kind].id;
}
function offers(kind,id) {return db.prepare(`SELECT ${kind === 'delivery' ? 'rider_id' : 'driver_id'} AS rider_id,status FROM ${kind}_offers WHERE ${kind}_id=? ORDER BY rowid`).all(id);}
for (const kind of ['delivery','ride']) test(`${kind}: fallback offers nearest zone alone, never radius-near farther zone`, async () => {
  const customer = await setup();
  const near = await riderAt(0,30.02), far = await riderAt(0,30.04);
  const id = await book(customer,kind);
  assert.equal(offers(kind,id).length,0);
  await sleep(3200);
  assert.deepEqual(offers(kind,id).map(o=>o.rider_id),[near.id]);
  db.prepare(`UPDATE ${kind === 'ride' ? 'rides' : 'deliveries'} SET status='cancelled' WHERE id=?`).run(id);
});
