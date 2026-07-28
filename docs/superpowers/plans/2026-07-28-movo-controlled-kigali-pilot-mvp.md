# MOVO Controlled Kigali Pilot MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the PDF-defined Recommended MVP for a controlled Kigali pilot, including Uber-style delivery dispatch and full role journeys.

**Architecture:** Evolve the current Express/SQLite prototype into a modular monolith with explicit domain modules and provider adapters. Build vertical delivery lifecycle slices first; web portals remain pilot clients while API contracts stay mobile-client ready.

**Tech Stack:** Node.js, Express, better-sqlite3, Socket.IO, browser-native JavaScript, PM2, Node test runner, Playwright.

## Global Constraints
- Phone is the primary identity/login field; email is optional.
- Pilot providers are sandbox adapters and must never claim real SMS/payment settlement.
- Every delivery state transition is authorized, validated, idempotent where externally triggered, and audited.
- Customer tracking/proof/receipts require delivery ownership; rider access requires assignment; business access requires membership.
- No public static identity-document URLs.
- English and Kinyarwanda strings must use resource keys; controls must meet keyboard and contrast requirements.

---

## File Structure
- `src/config/` — validated runtime settings and provider selection.
- `src/domain/` — quote, lifecycle, dispatch, authorization, and audit rules.
- `src/routes/` — focused Express route modules.
- `src/providers/` — SMS, payment, map/geocode interfaces and sandbox implementations.
- `src/services/` — dispatch, notification, proof, receipt, statement, and backup services.
- `public/shared/` — portal shell, i18n, tracking, and accessible components.
- `test/` — isolated API/domain/browser regression tests.
- `scripts/` — backup/restore and pilot health verification.

### Task 1: Establish modular runtime configuration and test isolation
**Files:** Create `src/config/runtime.js`, `src/db/database.js`, `test/helpers/test-app.js`; modify `server.js`, `package.json`, `ecosystem.config.js`.
- [ ] Write failing tests for required production settings, test database isolation, and readiness failure when the database/provider configuration is invalid.
- [ ] Implement typed configuration: `JWT_SECRET`, allowed origins, provider modes, quote/offer timeouts, pilot zones, and test database path.
- [ ] Move database initialization into a factory accepting a database path.
- [ ] Add `npm run test:unit`, `npm run test:api`, `npm run test:e2e`, and `npm run check`.
- [ ] Run focused and full tests; commit `refactor: isolate MOVO pilot runtime configuration`.

### Task 2: Secure phone identity and private rider documents
**Files:** Create `src/services/otp-service.js`, `src/routes/auth.js`, `src/routes/rider-documents.js`; modify schema/migrations and `server.js`; create tests.
- [ ] Write failing tests for hashed OTP storage, expiry, resend rate limits, lockout, production non-disclosure, and authenticated document access.
- [ ] Implement OTP provider interface with sandbox and production adapter contracts; never return OTP outside test mode.
- [ ] Replace public upload serving with authorized private download endpoints and MIME/size validation.
- [ ] Add rider payout, emergency contact, insurance, and document metadata onboarding fields.
- [ ] Run tests; commit `feat: secure phone onboarding and rider documents`.

### Task 3: Quote, payment-intent, and immutable pricing contracts
**Files:** Create `src/domain/quotes.js`, `src/providers/payments.js`, `src/routes/quotes.js`, `src/routes/payments.js`; modify delivery schema and routes; create tests.
- [ ] Write failing tests for quote expiry, pricing-rule version persistence, unauthorized quote use, and idempotent sandbox payment callbacks.
- [ ] Implement immutable quote records and payment intents.
- [ ] Require accepted non-expired quote plus payment authorization before dispatch.
- [ ] Add sandbox labels and receipts; reserve live settlement for verified provider callbacks.
- [ ] Run tests; commit `feat: add immutable quotes and pilot payment intents`.

### Task 4: Atomic Uber-style dispatch and delivery state machine
**Files:** Create `src/domain/delivery-state.js`, `src/services/dispatch-service.js`, `src/routes/deliveries.js`; modify schema; create concurrency tests.
- [ ] Write failing tests for every allowed/rejected transition, stale offer rejection, two-rider accept race, reassignment, and no-rider exhaustion.
- [ ] Implement offers with expiry and conditional first-accept assignment in a SQLite transaction.
- [ ] Implement controlled candidate batches/radius expansion using approved online location-fresh riders with one active job maximum.
- [ ] Add audited cancellation/reassignment/admin override rules.
- [ ] Run tests; commit `feat: implement atomic rider offer dispatch lifecycle`.

