# Environment Configuration

Every environment variable MOVO reads, what it defaults to, and how it's
validated. Parsing and validation live in
[`src/config/runtime.js`](src/config/runtime.js) (`loadRuntimeConfig()`); a
malformed value (e.g. a non-integer `PORT`) throws at startup rather than
silently falling back, so a bad config fails loudly instead of misbehaving in
production. Copy [`.env.example`](.env.example) to `.env` to get started.

## Core

| Variable | Default | Notes |
|---|---|---|
| `NODE_ENV` | `development` | `production` enforces the production-only checks below; `test` is what the test suite sets |
| `PORT` | `3000` | HTTP and Socket.IO listening port |
| `JWT_SECRET` | random per-boot in dev/test | **Required** in production — the process refuses to start without it. There's deliberately no hardcoded fallback secret: a value checked into the repo would let anyone with repo access forge tokens for any user if a deployment ever forgot to set this. Without it in dev/test you get a random secret that doesn't survive a restart. |
| `JWT_EXPIRY` | `7d` | Access-token lifetime |
| `DB_PATH` | `./movo.db` (`./movo-test.db` under `NODE_ENV=test`) | SQLite file path |
| `ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | Comma-separated CORS allowlist |
| `OTP_TEST_MODE` | `false` | Returns the OTP in the API response instead of sending SMS. **Refused at startup if `NODE_ENV=production`** — this is a test-only escape hatch, never a production toggle. |

## Transport and proxy

| Variable | Default | Notes |
|---|---|---|
| `TRUST_PROXY` | `true` in production, else `false` | Trust `X-Forwarded-*` headers from one reverse-proxy hop. Only enable behind a proxy you control. |
| `HTTPS_ONLY` | `true` in production, else `false` | Sends HSTS. Only enable once TLS is actually terminated in front of the app — HSTS on plain HTTP breaks browsers that remember it. |

## Observability

| Variable | Default | Notes |
|---|---|---|
| `LOG_LEVEL` | `info` in production, `debug` otherwise, `error` under test | One of `debug`, `info`, `warn`, `error`, `silent` |
| `METRICS_TOKEN` | unset | Bearer token required to read `GET /metrics` in production. Without it, `/metrics` is hidden (not just unauthenticated-but-served) in production. |
| `SHUTDOWN_GRACE_MS` | `10000` | Milliseconds allowed to drain in-flight connections on `SIGTERM` before forcing exit |

## Credential-attack controls

| Variable | Default | Notes |
|---|---|---|
| `MAX_LOGIN_ATTEMPTS` | `5` | Failed password attempts before the account locks |
| `LOCKOUT_MINUTES` | `15` | Lockout duration |
| `MAX_OTP_ATTEMPTS` | `5` | Wrong OTP submissions before lockout |
| `BCRYPT_ROUNDS` | `10` | Password hashing cost factor. Raise to `12` in production (see `README.md`'s pilot-readiness checklist). |

## Rate limiting

| Variable | Default | Notes |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` outside tests, `false` under `NODE_ENV=test` | Master switch. Production readiness (`GET /ready`) fails if this is `false` in production. |
| `RATE_LIMIT_WINDOW_MS` | `60000` | Fixed window length |
| `RATE_LIMIT_MAX` | `600` | Requests per window per caller, across all of `/api` |
| `RATE_LIMIT_AUTH_MAX` | `30` | Tighter bucket specifically for `/api/auth/*` |
| `RATE_LIMIT_WRITE_MAX` | `120` | Bucket for non-GET requests |

## Retention

| Variable | Default | Notes |
|---|---|---|
| `RIDER_LOCATION_RETENTION_DAYS` | `30` | How long location breadcrumbs (`rider_locations` table) are kept before pruning |
| `NOTIFICATION_RETENTION_DAYS` | `90` | How long *read* notifications are kept before pruning |

## Providers

