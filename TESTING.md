# Testing

MOVO has two independent test suites — Node (server + portal contracts) and
Kotlin (both Android apps + the shared `:design` module) — plus a Kotlin
source-pattern "contract test" convention worth understanding on its own.
There is no end-to-end test harness driving a real device against a real
server; the README notes that both apps have been verified by hand on
physical hardware, which is a manual step, not something CI runs.

## Node test suite

```bash
npm test                                          # everything, test/*.test.js
NODE_ENV=test DB_PATH=/tmp/movo-test.db npm test  # explicit, isolated DB
npm run test:syntax                               # node --check on server.js and public/portal-auth.js
node --test test/financial-and-zoning.test.js     # a single file
node --test test/financial-and-zoning.test.js --test-name-pattern="payout"  # a single test by name
```

Uses Node's built-in test runner (`node:test`) — no Jest/Mocha dependency.
CI runs `npm run test:syntax` then `npm test`; both must pass before anything
deploys (see [`DEPLOYMENT.md`](DEPLOYMENT.md)).

### How the integration tests work

Most files under `test/` spin up a **real server process** (`child_process.spawn('server.js', ...)`)
against a throwaway SQLite file in the OS temp directory, then exercise it
over real HTTP with `fetch`, exactly like a client would:

```js
async function startServer(env, listenPort) {
  const child = spawn(process.execPath, ['server.js'], {
    cwd: root,
    env: { ...process.env, NODE_ENV: 'test', PORT: String(listenPort), ...env },
    ...
  });
  // waits for the "Server running" stdout line, then resolves
}
```

This means these are integration tests, not mocked unit tests — they catch
real wiring bugs (a missing middleware, a route registered in the wrong
order) that a mocked request object never would. The cost is that each file
pays a real process-startup cost; that's why each file picks its own random
port and a private DB path, so files can run concurrently without colliding.

`test.before`/`test.after` in each file start the server once and tear it
down once for that file, not per-test — write new tests as additional
`test(...)` calls inside an existing file where the setup already fits,
rather than starting a new server per test.

`OTP_TEST_MODE=true` and `RATE_LIMIT_ENABLED=false` are standard in test env
blocks so tests aren't blocked by SMS delivery or rate limits; a few tests
specifically re-enable rate limiting to test lockout behavior itself.

### What each file covers

| File | Covers |
|---|---|
| `registration.test.js` | Registration/OTP/login for each role, password policy |
| `financial-and-zoning.test.js` | Zone resolution correctness, payout obligations/idempotency, role-scoped financial fields on delivery/POD/receipt, tiered location-tracking config handoff |
| `production-platform.test.js` | Security headers, readiness/metrics, POD/receipts, rider availability rules, incidents, KPIs/audit, bulk uploads, scheduled deliveries, cancellation fees, reassignment, rate limiting/lockout |
| `ride-hailing.test.js` | The ride-hailing lifecycle end to end, payment settlement timing, cancellation fees, rider/driver double-booking prevention |
| `customer-mobile-api.test.js` | Customer-facing mobile API contract, including blind dispatch behavior (a client-sent `preferred_rider_id` is ignored) |
| `customer-android-contract.test.js` | Kotlin source-pattern checks for the customer app (see below) |
| `rider-offer-ui.test.js` | Kotlin source-pattern checks for the rider app, including the tiered location-tracking regression test |
| `admin-portal.test.js` / `admin-login.test.js` | Admin portal API coverage and HTML/JS contract checks (map clustering, debounced search, wired endpoints) |
| `portal-login.test.js` | Registration vs. login page separation for customer/rider/business portals |
| `feature-flags.test.js` | `GET /api/config` shape, and that each flag actually gates behavior, not just parses |
| `analytics.test.js` | Analytics event catalog validation and ingestion |
| `runtime-config.test.js` | `src/config/runtime.js` unit tests — no server process, just `loadRuntimeConfig`/`evaluateReadiness` |

### Writing a new Node test

