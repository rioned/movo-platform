const test = require('node:test');
const assert = require('node:assert/strict');
const { createMessaging } = require('../src/services/messaging');
const { loadRuntimeConfig, evaluateReadiness } = require('../src/config/runtime');

const silent = { info() {}, warn() {}, error() {} };

function fakeFetch(status, payload) {
  const calls = [];
  const impl = async (url, options) => {
    calls.push({ url, options });
    return { ok: status >= 200 && status < 300, status, json: async () => payload };
  };
  return { impl, calls };
}

test('twilio sends from a messaging service with basic auth, and never logs the message body', async () => {
  const logs = [];
  const logger = { info: (event, data) => logs.push({ event, data }), warn() {}, error() {} };
  const fetch = fakeFetch(201, { sid: 'SM123' });
  const messaging = createMessaging({
    provider: 'twilio', logger, fetchImpl: fetch.impl,
    twilio: { accountSid: 'AC1', authToken: 'secret', messagingServiceSid: 'MG1', from: '+15550000000' }
  });
  const result = await messaging.sendSms('+250788123456', 'MOVO verification code: 123456', { purpose: 'login' });

  assert.deepEqual(result, { queued: true, provider: 'twilio', sid: 'SM123' });
  assert.equal(fetch.calls.length, 1);
  const { url, options } = fetch.calls[0];
  assert.equal(url, 'https://api.twilio.com/2010-04-01/Accounts/AC1/Messages.json');
  assert.equal(options.headers.Authorization, `Basic ${Buffer.from('AC1:secret').toString('base64')}`);
  const form = new URLSearchParams(options.body);
  assert.equal(form.get('To'), '+250788123456');
  assert.equal(form.get('MessagingServiceSid'), 'MG1');
  assert.equal(form.get('From'), null, 'a messaging service takes precedence over a fixed sender');
  assert.ok(!JSON.stringify(logs).includes('123456'), 'the one-time code must never reach the logs');
});

test('twilio falls back to a fixed From number', async () => {
  const fetch = fakeFetch(201, { sid: 'SM9' });
  const messaging = createMessaging({ provider: 'twilio', logger: silent, fetchImpl: fetch.impl, twilio: { accountSid: 'AC1', authToken: 't', from: '+15550000000' } });
  await messaging.sendSms('+250788123456', 'hi');
  assert.equal(new URLSearchParams(fetch.calls[0].options.body).get('From'), '+15550000000');
});

test('twilio failures reject so callers log them, without echoing the body', async () => {
  const fetch = fakeFetch(400, { code: 21211, message: 'Invalid To' });
  const messaging = createMessaging({ provider: 'twilio', logger: silent, fetchImpl: fetch.impl, twilio: { accountSid: 'AC1', authToken: 't', from: '+1' } });
  await assert.rejects(messaging.sendSms('+250788123456', 'code 654321'), error => /HTTP 400, code 21211/.test(error.message) && !error.message.includes('654321'));

  const unconfigured = createMessaging({ provider: 'twilio', logger: silent, fetchImpl: fetch.impl, twilio: {} });
  await assert.rejects(unconfigured.sendSms('+250788123456', 'x'), /not all set/);
});

test('production readiness fails when twilio is selected without complete credentials', () => {
  const base = { NODE_ENV: 'production', JWT_SECRET: 's', SMS_PROVIDER: 'twilio' };
  const missing = loadRuntimeConfig(base);
  assert.ok(evaluateReadiness(missing, { database: true }).failures.includes('SMS_PROVIDER=twilio but Twilio credentials are incomplete'));
  const complete = loadRuntimeConfig({ ...base, TWILIO_ACCOUNT_SID: 'AC1', TWILIO_AUTH_TOKEN: 't', TWILIO_FROM: '+1' });
  assert.deepEqual(evaluateReadiness(complete, { database: true }).failures, []);
});
