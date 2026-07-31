# MOVO Platform

**Deliver with confidence.** MOVO is a Rwanda-focused digital logistics platform for parcel and document delivery in Kigali. It ships two native Android applications — customer and rider — plus business and operations-admin web portals, backed by a Node.js modular monolith.

> Current status: the platform now carries production-grade controls (security headers, rate limiting, account lockout, structured logging, metrics, readiness probes, graceful shutdown, retention pruning) and a complete delivery lifecycle including proof of delivery, incidents and KPIs. Remaining launch blockers are commercial, not architectural: live mobile-money and SMS provider contracts, an external security assessment, and Kigali pilot acceptance. See [`docs/MOVO-spec-compliance-audit.md`](docs/MOVO-spec-compliance-audit.md).

## What is included

**Native Android applications**

- **MOVO Customer** — map-first booking with live rider availability, transparent pricing before confirmation, exclusive rider selection, live tracking with handover codes, delivery history, proof of delivery, ratings and support
- **MOVO Rider** — GO-online availability, countdown-timed delivery offers, staged pickup and delivery workflow with verification codes and proof photos, navigation and calling, earnings and performance dashboards, document verification, SOS and incident reporting, and an offline queue that never loses a delivery event
- A shared `:design` Material 3 module so both apps use one brand, one type scale and one delivery-status vocabulary, in light and dark themes

**Platform**

- Phone-first registration and login for customers, riders, and businesses, with OTP verification, password policy, account lockout and audit logging
- Admin portal for operational oversight: an attention queue and platform-health check on the dashboard, the KPI pack, live map, delivery management with reassignment, proof of delivery and receipts, a rider dossier with document verification, rider incidents and SOS handling, account suspension, pricing, zones, support and a paginated audit trail
- Parcel and document delivery requests, including scheduled and bulk business uploads
- Zone-based pricing, platform fees, cancellation fees and delivery estimates
- Delivery lifecycle from request through dispatch, verified pickup, tracking, verified handover, settlement and rating
- Proof-of-delivery records and digital receipts for every completed delivery
- Ratings, support tickets, rider incidents/SOS, saved addresses, and business members
- SQLite persistence with WAL mode, foreign keys, tuned pragmas and retention pruning
- Socket.IO events for authenticated rider location updates and live delivery updates
- Sandbox adapters for maps, payments, and SMS during pilot development

The implementation deliberately keeps the platform a modular monolith. It is a delivery marketplace, not a passenger ride-hailing application.

## Architecture

```text
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ MOVO Customer (Android)      │   │ MOVO Rider (Android)         │
│ android/customer-app         │   │ android/rider-app            │
│ map-first booking · tracking │   │ offers · workflow · earnings │
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │        shared :design module      │
               └──────────────┬───────────────────┘
┌──────────────────────────────────────────────────────────────┐
│ Web portals — Business · Admin · public/{business,admin}/     │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTPS / JSON + Socket.IO
┌──────────────────────────▼──────────────────────────────────┐
│ Edge middleware                                              │
│ security headers · rate limiting · request IDs · metrics     │
└──────────────────────────┬──────────────────────────────────┘
┌──────────────────────────▼──────────────────────────────────┐
│ Express application — server.js                              │
│ auth · profiles · pricing · deliveries · dispatch · payments │
│ tracking · proof of delivery · notifications · incidents     │
│ support · administration · KPIs · audit                      │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ SQLite (better-sqlite3)                                     │
│ movo.db · WAL · foreign keys · indexed · retention pruning  │
└─────────────────────────────────────────────────────────────┘
```

## Technology

- Node.js and Express
- SQLite via `better-sqlite3`
- Socket.IO for realtime events
- JWT authentication with bcrypt password hashing
- Multer for authenticated rider-document uploads
- Kotlin, Jetpack Compose and Material 3 for both Android applications
- osmdroid (OpenStreetMap) for in-app maps, with no proprietary map SDK dependency
- Plain HTML, CSS, and JavaScript portal frontends
- PM2 for process-managed deployments
- Node's built-in test runner and Gradle/Kotlin unit tests

Security, observability and validation are implemented as dependency-free modules under
[`src/middleware`](src/middleware) and [`src/lib`](src/lib): fewer third-party packages
in the request path means a smaller supply-chain surface for a platform handling
identity documents and payments.

## Android applications

Both apps are built from the same Gradle project in [`android/`](android):

