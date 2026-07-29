# MOVO Native Android Customer and Rider Apps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver two polished Android 10+ apps—MOVO Customer and MOVO Rider—plus secure mobile API contracts on the existing Express/SQLite backend.

**Architecture:** A Gradle composite workspace contains a shared Android core/design module and independent Compose applications. The Express monolith retains delivery rules and gains mobile-specific envelope, idempotency, cursor, home, push-token, and real-time contracts without breaking existing web routes.

**Tech Stack:** Kotlin, Jetpack Compose/Material 3, Retrofit/OkHttp, Room, WorkManager, CameraX, Fused Location Provider, Socket.IO, OpenStreetMap-compatible map SDK, Node.js, Express, SQLite/better-sqlite3, Socket.IO, Node test runner.

## Global Constraints

- Two separate packages: `com.movo.customer` and `com.movo.rider`; minSdk 29, compileSdk/targetSdk 35.
- Debug builds use `http://10.0.2.2:3000`; release builds require an HTTPS `MOVO_API_BASE_URL` and reject all cleartext URLs.
- Customer devices do not background-track location; rider location runs only in a visible foreground service while online or on an active delivery.
- Never persist bearer tokens, OTPs, passwords, proof, or identity media in plaintext or logs.
- Server authorization and state transitions are authoritative; clients render confirmed state and never calculate price, earnings, or payouts.
- Preserve web API behavior and the existing `npm run test` suite.

---

## File structure

- `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`: shared Android build and dependency versions.
- `android/core/*`: API models/client, secure session storage, design system, networking, map abstractions, test fakes.
- `android/customer-app/*`: customer Compose app, navigation, request/quote/tracking/history/support flows.
- `android/rider-app/*`: rider Compose app, approval/availability/offers/lifecycle/earnings, foreground location, offline queue.
- `server.js`: additive schema/middleware/mobile routes/events; existing web handlers remain compatible.
- `test/mobile-api.test.js`: isolated Express integration coverage for mobile contracts.
- `docs/android-mobile-runbook.md`: build, emulator, API URL, permissions, and APK verification instructions.

### Task 1: Establish the Android multi-app workspace

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/core/build.gradle.kts`
- Create: `android/customer-app/build.gradle.kts`, `android/rider-app/build.gradle.kts`
- Create: `android/customer-app/src/main/AndroidManifest.xml`, `android/rider-app/src/main/AndroidManifest.xml`
- Test: `android/core/src/test/kotlin/com/movo/core/BuildConfigPolicyTest.kt`

**Interfaces:**
- Produces `MovoBuildConfig.requireReleaseBaseUrl(value: String, debuggable: Boolean): String`.
- Produces application IDs `com.movo.customer` and `com.movo.rider`.

- [ ] Write a failing JVM test that accepts `http://10.0.2.2:3000` only for debug and rejects it for release.
- [ ] Run `./gradlew :core:testDebugUnitTest`; expect a missing `MovoBuildConfig` failure.
- [ ] Add Gradle modules, Compose/Kotlin plugins, API 29/35 SDK configuration, BuildConfig fields, and `MovoBuildConfig`:

```kotlin
object MovoBuildConfig {
  fun requireReleaseBaseUrl(value: String, debuggable: Boolean): String {
    require(value.isNotBlank()) { "MOVO_API_BASE_URL is required" }
    require(debuggable || value.startsWith("https://")) { "Release API URL must use HTTPS" }
    return value.trimEnd('/')
  }
}
```

- [ ] Re-run the JVM test; expect PASS.
- [ ] Run `./gradlew :customer-app:assembleDebug :rider-app:assembleDebug`; expect two debug APKs.

### Task 2: Create shared secure networking, session, and API envelope

**Files:**
- Create: `android/core/src/main/kotlin/com/movo/core/network/ApiEnvelope.kt`
- Create: `android/core/src/main/kotlin/com/movo/core/network/MovoApi.kt`
- Create: `android/core/src/main/kotlin/com/movo/core/session/SecureSessionStore.kt`
- Create: `android/core/src/main/kotlin/com/movo/core/network/IdempotencyKeyProvider.kt`
- Test: `android/core/src/test/kotlin/com/movo/core/network/ApiEnvelopeTest.kt`

**Interfaces:**
- `ApiEnvelope<T>(success: Boolean, data: T?, error: ApiError?, requestId: String?)`.
- `SessionStore.read(): Session?`, `save(session: Session)`, `clear()`.
- `MovoApi` adds `Authorization: Bearer <token>` only when a session exists and `Idempotency-Key` for mutations.

- [ ] Write failing envelope tests for success payloads, structured failures, and blank request IDs.
- [ ] Implement Retrofit serialization models and an OkHttp interceptor that adds headers but redacts them from logs.
- [ ] Implement encrypted preference-backed session storage using AndroidX Security and an in-memory fake for tests.
- [ ] Run `./gradlew :core:testDebugUnitTest`; expect PASS.

