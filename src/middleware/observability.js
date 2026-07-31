'use strict';

/**
 * MOVO — observability middleware (zero external dependencies).
 *
 * Structured JSON logging, per-request correlation IDs, and a Prometheus-style
 * metrics registry covering both HTTP traffic and delivery marketplace health.
 */

const crypto = require('crypto');

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40, silent: 100 };

function createLogger({ level = 'info', service = 'movo', stream = process.stdout } = {}) {
  const threshold = LEVELS[level] ?? LEVELS.info;

  function emit(levelName, message, fields) {
    if ((LEVELS[levelName] ?? LEVELS.info) < threshold) return;
    const record = { ts: new Date().toISOString(), level: levelName, service, msg: message, ...fields };
    stream.write(`${JSON.stringify(record)}\n`);
  }

  return {
    level,
    debug: (message, fields) => emit('debug', message, fields),
    info: (message, fields) => emit('info', message, fields),
    warn: (message, fields) => emit('warn', message, fields),
    error: (message, fields) => emit('error', message, fields),
    child: (bindings) => ({
      level,
      debug: (message, fields) => emit('debug', message, { ...bindings, ...fields }),
      info: (message, fields) => emit('info', message, { ...bindings, ...fields }),
      warn: (message, fields) => emit('warn', message, { ...bindings, ...fields }),
      error: (message, fields) => emit('error', message, { ...bindings, ...fields })
    })
  };
}

const DURATION_BUCKETS = [0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10];

function createMetrics() {
  const counters = new Map();
  const histogram = { buckets: DURATION_BUCKETS, counts: new Map(), sum: 0, total: 0 };
  const gaugeProviders = new Map();
  const startedAt = Date.now();

  function labelKey(labels) {
    return Object.keys(labels).sort().map(key => `${key}="${String(labels[key]).replace(/"/g, '')}"`).join(',');
  }

  function incrementCounter(name, labels = {}, value = 1) {
    const key = `${name}{${labelKey(labels)}}`;
    counters.set(key, (counters.get(key) || 0) + value);
  }

  function observeDuration(seconds, labels = {}) {
    histogram.sum += seconds;
    histogram.total += 1;
    const key = labelKey(labels);
    const series = histogram.counts.get(key) || { buckets: new Array(DURATION_BUCKETS.length).fill(0), sum: 0, count: 0 };
    for (let index = 0; index < DURATION_BUCKETS.length; index += 1) {
      if (seconds <= DURATION_BUCKETS[index]) series.buckets[index] += 1;
    }
    series.sum += seconds;
    series.count += 1;
    histogram.counts.set(key, series);
  }

  function registerGauge(name, help, provider) {
    gaugeProviders.set(name, { help, provider });
  }

  function render() {
    const lines = [];
    lines.push('# HELP movo_process_uptime_seconds Seconds since the MOVO API process started.');
    lines.push('# TYPE movo_process_uptime_seconds gauge');
    lines.push(`movo_process_uptime_seconds ${((Date.now() - startedAt) / 1000).toFixed(3)}`);

    const memory = process.memoryUsage();
    lines.push('# HELP movo_process_resident_memory_bytes Resident memory in use by the API process.');
    lines.push('# TYPE movo_process_resident_memory_bytes gauge');
    lines.push(`movo_process_resident_memory_bytes ${memory.rss}`);

    if (counters.size > 0) {
      lines.push('# HELP movo_http_requests_total Total HTTP requests handled, by method, route and status class.');
      lines.push('# TYPE movo_http_requests_total counter');
      for (const [key, value] of counters) lines.push(`${key} ${value}`);
    }

    if (histogram.counts.size > 0) {
      lines.push('# HELP movo_http_request_duration_seconds HTTP request latency in seconds.');
      lines.push('# TYPE movo_http_request_duration_seconds histogram');
      for (const [labels, series] of histogram.counts) {
        const prefix = labels ? `${labels},` : '';
        DURATION_BUCKETS.forEach((bound, index) => {
          lines.push(`movo_http_request_duration_seconds_bucket{${prefix}le="${bound}"} ${series.buckets[index]}`);
        });
        lines.push(`movo_http_request_duration_seconds_bucket{${prefix}le="+Inf"} ${series.count}`);
        lines.push(`movo_http_request_duration_seconds_sum{${labels}} ${series.sum.toFixed(6)}`);
        lines.push(`movo_http_request_duration_seconds_count{${labels}} ${series.count}`);
      }
    }

    for (const [name, { help, provider }] of gaugeProviders) {
      let value;
      try { value = provider(); } catch { value = null; }
      if (value === null || value === undefined || Number.isNaN(Number(value))) continue;
      lines.push(`# HELP ${name} ${help}`);
      lines.push(`# TYPE ${name} gauge`);
      lines.push(`${name} ${Number(value)}`);
    }

    return `${lines.join('\n')}\n`;
  }

  return { incrementCounter, observeDuration, registerGauge, render, counters, histogram };
}

/** Attaches a request id, binds a child logger, records metrics and logs completion. */
function requestObserver({ logger, metrics, ignorePaths = [] } = {}) {
  return function observe(req, res, next) {
    const requestId = req.headers['x-request-id']?.toString().slice(0, 64) || crypto.randomUUID();
    req.id = requestId;
    req.log = logger.child({ requestId });
    res.setHeader('X-Request-Id', requestId);
    const startedAt = process.hrtime.bigint();

    res.on('finish', () => {
      const durationSeconds = Number(process.hrtime.bigint() - startedAt) / 1e9;
      const route = req.route?.path ? `${req.baseUrl || ''}${req.route.path}` : normalizePath(req.path);
      const labels = { method: req.method, route, status: String(res.statusCode) };
      metrics.incrementCounter('movo_http_requests_total', labels);
      metrics.observeDuration(durationSeconds, { method: req.method, route });
      if (ignorePaths.includes(req.path)) return;
      const level = res.statusCode >= 500 ? 'error' : res.statusCode >= 400 ? 'warn' : 'info';
      req.log[level]('http_request', {
        method: req.method,
        path: req.originalUrl.split('?')[0],
        status: res.statusCode,
        durationMs: Number((durationSeconds * 1000).toFixed(2)),
        userId: req.user?.id,
        role: req.user?.role
      });
    });

    next();
  };
}

/** Collapses identifiers out of unmatched paths so metric cardinality stays bounded. */
function normalizePath(pathname) {
  return pathname
    .split('/')
    .map(segment => (/^[0-9a-f]{8}-[0-9a-f]{4}/i.test(segment) || /^\d+$/.test(segment) ? ':id' : segment))
    .join('/') || '/';
}

module.exports = { createLogger, createMetrics, requestObserver, normalizePath, LEVELS };
