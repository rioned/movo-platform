const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('landing page offers accessible delivery scene controls and existing portal paths', () => {
  const html = fs.readFileSync('public/index.html', 'utf8');
  assert.match(html, /id="delivery-scene"/);
  assert.match(html, /aria-pressed="true"[^>]*data-scene="parcel"/);
  assert.match(html, /aria-pressed="false"[^>]*data-scene="document"/);
  assert.match(html, /id="motion-toggle"/);
  for (const role of ['customer', 'rider', 'business']) assert.ok(html.includes(`href="/${role}/"`));
  assert.match(html, /href="#main"/);
});
