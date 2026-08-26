# Troubleshooting

Common problems and how to actually diagnose them, organized by where you'd
hit them.

## Server won't start

**"JWT_SECRET must be set in production"** — `NODE_ENV=production` with no
`JWT_SECRET` set. This is a deliberate refusal, not a bug (see
[`ENVIRONMENT.md`](ENVIRONMENT.md)) — set `JWT_SECRET` to a long random
value, don't work around it.

**"OTP_TEST_MODE must not be enabled in production"** — same idea:
`OTP_TEST_MODE=true` with `NODE_ENV=production` is refused at boot. Unset it
or set it to `false` in production; it exists purely for tests.

**`EADDRINUSE`** — something is already listening on `PORT`. Check what:
`lsof -i :3000` (or your configured port), then either stop it or set a
different `PORT`.

**A config value throws at startup** (e.g. `"PORT must be a positive
integer"`) — `src/config/runtime.js` validates every environment variable
eagerly rather than letting a bad value misbehave later. Fix the value named
in the error. You can check your whole `.env` against the parser without
starting the server:

```bash
node -e "console.log(require('./src/config/runtime').loadRuntimeConfig(process.env))"
```

## "Server looks up but doesn't work right"

Check `GET /health` (is the process even alive) vs. `GET /ready` (is it fit
to serve traffic) separately — they answer different questions. `/ready`'s
`failures` array tells you exactly what's wrong: an auto-generated JWT
secret, OTP test mode left on, rate limiting disabled, or an unrecognized
`*_PROVIDER` value. Fix what it names, then re-check.

## Database issues

**`SQLITE_BUSY` / "database is locked"** — `server.js` already configures
WAL mode and a busy_timeout, so a locked-database error under normal load
usually means either the canary deploy's two processes (`movo` and
`movo-canary`, see [`DEPLOYMENT.md`](DEPLOYMENT.md)) are both hammering the
same table in a way the busy_timeout can't absorb, or some *other* process
(a manual `sqlite3 movo.db` shell left open, a backup script) is holding a
write lock. Check for stray processes with the DB file open
(`lsof movo.db`) before assuming it's a server bug.

**Tests seem to interfere with each other / a table has unexpected rows** —
every Node integration test file uses its own temp DB path
(`os.tmpdir()/movo-<suite>-<pid>-<timestamp>.db`) specifically so test files
can run concurrently without sharing state. If you're seeing cross-test
pollution, check whether a new test reused an existing file's `dbPath`
pattern instead of generating its own, or whether `test.after` actually ran
(a test that throws before `test.after` registers can leave a stray temp DB
and a stray server process behind — check for orphaned `node server.js`
processes if a test run was interrupted).

