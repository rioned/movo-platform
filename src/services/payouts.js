'use strict';

/**
 * MOVO — rider payout provider adapter.
 *
 * No mobile-money payout provider (MTN MoMo disbursement, Airtel Money B2C, etc.)
 * is wired up yet, so this is an isolated mock adapter — mirrors messaging.js.
 * Swap `sandbox` for a real provider by adding a driver here; call sites in
 * server.js only ever see { status, providerRef, failureReason } and never a
 * provider-specific shape, so no call site changes when a real driver lands.
 *
 * A payout obligation's lifecycle (tracked in the `payouts` table) never depends
 * on this adapter succeeding on the first try — server.js persists PENDING before
 * calling initiate() and leaves the delivery DELIVERED regardless of the result.
 */
function createPayoutProvider({ provider = 'sandbox', logger = console } = {}) {
  async function initiate(payout) {
    switch (provider) {
      case 'mtn-momo':
      case 'airtel-money':
        // Production wiring point: call the disbursement API with the payout
        // reference as the idempotency key, then return PROCESSING/INITIATED
        // and let a webhook/poll move it to COMPLETED or FAILED.
        logger.warn?.('payout_provider_not_configured', { provider, reference: payout.reference });
        return { status: 'FAILED', failureReason: `${provider} payout driver not implemented` };
      case 'sandbox':
      default:
        // Simulates an instant successful settlement so the MVP has a realistic
        // end-to-end payout lifecycle without a live provider integration.
        logger.info?.('payout_sandbox_settled', { reference: payout.reference, amount: payout.amount });
        return { status: 'COMPLETED', providerRef: `SBX-${payout.reference}` };
    }
  }

  return { initiate, provider };
}

module.exports = { createPayoutProvider };
