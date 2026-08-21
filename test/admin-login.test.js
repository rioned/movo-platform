const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const Database = require('better-sqlite3');

const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'public/admin/index.html'), 'utf8');
const port = 31000 + Math.floor(Math.random() * 1000);
const dbPath = path.join(os.tmpdir(), `movo-admin-login-${process.pid}-${Date.now()}.db`);
let server;

// The admin account is seeded by the server's own first-run bootstrap, not
// checked into the repo — so this spins up a real server against a throwaway
// DB (same pattern as admin-portal.test.js) instead of reading a movo.db
// that only exists on a machine that has already run the app once.
test.before(async () => {
  server = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: { ...process.env, NODE_ENV: 'test', PORT: String(port), DB_PATH: dbPath, JWT_SECRET: 'admin-login-test-secret', OTP_TEST_MODE: 'true', ADMIN_SEED_PASSWORD: 'Admin@2026' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await new Promise((resolve, reject) => {
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
});

test.after(() => {
  server?.kill();
  for (const file of [dbPath, `${dbPath}-shm`, `${dbPath}-wal`]) fs.rmSync(file, { force: true });
});

test('admin login form defaults identify an active admin account', () => {
  const phoneMatch = html.match(/<input id="login-phone"[^>]*value="([^"]+)"/);
  assert.ok(phoneMatch, 'admin login form must provide its demo phone');

  const db = new Database(dbPath, { readonly: true });
  const admin = db.prepare("SELECT phone FROM users WHERE role='admin' AND status='active' LIMIT 1").get();
  db.close();
  assert.ok(admin, 'an active admin account must exist');
  assert.equal(phoneMatch[1], admin.phone);
});

test('admin page inline scripts parse so the Sign In handler is available', () => {
  // Only the opening tag decides whether a script is external; the body may well
  // contain `src=` inside markup templates or image handling.
  const inlineScripts = [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)]
    .filter(match => !/\bsrc\s*=/.test(match[1]))
    .map(match => match[2])
    .join('\n');

  assert.doesNotThrow(() => new Function(inlineScripts));
  assert.match(inlineScripts, /function handleLogin\s*\(/);
  assert.ok(inlineScripts.length > 5000, 'the portal logic must be extracted, not an empty match');
});

test('live map uses real OSM tiles, routes, and timed rider-location refreshes', () => {
  assert.match(html, /https:\/\/\{s\}\.tile\.openstreetmap\.org\/\{z\}\/\{x\}\/\{y\}\.png/);
  assert.match(html, /https:\/\/router\.project-osrm\.org\/route\/v1\/driving/);
  assert.match(html, /setInterval\(loadLiveMap,15000\)/);
  assert.match(html, /function addMapRoute\s*\(/);
  assert.match(html, /function focusMapPoint\s*\(/);
  assert.match(html, /\.map-container\.leaflet-container\{height:400px\}/);
  assert.match(html, /className:'moto-map-icon'/);
  assert.match(html, /fa-motorcycle/);
});

test('admin portal has a persistent dark-mode switch that also themes the map', () => {
  assert.match(html, /id="theme-toggle"/);
  assert.match(html, /function toggleTheme\s*\(/);
  assert.match(html, /localStorage\.setItem\('admin_theme'/);
  assert.match(html, /body\.dark/);
  assert.match(html, /basemaps\.cartocdn\.com\/dark_all/);
});