**Lost/corrupted `movo.db`** — there's no automated backup yet (see
[`DEPLOYMENT.md`](DEPLOYMENT.md#database-backup)); this is a known gap, not
something recoverable via a documented procedure. If you're setting up a new
deployment, put a snapshot job in place before you need it.

## Auth and OTP

**Registered but never got an OTP** — check `OTP_TEST_MODE`: if `true`, the
OTP is returned directly in the register/verify response body, not sent by
SMS at all (this is intentional for dev/test). If `false` and you expected
real SMS, check `SMS_PROVIDER` — `sandbox` never sends anything either; you
need a real provider (`twilio`) configured to receive SMS.

**"Account locked" / can't log in even with the right password** —
`MAX_LOGIN_ATTEMPTS` failed attempts locks the account for `LOCKOUT_MINUTES`.
This is deliberate credential-stuffing protection, not a bug; wait it out or
have an admin intervene. Same shape for `MAX_OTP_ATTEMPTS` on OTP
verification.

**A request you're sure should work returns 401/403** — check three things
in order: (1) is the `Authorization: Bearer <token>` header actually present
and not expired (`JWT_EXPIRY`, default `7d`)? (2) does the route have a
`roleAuth('customer', ...)` restricting it to roles you're not in? (3) is
there a `requireFeature(...)` gate — check `GET /api/config` for whether the
relevant flag is off (see [`ENVIRONMENT.md`](ENVIRONMENT.md#feature-flags)).

## Rate limiting during testing/development

Getting `429`/`auth_rate_limited` unexpectedly while manually testing
against a server you started yourself? Set `RATE_LIMIT_ENABLED=false` for
that session — it's already `false` under `NODE_ENV=test` for exactly this
reason, but a manually-started dev server defaults to enabled.

## Delivery/dispatch behaves unexpectedly

**"No rider found" immediately, even though riders are online nearby** —
`eligibleNearbyRiders()` requires a location update fresher than
`rider_location_freshness_sec` (a `pricing_config` value, default 120s). A
rider whose app hasn't sent a location ping recently (backgrounded,
force-stopped, or a location-service bug) is invisible to dispatch even
though their `online_status` says online. Check
`riders.last_location_update` for the rider in question.

**A client is sending `preferred_rider_id` and it's being ignored** — this
is intentional. Dispatch is blind and zone-based; the delivery-creation
endpoint no longer honors a client-chosen rider (see
[`ARCHITECTURE.md`](ARCHITECTURE.md)). If you're integrating a client that
still sends this field, it's harmless but has no effect — remove it.

**A delivery is stuck in `searching`** — either no eligible rider was found
within the max expanded radius (check the `no_rider` notification sent to
the customer, and the delivery's status will move to `failed`), or dispatch
genuinely is still retrying — check the server logs for the
`dispatchDelivery` radius-expansion cycle for that delivery ID. An admin can
force-assign via `PUT /api/admin/deliveries/:id/reassign` regardless of
dispatch state.

## Android app can't reach the server

**Customer/rider app shows a network error against a local dev server** —
check `API_BASE_URL` in the relevant module's `build.gradle.kts`. A physical
device or emulator can't reach `localhost` on your dev machine; use your
machine's LAN IP (or `10.0.2.2` for the standard Android emulator, which
maps to the host's `localhost`). Debug builds allow cleartext HTTP to make
this easier; release builds require HTTPS and will refuse a plain-HTTP
`API_BASE_URL` (see [`DEPLOYMENT.md`](DEPLOYMENT.md)'s pre-deployment
checklist).

**Rider location stops updating / rider goes stale on the customer's live
map** — check whether the device's battery optimization is throttling the
foreground location service; the rider app sends a heartbeat specifically to
survive OS throttling of a backgrounded/stationary rider (see
`RiderLocationService.kt` and the regression test in
`test/rider-offer-ui.test.js`). If the heartbeat itself stopped, check
`adb logcat` for the service being killed outright rather than throttled —
that's a battery-optimization exemption issue on the device, not a server
issue.

## Test suite failures

**A Node test times out waiting for "Server running"** — the spawned
`server.js` process crashed or hung during startup; the test harness pipes
`stderr` straight through (see the `startServer` helper in any test file),
so the actual startup error should be visible in the test output above the
timeout. Common cause: a leftover process already bound to that test file's
port from a previous interrupted run — check `lsof -i :<port>` for the range
that test file uses (each file picks a different base port specifically to
avoid this, but a truly interrupted run can still leak a process).

**A Kotlin contract test fails after a refactor** (`customer-android-contract.test.js`,
`rider-offer-ui.test.js`) — these assert on source-code *patterns*, not
behavior, by design (see [`TESTING.md`](TESTING.md)). If you renamed a
function or restructured a file, update the regex to match the new code —
that's expected maintenance, not a sign you broke something. If you're not
sure whether a specific pattern is safe to remove, check for a "Regression:"
comment near it first; several exist specifically to prevent a previously
shipped bug from coming back.

**Gradle build fails with "could not resolve dependency"** — the Android
build needs network access to Google's/Maven Central's repositories the
first time (`pluginManagement`/`dependencyResolutionManagement` in
`android/settings.gradle.kts`); a fully offline environment needs a
pre-populated Gradle cache.

## Still stuck

Check [`ARCHITECTURE.md`](ARCHITECTURE.md) for *why* a piece of behavior
exists before assuming it's a bug — several things that look like bugs at
first glance (no displacement filter without a heartbeat, blind dispatch
with no rider list, financial fields stripped server-side rather than
client-side) are deliberate, documented decisions with a regression test
behind them.