| Module | Purpose |
|---|---|
| `:design` | Shared Material 3 theme, components, delivery-status vocabulary and formatting |
| `:customer-app` | MOVO Customer — booking, tracking, history, account |
| `:rider-app` | MOVO Rider — availability, offers, delivery workflow, earnings, safety |

```bash
cd android
./gradlew assembleDebug          # Build both APKs
./gradlew testDebugUnitTest      # Run all Kotlin unit tests
./gradlew :rider-app:installDebug
```

Point the apps at your server by editing `API_BASE_URL` in each module's
`build.gradle.kts`. Debug builds default to a LAN address; release builds must be
given an HTTPS URL, and `usesCleartextTraffic` should be removed from the manifest
before any public release.

Both apps are verified on physical hardware, not only in tests: the booking journey,
rider discovery, offer countdown, acceptance and the live map were driven end-to-end
against a running server on an Android 15 device. Note that aggressive OEM battery
management can throttle a backgrounded rider's location updates; the rider app sends
a periodic heartbeat so a stationary rider stays discoverable, and the platform
deliberately shows customers an honest "no riders" rather than a stale rider.

Design principles carried through both apps:

- **One decision per screen.** The rider sees one next action; the customer sees one
  price and one confirmation.
- **State is never implied.** Availability, connectivity, location freshness and
  delivery stage are labelled explicitly rather than inferred from a spinner.
- **The server is the authority.** Realtime events trigger a refetch; the UI renders
  only what the API confirmed.
- **Offline is a first-class state.** Rider status updates are queued in encrypted
  storage and replayed automatically.

## Requirements

- Node.js 20 or newer
- npm
- A writable project directory for the SQLite database and upload storage
- For the Android applications: JDK 21 and the Android SDK (compile SDK 35, min SDK 29)

Check your installed versions:

```bash
node --version
npm --version
```

## Quick start

```bash
git clone https://github.com/rioned/movo-platform.git
cd movo-platform
npm ci
cp .env.example .env
npm start
```

The server listens on `http://localhost:3000` by default. Open one of these portals in a browser:

| Portal | URL |
|---|---|
| Customer registration | http://localhost:3000/customer/ |
| Customer login | http://localhost:3000/customer/login/ |
| Rider registration | http://localhost:3000/rider/ |
| Rider login | http://localhost:3000/rider/login/ |
| Business registration | http://localhost:3000/business/ |
| Business login | http://localhost:3000/business/login/ |
| Admin portal | http://localhost:3000/admin/ |
| Health check | http://localhost:3000/health |

The application creates `movo.db` and `uploads/` on first start. These are runtime data and should not be committed.

## Configuration

Copy `.env.example` to `.env` for local development. Supported settings include:

