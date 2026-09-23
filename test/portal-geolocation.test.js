const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../public/portal-auth.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); };
function portal(role = 'customer', online = 'offline', authenticated = true) {
  const nodes = new Map(), listeners = {}, requests = [], watches = new Map(), cleared = [], alerts = [];
  let serial = 0;
  function element(id = '') {
    const events = {}, classes = new Set();
    const el = { id, value: '', textContent: '', disabled: false,
      classList: { add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x) },
      append() {}, insertAdjacentHTML() {}, addEventListener(type, fn) { events[type] = fn; },
      async emit(type) { await (el['on' + type] || events[type])?.({ preventDefault() {} }); await flush(); },
      set innerHTML(html) { this.html = html; for (const match of html.matchAll(/id="([^"]+)"/g)) nodes.set(match[1], element(match[1])); },
      get innerHTML() { return this.html || ''; }
    }; return el;
  }
  nodes.set('main', element());
  const document = { hidden: false, head: element(), createElement: () => element(), querySelector: s => nodes.get(s), getElementById: id => nodes.get(id), addEventListener: (type, fn) => { listeners[type] = fn; } };
  const storage = new Map(authenticated ? [[`movo_${role}_token`, 'test-token']] : []);
  const geo = { watchPosition(success, error) { const id = ++serial; watches.set(id, { success, error }); return id; }, clearWatch(id) { cleared.push(id); watches.delete(id); }, getCurrentPosition(success, error) { geo.once = { success, error }; } };
  const context = { document, location: { pathname: `/${role}/login/`, reload() {} }, navigator: { geolocation: geo }, localStorage: { getItem: k => storage.get(k), setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }, Date, setTimeout, clearTimeout, console, alert: m => alerts.push(m), addEventListener: (type, fn) => { listeners[type] = fn; },
    fetch: async (url, options = {}) => {
      const request = { url, ...options, body: options.body ? JSON.parse(options.body) : undefined }; requests.push(request);
      let data = [];
      if (url === '/api/auth/me') data = { role, full_name: 'Test Person', online_status: online, approval_status: 'approved' };
      if (url === '/api/rider/performance') data = { total_deliveries: 0, total_earnings: 0 };
      if (url === '/api/business/dashboard') data = { active: 0, completed: 0, month_spend: 0 };
      if (url === '/api/ride-types') data = [{ id: 'economy', name: 'Economy' }];
      if (url === '/api/rides/estimate') data = { estimates: [] };
      if (url.startsWith('/api/places/search')) data = [{ address: 'Chosen street', lat: -2.123, lng: 30.456 }, { address: 'Other street', lat: -3, lng: 31 }];
      if (url === '/api/deliveries' && options.method === 'POST') data = { delivery: { order_no: 'TEST' } };
      if (url === '/api/rider/status') online = request.body.online ? 'online' : 'offline';
      return { ok: true, json: async () => ({ success: true, data }) };
    }
  };
  context.window = context;
  vm.runInNewContext(source, context);
  return { nodes, requests, watches, cleared, document, listeners, geo, storage, async fix(lat = -1.234, lng = 30.987, timestamp = Date.now()) { const position = { coords: { latitude: lat, longitude: lng, accuracy: 7 }, timestamp }; for (const watch of [...watches.values()]) watch.success(position); if (geo.once) { const cb = geo.once; geo.once = null; cb.success(position); } await flush(); }, async deny() { for (const watch of [...watches.values()]) watch.error({ code: 1, message: 'Permission denied' }); if (geo.once) { geo.once.error({ code: 1, message: 'Permission denied' }); geo.once = null; } await flush(); } };
}
test('GPS is foreground-only, ignores callbacks after stop, resumes without duplicate watchers', async () => {
  for (const role of ['customer', 'rider']) {
    const p = portal(role, 'online'); await flush();
    assert.equal(p.watches.size, 1);
    const late = [...p.watches.values()][0];
    p.document.hidden = true;
    await p.listeners.visibilitychange?.(); await flush();
    assert.equal(p.watches.size, 0);
    late.success({ coords: { latitude: 1, longitude: 2, accuracy: 3 }, timestamp: Date.now() }); await flush();
    assert.equal(p.requests.filter(r => r.url.endsWith('/location')).length, 0);
    p.document.hidden = false;
    await p.listeners.visibilitychange?.(); await flush();
    assert.equal(p.watches.size, 1);
    await p.nodes.get('refresh').emit('click');
    assert.equal(p.watches.size, 1);
    await p.listeners.pagehide?.();
    assert.equal(p.watches.size, 0);
  }
  for (const role of ['business', 'admin']) { const p = portal(role); await flush(); assert.equal(p.watches.size, 0); }
  const signedOut = portal('customer', 'offline', false); await flush(); assert.equal(signedOut.watches.size, 0);
});
test('parcel booking resolves both addresses and supports an explicit customer GPS pickup', async () => {
  for (const role of ['customer', 'business']) {
    const p = portal(role); await flush();
    p.nodes.get('pickup-address').value = 'Unresolved pickup';
    p.nodes.get('destination-address').value = 'Unresolved destination';
    await p.nodes.get('delivery-form').emit('submit');
    assert.equal(p.requests.filter(r => r.url === '/api/deliveries' && r.method === 'POST').length, 0);
    for (const id of ['pickup', 'destination']) {
      await p.nodes.get(`${id}-search`).emit('click');
      p.nodes.get(`${id}-results`).value = id === 'pickup' ? '1' : '0';
      await p.nodes.get(`${id}-results`).emit('change');
    }
    if (role === 'customer') { await p.fix(); await p.nodes.get('pickup-gps').emit('click'); }
    p.nodes.get('pickup-phone').value = '+250780000000';
    p.nodes.get('destination-phone').value = '+250780000001';
    await p.nodes.get('delivery-form').emit('submit');
    const parcel = p.requests.find(r => r.url === '/api/deliveries' && r.method === 'POST');
    assert.equal(parcel.body.pickup_lat, role === 'customer' ? -1.234 : -3);
    assert.equal(parcel.body.pickup_lng, role === 'customer' ? 30.987 : 31);
    assert.equal(parcel.body.pickup_address, role === 'customer' ? 'Current GPS location' : 'Other street');
    assert.equal(parcel.body.dest_address, 'Chosen street');
    assert.equal(parcel.body.dest_lat, -2.123);
    assert.equal(parcel.body.dest_lng, 30.456);
    assert.equal(p.watches.size, role === 'customer' ? 1 : 0);
  }
});
test('ride booking uses GPS pickup and explicitly selected address coordinates, never a demo estimate', async () => {
  const p = portal(); await flush();
  assert.equal(p.requests.filter(r => r.url === '/api/rides/estimate').length, 0);
  await p.fix();
  assert.ok(p.nodes.has('ride-destination-address'));
  p.nodes.get('ride-destination-address').value = 'Chosen';
  await p.nodes.get('ride-destination-search').emit('click');
  // Searching alone is not consent to book the first match.
  await p.nodes.get('ride-form').emit('submit');
  assert.equal(p.requests.filter(r => r.url === '/api/rides' && r.method === 'POST').length, 0);
  p.nodes.get('ride-destination-results').value = '0';
  await p.nodes.get('ride-destination-results').emit('change');
  p.nodes.get('ride-type-id').value = 'economy';
  p.nodes.get('ride-payment-method').value = 'cash';
  await p.nodes.get('ride-form').emit('submit');
  const ride = p.requests.find(r => r.url === '/api/rides' && r.method === 'POST');
  assert.ok(ride);
  assert.deepEqual(ride.body, { pickup_address: 'Current GPS location', pickup_lat: -1.234, pickup_lng: 30.987, dest_address: 'Chosen street', dest_lat: -2.123, dest_lng: 30.456, ride_type_id: 'economy', payment_method: 'cash' });
});
test('rider goes online only after a GPS write succeeds, streams fixes, and stops offline', async () => {
  const p = portal('rider'); await flush();
  assert.equal(p.watches.size, 0);
  const pending = p.nodes.get('rider-status').emit('click'); await flush();
  assert.equal(p.requests.filter(r => r.url === '/api/rider/status').length, 0);
  await p.fix(); await pending;
  const gpsIndex = p.requests.findIndex(r => r.url === '/api/rider/location');
  const onlineIndex = p.requests.findIndex(r => r.url === '/api/rider/status');
  assert.ok(gpsIndex >= 0 && onlineIndex > gpsIndex);
  assert.deepEqual(p.requests[onlineIndex].body, { online: true });
  assert.equal(p.watches.size, 1);
  await p.nodes.get('rider-status').emit('click');
  assert.deepEqual(p.requests.filter(r => r.url === '/api/rider/status').at(-1).body, { online: false });
  assert.equal(p.watches.size, 0);
});
test('authenticated customer streams actual GPS with original fix timestamp; logout stops watch', async () => {
  const p = portal(); await flush();
  assert.equal(p.watches.size, 1);
  const timestamp = Date.now() - 1000;
  await p.fix(-1.234, 30.987, timestamp);
  const update = p.requests.find(r => r.url === '/api/customer/location');
  assert.equal(update.method, 'PUT');
  assert.equal(update.headers.Authorization, 'Bearer test-token');
  assert.deepEqual(update.body, { lat: -1.234, lng: 30.987, accuracy: 7, recorded_at: new Date(timestamp).toISOString() });
  await p.nodes.get('logout').emit('click');
  assert.equal(p.watches.size, 0);
});
