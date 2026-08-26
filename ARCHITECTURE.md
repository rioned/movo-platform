# Architecture

This document describes how MOVO's pieces fit together and why they're shaped the
way they are. For what the platform does, see [`README.md`](README.md). For the
API surface, see [`API_INTEGRATION.md`](API_INTEGRATION.md).

## System shape

MOVO is a **modular monolith**, deliberately: one Node.js/Express process, one
SQLite database, two native Android apps, and a handful of static web portals.
There is no microservice boundary anywhere, and none is planned until traffic
outgrows a single process — see "Why a monolith" below.

```text
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ MOVO Customer (Android)      │   │ MOVO Rider (Android)         │
│ android/customer-app         │   │ android/rider-app            │
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │        shared :design module      │
               └──────────────┬───────────────────┘
┌──────────────────────────────────────────────────────────────┐
│ Web portals — public/{customer,rider,business,admin}/         │
│ static HTML/CSS/JS, no build step, served directly by Express │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTPS / JSON + Socket.IO
┌──────────────────────────▼──────────────────────────────────┐
│ Edge middleware (src/middleware)                              │
│ security headers · rate limiting · request IDs · metrics     │
└──────────────────────────┬──────────────────────────────────┘
┌──────────────────────────▼──────────────────────────────────┐
│ Express application — server.js                              │
│ auth · pricing · dispatch · deliveries · rides · payments     │
│ tracking · proof of delivery · notifications · incidents      │
│ support · administration · KPIs · audit · analytics           │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ SQLite (better-sqlite3) — movo.db                             │
│ WAL mode · foreign keys · indexed · retention pruning         │
└─────────────────────────────────────────────────────────────┘
```

## Why a monolith

`better-sqlite3` is an in-process, synchronous database driver — there is no
network hop between `server.js` and its data, which is most of why the API is
as fast as it is. That constrains scaling to a single writer process; WAL mode
lets multiple readers and one writer coexist (which is what makes the canary
deploy in [`DEPLOYMENT.md`](DEPLOYMENT.md) safe — `movo` and `movo-canary` share
one SQLite file), but it rules out horizontal scaling of the write path. The
correct next step when that becomes a real constraint is moving to a networked
database (Postgres is the natural choice), not adding application-level
sharding or splitting services prematurely. Until then, one process is simpler
to reason about, deploy, and debug than a distributed system would be, for a
platform at this stage's traffic.

## Request lifecycle

1. **Edge middleware** (`src/middleware/security.js`, `src/middleware/observability.js`)
   runs first for every request: security headers, a fixed-window rate limiter
   (global + a tighter bucket for `/api/auth/*` + one for writes), a request ID
   (generated or echoed from `X-Request-Id`), and structured JSON access logging.
2. **`auth`** (server.js) verifies the JWT bearer token and attaches `req.user`.
   Unauthenticated routes (`/health`, `/ready`, `/api/config`, registration/login)
   skip this.
