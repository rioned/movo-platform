# MOVO App Overview & High-Level Architecture Compliance Audit

Source: `/home/lab/Downloads/MOVO - Deliver with Confidence.pdf` (49 pages), extracted to `docs/source/MOVO-Deliver-with-Confidence-extracted.txt`.
Scope: document sections 4–20, especially the recommended MVP (pp. 15, 17–44).

## Current baseline
- Node/Express + SQLite modular-monolith backend; Customer/Rider/Business/Admin web portals.
- Current public surfaces are web pages, not native mobile apps.
- Basic auth, registration, pricing, dispatch, notifications, delivery state endpoints, and administration routes exist.

## Traceability matrix — recommended MVP

| Document requirement | Source pages | Current status | Evidence / gap |
|---|---:|---|---|
| Customer registration; phone-first identity | 17, 43 | Partial | Customer registration and phone-first login exist. Testing OTP mode is currently enabled in the live process; production OTP/SMS verification is not implemented. |
| Parcel and document requests | 6, 17–21, 43 | Partial | Backend accepts `parcel`/`document`; Customer UI only creates parcel and uses fixed coordinates. |
| Zone/distance pricing | 6, 31–37, 43 | Partial | Pricing engine/config routes exist; customer UI does not show a quote or require acceptance. |
| Verified rider assignment | 6, 22–24, 43 | Partial | Dispatch matches approved online riders, but full rider offer/accept UI and concurrency-safe acceptance are incomplete. |
| Live tracking and customer status | 6, 19–21, 43 | Partial | Tracking backend/websocket exists; Customer UI lacks tracking timeline/map. Tracking ownership is now protected. |
| Mobile-money payment | 6, 39–41, 43 | Missing | Current payment flow is simulated; no provider callback/signature/idempotency/reconciliation. |
| OTP/digital proof of delivery | 6, 38–39, 43 | Partial | Backend fields/state routes exist; Rider/Customer proof, signature/photo, receipt, and certificate UI are incomplete. |
| Delivery history, receipts, rebooking | 21, 43 | Partial | Delivery list endpoint exists; no complete history/receipt/rebook UI. |
| Rating and customer support | 38, 43 | Partial | Backend support/ratings capability is incomplete or not exposed in user portals. |
| Rider registration + documents | 22, 43 | Partial | Basic identity registration exists; document upload UI, secure document storage, and payment details are absent. |
| Rider approval, online/offline, job lifecycle | 22–25, 43 | Partial | Approval and status APIs exist; online/offline UI exists. Offers, navigation, pickup/delivery confirmation, proof, and incident/support UI are absent. |
| Rider earnings and performance | 24–25, 43 | Partial | API/dashboard summary exists; detailed daily/weekly/monthly settlement and payout UI is absent. |
| Business delivery creation/tracking/history/proof | 25–27, 44 | Partial | Registration/dashboard/create delivery exist; tracking, history, proof, statement UI are absent. |
| Business members/basic user management | 26–27, 44 | Missing | Backend membership currently creates customer-role users and does not implement business RBAC. |
| Administration MVP | 27–31, 44 | Partial | Admin login/dashboard/routes exist. Full live map, audit log, secure rider document review, payment/support workflows require verification and completion. |
| Secure, API-driven, observable architecture | 31–42 | Partial | API and health endpoint exist. No API gateway/rate limiting, managed secrets, TLS proxy, backups/restore, CI/CD, monitoring/error tracking, or DR verification. |
| English and Kinyarwanda; accessibility | 42–43 | Missing | English-centric UI only; no Kinyarwanda locale or demonstrated accessibility review. |
| Low-connectivity rider operation | 42 | Missing | No offline queue, synchronization, retry, connectivity indicator, or SMS fallback. |

## Release decision
The project is **not compliant with the document’s Recommended MVP** and must not be represented as production-ready. It is a partial web prototype with meaningful backend and portal foundations.

## Required implementation order
1. Make phone OTP production-safe: SMS adapter, hash OTPs, rate limits, expiry, no OTP in responses; disable test mode in deployed process.
2. Complete the delivery vertical slice: quote → payment authorization/callback → dispatch → rider pickup/delivery proof → customer tracking/history/receipt/rating.
3. Complete Rider and Business MVP journeys with browser/mobile acceptance tests.
4. Complete Admin operational, audit, pricing, support, and payment-reconciliation flows.
5. Add production architecture: environment/secret injection, HTTPS proxy, restricted uploads, database backup/restore, CI, monitoring, security and authorization tests.
6. Add Kinyarwanda/accessibility and rider low-connectivity support before broad launch.

## 2026-08-24 update — zoning and financial-architecture hardening

Source: `MOVO_MVP_AI_Code_Agent_Master_Prompt_v2.md` §7A (financial architecture) and §12/§50/§84 (zoning). Two structural gaps against that spec were closed in `server.js`:

- **Zoning correctness (critical fix).** `findZone()` previously fell back to the *nearest* zone when a coordinate fell outside every zone's radius, so a pickup anywhere on the map — including outside Rwanda — silently priced and dispatched. It now returns `null` for out-of-area points, and `calcPrice()`/`POST /api/deliveries` reject with `422 out_of_service_area` ("MOVO is not currently available at this location."), matching the master prompt's Test 7. Zones also gained an optional GeoJSON `boundary_geojson` polygon (ray-casting point-in-polygon), checked before the circular radius fallback, so `delivery_zones` is no longer limited to circles. Editing a zone's geometry (center/radius/boundary) after it has been used by a delivery now bumps `delivery_zones.version` and writes an `audit_log` entry (`zone_geometry_changed`) — commercial-only edits (name/price/active flag) do not.
- **Rider payout obligations (was missing entirely).** Delivery completion used to write a `payments` row with `status='completed'` unconditionally, with no obligation/idempotency record and no way to represent a stuck or failed payout. A new `payouts` table (`UNIQUE(delivery_id)`, `UNIQUE(reference)`, states `PENDING → INITIATED → PROCESSING → COMPLETED | FAILED | REVERSED`) now backs a `settlePayout()` flow: the delivery is moved to `DELIVERED` via a compare-and-swap update (closes a duplicate-completion race — spec Test 14), then a payout obligation is created idempotently and handed to a pluggable provider adapter (`src/services/payouts.js`, sandbox-only today, same pattern as `src/services/messaging.js`). A payout failure never rolls back delivery completion. `GET /api/admin/payouts` and `PUT /api/admin/payouts/:id/retry` give operations a reconciliation surface; `/api/rider/earnings` and `GET /api/deliveries/:id` now read payout status from this table instead of assuming instant settlement, and the customer-facing delivery view still never exposes it.

Covered by `test/financial-and-zoning.test.js` (7 tests: out-of-zone rejection, polygon resolution, zone version/audit on geometry change, single payout obligation on completion, duplicate-completion race, admin reconciliation listing, customer/rider financial separation).

Not done in this pass (flagged for follow-up, not silently skipped): a real mobile-money payout provider driver (MTN MoMo/Airtel Money disbursement — the adapter seam exists, no credentials/API integration); the identical unconditional-settlement pattern still exists in the ride-hailing completion path (`PUT /api/rides/:id/complete`) and was left alone to keep this change scoped to the parcel-delivery MVP the master prompt targets; a full temporal zone-pricing version history (current pricing edits are safe because each delivery stores its own price snapshot at creation time, so this was assessed as already spec-compliant rather than a gap).
