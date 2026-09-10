const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const root = path.join(__dirname, '..');
// Frozen before the visual refresh: scripts and control attributes must not drift.
const baseline = {
  "public/customer/index.html": {
    "scripts": "a36cfaff8512729879b53952cc812d618dffa8256c144f80e211e6bc2bd785c5",
    "controls": [
      "<form id=\"registration-form\">",
      "<input id=\"full_name\" required placeholder=\"Full name\">",
      "<input id=\"phone\" required type=\"tel\" placeholder=\"Phone, e.g. +2588... or +25078...\">",
      "<input id=\"email\" type=\"email\" placeholder=\"Email (optional)\">",
      "<input id=\"password\" minlength=\"8\" type=\"password\" placeholder=\"Password (optional \u2014 leave blank to sign in with an SMS code only)\">",
      "<button>",
      "<input id=\"otp-code\" inputmode=\"numeric\" maxlength=\"6\" placeholder=\"OTP\">",
      "<button id=\"verify\">"
    ],
    "ids": [
      "registration-form",
      "full_name",
      "phone",
      "email",
      "password",
      "otp",
      "otp-code",
      "verify",
      "message",
      "success"
    ]
  },
  "public/customer/login/index.html": {
    "scripts": "f3a8ea1da95cd93ed87a126a65867c0c9cbc0b6b22808aeb986e659be07eaa82",
    "controls": [],
    "ids": []
  },
  "public/rider/index.html": {
    "scripts": "243a6609bdfb9502a639a63b23e4a57c08b9c6237a4f33a18833057e82902899",
    "controls": [
      "<form id=\"registration-form\">",
      "<select id=\"vehicle_type\">",
      "<option value=\"car\">",
      "<option value=\"motorcycle\">",
      "<input id=\"national_id\" required placeholder=\"National ID\">",
      "<input id=\"license_number\" required placeholder=\"Driver license number\">",
      "<input id=\"car_plate\" placeholder=\"Car plate, e.g. AAB 123 MP\">",
      "<input id=\"car_make\" placeholder=\"Car make, e.g. Toyota\">",
      "<input id=\"car_model\" placeholder=\"Car model, e.g. Corolla\">",
      "<input id=\"car_color\" placeholder=\"Car color\">",
      "<input id=\"motorcycle_plate\" placeholder=\"Motorcycle plate, e.g. RAA123B\">",
      "<input id=\"full_name\" required placeholder=\"Full name\">",
      "<input id=\"phone\" required type=\"tel\" placeholder=\"Phone, e.g. +2588... or +25078...\">",
      "<input id=\"email\" type=\"email\" placeholder=\"Email (optional)\">",
      "<input id=\"password\" minlength=\"8\" type=\"password\" placeholder=\"Password (optional \u2014 leave blank to sign in with an SMS code only)\">",
      "<button>",
      "<input id=\"otp-code\" inputmode=\"numeric\" maxlength=\"6\" placeholder=\"OTP\">",
      "<button id=\"verify\">"
    ],
    "ids": [
      "registration-form",
      "vehicle_type",
      "national_id",
      "license_number",
      "car_plate",
      "car_make",
      "car_model",
      "car_color",
      "motorcycle_plate",
      "full_name",
      "phone",
      "email",
      "password",
      "otp",
      "otp-code",
      "verify",
      "message",
      "success"
    ]
  },
  "public/rider/login/index.html": {
    "scripts": "f3a8ea1da95cd93ed87a126a65867c0c9cbc0b6b22808aeb986e659be07eaa82",
    "controls": [],
    "ids": []
  },
  "public/business/index.html": {
    "scripts": "2594b751fe849a8f5a91324f16adc4c7e22b7a6139e5b1901da94c77552f9612",
    "controls": [
      "<form id=\"registration-form\">",
      "<input id=\"company_name\" required placeholder=\"Registered company name\">",
      "<input id=\"tax_id\" placeholder=\"TIN (optional)\">",
      "<input id=\"full_name\" required placeholder=\"Full name\">",
      "<input id=\"phone\" required type=\"tel\" placeholder=\"Phone, e.g. +25078...\">",
      "<input id=\"email\" type=\"email\" placeholder=\"Email (optional)\">",
      "<input id=\"password\" required minlength=\"8\" type=\"password\" placeholder=\"Password (minimum 8 characters)\">",
      "<button>",
      "<input id=\"otp-code\" inputmode=\"numeric\" maxlength=\"6\" placeholder=\"OTP\">",
      "<button id=\"verify\">"
    ],
    "ids": [
      "registration-form",
      "company_name",
      "tax_id",
      "full_name",
      "phone",
      "email",
      "password",
      "otp",
      "otp-code",
      "verify",
      "message",
      "success"
    ]
  },
  "public/business/login/index.html": {
    "scripts": "f3a8ea1da95cd93ed87a126a65867c0c9cbc0b6b22808aeb986e659be07eaa82",
    "controls": [],
    "ids": []
  }
};
for (const [file, original] of Object.entries(baseline)) {
  const html = fs.readFileSync(path.join(root, file), 'utf8');
  test(`${file}: keeps the existing authentication contract`, () => {
    const scripts = (html.match(/<script\b[^>]*>[\s\S]*?<\/script>/g) || []).join('');
    assert.equal(createHash('sha256').update(scripts).digest('hex'), original.scripts);
    assert.deepEqual(html.match(/<(?:form|input|select|option|button)\b[^>]*>/g) || [], original.controls);
    assert.deepEqual([...html.matchAll(/ id="([^"]+)"/g)].map(m => m[1]), original.ids);
  });
  test(`${file}: exposes a consistent accessible portal shell`, () => {
    assert.match(html, /<html lang="en">/);
    assert.match(html, /name="viewport" content="width=device-width,initial-scale=1"/);
    assert.match(html, /<body class="portal-page[^"]*">/);
    assert.match(html, /<link rel="stylesheet" href="\/portal-theme.css">/);
    assert.match(html, /<nav aria-label="[^"]+">[\s\S]*?<a[^>]*href="\/"[^>]*>[^<]*Back to home<\/a>/);
    assert.equal((html.match(/<main\b/g) || []).length, 1);
    assert.match(html, /class="portal-intro"/);
    for (const control of original.controls.filter(tag => /^<(input|select) /.test(tag))) {
      const id = control.match(/id="([^"]+)"/)[1];
      assert.ok(html.includes(`<label for="${id}">`), `${id} has a persistent label`);
    }
  });
}
test('shared theme provides scoped, responsive, keyboard-accessible controls', () => {
  const themePath = path.join(root, 'public/portal-theme.css');
  assert.ok(fs.existsSync(themePath), 'shared portal stylesheet exists');
  const css = fs.readFileSync(themePath, 'utf8');
  assert.match(css, /body\.portal-page/);
  assert.match(css, /--portal-forest:/);
  assert.match(css, /--portal-ivory:/);
  assert.match(css, /--portal-lime:/);
  assert.match(css, /box-sizing:\s*border-box/);
  assert.match(css, /:focus-visible/);
  assert.match(css, /min-height:\s*48px/);
  assert.match(css, /@media\s*\(max-width:\s*760px\)/);
  assert.match(css, /grid-template-columns:\s*1fr/);
  assert.match(css, /\.portal-page #otp,\s*\.portal-page #success\s*\{\s*display:\s*none/);
  assert.doesNotMatch(css, /@keyframes|animation:/);
});
