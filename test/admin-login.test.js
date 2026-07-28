const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Database = require('better-sqlite3');

const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'public/admin/index.html'), 'utf8');
const db = new Database(path.join(root, 'movo.db'), { readonly: true });

test('admin login form defaults identify an active admin account', () => {
  const phoneMatch = html.match(/<input id="login-phone"[^>]*value="([^"]+)"/);
  assert.ok(phoneMatch, 'admin login form must provide its demo phone');

  const admin = db.prepare("SELECT phone FROM users WHERE role='admin' AND status='active' LIMIT 1").get();
  assert.ok(admin, 'an active admin account must exist');
  assert.equal(phoneMatch[1], admin.phone);
});

test('admin page inline scripts parse so the Sign In handler is available', () => {
  const inlineScripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
    .filter(match => !/\bsrc\s*=/.test(match[0]))
    .map(match => match[1])
    .join('\n');

  assert.doesNotThrow(() => new Function(inlineScripts));
  assert.match(inlineScripts, /function handleLogin\s*\(/);
});