| Variable | Default | Description |
|---|---|---|
| `NODE_ENV` | `development` | Runtime environment; production requires `JWT_SECRET` |
| `PORT` | `3000` | HTTP and Socket.IO listening port |
| `JWT_SECRET` | Development-only fallback | Long random signing secret; required in production |
| `ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | Comma-separated CORS origins |
| `DB_PATH` | `./movo.db` | SQLite database path |
| `OTP_TEST_MODE` | `false` | Test-only OTP behavior; never enable for public production traffic |
| `MAP_PROVIDER` | `sandbox` | `sandbox` or `osm` |
| `PAYMENT_PROVIDER` | `sandbox` | `sandbox`, `mtn-momo`, or `airtel-money` |
| `SMS_PROVIDER` | `sandbox` | `sandbox` or `twilio` |
| `DISPATCH_OFFER_TIMEOUT_SEC` | `30` | Rider-offer timeout |
| `DISPATCH_RADIUS_KM` | `5` | Initial rider search radius |
| `JWT_EXPIRY` | `7d` | Access-token lifetime |
| `TRUST_PROXY` | production only | Trust `X-Forwarded-*` from one reverse proxy hop |
| `HTTPS_ONLY` | production only | Send HSTS; enable only behind TLS |
| `LOG_LEVEL` | `info` in production | `debug`, `info`, `warn`, `error`, `silent` |
| `METRICS_TOKEN` | unset | Bearer token for `/metrics`; without it the endpoint is hidden in production |
| `SHUTDOWN_GRACE_MS` | `10000` | Time allowed to drain connections on SIGTERM |
| `RATE_LIMIT_ENABLED` | on outside tests | Master switch for request rate limiting |
| `RATE_LIMIT_WINDOW_MS` | `60000` | Rate-limit window |
| `RATE_LIMIT_MAX` | `600` | Requests per window per caller across `/api` |
| `RATE_LIMIT_AUTH_MAX` | `30` | Requests per window against `/api/auth/*` |
| `RATE_LIMIT_WRITE_MAX` | `120` | Non-GET requests per window |
| `MAX_LOGIN_ATTEMPTS` | `5` | Failed passwords before the account locks |
| `LOCKOUT_MINUTES` | `15` | Lockout duration |
| `MAX_OTP_ATTEMPTS` | `5` | Wrong verification codes before lockout |
| `BCRYPT_ROUNDS` | `10` | Password hashing cost; raise to 12 in production |
| `RIDER_LOCATION_RETENTION_DAYS` | `30` | Location-breadcrumb retention |
| `NOTIFICATION_RETENTION_DAYS` | `90` | Read-notification retention |

Do not commit `.env`, JWT secrets, real provider credentials, identity documents, database files, or uploaded evidence.

## Development commands

```bash
npm install              # Install dependencies
npm start                # Start the production-style server
npm run dev              # Restart the server when files change
npm test                 # Run all Node.js tests
npm run test:syntax      # Check server and portal JavaScript syntax
npm run start:pm2        # Start with the PM2 ecosystem configuration
npm run android:build    # Assemble both Android debug APKs
npm run android:test     # Run all Kotlin unit tests
```

For a clean local test database, set an explicit path and test environment:

```bash
NODE_ENV=test DB_PATH=/tmp/movo-test.db npm test
```

## API overview

All JSON endpoints are served under `/api`. Authenticated routes expect the JWT returned by login or OTP verification, normally as a bearer token.

### Authentication and profiles

- `POST /api/auth/register`
- `POST /api/auth/verify-otp`
- `POST /api/auth/login`
- `GET /api/auth/me`
- `PUT /api/profile`

### Delivery operations

- `POST /api/deliveries/price` — calculate a quote
- `POST /api/deliveries` — create a parcel or document delivery
- `GET /api/deliveries` — list deliveries visible to the authenticated role
- `GET /api/deliveries/:id`
- `GET /api/deliveries/:id/track`
- `PUT /api/deliveries/:id/accept`
- `PUT /api/deliveries/:id/going-pickup`
- `PUT /api/deliveries/:id/arrive-pickup`
- `PUT /api/deliveries/:id/verify-pickup`
- `PUT /api/deliveries/:id/in-transit`
- `PUT /api/deliveries/:id/arrive-dest`
- `PUT /api/deliveries/:id/complete`
- `PUT /api/deliveries/:id/cancel`

### Proof of delivery and receipts

- `GET /api/deliveries/:id/pod` — proof-of-delivery record: verification timestamps, coordinates, recipient, rider and charges
- `GET /api/deliveries/:id/proof/:kind` — the pickup or delivery photograph, restricted to the delivery participants
- `GET /api/deliveries/:id/receipt` — digital receipt with the fee breakdown and settlement lines

### Rider, business, and support operations

- Rider profile, documents, availability (`online`, `busy`, `unavailable`, `offline`), location, earnings, performance, offers, incidents/SOS, and active-delivery endpoints are under `/api/rider/*` and `/api/mobile/v1/rider/*`.
- Business profile, dashboard, members, invoices and bulk delivery upload (`POST /api/business/deliveries/bulk`) are under `/api/business/*`.
- Ratings and support tickets are under `/api/ratings` and `/api/tickets`.
- Admin operations are under `/api/admin/*`, including rider approval, delivery oversight and reassignment, account suspension (`PUT /api/admin/users/:id/status`), zone/pricing configuration, finances, live map, reports, incidents, the audit trail (`/api/admin/audit`) and the KPI pack (`/api/admin/kpis`).

Suspension refuses to strand deliveries that are in flight unless the caller passes
`force`, never applies to administrator accounts, invalidates the account's existing
tokens, and records the reason in the audit trail.

Errors carry a stable machine-readable `code` alongside the human message, so clients can
localise their own copy:

```json
{ "success": false, "error": "A valid latitude is required", "code": "invalid_coordinate" }
```

The route handlers in [`server.js`](server.js) are the authoritative API contract until an OpenAPI specification is added.

## Operating the service

| Endpoint | Purpose |
|---|---|
| `GET /health` | Liveness — process is up and the database answers |
| `GET /ready` | Readiness — dependencies and configuration are fit to serve traffic |
| `GET /metrics` | Prometheus exposition: request counts and latency, active deliveries, riders online, pending offers, open tickets |

Every response carries an `X-Request-Id` (echoed from the caller when supplied) and every
request is logged as one JSON line with method, route, status, duration, and user role.
`SIGTERM` drains connections, closes Socket.IO, checkpoints the WAL and exits within
`SHUTDOWN_GRACE_MS`.

## Realtime tracking

The server exposes Socket.IO on the same port as HTTP. A client authenticates with the `authenticate` event using a JWT and may send authorized `rider_location` updates. Delivery updates are emitted to the relevant delivery room. Location and tracking access must remain restricted to the assigned rider, customer, business membership, or authorized administrator.

## Project structure

```text
movo-platform/
├── android/
│   ├── design/                # Shared Material 3 theme, components, delivery vocabulary
│   ├── customer-app/          # MOVO Customer Android application
│   └── rider-app/             # MOVO Rider Android application
├── public/
│   ├── customer/              # Customer registration, login, and portal
│   ├── rider/                 # Rider registration, login, and portal
│   ├── business/              # Business registration, login, and portal
│   ├── admin/                 # Operations-admin portal
│   └── portal-auth.js         # Shared portal authentication client
├── src/
│   ├── config/runtime.js      # Environment parsing and readiness checks
│   ├── lib/validate.js        # Request validation with stable error codes
│   ├── middleware/security.js # Security headers and rate limiting
│   ├── middleware/observability.js # Structured logging, request IDs, metrics
│   └── services/messaging.js  # SMS/push provider abstraction
├── server.js                  # Express, Socket.IO, API routes, and database setup
├── test/                      # Node.js unit, integration, and contract tests
├── docs/                      # Design specification, source material, and audits
├── ecosystem.config.js        # PM2 process configuration
├── .env.example               # Runtime configuration template
├── package.json               # Scripts and dependencies
└── package-lock.json          # Locked npm dependency tree
```

## Security and pilot readiness

Implemented controls:

- JWT authentication with issuer/audience binding, role checks and per-delivery ownership checks
- Password policy, account lockout after repeated failures, and OTP attempt limits
- Fixed-window rate limiting: a global ceiling, a tighter bucket for `/api/auth/*`, and one for writes
- Security headers on every response: CSP, HSTS (behind TLS), `nosniff`, `DENY` framing, referrer and permissions policy
- Request validation with stable error codes, bounded pagination, and a 1 MB JSON body limit
- Identity documents and proof photos served only to the participants or operations, never from static hosting
- One-time passwords delivered by SMS, never returned in an API response outside test mode
- Audit logging of administrative actions, approvals, cancellations, reassignments and incidents
- Retention pruning of location breadcrumbs and read notifications
- Structured logs, Prometheus metrics, readiness probes and graceful shutdown

Still required before a public launch — these are commercial and assurance steps, not code:

1. Configure a strong `JWT_SECRET` through a secret manager and set `BCRYPT_ROUNDS=12`.
2. Connect a contracted SMS provider and disable `OTP_TEST_MODE`.
3. Configure verified mobile-money callbacks, idempotency, reconciliation, and settlement handling.
4. Put the service behind HTTPS and a hardened reverse proxy, and set `TRUST_PROXY`/`HTTPS_ONLY`.
5. Remove `usesCleartextTraffic` from both Android manifests and ship release builds with an HTTPS `API_BASE_URL`.
6. Add tested SQLite backup/restore evidence, alerting on the exposed metrics, and rollback procedures.
7. Commission an external authorization, upload-access, callback-forgery, race-condition, and cross-tenant security test.
8. Complete the controlled Kigali pilot acceptance journeys for all four roles.

See [`docs/superpowers/specs/2026-07-28-movo-kigali-pilot-mvp-design.md`](docs/superpowers/specs/2026-07-28-movo-kigali-pilot-mvp-design.md) for the binding pilot scope and [`docs/MOVO-spec-compliance-audit.md`](docs/MOVO-spec-compliance-audit.md) for known gaps.

## Contributing

1. Fork the repository and clone your fork.
2. Create a focused branch from `main`.
3. Copy `.env.example` and keep secrets out of Git.
4. Run `npm run test:syntax` and `npm test` before opening a pull request.
5. Document changes that affect the API, portal flows, security model, or deployment behavior.

Use conventional commit messages where practical. Issues and pull requests should include reproduction steps and the affected role or endpoint.

## License

No license file is currently included in the repository. All rights reserved unless the project owner publishes separate licensing terms.
