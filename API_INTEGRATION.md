# API Integration Guide

Everything a client (the Android apps, the web portals, or a third-party
integration) needs to call the MOVO API. For *why* the API is shaped this way,
see [`ARCHITECTURE.md`](ARCHITECTURE.md). The route handlers in
[`server.js`](server.js) remain the authoritative contract until an OpenAPI
spec is added — this document is a map, not a replacement for reading the
handler when a detail matters.

## Base URL and transport

All endpoints are served under `/api` on the same host/port as the web
portals, plus Socket.IO on that same port for realtime events. There is no
separate API host. Android builds point at a server via `API_BASE_URL` in
each module's `build.gradle.kts` — debug builds default to a LAN address,
release builds must use an HTTPS URL (see [`DEPLOYMENT.md`](DEPLOYMENT.md)).

## Authentication

1. `POST /api/auth/register` — creates an account and sends an OTP (role one
   of `customer`, `rider`, `business`; riders must also supply
   `national_id`, `license_number`, and vehicle fields).
2. `POST /api/auth/verify-otp` — exchanges the OTP for a JWT.
3. `POST /api/auth/login` — phone + password (or SMS OTP for supported phone
   prefixes) for a returning user.
4. Send the JWT as `Authorization: Bearer <token>` on every subsequent
   request. Tokens expire after `JWT_EXPIRY` (default `7d`).
5. `GET /api/auth/me` — resolve the current session.

`OTP_TEST_MODE=true` (never in production — `runtime.js` refuses to boot with
it in `NODE_ENV=production`) returns the OTP directly in the register/login
response instead of sending SMS, which is what every automated test uses.

A handful of routes are intentionally unauthenticated: `GET /health`,
`GET /ready`, `GET /api/config` (feature flags — see below), and the
registration/login endpoints themselves.

## Feature flags

`GET /api/config` returns the current feature-flag state and the active map
provider, with no auth required, so a client can decide what UI to show
before login:

```json
{
  "features": {
    "paymentsEnabled": true,
    "podPhotoEnabled": true,
    "signatureEnabled": true,
    "chatEnabled": false,
    "scheduledDeliveryEnabled": true
  },
  "map_provider": "osm"
}
```

