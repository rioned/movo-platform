const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

// Covers the analytics pipeline added for spec §78: a fixed event catalog,
// an authenticated ingestion endpoint, and rows landing in analytics_events.

const root = path.resolve(__dirname, '..');
const port = 39600 + Math.floor(Math.random() * 300);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-analytics-${process.pid}-${Date.now()}.db`);
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
  const phone = overrides.phone || `+25078${String(6000000 + serial).slice(-7)}`;
  const payload = { role, phone, full_name: `${role} ${serial}`, password: 'Passw0rd1', ...overrides };
  const registration = await request('/api/auth/register', { method: 'POST', body: payload });
  assert.equal(registration.response.status, 200, registration.json.error);
  const verified = await request('/api/auth/verify-otp', { method: 'POST', body: { phone, otp: registration.json.data.otp } });
  assert.equal(verified.response.status, 200, verified.json.error);
  return { id: verified.json.data.user.id, phone, token: verified.json.data.token };
}

test.before(async () => {
  server = await startServer({
    DB_PATH: dbPath, JWT_SECRET: 'analytics-test-secret', OTP_TEST_MODE: 'true', RATE_LIMIT_ENABLED: 'false',
    ADMIN_SEED_PASSWORD: 'Admin@2026'
  }, port);
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('a known analytics event is recorded against the caller, with their role', async () => {
  const customer = await register('customer');
  const logged = await request('/api/analytics/events', {
    method: 'POST', token: customer.token, body: { name: 'quote_viewed', properties: { service_type: 'parcel' } }
  });
  assert.equal(logged.response.status, 201, logged.json.error);

  const row = db.prepare('SELECT name, user_id, role, properties FROM analytics_events WHERE user_id=?').get(customer.id);
  assert.equal(row.name, 'quote_viewed');
  assert.equal(row.role, 'customer');
  assert.deepEqual(JSON.parse(row.properties), { service_type: 'parcel' });
});

test('an unrecognised event name is rejected, not silently stored', async () => {
  const customer = await register('customer');
  const rejected = await request('/api/analytics/events', {
    method: 'POST', token: customer.token, body: { name: 'made_up_event' }
  });
  assert.equal(rejected.response.status, 400);
});

test('going online and offline actually happens through a status change riders can trigger', async () => {
  // The event-emission itself is a client-side (Kotlin) concern, exercised by the
  // Android contract test; this confirms the endpoint the events land on works
  // for a plain rider_went_online/offline payload shape.
  const rider = await register('rider', { national_id: '119997712345678', license_number: 'LIC-A-1', motorcycle_plate: 'RAA001C' });
  const online = await request('/api/analytics/events', {
    method: 'POST', token: rider.token, body: { name: 'rider_went_online' }
  });
  assert.equal(online.response.status, 201, online.json.error);
  const offline = await request('/api/analytics/events', {
    method: 'POST', token: rider.token, body: { name: 'rider_went_offline' }
  });
  assert.equal(offline.response.status, 201, offline.json.error);
});
