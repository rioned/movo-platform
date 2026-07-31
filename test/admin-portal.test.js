const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const Database = require('better-sqlite3');
const { io } = require('socket.io-client');

const root = path.resolve(__dirname, '..');
const port = 31000 + Math.floor(Math.random() * 1000);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-admin-portal-${process.pid}-${Date.now()}.db`);
let server;

async function request(pathname, { token, method = 'GET', body } = {}) {
  const response = await fetch(`${base}${pathname}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  const payload = await response.json();
  assert.equal(payload.success, true, `${method} ${pathname}: ${payload.error}`);
  return payload.data;
}

async function register(role, suffix, extra = {}) {
  const phone = `+25078${String(suffix).padStart(7, '0')}`;
  const registration = await request('/api/auth/register', {
    method: 'POST',
    body: { role, phone, full_name: `${role} admin portal test`, password: 'Passw0rd!', ...extra }
  });
  const verified = await request('/api/auth/verify-otp', {
    method: 'POST',
    body: { phone, otp: registration.otp }
  });
  return verified;
}

test.before(async () => {
  server = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: { ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath, JWT_SECRET: 'admin-portal-test-secret', OTP_TEST_MODE: 'true', LIVE_MAP_DEMO_MODE: 'true' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  const started = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for test server')), 10000);
    server.stdout.on('data', chunk => {
      if (chunk.toString().includes('Server running')) {
        clearTimeout(timeout);
        resolve();
      }
    });
    server.once('error', reject);
    server.once('exit', code => reject(new Error(`Test server exited early with ${code}`)));
  });
  return started;
});