Flags are set via environment variables (`PAYMENTS_ENABLED`,
`POD_PHOTO_ENABLED`, `SIGNATURE_ENABLED`, `CHAT_ENABLED`,
`SCHEDULED_DELIVERY_ENABLED` — see [`ENVIRONMENT.md`](ENVIRONMENT.md#feature-flags))
and enforced server-side, not just advertised: e.g. with
`PAYMENTS_ENABLED=false`, `POST /api/rides` returns `503 payments_disabled`
regardless of what the client does with the flag.

## Delivery lifecycle

```text
POST /api/deliveries/price        → quote (customer-facing fee only, unless caller is admin)
POST /api/deliveries              → create; server dispatches blind to nearby riders
GET  /api/deliveries              → list deliveries visible to the caller's role
GET  /api/deliveries/:id          → detail (role-scoped financial fields)
GET  /api/deliveries/:id/track    → live position + status for an in-flight delivery
PUT  /api/deliveries/:id/accept
PUT  /api/deliveries/:id/going-pickup
PUT  /api/deliveries/:id/arrive-pickup
PUT  /api/deliveries/:id/verify-pickup   (rider submits the pickup OTP)
PUT  /api/deliveries/:id/in-transit
PUT  /api/deliveries/:id/arrive-dest
PUT  /api/deliveries/:id/complete        (rider submits the delivery OTP; settles payment)
PUT  /api/deliveries/:id/cancel
```

Dispatch is blind and zone-based (see [`ARCHITECTURE.md`](ARCHITECTURE.md)):
the customer is never shown a list of individual riders to choose from.
`GET /api/mobile/v1/customer/nearby-riders` exists only to tell the customer
app whether riders are plausibly available nearby before they commit to a
request — it does not identify individual riders to a customer.

### Proof of delivery and receipts

- `GET /api/deliveries/:id/pod` — verification timestamps, coordinates,
  recipient, rider, and charges.
- `GET /api/deliveries/:id/proof/:kind` (`pickup` or `delivery`) — the photo,
  restricted to delivery participants. Gated by `POD_PHOTO_ENABLED`.
- `GET /api/deliveries/:id/receipt` — fee breakdown and settlement lines.

Both `/pod` and `/receipt` apply the same role-based financial redaction as
the delivery detail endpoint: a customer/business sees only their own fee, a
rider sees only their own payout, and only `admin` sees the full breakdown
(`platform_fee`, `rider_earnings`, and the customer fee together).

## Ride-hailing lifecycle

Mirrors the delivery lifecycle under `/api/rides` (`POST /api/rides/estimate`,
`POST /api/rides`, `PUT /api/rides/:id/accept` … `/complete`, `/cancel`). Ride
dispatch (`dispatchRide()`) has always been blind/automatic — there was never
a driver-picker on the ride-hailing side.

## Rider operations

Under `/api/rider/*` and `/api/mobile/v1/rider/*`:

- `PUT /api/rider/status` — availability (`online`, `busy`, `unavailable`,
  `offline`); rejected mid-delivery except a transition to `busy`.
- `PUT /api/rider/location` — position ping. The response includes a
  `tracking` object (`interval_ms`, `min_distance_m`, `min_accuracy_m`) that
  the rider app's foreground location service adopts for its next update
  cycle — see [`ARCHITECTURE.md`](ARCHITECTURE.md) and
  `RiderLocationService.kt`.
- `GET /api/mobile/v1/rider/home` — profile, active delivery/ride, pending
  offers, and the same `tracking` object (so the location service can start
  at the right cadence before its first location PUT).
- `GET /api/mobile/v1/rider/offers`, `PUT .../offers/:id/decline` — the offer
  feed; sender/recipient identity is withheld until acceptance.
- `POST /api/rider/deliveries/:id/proof` — pickup/delivery photo upload.
  Gated by `POD_PHOTO_ENABLED`.
- `POST /api/rider/incidents`, `GET /api/rider/incidents` — SOS and safety
  reports; a `critical`-severity incident notifies every admin immediately.
- `GET /api/rider/earnings`, `GET /api/rider/performance` — earnings/KPI
  summaries.

## Business operations

Under `/api/business/*`: profile, dashboard, members, invoices, and
`POST /api/business/deliveries/bulk` (up to 50 rows per request, partial
success reported per row).

## Admin operations

Under `/api/admin/*`: rider approval, delivery oversight and reassignment
(`PUT /api/admin/deliveries/:id/reassign`), account suspension
(`PUT /api/admin/users/:id/status` — refuses to strand in-flight deliveries
unless `force` is passed, never applies to admin accounts, invalidates the
account's existing tokens, and is audit-logged), zone/pricing configuration,
finances, the live map (`GET /api/admin/live-map`), reports, incidents, the
audit trail (`GET /api/admin/audit`), and the KPI pack
(`GET /api/admin/kpis`).

## Analytics events

`POST /api/analytics/events` (authenticated, any role) records a client-fired
product analytics event:

```json
{ "name": "quote_viewed", "properties": { "service_type": "parcel" } }
```

`name` must be one of a fixed catalog (`quote_viewed`, `delivery_confirmed`,
`delivery_completed`, `ride_requested`, `ride_completed`, `rider_went_online`,
`rider_went_offline`, `offer_accepted`, `offer_declined`) — an unrecognized
name is rejected with `400`, so a typo fails loudly instead of silently
fragmenting the data. Events are stored in the `analytics_events` table
against the caller's `user_id` and `role`; there is no dashboard on top of
this yet, only the ingestion path and the raw table.

## Realtime (Socket.IO)

Connect to the same host/port as HTTP. Authenticate with an `authenticate`
event carrying the JWT. A rider may emit `rider_location` updates (also
achievable via the REST `PUT /api/rider/location`, which is what the rider
app actually uses). Delivery/ride updates are broadcast to a room scoped to
that delivery/ride (`delivery:<id>`, `ride:<id>`); a client must have joined
the room it's authorized for — access follows the same rules as the REST
endpoints (assigned rider, the customer/business, or an admin).

## Error shape

Every error response carries a stable, machine-readable `code` alongside a
human-readable `error` message, so a client can localize its own copy instead
of pattern-matching on English text:

```json
{ "success": false, "error": "A valid latitude is required", "code": "invalid_coordinate" }
```

Common codes you'll want to branch on: `missing_field`, `invalid_coordinate`,
`out_of_service_area`, `rider_unavailable`, `active_delivery`, `active_ride`,
`invalid_otp`, `feature_disabled`, `payments_disabled`,
`idempotency_conflict`, `forbidden` / `forbidden_target`,
`auth_rate_limited` / `otp_handover_limited` (rate limiting), and
`route_not_found` for anything under `/api` that doesn't match a handler.

## Idempotency

`POST /api/deliveries` accepts an `Idempotency-Key` header (max 128 chars). A
repeated request with the same key and the same body returns the original
result (`200`, "Delivery already created"); the same key with a *different*
body is rejected with `409 idempotency_conflict`. Use this for any
create-delivery call that might be retried by a flaky mobile connection.

## Pagination

List endpoints that support it accept `limit`/`offset` query params, bounded
server-side (see `validate.pagination` — default 50, max 100 for most
listings). Check the individual handler for its specific default/max.
