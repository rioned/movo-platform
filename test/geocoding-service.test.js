'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createGeocodingService } = require('../src/services/geocoding');

function fakeJsonResponse(body, ok = true, status = 200) {
  return { ok, status, json: async () => body };
}

test('sandbox provider never calls the network and returns no suggestions', async () => {
  let called = false;
  const service = createGeocodingService({ provider: 'sandbox', fetchImpl: async () => { called = true; return fakeJsonResponse([]); } });
  assert.deepEqual(await service.search('Kigali'), []);
  assert.equal(await service.reverseGeocode(-1.94, 30.06), null);
  assert.equal(called, false);
});

test('blank queries short-circuit before any network call', async () => {
  let called = false;
  const service = createGeocodingService({ provider: 'osm', fetchImpl: async () => { called = true; return fakeJsonResponse([]); } });
  assert.deepEqual(await service.search('   '), []);
  assert.equal(called, false);
});

test('osm provider without a MapTiler key calls Nominatim and maps its response shape', async () => {
  const service = createGeocodingService({
    provider: 'osm',
    fetchImpl: async (url) => {
      assert.match(String(url), /nominatim\.openstreetmap\.org\/search/);
      return fakeJsonResponse([{ place_id: 1, display_name: 'Kigali Heights, KG 7 Ave, Kigali', lat: '-1.9367', lon: '30.0867' }]);
    }
  });
  const results = await service.search('Kigali Heights');
  assert.equal(results.length, 1);
  assert.equal(results[0].source, 'osm');
  assert.equal(results[0].label, 'Kigali Heights');
  assert.equal(results[0].lat, -1.9367);
  assert.equal(results[0].lng, 30.0867);
});

test('osm provider prefers MapTiler geocoding over Nominatim when a key is configured', async () => {
  let nominatimCalled = false;
  const service = createGeocodingService({
    provider: 'osm',
    maptilerApiKey: 'test-key',
    fetchImpl: async (url) => {
      const href = String(url);
      if (href.includes('nominatim')) { nominatimCalled = true; return fakeJsonResponse([]); }
      assert.match(href, /api\.maptiler\.com\/geocoding/);
      return fakeJsonResponse({ features: [{ id: 'abc', place_name: 'Kigali, Rwanda', geometry: { coordinates: [30.0619, -1.9441] } }] });
    }
  });
  const results = await service.search('Kigali');
  assert.equal(results.length, 1);
  assert.equal(results[0].source, 'maptiler');
  assert.equal(results[0].lat, -1.9441);
  assert.equal(results[0].lng, 30.0619);
  assert.equal(nominatimCalled, false);
});

test('osm provider falls back to Nominatim when MapTiler fails', async () => {
  const service = createGeocodingService({
    provider: 'osm',
    maptilerApiKey: 'test-key',
    fetchImpl: async (url) => {
      const href = String(url);
      if (href.includes('maptiler')) return fakeJsonResponse({}, false, 500);
      return fakeJsonResponse([{ place_id: 2, display_name: 'Kigali, Rwanda', lat: '-1.9441', lon: '30.0619' }]);
    }
  });
  const results = await service.search('Kigali');
  assert.equal(results.length, 1);
  assert.equal(results[0].source, 'osm');
});

test('hybrid provider merges Google Places on top of OSM results and dedupes by rounded coordinate', async () => {
  const service = createGeocodingService({
    provider: 'hybrid',
    googlePlacesApiKey: 'google-key',
    fetchImpl: async (url) => {
      const href = String(url);
      if (href.includes('googleapis.com')) {
        return fakeJsonResponse({
          status: 'OK',
          results: [{ place_id: 'g1', name: 'Kigali Convention Centre', formatted_address: 'KG 2 Roundabout, Kigali', geometry: { location: { lat: -1.9536, lng: 30.0925 } } }]
        });
      }
      return fakeJsonResponse([{ place_id: 1, display_name: 'Kigali Convention Centre, KG 2 Roundabout', lat: '-1.9536', lon: '30.0925' }]);
    }
  });
  const results = await service.search('Kigali Convention Centre');
  // Same coordinate rounded to 4 decimals from both sources — deduped to one, Google kept first.
  assert.equal(results.length, 1);
  assert.equal(results[0].source, 'google');
});

test('hybrid provider without a Google key behaves exactly like osm', async () => {
  const service = createGeocodingService({
    provider: 'hybrid',
    fetchImpl: async () => fakeJsonResponse([{ place_id: 1, display_name: 'Kigali, Rwanda', lat: '-1.9441', lon: '30.0619' }])
  });
  const results = await service.search('Kigali');
  assert.equal(results.length, 1);
  assert.equal(results[0].source, 'osm');
});

test('a failing Google Places call degrades to OSM-only results instead of throwing', async () => {
  const service = createGeocodingService({
    provider: 'hybrid',
    googlePlacesApiKey: 'google-key',
    fetchImpl: async (url) => {
      const href = String(url);
      if (href.includes('googleapis.com')) throw new Error('simulated network failure');
      return fakeJsonResponse([{ place_id: 1, display_name: 'Kigali, Rwanda', lat: '-1.9441', lon: '30.0619' }]);
    }
  });
  const results = await service.search('Kigali');
  assert.equal(results.length, 1);
  assert.equal(results[0].source, 'osm');
});

test('reverseGeocode prefers MapTiler, falls back to Nominatim, and returns null in sandbox', async () => {
  const maptiler = createGeocodingService({
    provider: 'osm', maptilerApiKey: 'test-key',
    fetchImpl: async () => fakeJsonResponse({ features: [{ place_name: 'Kigali, Rwanda' }] })
  });
  assert.equal(await maptiler.reverseGeocode(-1.9441, 30.0619), 'Kigali, Rwanda');

  const nominatim = createGeocodingService({
    provider: 'osm',
    fetchImpl: async (url) => { assert.match(String(url), /nominatim\.openstreetmap\.org\/reverse/); return fakeJsonResponse({ display_name: 'Kigali, Rwanda' }); }
  });
  assert.equal(await nominatim.reverseGeocode(-1.9441, 30.0619), 'Kigali, Rwanda');

  const sandbox = createGeocodingService({ provider: 'sandbox', fetchImpl: async () => fakeJsonResponse({}) });
  assert.equal(await sandbox.reverseGeocode(-1.9441, 30.0619), null);

  assert.equal(await nominatim.reverseGeocode(NaN, NaN), null);
});
