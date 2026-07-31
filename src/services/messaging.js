'use strict';

/**
 * MOVO — outbound messaging (SMS / push / email).
 *
 * The platform must never block a delivery because a provider is down, so every
 * send is best-effort and logged. Swap `sandbox` for a real provider by adding a
 * driver here; no call site changes are required.
 */

function createMessaging({ provider = 'sandbox', logger = console, deliveries = [] } = {}) {
  const outbox = deliveries;

  async function sendSms(to, body, meta = {}) {
    const record = { channel: 'sms', to, body, provider, at: new Date().toISOString(), ...meta };
    outbox.push(record);
    if (outbox.length > 200) outbox.shift();
    switch (provider) {
      case 'twilio':
        // Production wiring point: POST to the provider with credentials from env.
        logger.warn?.('sms_provider_not_configured', { provider, to: mask(to), purpose: meta.purpose });
        return { queued: false, provider };
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
