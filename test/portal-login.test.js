const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

for (const role of ['customer', 'rider', 'business']) {
  test(`${role} registration and phone-first login are separate pages`, () => {
    const registration = fs.readFileSync(path.join(__dirname, '..', 'public', role, 'index.html'), 'utf8');
    const login = fs.readFileSync(path.join(__dirname, '..', 'public', role, 'login', 'index.html'), 'utf8');
    assert.match(registration, new RegExp(`href="/${role}/login/"`));
    assert.doesNotMatch(registration, /portal-auth\.js/);
    assert.match(login, /portal-auth\.js/);
    assert.match(login, /phone number/i);
    assert.doesNotMatch(login, /Email/);
  });
}
