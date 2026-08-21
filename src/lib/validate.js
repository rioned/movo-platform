'use strict';

/**
 * MOVO — request validation helpers.
 *
 * Every helper either returns a clean value or throws a ValidationError carrying
 * a stable machine-readable code, so API clients can localise their own copy.
 */

class ValidationError extends Error {
  constructor(message, { code = 'invalid_request', status = 400, field = null } = {}) {
    super(message);
    this.name = 'ValidationError';
    this.code = code;
    this.status = status;
    this.field = field;
  }
}

function fail(message, field, code = 'invalid_request') {
  throw new ValidationError(message, { code, field });
}

function requiredString(value, field, { min = 1, max = 500, label = field } = {}) {
  if (typeof value !== 'string' || value.trim().length < min) fail(`${label} is required`, field, 'missing_field');
  const trimmed = value.trim();
  if (trimmed.length > max) fail(`${label} must be at most ${max} characters`, field, 'field_too_long');
  return trimmed;
}

function optionalString(value, field, { max = 500, label = field } = {}) {
  if (value === undefined || value === null || value === '') return null;
  if (typeof value !== 'string') fail(`${label} must be text`, field);
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (trimmed.length > max) fail(`${label} must be at most ${max} characters`, field, 'field_too_long');
  return trimmed;
}

function oneOf(value, allowed, field, { label = field, optional = false } = {}) {
  if (value === undefined || value === null || value === '') {
    if (optional) return null;
    fail(`${label} is required`, field, 'missing_field');
  }
  if (!allowed.includes(value)) fail(`${label} must be one of: ${allowed.join(', ')}`, field, 'unsupported_value');
  return value;
}

function latitude(value, field = 'lat') {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < -90 || parsed > 90) fail('A valid latitude is required', field, 'invalid_coordinate');
  return parsed;
}

function longitude(value, field = 'lng') {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < -180 || parsed > 180) fail('A valid longitude is required', field, 'invalid_coordinate');
  return parsed;
}

function boundedNumber(value, field, { min = 0, max = Number.MAX_SAFE_INTEGER, label = field, fallback = undefined } = {}) {
  if (value === undefined || value === null || value === '') {
    if (fallback !== undefined) return fallback;
    fail(`${label} is required`, field, 'missing_field');
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || parsed > max) fail(`${label} must be between ${min} and ${max}`, field, 'out_of_range');
  return parsed;
}

function integerInRange(value, field, { min = 1, max = 100, fallback } = {}) {
  if (value === undefined || value === null || value === '') return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed)) fail(`${field} must be a whole number`, field, 'invalid_number');
  return Math.min(Math.max(parsed, min), max);
}

/** Pagination guard: page/limit are always clamped so a client cannot ask for the whole table. */
function pagination(query = {}, { defaultLimit = 20, maxLimit = 100 } = {}) {
  const limit = integerInRange(query.limit, 'limit', { min: 1, max: maxLimit, fallback: defaultLimit });
  const page = integerInRange(query.page, 'page', { min: 1, max: 10_000, fallback: 1 });
  return { limit, page, offset: (page - 1) * limit };
}

const RWANDA_MOBILE = /^\+2507\d{8}$/;

function normalizePhone(phone) {
  if (typeof phone !== 'string') return null;
  const compact = phone.trim().replace(/[\s()-]/g, '');
  let national;
  if (/^07\d{8}$/.test(compact)) national = compact;
  else if (/^2507\d{8}$/.test(compact)) national = `0${compact.slice(3)}`;
  else if (/^\+2507\d{8}$/.test(compact)) national = `0${compact.slice(4)}`;
  return national ? `+250${national.slice(1)}` : null;
}

function requiredPhone(value, field, { label = field } = {}) {
  const normalized = normalizePhone(value);
  if (!normalized || !RWANDA_MOBILE.test(normalized)) fail(`${label} must be a valid Rwanda mobile number`, field, 'invalid_phone');
  return normalized;
}

/** Rejects passwords that would fail the platform's account-security baseline. */
function password(value, field = 'password', { minLength = 8, maxLength = 128 } = {}) {
  if (typeof value !== 'string' || value.length < minLength) {
    fail(`Password must be at least ${minLength} characters`, field, 'weak_password');
  }
  if (value.length > maxLength) {
    fail(`Password must be at most ${maxLength} characters`, field, 'weak_password');
  }
  if (!/[A-Za-z]/.test(value) || !/\d/.test(value)) {
    fail('Password must include at least one letter and one number', field, 'weak_password');
  }
  return value;
}

module.exports = {
  ValidationError,
  requiredString,
  optionalString,
  oneOf,
  latitude,
  longitude,
  boundedNumber,
  integerInRange,
  pagination,
  normalizePhone,
  requiredPhone,
  password,
  RWANDA_MOBILE
};
