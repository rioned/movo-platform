# MOVO Platform

**Deliver with confidence.** MOVO is a Rwanda-focused digital logistics platform for parcel and document delivery in Kigali. The pilot provides separate customer, rider, business, and operations-admin portals backed by a Node.js modular monolith.

> Current status: this repository is a pilot web prototype with meaningful backend and portal foundations. It is not production-ready until live provider integrations, security review, monitoring, backup/restore evidence, operational policies, and end-to-end pilot acceptance are complete. See [`docs/MOVO-spec-compliance-audit.md`](docs/MOVO-spec-compliance-audit.md).

## What is included

- Phone-first registration and login for customers, riders, and businesses
- Admin portal for operational oversight, rider approval, pricing, zones, support, and reports
- Parcel and document delivery requests
- Zone-based pricing and delivery estimates
- Rider onboarding, document upload, availability, location, earnings, and performance views
- Delivery lifecycle management from assignment through proof of delivery
- Delivery tracking and notifications
- Ratings, support tickets, saved addresses, and business members
- SQLite persistence with WAL mode and foreign-key enforcement
- Socket.IO events for authenticated rider location updates and live delivery updates
- Sandbox adapters for maps, payments, and SMS during pilot development

The current implementation deliberately keeps the pilot as a modular monolith. It is a delivery marketplace, not a passenger ride-hailing application.

## Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│ Web portals                                                  │
│ Customer · Rider · Business · Admin                          │
│ public/{customer,rider,business,admin}/                      │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP / JSON + Socket.IO
┌──────────────────────────▼──────────────────────────────────┐
│ Express application                                          │
│ server.js                                                    │
│ auth · profiles · pricing · deliveries · dispatch            │
│ payments · tracking · notifications · support · administration│
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ SQLite (better-sqlite3)                                     │
│ movo.db · WAL · foreign keys                                │
└─────────────────────────────────────────────────────────────┘
```

## Technology

- Node.js and Express
- SQLite via `better-sqlite3`
- Socket.IO for realtime events
- JWT authentication with bcrypt password hashing
- Multer for authenticated rider-document uploads
- Plain HTML, CSS, and JavaScript portal frontends
- PM2 for process-managed deployments
- Node's built-in test runner

## Requirements

- Node.js 20 or newer
- npm
- A writable project directory for the SQLite database and upload storage

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

Do not commit `.env`, JWT secrets, real provider credentials, identity documents, database files, or uploaded evidence.

## Development commands

```bash
npm install              # Install dependencies
npm start                # Start the production-style server
npm run dev              # Restart the server when files change
npm test                 # Run all Node.js tests
npm run test:syntax      # Check server and portal JavaScript syntax
npm run start:pm2        # Start with the PM2 ecosystem configuration
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

### Rider, business, and support operations

- Rider profile, documents, availability, location, earnings, performance, and active-delivery endpoints are under `/api/rider/*`.
- Business profile, dashboard, members, and invoices are under `/api/business/*`.
- Ratings and support tickets are under `/api/ratings` and `/api/tickets`.
- Admin operations are under `/api/admin/*`, including rider approval, delivery oversight, zone/pricing configuration, finances, live map, and reports.

The route handlers in [`server.js`](server.js) are the authoritative API contract until an OpenAPI specification is added.

## Realtime tracking

The server exposes Socket.IO on the same port as HTTP. A client authenticates with the `authenticate` event using a JWT and may send authorized `rider_location` updates. Delivery updates are emitted to the relevant delivery room. Location and tracking access must remain restricted to the assigned rider, customer, business membership, or authorized administrator.

## Project structure

```text
movo-platform/
├── public/
│   ├── customer/              # Customer registration, login, and portal
│   ├── rider/                 # Rider registration, login, and portal
│   ├── business/              # Business registration, login, and portal
│   ├── admin/                 # Operations-admin portal
│   └── portal-auth.js         # Shared portal authentication client
├── src/config/runtime.js      # Environment parsing and readiness checks
├── server.js                  # Express, Socket.IO, API routes, and database setup
├── test/                      # Node.js unit, integration, and portal contract tests
├── docs/                      # Design specification, source material, and audits
├── ecosystem.config.js        # PM2 process configuration
├── .env.example               # Runtime configuration template
├── package.json               # Scripts and dependencies
└── package-lock.json          # Locked npm dependency tree
```

## Security and pilot readiness

The application contains controls for JWT authentication, role checks, restricted CORS, file-size/type limits, and ownership checks, but these are not a substitute for a production security assessment. Before a public launch:

1. Configure a strong secret through a secret manager or deployment environment.
2. Disable test OTP behavior and connect a real SMS provider with rate limiting and abuse controls.
3. Configure verified mobile-money callbacks, idempotency, reconciliation, and settlement handling.
4. Put the service behind HTTPS and a hardened reverse proxy.
5. Protect uploaded identity documents as authenticated private resources.
6. Add tested SQLite backup/restore, monitoring, error reporting, structured logs, and rollback procedures.
7. Complete authorization, upload-access, callback-forgery, race-condition, and cross-tenant security tests.
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