test.after(() => {
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('admin portal APIs support every rendered management view and mutation', async () => {
  const testDb = new Database(dbPath, { readonly: true });
  const adminPhone = testDb.prepare("SELECT phone FROM users WHERE role='admin'").get().phone;
  testDb.close();
  const admin = await request('/api/auth/login', { method: 'POST', body: { phone: adminPhone, password: 'Admin@2026' } });
  const adminToken = admin.token;
  const customer = await register('customer', 1, { email: 'customer@example.test' });
  const rider = await register('rider', 2, { national_id: '1199980012345678', license_number: 'RDL-12345', motorcycle_plate: 'RAA123B' });
  const business = await register('business', 3, { company_name: 'Admin Portal Test Ltd', tax_id: '103456789' });

  const dashboard = await request('/api/admin/dashboard', { token: adminToken });
  assert.equal(typeof dashboard.active, 'number');
  assert.equal((await request('/api/admin/users?role=customer', { token: adminToken })).some(user => user.id === customer.user.id), true);
  assert.equal((await request('/api/admin/users?role=rider', { token: adminToken })).some(user => user.id === rider.user.id), true);
  const businesses = await request('/api/admin/users?role=business', { token: adminToken });
  assert.equal(businesses.length, 1);
  assert.equal(businesses.find(user => user.id === business.user.id).tax_id, '103456789');
  await request(`/api/admin/riders/${rider.user.id}/approve`, { token: adminToken, method: 'PUT', body: { action: 'approve' } });
  await request('/api/rider/status', { token: rider.token, method: 'PUT', body: { online: true } });
  await request('/api/rider/location', { token: rider.token, method: 'PUT', body: { lat: -1.9441, lng: 30.0619 } });

  const riderSocket = io(base, { transports: ['websocket'], reconnection: false, forceNew: true });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out connecting rider socket')), 3000);
    riderSocket.once('connect', () => { clearTimeout(timeout); resolve(); });
    riderSocket.once('connect_error', reject);
  });
  riderSocket.emit('authenticate', rider.token);
  await new Promise(resolve => setTimeout(resolve, 50));
  const realtimeOfferPromise = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for realtime delivery offer')), 3000);
    riderSocket.once('new_delivery', offer => { clearTimeout(timeout); resolve(offer); });
  });

  const deliveryResult = await request('/api/deliveries', {
    token: customer.token,
    method: 'POST',
    body: {
      service_type: 'parcel', pickup_address: 'Kacyiru', pickup_lat: -1.9441, pickup_lng: 30.0619,
      pickup_name: 'Sender', pickup_phone: '+250788000001', dest_address: 'Downtown', dest_lat: -1.9534,
      dest_lng: 30.0585, dest_name: 'Recipient', dest_phone: '+250788000002', item_description: 'Admin test parcel'
    }
  });
  const deliveryId = deliveryResult.delivery.id;
  const realtimeOffer = await realtimeOfferPromise;
  riderSocket.disconnect();
  assert.equal(realtimeOffer.pickup_name, 'Sender');
  assert.equal(realtimeOffer.pickup_phone, deliveryResult.delivery.pickup_phone);
  assert.equal(realtimeOffer.pickup_lat, -1.9441);
  assert.equal(realtimeOffer.pickup_lng, 30.0619);
  assert.equal(realtimeOffer.dest_name, 'Recipient');
  assert.equal(realtimeOffer.dest_phone, deliveryResult.delivery.dest_phone);
  assert.equal(realtimeOffer.dest_lat, -1.9534);
  assert.equal(realtimeOffer.dest_lng, 30.0585);
  const riderHome = await request('/api/mobile/v1/rider/home', { token: rider.token });
  assert.equal(riderHome.approval_status, 'approved');
  assert.equal(riderHome.online_status, 'online');
  assert.equal(riderHome.offers.some(offer => offer.id === deliveryId && offer.offer_id), true);
  await request(`/api/deliveries/${deliveryId}/accept`, { token: rider.token, method: 'PUT', body: {} });
  const riderHomeAfterAccept = await request('/api/mobile/v1/rider/home', { token: rider.token });
  assert.equal(riderHomeAfterAccept.activeDelivery.id, deliveryId);
  const deliveries = await request('/api/admin/deliveries?search=Kacyiru', { token: adminToken });
  assert.equal(deliveries.some(delivery => delivery.id === deliveryId), true);
  const liveMap = await request('/api/admin/live-map', { token: adminToken });
  assert.equal(liveMap.riders.filter(item => item.is_demo).length, 3);
  assert.equal(liveMap.activeDeliveries.filter(item => item.is_demo).length, 3);
  assert.equal(liveMap.riders.some(item => item.id === rider.user.id && item.lat === -1.9441 && item.lng === 30.0619), true);
  assert.equal(liveMap.activeDeliveries.some(item => item.id === deliveryId && item.rider_lat === -1.9441 && item.rider_lng === 30.0619), true);
  await request(`/api/admin/deliveries/${deliveryId}`, { token: adminToken, method: 'PUT', body: { status: 'cancelled', note: 'Admin portal test' } });

  const approvedRider = (await request('/api/admin/users?role=rider', { token: adminToken })).find(user => user.id === rider.user.id);
  assert.equal(approvedRider.approval_status, 'approved');

  const zones = await request('/api/admin/zones', { token: adminToken });
  assert.ok(zones.length >= 10);
  const zone = await request('/api/admin/zones', {
    token: adminToken,
    method: 'POST',
    body: { name: 'Admin Portal Test Zone', center_lat: -1.94, center_lng: 30.06, radius_km: 2, base_price_parcel: 1700, base_price_document: 1100, per_km_rate: 210 }
  });
  await request(`/api/admin/zones/${zone.id}`, { token: adminToken, method: 'PUT', body: { name: 'Updated Admin Portal Test Zone', is_active: 0 } });

  const pricing = await request('/api/admin/pricing', { token: adminToken });
  assert.ok(pricing.config.length > 0);
  assert.ok(pricing.matrix.length > 0);
  await request('/api/admin/pricing/config', { token: adminToken, method: 'PUT', body: { key: 'min_ride_price', value: 900 } });
  const matrixItem = pricing.matrix[0];
  await request('/api/admin/pricing/zone', { token: adminToken, method: 'PUT', body: { id: matrixItem.id, parcel_price: 1900, document_price: 1200, estimated_min: 35 } });

  const ticket = await request('/api/tickets', {
    token: customer.token,
    method: 'POST',
    body: { category: 'delivery', subject: 'Admin portal test ticket', description: 'Please resolve this ticket', priority: 'high' }
  });
  const tickets = await request('/api/tickets', { token: adminToken });
  assert.equal(tickets.some(item => item.id === ticket.id), true);
  await request(`/api/tickets/${ticket.id}`, { token: adminToken, method: 'PUT', body: { status: 'resolved', resolution: 'Resolved by admin portal test' } });

  const finances = await request('/api/admin/finances', { token: adminToken });
  assert.ok(Array.isArray(finances.daily));

  for (const type of ['delivery-status', 'service-type', 'top-zones', 'rider-performance']) {
    assert.ok(Array.isArray(await request(`/api/admin/reports?type=${type}`, { token: adminToken })));
  }
});

