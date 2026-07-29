const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('path');

const root = path.resolve(__dirname, '..');
const port = 32000 + Math.floor(Math.random() * 1000);
const base = `http://127.0.0.1:${port}`;
const dbPath = path.join(os.tmpdir(), `movo-registration-${process.pid}-${Date.now()}.db`);
let db;
let server;
const phoneFor = role => `+25079${Date.now().toString().slice(-7)}${{ customer: '1', rider: '2', business: '3' }[role]}`;

test.before(async () => {
  server = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: { ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath, JWT_SECRET: 'registration-test-secret', OTP_TEST_MODE: 'true' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out waiting for registration test server')), 10000);
    server.stdout.on('data', chunk => {
      if (chunk.toString().includes('Server running')) {
        clearTimeout(timeout);
        resolve();
      }
    });
    server.once('error', reject);
    server.once('exit', code => reject(new Error(`Registration test server exited early with ${code}`)));
  });
  db = new Database(dbPath);
});

test.after(() => {
  db?.close();
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

async function registerAndVerify(role, extra = {}) {
  const phone = phoneFor(role);
  const payload = { role, phone, full_name: `${role} registration test`, password: 'Passw0rd!', ...extra };
  const registerResponse = await fetch(`${base}/api/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  const registration = await registerResponse.json();
  assert.equal(registration.success, true, registration.error);
  const verifyResponse = await fetch(`${base}/api/auth/verify-otp`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone, otp: registration.data.otp }) });
  const verified = await verifyResponse.json();
  assert.equal(verified.success, true, verified.error);
  assert.equal(verified.data.user.role, role);
  return { phone, user: verified.data.user };
}

test('customer registration creates an active customer', async () => {
  const { phone } = await registerAndVerify('customer', { email: 'customer@test.local' });
  assert.equal(db.prepare('SELECT status FROM users WHERE phone=?').get(phone).status, 'active');
  db.prepare('DELETE FROM users WHERE phone=?').run(phone);
});

test('rider registration persists required rider identity fields', async () => {
  const { phone, user } = await registerAndVerify('rider', { national_id: '1199980012345678', license_number: 'RDL-12345', motorcycle_plate: 'RAA123B' });
  assert.equal(user.national_id, '1199980012345678');
  assert.equal(user.license_number, 'RDL-12345');
  assert.equal(user.motorcycle_plate, 'RAA123B');
  db.prepare('DELETE FROM riders WHERE user_id=(SELECT id FROM users WHERE phone=?)').run(phone);
  db.prepare('DELETE FROM users WHERE phone=?').run(phone);
});

test('business registration persists the company and tax identity', async () => {
  const { phone, user } = await registerAndVerify('business', { company_name: 'Registration Test Ltd', tax_id: '103456789' });
  assert.equal(user.company_name, 'Registration Test Ltd');
  assert.equal(user.tax_id, '103456789');
  db.prepare('DELETE FROM businesses WHERE user_id=(SELECT id FROM users WHERE phone=?)').run(phone);
  db.prepare('DELETE FROM users WHERE phone=?').run(phone);
});

test('testing mode accepts any six-digit OTP for a newly registered customer', async () => {
  const phone = phoneFor('customer');
  const registration = await fetch(`${base}/api/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: 'customer', phone, full_name: 'Testing OTP customer', password: 'Passw0rd!' }) }).then(response => response.json());
  assert.equal(registration.success, true, registration.error);
  const verified = await fetch(`${base}/api/auth/verify-otp`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone, otp: '000000' }) }).then(response => response.json());
  assert.equal(verified.success, true, verified.error);
  db.prepare('DELETE FROM users WHERE phone=?').run(phone);
});
