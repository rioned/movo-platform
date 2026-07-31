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