3. **`roleAuth(...)`** restricts a route to specific roles (`customer`, `rider`,
   `business`, `admin`). A route with no `roleAuth` is reachable by any
   authenticated role but still scopes data by `req.user` inside the handler
   (e.g. `GET /api/deliveries` filters by the caller's own deliveries).
4. **`requireFeature(name)`** (where present) gates a route behind a runtime
   feature flag before any heavier work runs — see [`ENVIRONMENT.md`](ENVIRONMENT.md#feature-flags).
5. The **route handler** validates input via `src/lib/validate.js` (stable
   error codes, not just messages — see [`API_INTEGRATION.md`](API_INTEGRATION.md#error-shape)),
   does the database work (usually inside a `db.transaction(...)` when more
   than one table changes together), and returns `resOK`/`resErr`.
6. Side effects — Socket.IO emits, SMS/push notifications, audit log rows — are
   fired after the transaction commits, never inside it.

## Core domain concepts

- **Two marketplaces, one codebase.** Parcel/document delivery (`deliveries`
  table, Kigali/Rwanda) and ride-hailing (`rides` table, Maputo/Mozambique) are
  modeled as separate tables with separate pricing config
  (`platform_fee_percent` vs `ride_platform_fee_percent`, etc.), deliberately
  kept independent so the two markets can be tuned without cross-affecting each
  other, even though they share the same rider pool, dispatch machinery shape,
  and auth/session layer.
- **Dispatch is blind and zone-based.** `dispatchDelivery()` / `dispatchRide()`
  broadcast a new request to the nearest eligible online riders within a radius
  that expands on timeout; a customer never browses or hand-picks an individual
  rider before assignment. This is a deliberate product decision, confirmed
  with the platform owner on 2026-08-26: the alternative (letting a customer
  browse and pick a named rider, which an earlier build of the Android
  customer app did) undermines fairness (new/low-rated riders never get
  picked) and invites gaming (riders soliciting selection outside the app), so
  MOVO standardized on the spec's blind-dispatch model everywhere, matching
  how ride-hailing already worked.
- **Financial fields are role-scoped, not just hidden in the UI.** A delivery
  row carries `customer_price`, `rider_earnings`, `platform_fee`, and
  `total_charge` together, but `customerDeliveryView()` and the equivalent
  logic in the POD/receipt endpoints strip `rider_earnings`/`platform_fee`
  before the response ever reaches a non-admin caller. This is enforced
  server-side because a client-side redaction is trivially bypassed by anyone
  reading the raw response.
- **Idempotency at the write boundary.** Delivery creation accepts an
  `Idempotency-Key` header; the OTP-gated handover endpoints (`verify-pickup`,
  `complete`) use a compare-and-swap on the delivery's current status so a
  retried or racing request can't double-settle a payment.
- **Payout is a separate obligation from the customer charge.** Completing a
  delivery immediately settles the customer's payment and the platform fee,
  but the rider's payout is inserted as its own `payouts` row and reconciled
  independently (via `src/services/payouts.js`) — a failed/slow payout
  provider never blocks or reverses the delivery itself.

## Android apps

Both apps build from one Gradle project (`android/`, modules `:design`,
`:customer-app`, `:rider-app`). `:design` is a Jetpack Compose/Material 3
library with no networking dependency — it owns the shared theme, delivery
status vocabulary, and the `AnalyticsEvent`/`AnalyticsLogger` contract (see
`android/design/src/main/kotlin/com/movo/design/Analytics.kt`). Each app
supplies its own networking (`RiderApi`/`CustomerApi`, plain
`HttpURLConnection`-based clients, no OkHttp/Retrofit dependency) and its own
concrete analytics sender (`RiderAnalytics`) that POSTs to
`/api/analytics/events`.

The rider app's location-sharing (`RiderLocationService.kt`) is a foreground
service with tiered cadence: tight polling while a delivery/ride is active,
loose polling while merely available-and-idle, both thresholds handed down by
the server on every location PUT (`GET /api/rider/location` response's
`tracking` object) rather than hardcoded client-side — see
`server.js`'s `locationTrackingConfig()`.

### Map/geocoding/routing abstraction

Both apps render tiles directly via osmdroid (`CustomerMap.kt`, `RiderMap.kt`)
— there is no second tile backend integrated, and faking one would be
decorative. What *is* abstracted, in `android/design/.../maps/MapServices.kt`,
is routing and geocoding: `RoutingService` and `GeocodingService` interfaces
with an `OSM` implementation (real calls to OSRM's and Nominatim's public
instances) and a `SANDBOX` implementation (no third-party network call at
all — a straight line, and no geocoding result). `MapServices.routing(...)`/
`.geocoding(...)` resolve the right pair for a `MapProvider`, so a screen
depends on "the configured provider," never a concrete OSRM/Nominatim class.
This is what makes the server's `MAP_PROVIDER` config (see
[`ENVIRONMENT.md`](ENVIRONMENT.md)) actually mean something on the client,
rather than being read only by the backend. `RiderMap.kt` uses `RoutingService`
for its pickup-to-destination line (upgrading instantly-drawn straight line to
a road-following route as soon as OSRM responds, falling back silently on
failure); `MapFirstSendScreen.kt` uses `GeocodingService` to resolve a
human-readable pickup address after a GPS fix, instead of leaving a
placeholder string. Both currently default to `MapProvider.OSM` directly
rather than reading `GET /api/config` — wiring that fetch through app startup
is the natural next step, not yet done.

## Web portals

`public/{customer,rider,business,admin}/` are static HTML/CSS/vanilla-JS, no
build step, served directly by Express as static files. The admin portal
(`public/admin/index.html`) is the largest — a single-file SPA-style page with
a Leaflet live map (marker-clustered for riders), debounced list search, and
every operational surface (deliveries, riders, incidents, zones, pricing,
finances, tickets, audit trail) wired to `/api/admin/*`.

## Observability

- `GET /health` — liveness (process up, DB answers).
- `GET /ready` — readiness (`evaluateReadiness()` in `src/config/runtime.js`:
  dependency health plus production-safety checks like "JWT secret wasn't
  auto-generated").
- `GET /metrics` — Prometheus exposition (request counts/latency, active
  deliveries, riders online, pending offers, open tickets), gated by
  `METRICS_TOKEN` in production.
- Every request is logged as one structured JSON line (method, route, status,
  duration, user role, request ID).

See [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) for how to use these when
something's wrong, and [`TESTING.md`](TESTING.md) for how the test suite
verifies the guarantees described above.
