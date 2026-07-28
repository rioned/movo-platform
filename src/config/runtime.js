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

function loadRuntimeConfig(env = process.env) {
  const production = env.NODE_ENV === 'production';
  if (production && !env.JWT_SECRET) throw new Error('JWT_SECRET must be set in production');
  const allowedOrigins = (env.ALLOWED_ORIGINS || 'http://localhost:3000,http://127.0.0.1:3000')
    .split(',').map(value => value.trim()).filter(Boolean);
  return {
    nodeEnv: env.NODE_ENV || 'development',
    production,
    port: positiveInteger(env.PORT, 3000, 'PORT'),
    jwtSecret: env.JWT_SECRET || 'development-only-movo-jwt-secret',
    dbPath: env.DB_PATH || (env.NODE_ENV === 'test' ? path.join(process.cwd(), 'movo-test.db') : path.join(process.cwd(), 'movo.db')),
    allowedOrigins,
    otpTestMode: env.OTP_TEST_MODE === 'true',
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
  return { ready: failures.length === 0, failures };
}

module.exports = { loadRuntimeConfig, evaluateReadiness };
