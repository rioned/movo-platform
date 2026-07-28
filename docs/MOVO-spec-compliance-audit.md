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