test('the operations portal wires every management surface it renders', () => {
  const portal = fs.readFileSync(path.join(root, 'public/admin/index.html'), 'utf8');
  const wired = [
    // Pages a page-switch must be able to reach
    /data-page="kpis"/, /data-page="incidents"/, /data-page="audit"/,
    /id="page-kpis"/, /id="page-incidents"/, /id="page-audit"/,
    // Endpoints each new view depends on
    /\/api\/admin\/kpis\?days=/, /\/api\/admin\/incidents/, /\/api\/admin\/audit\?limit=/,
    /\/api\/admin\/deliveries\/\$\{id\}\/reassign/, /\/api\/deliveries\/\$\{id\}\/pod/,
    /\/api\/deliveries\/\$\{id\}\/receipt/, /\/api\/deliveries\/\$\{id\}\/track/,
    /\/api\/rider\/documents\/\$\{riderId\}\/\$\{kind\}/, /\/api\/admin\/riders\/\$\{id\}/,
    // Operational affordances
    /function loadKpis/, /function loadIncidents/, /function loadAudit/,
    /function showRiderModal/, /function reassignDelivery/, /function loadSystemHealth/,
    /function loadAttention/, /renderAuthorisedImage/
  ];
  for (const pattern of wired) assert.match(portal, pattern, `admin portal must wire ${pattern}`);

  // Protected media must travel with the admin token, never as a bare URL.
  assert.match(portal, /headers: \{ Authorization: 'Bearer ' \+ token \}/);
  // Every loader is reachable from the page switcher.
  const switcher = portal.match(/const loaders=\{[^}]+\}/)[0];
  for (const page of ['kpis', 'incidents', 'audit']) {
    assert.match(switcher, new RegExp(page), `${page} must have a loader`);
  }
});