### Task 3: Add mobile API foundations, schema, and contract tests

**Files:**
- Modify: `server.js:60-325`, `server.js:425-426`, `server.js:500-521`
- Create: `test/mobile-api.test.js`

**Interfaces:**
- Every API response is extended—not replaced—with optional `requestId`.
- `Idempotency-Key` protects mobile mutating requests through table `idempotency_records(key, actor_id, request_hash, status_code, response_json, expires_at)`.
- `mobile_push_tokens(id, user_id, token, platform, updated_at)` stores only token metadata.

- [ ] Write failing Node tests proving the same authenticated request/key returns the original 201 response and a changed body/key pair returns 409.
- [ ] Add schema tables/indexes and request middleware that assigns `req.requestId`, hashes JSON request bodies, and replays a stored idempotent result only for the same actor/hash.
- [ ] Change `resOK`/`resErr` to include `requestId` and structured `{ code, message, fields? }` errors while retaining `success` and existing `data` shapes.
- [ ] Run `node --test test/mobile-api.test.js`; expect PASS.
- [ ] Run `npm run test`; expect all prior tests plus mobile tests PASS.

### Task 4: Implement mobile home, cursor, push-token, and realtime contracts

**Files:**
- Modify: `server.js:618-750`, `server.js:867-905`, Socket.IO handlers near `server.js:1370`
- Modify: `test/mobile-api.test.js`

**Interfaces:**
- `GET /api/mobile/v1/customer/home` returns `{ activeDelivery, addresses, unreadNotificationCount, serverTime }`.
- `GET /api/mobile/v1/customer/deliveries?cursor=&limit=` returns `{ items, nextCursor }`.
- `GET /api/mobile/v1/rider/home` returns `{ approvalStatus, onlineStatus, activeDelivery, earningsSummary, serverTime }`.
- `GET /api/mobile/v1/rider/offers` returns only valid authenticated rider offers.
- `POST /api/mobile/v1/push-tokens`, `DELETE /api/mobile/v1/push-tokens/:id` enforce caller ownership.

- [ ] Write failing tests for customer/rider cross-role denial, cursor limit validation, and push-token owner-only deletion.
- [ ] Implement ownership-filtered queries with `(created_at, id)` cursor ordering, max 50 limit, and mobile endpoints.
- [ ] Emit `mobile.delivery.updated`, `mobile.delivery.location`, and `mobile.notification.created` with `eventId`, `occurredAt`, and minimized authorized payloads alongside legacy web events.
- [ ] Run `node --test test/mobile-api.test.js && npm run test`; expect PASS.

### Task 5: Build the shared MOVO Compose design system and map shell

**Files:**
- Create: `android/core/src/main/kotlin/com/movo/core/design/MovoTheme.kt`
- Create: `android/core/src/main/kotlin/com/movo/core/design/MovoComponents.kt`
- Create: `android/core/src/main/kotlin/com/movo/core/map/MovoMap.kt`
- Test: `android/core/src/androidTest/kotlin/com/movo/core/design/MovoComponentsTest.kt`

**Interfaces:**
- `MovoTheme { content }`, `MovoPrimaryButton`, `DeliveryStatusCard`, `MapBottomSheet`.
- `MovoMap(state: MapState, markers: List<MapMarker>, onMapTap: (LatLng) -> Unit)`.

- [ ] Write Compose tests that assert the primary CTA has a content description, 48dp minimum semantics target, and disabled state explanation.
- [ ] Implement green/neutral/semantic color tokens, typography, status chips, price/ETA cards, and rounded sheet components.
- [ ] Implement OSM-compatible map wrapper with attribution text, tile error state, and a test fake map renderer.
- [ ] Run `./gradlew :core:connectedDebugAndroidTest`; expect PASS on an emulator.

### Task 6: Implement customer authentication and delivery request/quote flow

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/auth/AuthFeature.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/request/RequestFeature.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/navigation/CustomerNavGraph.kt`
- Test: `android/customer-app/src/test/kotlin/com/movo/customer/request/RequestReducerTest.kt`

**Interfaces:**
- `CustomerRepository.quote(request: QuoteRequest): ApiResult<Quote>`.
- `CustomerRepository.createDelivery(request: CreateDeliveryRequest, idempotencyKey: String): ApiResult<Delivery>`.
- Request states: `Editing`, `Quoting`, `QuoteReady`, `Creating`, `Created`, `Error`.

- [ ] Write reducer tests proving duplicate confirmation in `Creating` is ignored and quote expiry returns to `Editing`.
- [ ] Implement phone login/OTP/session restoration, map address selection, parcel/document request form, quote sheet, sandbox payment label, and idempotent create call.
- [ ] Run `./gradlew :customer-app:testDebugUnitTest`; expect PASS.

### Task 7: Implement customer active tracking, history, rating, and support

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/tracking/TrackingFeature.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/history/HistoryFeature.kt`
- Create: `android/customer-app/src/androidTest/kotlin/com/movo/customer/CustomerJourneyTest.kt`

