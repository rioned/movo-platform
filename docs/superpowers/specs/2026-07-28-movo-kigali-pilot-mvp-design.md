# MOVO Controlled Kigali Pilot MVP — Binding Design Specification

Date: 2026-07-28
Source requirements: `docs/source/MOVO-Deliver-with-Confidence-extracted.txt`, pp. 13–44.
Compliance baseline: `docs/MOVO-spec-compliance-audit.md`.

## 1. Scope and release decision

Build the document’s Recommended MVP for an invite-only, restricted-zone Kigali pilot. MOVO remains a parcel/document logistics marketplace; Uber-style marketplace mechanics apply to delivery dispatch, not passenger trips. The existing Express/SQLite application remains a modular monolith for the pilot.

Pilot-only provider policy:
- SMS, mobile-money, maps, and geocoding use explicit provider interfaces and sandbox adapters.
- No response or UI may claim real SMS delivery or settled real money when the sandbox adapter is active.
- Real integrations require environment configuration and provider credentials before public launch.

Out of scope: subscriptions, loyalty, wallet, multi-stop, vans/trucks, intercity, AI route optimization, forecasts, corporate credit, and international expansion.

## 2. Roles and boundaries

| Role | Can do | Must not do |
|---|---|---|
| Customer | Register by phone, request/quote/pay for deliveries, track own deliveries, view proof/receipt/history, rate and open support tickets | View another customer’s deliveries, locations, receipts, or personal data |
| Rider | Complete approved onboarding, set availability, receive/accept offers, perform assigned lifecycle steps, upload proof, view own earnings/incidents | View unassigned delivery details or alter price/payment/other riders |
| Business owner/member | Create/manage business deliveries, view organization history/proof/statements under membership permissions | Access unrelated businesses or bypass business limits |
| Operations admin | Approve riders, manage zones/pricing, resolve support/payment exceptions, supervised delivery actions | Make unlogged operational changes |
| System admin | Manage roles/configuration/security/auditing | Use ordinary operations actions without audit trail |

## 3. Delivery state machine and Uber-style dispatch

### 3.1 Quote and payment states

A quote is immutable and carries origin/destination coordinates, service type, pricing-rule version, customer price, rider earnings, platform fee, currency, created time, and expiry. A delivery cannot dispatch without an accepted, unexpired quote and a valid pilot payment intent.

`draft → quoted → payment_pending → payment_authorized → searching`

Payment callback state must be idempotent. Sandbox authorization is visibly labelled in admin/testing; production settlement requires a verified provider callback.

### 3.2 Delivery states

`searching → offered → assigned → going_pickup → arrived_pickup → picked_up → in_transit → arrived_destination → delivered → settled`

Terminal alternatives: `cancelled`, `failed`, `payment_failed`, `expired`.

Every transition validates allowed prior state, actor role/ownership, transition reason where manual/exceptional, and writes a timestamped audit event.

### 3.3 Dispatch contract

1. Select approved riders who are online, location-fresh, not suspended, within configured radius, and have no active delivery.
2. Send a bounded offer batch ordered by eligibility/distance/rating policy; each offer has expiry.
3. First successful conditional acceptance wins. The database update must condition on a still-offered delivery and active offer so concurrent acceptance cannot assign two riders.
4. Expired/declined/failed offers trigger controlled radius expansion and next candidate batch.
5. Exhaustion transitions to `failed` with a customer notification and a retry option.
6. Rider cancellation/unavailability before pickup re-enters dispatch only under policy and audit rules.

## 4. Portal MVP acceptance journeys

### Customer
- Phone-first registration/login; email optional.
- Parcel and document request with pickup/destination, recipient, item details, quote review, and payment state.
- Live status timeline, ETA, rider identity appropriate to current state, map/location updates, cancellation policy.
- Delivery history, receipt, proof of delivery, rebook, rating, and support ticket.

### Rider
- Registration with identity, motorcycle, insurance, emergency contact, payout details, and protected document upload.
- Pending/approved/suspended status; only approved riders may become online.
- Offer countdown, accept/decline, pickup checklist/OTP, destination confirmation OTP, proof photo/signature where policy requires it.
- Earnings by delivery/day/week/month, settlement state, performance, incident/support reporting.
- Low-connectivity queue for lifecycle/location updates, visible connectivity state, retry/sync behavior.

### Business
- Business registration/profile, owner/member roles, basic member access boundaries.
- Delivery creation, quote/payment state, active tracking/history, proof/receipt, CSV-free basic statement by month.

### Admin
- Rider document/approval review, live operational delivery list/map, controlled status changes, zone/pricing version management, payment exceptions, support tickets, reports.
- All manual changes have actor, old/new values, reason, timestamp, and request correlation ID.

## 5. Security and privacy

- Phone OTPs are hashed, rate-limited, expiry-bound, never returned by production APIs, and tracked for abuse controls.
- Password policy, account lockout/backoff, secure session expiry, role authorization, and administrator MFA-ready design.
- Delivery tracking/proof/receipts require ownership or authorized business membership/assignment.
- Documents are private authenticated resources; no public static identity-document URLs.
- TLS at the proxy, secret injection only through environment/secret store, restricted CORS, request validation, audit logs, and database backup/restore.

## 6. Architecture and operations

- API modules: auth, users, quotes, deliveries, dispatch, tracking, payments, notifications, support, admin, provider adapters.
- WebSocket events are authorized by role/assignment; rider location updates require an assigned active delivery when a delivery ID is supplied.
- Health: liveness, readiness (database/provider configuration), and version metadata.
- Pilot operations: HTTPS proxy, PM2/system service, automated SQLite backup with restore test, structured logs, error reporting adapter, deployment runbook, and rollback procedure.
- Languages: English and Kinyarwanda resource bundles; simple wording, large action targets, contrast and keyboard accessibility.

## 7. Verification gates

- Unit: quote, lifecycle, dispatch candidate/timeout/atomic accept, authorization, payment callback idempotency.
- API integration: every role boundary, tracking privacy, upload access, rate limits, transition rejection, quote expiry.
- Browser E2E: Customer request through receipt; Rider offer through proof; Business delivery/history; Admin approval/exception.
- Security negatives: unauthenticated/cross-role/cross-organization access, stale offer race, forged provider callback, public document access.
- Pilot readiness: fresh backup/restore, production-config start, health/readiness, controlled rollback.

## 8. Explicit limitations before public launch

Public launch is blocked until live provider credentials/integrations, security testing, production monitoring/backup evidence, policy/legal review, and pilot acceptance metrics are completed.