test('operations can run the incident, audit, reassignment and proof workflows end to end', async () => {
  const testDb = new Database(dbPath, { readonly: true });
  const adminPhone = testDb.prepare("SELECT phone FROM users WHERE role='admin'").get().phone;
  testDb.close();
  const adminToken = (await request('/api/auth/login', { method: 'POST', body: { phone: adminPhone, password: 'Admin@2026' } })).token;
  const customer = await register('customer', 11, { email: 'ops-customer@example.test' });
  const rider = await register('rider', 12, { national_id: '1199980099999999', license_number: 'RDL-OPS-1', motorcycle_plate: 'RAO111A' });
  const standby = await register('rider', 13, { national_id: '1199980088888888', license_number: 'RDL-OPS-2', motorcycle_plate: 'RAO222B' });

  for (const person of [rider, standby]) {
    await request(`/api/admin/riders/${person.user.id}/approve`, { token: adminToken, method: 'PUT', body: { action: 'approve' } });
    await request('/api/rider/status', { token: person.token, method: 'PUT', body: { status: 'online' } });
    await request('/api/rider/location', { token: person.token, method: 'PUT', body: { lat: -1.9441, lng: 30.0619 } });
  }

  // KPI pack backs the Performance page.
  const kpis = await request('/api/admin/kpis?days=30', { token: adminToken });
  for (const group of ['customers', 'deliveries', 'riders', 'financial', 'operations']) {
    assert.ok(kpis[group], `KPI pack must include ${group}`);
  }

  // A rider raises an incident; operations sees it, then resolves it.
  const incident = await request('/api/rider/incidents', {
    token: rider.token, method: 'POST',
    body: { kind: 'unsafe_item', severity: 'high', description: 'Package leaking fluid', lat: -1.9441, lng: 30.0619 }
  });
  const openIncidents = await request('/api/admin/incidents?status=open', { token: adminToken });
  const listed = openIncidents.find(item => item.id === incident.id);
  assert.ok(listed, 'the incident must appear in the operations queue');
  assert.equal(listed.reporter_name, 'rider admin portal test');
  await request(`/api/admin/incidents/${incident.id}`, {
    token: adminToken, method: 'PUT', body: { status: 'resolved', resolution: 'Rider stood down, customer refunded' }
  });
  assert.equal((await request('/api/admin/incidents?status=open', { token: adminToken })).some(item => item.id === incident.id), false);

  // A delivery is created, accepted, then handed to the standby rider by operations.
  const delivery = (await request('/api/deliveries', {
    token: customer.token, method: 'POST',
    body: {
      service_type: 'document', pickup_address: 'Kigali Heights', pickup_lat: -1.9441, pickup_lng: 30.0619,
      pickup_name: 'Sender', pickup_phone: '+250788000011', dest_address: 'Kimironko', dest_lat: -1.9534,
      dest_lng: 30.0585, dest_name: 'Recipient', dest_phone: '+250788000012'
    }
  })).delivery;
  await request(`/api/deliveries/${delivery.id}/accept`, { token: rider.token, method: 'PUT', body: {} });
  await request(`/api/admin/deliveries/${delivery.id}/reassign`, { token: adminToken, method: 'PUT', body: { rider_id: standby.user.id } });

  // Complete the delivery so the proof and receipt views have something to show.
  const codes = new Database(dbPath, { readonly: true });
  const otps = codes.prepare('SELECT pickup_otp, delivery_otp FROM deliveries WHERE id=?').get(delivery.id);
  codes.close();
  for (const [step, body] of [['going-pickup', {}], ['arrive-pickup', {}], ['verify-pickup', { otp: otps.pickup_otp }], ['in-transit', {}], ['arrive-dest', {}]]) {
    await request(`/api/deliveries/${delivery.id}/${step}`, { token: standby.token, method: 'PUT', body });
  }
  await request(`/api/deliveries/${delivery.id}/complete`, { token: standby.token, method: 'PUT', body: { otp: otps.delivery_otp, recipient_name: 'Recipient' } });

  const pod = await request(`/api/deliveries/${delivery.id}/pod`, { token: adminToken });
  assert.equal(pod.delivery.otp_verified, true);
  assert.equal(pod.rider.plate, 'RAO222B');
  const receipt = await request(`/api/deliveries/${delivery.id}/receipt`, { token: adminToken });
  assert.ok(receipt.payments.length >= 3, 'settlement lines must be visible to operations');

  // The rider dossier the approval decision is made from.
  const dossier = await request(`/api/admin/riders/${standby.user.id}`, { token: adminToken });
  assert.equal(dossier.motorcycle_plate, 'RAO222B');
  assert.ok(Array.isArray(dossier.recentDeliveries));
  assert.equal(dossier.recentDeliveries.some(item => item.id === delivery.id), true);

  // Every one of those administrative actions is recorded.
  const audit = await request('/api/admin/audit?limit=100', { token: adminToken });
  assert.ok(audit.total > 0);
  const actions = audit.entries.map(entry => entry.action);
  for (const action of ['rider_approve', 'incident_reported', 'incident_updated', 'delivery_reassigned', 'delivery_completed']) {
    assert.ok(actions.includes(action), `audit trail must record ${action}`);
  }
  const filtered = await request('/api/admin/audit?entity=incident&limit=10', { token: adminToken });
  assert.equal(filtered.entries.every(entry => entry.entity === 'incident'), true);
});

