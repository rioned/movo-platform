const path = require('path');

function positiveInteger(value, fallback, name) {
  if (value === undefined || value === '') return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error(`${name} must be a positive integer`);
  return parsed;
}

function positiveNumber(value, fallback, name) {
  if (value === undefined || value === '') return fallback;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) throw new Error(`${name} must be a positive number`);
  return parsed;
}

function boolean(value, fallback) {
  if (value === undefined || value === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
}

function loadRuntimeConfig(env = process.env) {
  const production = env.NODE_ENV === 'production';
  const test = env.NODE_ENV === 'test';
  if (production && !env.JWT_SECRET) throw new Error('JWT_SECRET must be set in production');
  if (production && env.OTP_TEST_MODE === 'true') throw new Error('OTP_TEST_MODE must not be enabled in production');
  const allowedOrigins = (env.ALLOWED_ORIGINS || 'http://localhost:3000,http://127.0.0.1:3000')
    .split(',').map(value => value.trim()).filter(Boolean);
  return {
    nodeEnv: env.NODE_ENV || 'development',
    production,
    test,
    port: positiveInteger(env.PORT, 3000, 'PORT'),
    jwtSecret: env.JWT_SECRET || 'development-only-movo-jwt-secret',
    jwtExpiry: env.JWT_EXPIRY || '7d',
    dbPath: env.DB_PATH || (env.NODE_ENV === 'test' ? path.join(process.cwd(), 'movo-test.db') : path.join(process.cwd(), 'movo.db')),
    allowedOrigins,
    otpTestMode: env.OTP_TEST_MODE === 'true',
    logLevel: env.LOG_LEVEL || (test ? 'error' : production ? 'info' : 'debug'),
    trustProxy: boolean(env.TRUST_PROXY, production),
    httpsOnly: boolean(env.HTTPS_ONLY, production),
    metricsToken: env.METRICS_TOKEN || null,
    shutdownGraceMs: positiveInteger(env.SHUTDOWN_GRACE_MS, 10_000, 'SHUTDOWN_GRACE_MS'),
    security: {
      maxLoginAttempts: positiveInteger(env.MAX_LOGIN_ATTEMPTS, 5, 'MAX_LOGIN_ATTEMPTS'),
      lockoutMinutes: positiveInteger(env.LOCKOUT_MINUTES, 15, 'LOCKOUT_MINUTES'),
      maxOtpAttempts: positiveInteger(env.MAX_OTP_ATTEMPTS, 5, 'MAX_OTP_ATTEMPTS'),
      bcryptRounds: positiveInteger(env.BCRYPT_ROUNDS, 10, 'BCRYPT_ROUNDS')
    },
    rateLimit: {
      enabled: boolean(env.RATE_LIMIT_ENABLED, !test),
      windowMs: positiveInteger(env.RATE_LIMIT_WINDOW_MS, 60_000, 'RATE_LIMIT_WINDOW_MS'),
      global: positiveInteger(env.RATE_LIMIT_MAX, 600, 'RATE_LIMIT_MAX'),
      auth: positiveInteger(env.RATE_LIMIT_AUTH_MAX, 30, 'RATE_LIMIT_AUTH_MAX'),
      write: positiveInteger(env.RATE_LIMIT_WRITE_MAX, 120, 'RATE_LIMIT_WRITE_MAX')
    },
    retention: {
      riderLocationDays: positiveInteger(env.RIDER_LOCATION_RETENTION_DAYS, 30, 'RIDER_LOCATION_RETENTION_DAYS'),
      notificationDays: positiveInteger(env.NOTIFICATION_RETENTION_DAYS, 90, 'NOTIFICATION_RETENTION_DAYS')
    },
    providers: {
      maps: env.MAP_PROVIDER || 'sandbox',
      payment: env.PAYMENT_PROVIDER || 'sandbox',
      sms: env.SMS_PROVIDER || 'sandbox'
    },
    dispatch: {
      offerTimeoutSeconds: positiveInteger(env.DISPATCH_OFFER_TIMEOUT_SEC, 30, 'DISPATCH_OFFER_TIMEOUT_SEC'),
      initialRadiusKm: positiveNumber(env.DISPATCH_RADIUS_KM, 5, 'DISPATCH_RADIUS_KM')
    }
  };
}

function evaluateReadiness(config, dependencies) {
  const failures = [];
  if (!dependencies.database) failures.push('database unavailable');
  for (const [name, mode] of Object.entries(config.providers)) {
    if (!['sandbox', 'osm', 'mtn-momo', 'airtel-money', 'twilio'].includes(mode)) failures.push(`unsupported ${name} provider: ${mode}`);
  }
  if (config.production) {
    if (config.jwtSecret === 'development-only-movo-jwt-secret') failures.push('production JWT secret is the development default');
    if (config.otpTestMode) failures.push('OTP test mode is enabled');
    if (!config.rateLimit.enabled) failures.push('rate limiting is disabled');
  }
  return { ready: failures.length === 0, failures };
}

module.exports = { loadRuntimeConfig, evaluateReadiness };