**Interfaces:**
- `TrackingRepository.observe(deliveryId: String): Flow<TrackingState>` merges HTTP refresh and authorized socket events.
- `CustomerRepository.listDeliveries(cursor: String?): ApiResult<Page<DeliverySummary>>`.

- [ ] Write an instrumented journey test: seeded signed-in user opens active delivery, receives a state update, views proof/receipt affordance, and opens rating/support.
- [ ] Implement map-led tracker/bottom sheet, reconnect refresh, paged history, delivery detail, rating, ticket form, and rebook that starts a fresh quote.
- [ ] Run `./gradlew :customer-app:connectedDebugAndroidTest`; expect PASS.

### Task 8: Implement rider approval, availability, offers, and lifecycle screens

**Files:**
- Create: `android/rider-app/src/main/kotlin/com/movo/rider/home/RiderHomeFeature.kt`
- Create: `android/rider-app/src/main/kotlin/com/movo/rider/delivery/RiderDeliveryFeature.kt`
- Create: `android/rider-app/src/main/kotlin/com/movo/rider/navigation/RiderNavGraph.kt`
- Test: `android/rider-app/src/test/kotlin/com/movo/rider/delivery/RiderLifecycleReducerTest.kt`

**Interfaces:**
- `RiderHome(approvalStatus, onlineStatus, activeDelivery, offer)` is the only route deciding whether online toggle/offers render.
- `nextAction(status)` maps `assigned→going-pickup→arrived-pickup→verify-pickup→in-transit→arrived-dest→complete`.
- `RiderRepository.transition(deliveryId, action, payload, idempotencyKey)`.

- [ ] Write failing reducer tests that suppress availability for pending/suspended riders and expose exactly one next valid action per state.
- [ ] Implement rider session/approval screen, online toggle, offer countdown, accept race messaging, active task sheet, OTP verification, earnings and performance views.
- [ ] Run `./gradlew :rider-app:testDebugUnitTest`; expect PASS.

### Task 9: Add rider secure proof capture, foreground location, and offline queue

**Files:**
- Create: `android/rider-app/src/main/kotlin/com/movo/rider/location/RiderLocationService.kt`
- Create: `android/rider-app/src/main/kotlin/com/movo/rider/sync/MutationQueue.kt`
- Create: `android/rider-app/src/main/kotlin/com/movo/rider/proof/ProofCapture.kt`
- Modify: `android/rider-app/src/main/AndroidManifest.xml`
- Test: `android/rider-app/src/test/kotlin/com/movo/rider/sync/MutationQueueTest.kt`

**Interfaces:**
- `RiderLocationService` starts only when approved and `online || activeDelivery != null`; it stops on logout/offline/permission loss.
- `QueuedMutation(id, endpoint, body, idempotencyKey, createdAt, attempts)` is Room-backed.
- `MutationSyncWorker` retries network failures with exponential WorkManager backoff.

- [ ] Write queue tests that retain failed transition submissions, preserve their idempotency key, and delete only after a confirmed response.
- [ ] Implement permission education, foreground notification channel/service, throttled authorized location updates, CameraX capture to private cache, authenticated multipart upload, Room queue, and WorkManager retry state UI.
- [ ] Run `./gradlew :rider-app:testDebugUnitTest :rider-app:connectedDebugAndroidTest`; expect PASS.

### Task 10: Verify API integration, package APKs, and document operation

**Files:**
- Create: `docs/android-mobile-runbook.md`
- Modify: `README.md:114-129`
- Create: `android/scripts/verify-apks.sh`

**Interfaces:**
- `android/scripts/verify-apks.sh` runs JVM tests, both debug assemblies, checks package/minSdk/targetSdk with `aapt2`, and reports APK paths/sizes.

- [ ] Write the verification script so `trap` deletes temporary files and it fails on a missing APK, minSdk other than 29, targetSdk other than 35, or APK under 1 MiB.
- [ ] Document local Express startup, emulator address, release `MOVO_API_BASE_URL`, runtime permissions, foreground-location behavior, and APK install commands.
- [ ] Run `npm run test && npm run test:syntax && cd android && ./gradlew :customer-app:assembleDebug :rider-app:assembleDebug && ./scripts/verify-apks.sh`.
- [ ] Install both debug APKs on an emulator, exercise customer quote/create and rider approval/offer/lifecycle against an isolated test server, and record only non-secret evidence in the runbook.

## Plan self-review

- Spec coverage: Tasks 1–2 cover Android build/security foundations; 3–4 cover the mobile backend contract; 5 covers visual/maps; 6–7 cover customer MVP; 8–9 cover rider MVP/location/offline/proof; 10 covers verification/docs.
- No placeholder scan terms are present in task requirements; exact paths and named interfaces are defined before their consumers.
- Type consistency: both applications use `ApiEnvelope`, `ApiResult`, session storage, idempotency keys, and server-confirmed delivery states; customer and rider packages remain isolated.