Copy the `request`/`startServer`/`register`/`adminToken` helper pattern from
an existing file in the same problem area (e.g. `financial-and-zoning.test.js`
for anything delivery-financial, `ride-hailing.test.js` for anything
ride-related) rather than inventing a new harness — nearly every file
duplicates the same ~40 lines deliberately, so each file stays runnable in
isolation with `node --test test/that-file.test.js` and there's no shared
mutable test-server state across files.

Prefer asserting on **behavior observable through the API** (status codes,
response shapes, database rows) over asserting on internal function names —
except in the Kotlin contract tests below, where asserting on source patterns
is the point.

## Kotlin contract tests (`customer-android-contract.test.js`, `rider-offer-ui.test.js`)

These are Node tests, but instead of hitting a server they `fs.readFileSync`
Kotlin source files and regex-match against them:

```js
function source(relative, patterns) {
  const body = read(relative);
  for (const pattern of patterns) assert.match(body, pattern, `${relative} must match ${pattern}`);
  return body;
}

source('RiderLocationService.kt', [
  /startHeartbeat/, /lastSent/, /\/api\/rider\/location/,
  /setMinUpdateDistanceMeters/, ...
]);
```

This exists because the Node suite is what CI actually gates on (a full
Android Gradle build is slow and needs the Android SDK, which CI doesn't
have set up) — these checks give fast, CI-enforced coverage that specific
Kotlin wiring exists and specific regressions don't come back, without
compiling anything. They are intentionally narrow: they prove a pattern is
*present* (or, via `assert.doesNotMatch`, *absent*), not that the Compose UI
actually renders correctly.

Two things worth knowing before you touch a file these tests check:

- **A pattern with a "Regression:" comment above it exists because something
  broke in production before.** `rider-offer-ui.test.js`'s
  `setMinUpdateDistanceMeters`/`force = true` checks exist because a naive
  displacement filter once made a stationary rider go stale and disappear
  from customer discovery — read the comment before loosening the assertion.
- **When you rename or restructure the Kotlin it's checking, update the
  regex, don't delete the test.** The test is documenting an invariant, not
  just checking today's spelling of a function name.

## Kotlin unit tests

```bash
cd android
./gradlew testDebugUnitTest              # all three modules
./gradlew :rider-app:testDebugUnitTest   # one module
./gradlew :design:compileDebugKotlin :rider-app:compileDebugKotlin   # compile-only, faster than a full test run
```

These are plain JVM unit tests (no Robolectric/instrumentation, no emulator)
against pure logic, each isolating the thing under test from real networking
via a small seam rather than mocking the HTTP client directly:

- `RiderControllerTest.kt` drives `RiderController` against a fake
  `RiderGateway` (see the `interface RiderGateway { ... }` in
  `home/RiderController.kt`).
- `RiderDiscoveryControllerTest.kt` drives `RiderDiscoveryController` against
  a plain lambda (`NearbyRiderSource`) that returns a rider *count* — this
  tests the blind-dispatch scan/coalesce/race-safety logic without a rider
  list, matching the "no identified riders reach the customer app"
  invariant described in `ARCHITECTURE.md`.
- `SendDiscoveryStateTest.kt` tests the send-flow state machine directly.
- `DeliveryStatusTest.kt` in `:design` tests the shared delivery-status
  vocabulary.

Follow whichever seam pattern the file you're extending already uses (a fake
interface implementation, or a plain function/lambda parameter) rather than
mocking `RiderApi`/`CustomerApi` directly.

## What's not covered

- No instrumented/UI (Espresso/Compose-test) tests — Compose screens are
  exercised manually on physical hardware, per the README.
- No load/performance testing.
- No automated cross-role end-to-end journey runner (the closest thing is
  `production-platform.test.js`'s single-file integration tests, which do
  exercise multi-step flows like a full delivery lifecycle, but each within
  one test file, not across the whole system).

See [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) if a test is failing and you're
not sure why, and [`ARCHITECTURE.md`](ARCHITECTURE.md) for the invariants
these tests are protecting.