### Task 5: Tracking, pickup/delivery proof, receipt, rating, and support vertical slice
**Files:** Create `src/services/proof-service.js`, `src/routes/tracking.js`, `src/routes/support.js`, `src/routes/ratings.js`; modify schemas and Socket.IO handlers; create tests.
- [ ] Write failing ownership/assignment tests for tracking, proof, ratings, and support.
- [ ] Implement pickup OTP/checklist, delivery OTP, photo/signature proof metadata, receipt generation, ratings, and tickets.
- [ ] Bind rider location events to assigned active deliveries and add connection retry/event ordering rules.
- [ ] Run API and WebSocket tests; commit `feat: complete delivery proof and support lifecycle`.

### Task 6: Customer portal MVP
**Files:** Refactor `public/customer/`; create `public/shared/portal.css`, `public/shared/i18n.js`, `public/shared/api.js`, `test/e2e/customer.spec.js`.
- [ ] Write Playwright tests for phone login, parcel/document quote, payment state, tracking, proof/receipt/history/rebook/rating/support.
- [ ] Implement separate phone-first auth pages, quote review, request wizard, accessible tracking/status, history, and support views.
- [ ] Add English/Kinyarwanda resource keys, keyboard focus, error states, and mobile layouts.
- [ ] Run E2E; commit `feat: complete customer pilot journey`.

### Task 7: Rider portal MVP and low-connectivity queue
**Files:** Refactor `public/rider/`; create `public/shared/offline-queue.js`, `test/e2e/rider.spec.js`.
- [ ] Write Playwright tests for onboarding state, availability, offer timeout, accept, pickup proof, delivery proof, earnings, and incident reporting.
- [ ] Implement offer UI, active-job lifecycle, proof capture, earnings/settlements, support/incidents, and location status.
- [ ] Implement IndexedDB queue/retry/synchronization and connectivity indicator for rider lifecycle/location updates.
- [ ] Run E2E; commit `feat: complete rider pilot workflow`.

### Task 8: Business portal MVP and membership boundaries
**Files:** Create `src/routes/business-members.js`, `src/services/statements.js`; refactor `public/business/`; create `test/e2e/business.spec.js`.
- [ ] Write tests for owner/member authorization, business-scoped deliveries, statements, proof, and cross-organization denial.
- [ ] Implement business membership roles, delivery history/tracking/proof, monthly statement, and basic member management.
- [ ] Run API/E2E tests; commit `feat: complete business pilot operations`.

### Task 9: Administration and operational audit MVP
**Files:** Create `src/routes/admin-operations.js`, `src/services/audit-service.js`; refactor `public/admin/`; create tests.
- [ ] Write tests for rider approval, pricing version changes, payment exception actions, audit event completeness, and authorization.
- [ ] Implement rider review, delivery monitoring/reassignment, zone/pricing management, support/payment exception views, and operational reports.
- [ ] Run admin E2E; commit `feat: complete pilot operations administration`.

### Task 10: Pilot deployment and acceptance evidence
**Files:** Create `Dockerfile`, `docker-compose.yml`, `deploy/nginx.conf`, `.github/workflows/ci.yml`, `scripts/backup.sh`, `scripts/restore-verify.sh`, `docs/PILOT-RUNBOOK.md`.
- [ ] Write checks for production configuration, readiness, backup/restore, secret absence, and CI commands.
- [ ] Implement HTTPS proxy configuration, health/readiness/version endpoints, structured logs, backup/restore, CI, and rollback runbook.
- [ ] Run full unit/API/Playwright/security-negative/backup-restore suite.
- [ ] Commit `feat: add controlled Kigali pilot deployment operations`.

## Spec coverage self-review
All requirements from `docs/superpowers/specs/2026-07-28-movo-kigali-pilot-mvp-design.md` are mapped: phone identity, quotes/payments, atomic dispatch, lifecycle/proof/tracking, four portals, provider policy, security, low-connectivity, i18n/accessibility, operations, and verification.