| Variable | Default | Accepted values | Notes |
|---|---|---|---|
| `MAP_PROVIDER` | `sandbox` | `sandbox`, `osm` | Selects the map tile/geocoding backend. `osm` is what the Android apps and admin portal actually render with (OpenStreetMap, no proprietary map SDK). |
| `PAYMENT_PROVIDER` | `sandbox` | `sandbox`, `mtn-momo`, `airtel-money`, `mpesa` | Customer-facing payment collection. `sandbox` settles instantly for dev/pilot use; the live drivers are integration points, not yet wired to a real gateway. |
| `PAYOUT_PROVIDER` | `sandbox` | `sandbox`, `mtn-momo`, `airtel-money` | Rider payout disbursement — a **separate** integration from `PAYMENT_PROVIDER` because collecting from the customer and paying out the rider go through different rails. See `src/services/payouts.js`. |
| `SMS_PROVIDER` | `sandbox` | `sandbox`, `twilio` | OTP/notification delivery. See `src/services/messaging.js`. |

`GET /ready` fails if any provider is set to a value outside this list —
that's the config-validation half of readiness, independent of whether the
provider is actually reachable.

## Dispatch tuning

| Variable | Default | Notes |
|---|---|---|
| `DISPATCH_OFFER_TIMEOUT_SEC` | `30` | How long a rider has to accept an offered delivery before it's re-offered/expanded |
| `DISPATCH_RADIUS_KM` | `5` | Initial search radius for blind dispatch; expands automatically if no rider accepts (see `ARCHITECTURE.md`) |

There are additional dispatch/pricing knobs stored in the database
(`pricing_config` table, not environment variables) that ops can tune at
runtime through the admin portal's Pricing page — e.g.
`rider_search_expand_km`, `rider_accept_timeout_sec`,
`rider_location_interval_active_sec` / `rider_location_interval_idle_sec`
(the tiered location-tracking cadence handed down to the rider app — see
`ARCHITECTURE.md`), `platform_fee_percent`, and the ride-hailing equivalents
prefixed `ride_*`. These are seeded with defaults on first boot and are
deliberately *not* environment variables, because they're the kind of value
ops needs to change without a deploy.

## Feature flags

All default to enabled except `CHAT_ENABLED` (no backend exists yet for
chat — the flag exists so the client UI can be staged ahead of that feature
shipping). Parsed with the same `boolean()` helper as `TRUST_PROXY`/`HTTPS_ONLY`
(`1`/`true`/`yes`/`on`, case-insensitive, count as true).

| Variable | Default | Enforced where |
|---|---|---|
| `PAYMENTS_ENABLED` | `true` | `POST /api/rides` returns `503 payments_disabled` when off |
| `POD_PHOTO_ENABLED` | `true` | `POST /api/rider/deliveries/:id/proof` returns `403 feature_disabled` when off, before the upload is even processed |
| `SIGNATURE_ENABLED` | `true` | A signature submitted with `PUT /api/deliveries/:id/complete` is silently not persisted when off (the completion itself still succeeds) |
| `CHAT_ENABLED` | `false` | Not enforced anywhere yet — no chat backend exists. Advertised via `GET /api/config` only. |
| `SCHEDULED_DELIVERY_ENABLED` | `true` | Gates the `scheduled_for` field on delivery creation |

All five are readable at runtime, without authentication, via
`GET /api/config` — see [`API_INTEGRATION.md`](API_INTEGRATION.md#feature-flags).
This lets a client (including a signed-out one) adapt its UI without a
release. Flags are process-wide, not per-user or per-tenant; there is no A/B
or gradual-rollout mechanism here — that's what the canary deploy in
[`DEPLOYMENT.md`](DEPLOYMENT.md) is for.

## Validating your configuration

`node -e "console.log(require('./src/config/runtime').loadRuntimeConfig(process.env))"`
parses your current environment and prints the resolved config, without
starting the server — useful for checking a `.env` file before deploying.
Once the server is running, `GET /ready` reports the same
production-safety checks (auto-generated JWT secret, OTP test mode left on,
rate limiting disabled, an unrecognized provider value) as a JSON list of
`failures`.