test('operations can suspend and reinstate customer accounts with an audit trail', async () => {
  const testDb = new Database(dbPath, { readonly: true });
  const adminPhone = testDb.prepare("SELECT phone FROM users WHERE role='admin'").get().phone;
  testDb.close();
  const adminToken = (await request('/api/auth/login', { method: 'POST', body: { phone: adminPhone, password: 'Admin@2026' } })).token;
  const customer = await register('customer', 21, { email: 'suspend-me@example.test' });

  const suspended = await request(`/api/admin/users/${customer.user.id}/status`, {
    token: adminToken, method: 'PUT', body: { status: 'suspended', reason: 'Fraudulent chargeback pattern' }
  });
  assert.equal(suspended.status, 'suspended');

  // A suspended account cannot authenticate, and its live token stops working.
  const blocked = await fetch(`${base}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: customer.user.phone, password: 'Passw0rd!' })
  });
  const blockedBody = await blocked.json();
  assert.equal(blocked.status, 403);
  assert.equal(blockedBody.code, 'account_suspended');
  const withOldToken = await fetch(`${base}/api/auth/me`, { headers: { Authorization: `Bearer ${customer.token}` } });
  assert.equal(withOldToken.status, 403, 'an issued token must stop working once the account is suspended');

  // The suspension is explainable afterwards.
  const audit = await request('/api/admin/audit?entity=user&limit=20', { token: adminToken });
  const record = audit.entries.find(entry => entry.entity_id === customer.user.id && entry.action === 'account_suspended');
  assert.ok(record, 'suspension must be recorded');
  assert.match(record.details, /Fraudulent chargeback pattern/);

  const reinstated = await request(`/api/admin/users/${customer.user.id}/status`, {
    token: adminToken, method: 'PUT', body: { status: 'active' }
  });
  assert.equal(reinstated.status, 'active');
  const relogin = await request('/api/auth/login', { method: 'POST', body: { phone: customer.user.phone, password: 'Passw0rd!' } });
  assert.ok(relogin.token, 'a reinstated customer can sign in again');
});

test('suspension refuses to strand deliveries and protects administrator accounts', async () => {
  const testDb = new Database(dbPath, { readonly: true });
  const adminRow = testDb.prepare("SELECT id, phone FROM users WHERE role='admin'").get();
  testDb.close();
  const adminToken = (await request('/api/auth/login', { method: 'POST', body: { phone: adminRow.phone, password: 'Admin@2026' } })).token;
  const customer = await register('customer', 22);
  const rider = await register('rider', 23, { national_id: '1199980077777777', license_number: 'RDL-SUS-1', motorcycle_plate: 'RAS777S' });
  await request(`/api/admin/riders/${rider.user.id}/approve`, { token: adminToken, method: 'PUT', body: { action: 'approve' } });
  await request('/api/rider/status', { token: rider.token, method: 'PUT', body: { status: 'online' } });
  await request('/api/rider/location', { token: rider.token, method: 'PUT', body: { lat: -1.9441, lng: 30.0619 } });
  await request('/api/deliveries', {
    token: customer.token, method: 'POST',
    body: {
      service_type: 'parcel', pickup_address: 'Kacyiru', pickup_lat: -1.9441, pickup_lng: 30.0619,
      pickup_name: 'Sender', pickup_phone: '+250788000021', dest_address: 'Kicukiro', dest_lat: -1.9783,
      dest_lng: 30.1125, dest_name: 'Recipient', dest_phone: '+250788000022'
    }
  });

  const guarded = await fetch(`${base}/api/admin/users/${customer.user.id}/status`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
    body: JSON.stringify({ status: 'suspended', reason: 'Investigation' })
  });
  const guardedBody = await guarded.json();
  assert.equal(guarded.status, 409);
  assert.equal(guardedBody.code, 'active_deliveries');

  // Operations can still override deliberately.
  const forced = await request(`/api/admin/users/${customer.user.id}/status`, {
    token: adminToken, method: 'PUT', body: { status: 'suspended', reason: 'Investigation', force: true }
  });
  assert.equal(forced.status, 'suspended');

  // Administrators are not suspendable from the portal.
  const protectedAdmin = await fetch(`${base}/api/admin/users/${adminRow.id}/status`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
    body: JSON.stringify({ status: 'suspended', reason: 'oops' })
  });
  assert.equal(protectedAdmin.status, 409);
  assert.equal((await protectedAdmin.json()).code, 'forbidden_target');
});

test('the portal exposes account suspension and richer rider facts', () => {
  const portal = fs.readFileSync(path.join(root, 'public/admin/index.html'), 'utf8');
  assert.match(portal, /function setAccountStatus/);
  assert.match(portal, /\/api\/admin\/users\/\$\{id\}\/status/);
  assert.match(portal, /Reinstate/);
  assert.match(portal, /motorcycle_plate\|\|'Not provided'/);
  assert.match(portal, /rider\.availability\|\|rider\.online_status/);
  // Login must be a real form so Enter submits and password managers work.
  assert.match(portal, /<form class="space-y-3" onsubmit="event\.preventDefault\(\);handleLogin\(\)"/);
  assert.match(portal, /rel="icon"/);
});
