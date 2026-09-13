'use strict';

/**
 * MOVO — server-side place suggestions ("strong location suggestions" for
 * pickup/destination pickers), mixing two geocoding sources instead of
 * depending on one:
 *
 *  - Google Places Text Search — best for named businesses/landmarks
 *    ("Kigali Convention Centre"), but metered and requires
 *    GOOGLE_PLACES_API_KEY. Used only when a key is configured, and only
 *    ever adds results on top — never required for the endpoint to work.
 *  - OpenStreetMap geocoding — Nominatim (free, no key, low rate limits) or
 *    MapTiler's hosted geocoding API when MAPTILER_API_KEY is set (the same
 *    key the apps already pay for map tiles — see MapTileSources on
 *    Android), which is both cheap and much less rate-limited than public
 *    Nominatim. Always runs unless PLACES_PROVIDER=sandbox.
 *
 * Results are merged and deduplicated by rounded coordinate into one ranked
 * list (Google first, since it identifies named places OSM often can't),
 * mirroring the same hybrid pattern the Android client already uses in
 * HybridPlacesSearch — this is that same idea, server-side, so web/portal
 * clients and any future client get the same strong suggestions without
 * reimplementing the merge logic or shipping a Google Places SDK.
 *
 * Every network call is best-effort: a failing/quota-exceeded/timing-out
 * provider degrades the suggestion list, it never fails the request.
 */

const NOMINATIM_CONTACT = 'support@movo.example.com';
const USER_AGENT = 'MOVO/1.0 (+support@movo.example.com)';

function round(value) {
  return Number.isFinite(value) ? value.toFixed(4) : null;
}

function createGeocodingService({
  provider = 'osm',
  googlePlacesApiKey = '',
  maptilerApiKey = '',
  logger = console,
  fetchImpl = (...args) => fetch(...args)
} = {}) {
  async function nominatimSearch(query) {
    const params = new URLSearchParams({ q: query, format: 'json', limit: '6', addressdetails: '1', email: NOMINATIM_CONTACT });
    const response = await fetchImpl(`https://nominatim.openstreetmap.org/search?${params}`, { headers: { 'User-Agent': USER_AGENT } });
    if (!response.ok) throw new Error(`nominatim search failed: ${response.status}`);
    const body = await response.json();
    return (Array.isArray(body) ? body : []).map(entry => ({
      id: `osm:${entry.place_id}`,
      label: (entry.display_name || '').split(',')[0] || entry.display_name || 'Location',
      address: entry.display_name || '',
      lat: Number(entry.lat),
      lng: Number(entry.lon),
      source: 'osm'
    }));
  }

  async function maptilerSearch(query) {
    const response = await fetchImpl(`https://api.maptiler.com/geocoding/${encodeURIComponent(query)}.json?key=${maptilerApiKey}&limit=6`);
    if (!response.ok) throw new Error(`maptiler search failed: ${response.status}`);
    const body = await response.json();
    return (body.features || []).map(feature => ({
      id: `maptiler:${feature.id || feature.place_name}`,
      label: (feature.place_name || '').split(',')[0] || feature.place_name || 'Location',
      address: feature.place_name || '',
      lat: feature.geometry?.coordinates?.[1],
      lng: feature.geometry?.coordinates?.[0],
      source: 'maptiler'
    }));
  }

  /** OSM-ecosystem lookup: MapTiler (cheap, higher limits) when configured, Nominatim otherwise. */
  async function osmSearch(query) {
    if (maptilerApiKey) {
      try {
        return await maptilerSearch(query);
      } catch (err) {
        logger.warn?.('maptiler_search_failed', { error: err.message });
      }
    }
    try {
      return await nominatimSearch(query);
    } catch (err) {
      logger.warn?.('nominatim_search_failed', { error: err.message });
      return [];
    }
  }

  async function googleSearch(query, { lat, lng } = {}) {
    if (!googlePlacesApiKey) return [];
    const params = new URLSearchParams({ query, key: googlePlacesApiKey, region: 'rw' });
    if (Number.isFinite(lat) && Number.isFinite(lng)) {
      params.set('location', `${lat},${lng}`);
      params.set('radius', '50000');
    }
    try {
      const response = await fetchImpl(`https://maps.googleapis.com/maps/api/place/textsearch/json?${params}`);
      if (!response.ok) throw new Error(`google places request failed: ${response.status}`);
      const body = await response.json();
      if (body.status !== 'OK' && body.status !== 'ZERO_RESULTS') throw new Error(`google places status ${body.status}`);
      return (body.results || []).slice(0, 6).map(result => ({
        id: `google:${result.place_id}`,
        label: result.name || (result.formatted_address || '').split(',')[0],
        address: result.formatted_address || '',
        lat: result.geometry?.location?.lat,
        lng: result.geometry?.location?.lng,
        source: 'google'
      }));
    } catch (err) {
      logger.warn?.('google_places_search_failed', { error: err.message });
      return [];
    }
  }

  function dedupe(results) {
    const seen = new Set();
    const out = [];
    for (const result of results) {
      const key = `${round(result.lat)},${round(result.lng)}`;
      if (!Number.isFinite(result.lat) || !Number.isFinite(result.lng) || seen.has(key)) continue;
      seen.add(key);
      out.push(result);
    }
    return out;
  }

  /**
   * @param query Free-text search string typed into the pickup/destination field.
   * @param hint  Optional { lat, lng } to bias Google's results toward the
   *   customer's current area (e.g. their live pickup pin) — OSM/MapTiler
   *   ignore this today since neither free-tier API takes a bias param worth
   *   the extra request shape.
   */
  async function search(query, hint = {}) {
    const trimmed = (query || '').trim();
    if (!trimmed) return [];
    if (provider === 'sandbox') return [];
    if (provider === 'hybrid') {
      const [google, osm] = await Promise.all([googleSearch(trimmed, hint), osmSearch(trimmed)]);
      return dedupe([...google, ...osm]).slice(0, 8);
    }
    return dedupe(await osmSearch(trimmed)).slice(0, 8);
  }

  async function reverseGeocode(lat, lng) {
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || provider === 'sandbox') return null;
    if (maptilerApiKey) {
      try {
        const response = await fetchImpl(`https://api.maptiler.com/geocoding/${lng},${lat}.json?key=${maptilerApiKey}`);
        if (response.ok) {
          const body = await response.json();
          const name = body.features?.[0]?.place_name;
          if (name) return name;
        }
      } catch (err) {
        logger.warn?.('maptiler_reverse_failed', { error: err.message });
      }
    }
    try {
      const params = new URLSearchParams({ lat: String(lat), lon: String(lng), format: 'json', email: NOMINATIM_CONTACT });
      const response = await fetchImpl(`https://nominatim.openstreetmap.org/reverse?${params}`, { headers: { 'User-Agent': USER_AGENT } });
      if (!response.ok) return null;
      const body = await response.json();
      return body.display_name || null;
    } catch (err) {
      logger.warn?.('nominatim_reverse_failed', { error: err.message });
      return null;
    }
  }

  return { search, reverseGeocode, provider };
}

module.exports = { createGeocodingService };
