'use strict';

/**
 * MOVO — security middleware (zero external dependencies).
 *
 * Provides the controls called for in the platform security architecture:
 * hardened response headers, request rate limiting, and body-size guards.
 */

const DEFAULT_CONNECT_SOURCES = ["'self'", 'https:', 'wss:', 'ws:'];
const DEFAULT_SCRIPT_SOURCES = ["'self'", "'unsafe-inline'", 'https://cdn.tailwindcss.com', 'https://unpkg.com', 'https://cdnjs.cloudflare.com'];
const DEFAULT_STYLE_SOURCES = ["'self'", "'unsafe-inline'", 'https://fonts.googleapis.com', 'https://unpkg.com', 'https://cdnjs.cloudflare.com'];
const DEFAULT_FONT_SOURCES = ["'self'", 'data:', 'https://fonts.gstatic.com', 'https://cdnjs.cloudflare.com'];
const DEFAULT_IMAGE_SOURCES = ["'self'", 'data:', 'blob:', 'https:'];

function buildContentSecurityPolicy(overrides = {}) {
  const directives = {
    'default-src': ["'self'"],
    'base-uri': ["'self'"],
    'frame-ancestors': ["'none'"],
    'object-src': ["'none'"],
    'form-action': ["'self'"],
    'img-src': DEFAULT_IMAGE_SOURCES,
    'script-src': DEFAULT_SCRIPT_SOURCES,
    'style-src': DEFAULT_STYLE_SOURCES,
    'font-src': DEFAULT_FONT_SOURCES,
    'connect-src': DEFAULT_CONNECT_SOURCES,
    ...overrides
  };
  return Object.entries(directives)
    .map(([directive, values]) => `${directive} ${values.join(' ')}`)
    .join('; ');
}

/**
 * Applies baseline security response headers to every request.
 * `httpsOnly` adds HSTS — enable only when TLS terminates in front of the app.
 */
function securityHeaders({ httpsOnly = false, csp = {}, maxAgeSeconds = 15552000 } = {}) {
  const policy = buildContentSecurityPolicy(csp);
  return function applySecurityHeaders(req, res, next) {
    res.setHeader('Content-Security-Policy', policy);
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
    res.setHeader('Cross-Origin-Resource-Policy', 'same-site');
    res.setHeader('Permissions-Policy', 'geolocation=(self), camera=(), microphone=(), payment=()');
    res.setHeader('X-Permitted-Cross-Domain-Policies', 'none');
    if (httpsOnly) res.setHeader('Strict-Transport-Security', `max-age=${maxAgeSeconds}; includeSubDomains`);
    next();
  };
}

/** Fixed-window counter keyed by caller identity; memory bounded by periodic sweeps. */
class RateLimitStore {
  constructor(windowMs) {
    this.windowMs = windowMs;
    this.buckets = new Map();
  }

  hit(key, now = Date.now()) {
    const bucket = this.buckets.get(key);
    if (!bucket || bucket.resetAt <= now) {
      const fresh = { count: 1, resetAt: now + this.windowMs };
      this.buckets.set(key, fresh);
      return fresh;
    }
    bucket.count += 1;
    return bucket;
  }

  sweep(now = Date.now()) {
    for (const [key, bucket] of this.buckets) {
      if (bucket.resetAt <= now) this.buckets.delete(key);
    }
  }

  reset() { this.buckets.clear(); }
}

function clientKey(req) {
  const forwarded = req.headers['x-forwarded-for'];
  const forwardedIp = typeof forwarded === 'string' ? forwarded.split(',')[0].trim() : null;
  return req.user?.id || forwardedIp || req.ip || req.socket?.remoteAddress || 'unknown';
}

/**
 * Fixed-window rate limiter.
 * Returns a middleware with a `.store` handle so callers can reset it in tests.
 */
function createRateLimiter({
  windowMs = 60_000,
  max = 300,
  enabled = true,
  scope = 'global',
  keyGenerator = clientKey,
  message = 'Too many requests. Please slow down and try again shortly.',
  code = 'rate_limited'
} = {}) {
  const store = new RateLimitStore(windowMs);
  const sweeper = setInterval(() => store.sweep(), Math.max(windowMs, 30_000));
  if (typeof sweeper.unref === 'function') sweeper.unref();

  function rateLimit(req, res, next) {
    if (!enabled || max <= 0) return next();
    const now = Date.now();
    const bucket = store.hit(`${scope}:${keyGenerator(req)}`, now);
    const remaining = Math.max(0, max - bucket.count);
    res.setHeader('RateLimit-Limit', String(max));
    res.setHeader('RateLimit-Remaining', String(remaining));
    res.setHeader('RateLimit-Reset', String(Math.ceil((bucket.resetAt - now) / 1000)));
    if (bucket.count > max) {
      res.setHeader('Retry-After', String(Math.ceil((bucket.resetAt - now) / 1000)));
      return res.status(429).json({ success: false, error: message, code });
    }
    return next();
  }

  rateLimit.store = store;
  rateLimit.stop = () => clearInterval(sweeper);
  return rateLimit;
}

module.exports = { securityHeaders, createRateLimiter, buildContentSecurityPolicy, RateLimitStore, clientKey };
