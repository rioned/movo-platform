'use strict';

/**
 * MOVO — outbound messaging (SMS / push / email).
 *
 * The platform must never block a delivery because a provider is down, so every
 * send is best-effort and logged. Swap `sandbox` for a real provider by adding a
 * driver here; no call site changes are required.
 */

function createMessaging({ provider = 'sandbox', logger = console, deliveries = [], twilio = {}, fetchImpl = globalThis.fetch } = {}) {
  const outbox = deliveries;

  /**
   * Twilio Programmable Messaging. Sends from a Messaging Service when one is
   * configured (preferred: Twilio picks a sender valid for the destination
   * country), otherwise from a fixed number. The message body is never logged —
   * it carries one-time codes.
   */
  async function sendViaTwilio(to, body, meta) {
    const { accountSid, authToken, from, messagingServiceSid } = twilio;
    if (!accountSid || !authToken || (!from && !messagingServiceSid)) {
      throw new Error('Twilio is selected but TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM or TWILIO_MESSAGING_SERVICE_SID are not all set');
    }
    const form = new URLSearchParams({ To: to, Body: body });
    if (messagingServiceSid) form.set('MessagingServiceSid', messagingServiceSid);
    else form.set('From', from);
    const response = await fetchImpl(`https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(accountSid)}/Messages.json`, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${Buffer.from(`${accountSid}:${authToken}`).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: form.toString(),
      signal: AbortSignal.timeout(10_000)
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(`Twilio rejected the message (HTTP ${response.status}${payload.code ? `, code ${payload.code}` : ''})`);
    }
    logger.info?.('sms_sent', { provider: 'twilio', to: mask(to), purpose: meta.purpose, sid: payload.sid });
    return { queued: true, provider: 'twilio', sid: payload.sid };
  }

  async function sendSms(to, body, meta = {}) {
    const record = { channel: 'sms', to, body, provider, at: new Date().toISOString(), ...meta };
    outbox.push(record);
    if (outbox.length > 200) outbox.shift();
    switch (provider) {
      case 'twilio':
        return sendViaTwilio(to, body, meta);
      case 'sandbox':
      default:
        logger.info?.('sms_sandbox_delivery', { to: mask(to), purpose: meta.purpose });
        return { queued: true, provider };
    }
  }

  function mask(phone) {
    if (typeof phone !== 'string' || phone.length < 6) return 'unknown';
    return `${phone.slice(0, 6)}****${phone.slice(-2)}`;
  }

  return { sendSms, outbox, provider, mask };
}

module.exports = { createMessaging };
