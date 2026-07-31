# MOVO Customer Sender/Receiver Realtime Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder customer APK with a native map-first sender/receiver app that selects one nearby rider and gives both phone-authorized parties simultaneous, secure live tracking.

**Architecture:** Extend the existing Express/SQLite server additively with canonical phone authorization, customer mobile endpoints, selected-rider offers, and authorized Socket.IO delivery rooms. Split the Android customer module into focused session/network/model/map/feature files; treat socket events as refresh signals and HTTP tracking as authoritative.

**Tech Stack:** Node.js, Express, SQLite/better-sqlite3, Socket.IO, Node test runner, Kotlin 1.9.25, Android SDK 35/minSdk 29, Jetpack Compose Material 3, coroutines, Fused Location Provider, Socket.IO Android client, osmdroid.

## Global Constraints

- Preserve all current web/rider contracts and the existing automatic dispatch path.
- Receiver authorization is server-side canonical destination-phone matching; unrelated customers get non-enumerating denial and no socket data.
- Selected offers target exactly one rider; decline/expiry returns `awaiting_rider_selection` and never broadcasts automatically.
- Sender and receiver see the same server-confirmed status and rider sample; socket events trigger HTTP refresh.
- Customer location is foreground-only; use `ACTION_DIAL` and real external driving-navigation intents.
- Never log/persist passwords or OTPs; keep JWT reporting redacted and errors structured/bounded.
- Debug API URL remains `http://192.168.0.173:3000`; do not alter rider app behavior except shared server compatibility.
- Work in the existing dirty checkout without committing, pushing, stashing, resetting, or rewriting history.

---

### Task 1: Phone-normalized customer authorization and mobile home

**Files:**
- Modify: `server.js`
- Create: `test/customer-mobile-api.test.js`

**Interfaces:**
- `normalizePhone(phone: unknown): string | null`
- `canAccessDelivery(user, delivery): boolean`
- `GET /api/mobile/v1/customer/home`
- `GET /api/mobile/v1/customer/deliveries?role=sent|received|all`

- [ ] Write an isolated-server test that registers sender, matching receiver, and unrelated customer; create one delivery addressed using a local Rwanda phone variant; assert matching receiver sees it and unrelated customer does not.
- [ ] Run `node --test test/customer-mobile-api.test.js`; expect failure because customer mobile endpoints are absent.
- [ ] Implement canonical `07XXXXXXXX`, `2507XXXXXXXX`, and `+2507XXXXXXXX` normalization; reject malformed numbers.
- [ ] Centralize delivery access and redact `pickup_otp`/`delivery_otp` according to participant role and lifecycle stage.
- [ ] Implement customer home and role-filtered list responses containing active/recent sent and received deliveries plus `serverTime`.
- [ ] Adapt delivery detail/track authorization to permit phone-matched receivers without weakening sender/rider/business/admin rules.
- [ ] Re-run focused test and `npm run test`; expect all pass.

### Task 2: Nearby eligible riders and exclusive selected-rider dispatch

**Files:**
- Modify: `server.js`
- Modify: `test/customer-mobile-api.test.js`

**Interfaces:**
- `GET /api/mobile/v1/customer/nearby-riders?lat=&lng=&radius_km=`
- `POST /api/deliveries` optional `preferred_rider_id`
- `PUT /api/deliveries/:id/select-rider`
- Delivery fields `preferred_rider_id`, `dispatch_mode`; selected state `awaiting_rider_selection`.

- [ ] Add failing tests proving nearby results exclude offline, pending, busy, stale, invalid-coordinate, and out-of-radius riders and are distance-sorted.
- [ ] Add failing test proving selected creation persists one offer only for the chosen eligible rider.
- [ ] Add failing decline/reselection test: selected rider declines, delivery becomes `awaiting_rider_selection`, sender selects another eligible rider, one new offer is persisted.
- [ ] Add idempotent SQLite migration for selected-dispatch columns and useful indexes.
- [ ] Implement capped-radius nearby query with operational rider fields only and a freshness threshold from config/default 120 seconds.
- [ ] Split dispatch into automatic and selected paths; selected path validates approved/online/idle/fresh/nearby rider and creates one offer.
- [ ] Implement sender-only replacement selection and selected decline/expiry state transition.
- [ ] Add actor-scoped delivery creation idempotency using `Idempotency-Key`; same body/key returns original delivery, changed body conflicts.
- [ ] Run focused tests and `npm run test`; expect all pass.

### Task 3: Authorized multi-party realtime rooms

**Files:**
- Modify: `server.js`
- Modify: `test/customer-mobile-api.test.js`

**Interfaces:**
- Socket events `authenticated`, `authentication_error`, `subscribe_delivery`, `delivery_subscribed`, `delivery_update`, `rider_location`.
- `emitDeliveryUpdate(deliveryId, payload)` broadcasts to `delivery:<id>`.

- [ ] Add failing Socket.IO integration tests with simultaneous sender/receiver/unrelated sockets.
- [ ] Prove sender and matched receiver can subscribe; unrelated customer cannot join or receive assignment/location.
- [ ] Prove only the assigned rider can publish finite in-range coordinates for that delivery.
- [ ] Replace single `userSockets[userId]` storage with multi-socket sets while preserving rider offer/notification delivery.
- [ ] Authenticate sockets explicitly, authorize room subscription through `canAccessDelivery`, and broadcast assignment/lifecycle/cancellation/location to the room.
- [ ] Validate rider role, approval/availability, assignment, delivery status, and coordinates before location persistence/broadcast.
- [ ] Ensure HTTP rider location updates also associate/broadcast active-delivery samples when applicable.
- [ ] Run focused and full Node tests.

### Task 4: Android customer foundation, API, session, models, and map

**Files:**
- Modify: `android/customer-app/build.gradle.kts`
- Modify: `android/customer-app/src/main/AndroidManifest.xml`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/model/CustomerModels.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/network/CustomerApi.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/session/CustomerSession.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/realtime/CustomerRealtime.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/location/CustomerLocation.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/map/CustomerMap.kt`
- Create: `test/customer-android-contract.test.js`

**Interfaces:**
- `CustomerApi.get/post/put`, structured `CustomerApiException`.
- `CustomerSession.token/save/clear` and stored non-secret profile metadata.
- `CustomerRealtime.subscribe(deliveryId)` and reconnect/update callbacks.
- `CustomerLocation.requestCurrent` foreground location callback.
- `CustomerMap` renders pickup, destination, nearby motorcycles, and assigned-rider marker.

- [ ] Write a failing source contract test for dependencies, Internet/location permissions, structured error parsing, Socket.IO auth/subscription, real OSM map, and motorcycle marker.
- [ ] Add proven rider-app dependencies: location, lifecycle/coroutines, Socket.IO, osmdroid, and Coil.
- [ ] Implement API/session/models with JSON envelope parsing, 15-second timeouts, JWT redaction, and bounded display errors.
- [ ] Implement Socket.IO auth, explicit subscription, reconnect callbacks, and disconnect lifecycle.
- [ ] Implement foreground-only fused location acquisition with permission-aware fallback.
- [ ] Implement osmdroid map with attribution, tap/long-press coordinate selection, fit-bounds, and separate marker icons/states.
- [ ] Run contract test and `./gradlew :customer-app:compileDebugKotlin`.

### Task 5: Authentication and top-level customer navigation

**Files:**
- Replace: `android/customer-app/src/main/kotlin/com/movo/customer/MainActivity.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/auth/AuthScreen.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/profile/ProfileScreen.kt`
- Modify: `test/customer-android-contract.test.js`

**Interfaces:**
- App destinations `Send`, `Receive`, `Activity`, `Profile`, `Tracking`.
- Auth states login, registration, verification, authenticated.

- [ ] Extend failing contract tests for phone-first sign-in, create-account, OTP verification, session restore through `/api/auth/me`, four main destinations, profile sign-out, and Back behavior.
- [ ] Implement MainActivity as state/navigation coordinator rather than feature body.
- [ ] Implement login, customer registration, OTP verification, optional email, safe loading/error states, and session restoration.
- [ ] Implement Material 3 MOVO theme, map-first shell, bottom navigation, and profile identity/connectivity/sign-out.
- [ ] Run contract test and Kotlin compilation.

### Task 6: Sender request, quote, nearby-rider scan, and exclusive request UI

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/send/SendScreen.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/send/RiderSelectionScreen.kt`
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/MainActivity.kt`
- Modify: `test/customer-android-contract.test.js`

**Interfaces:**
- `SendDraft` holds pickup/destination/contact/item/payment fields.
- Quote → nearby riders → selected confirmation → waiting/reselection state.

- [ ] Add failing UI contract assertions for pickup GPS/map adjustment, destination selection, sender/receiver contacts, parcel/document, quote price/distance/ETA, rider scan/list, exclusive confirmation, decline/expiry reselection, and duplicate-submit guard.
- [ ] Implement scroll-safe Send form and map selection while preserving entered state on validation/network errors.
- [ ] Call price endpoint only with finite coordinates; render server-calculated quote.
- [ ] Fetch nearby riders, display motorcycle markers/cards with name/rating/vehicle/distance/freshness, and select exactly one.
- [ ] Create delivery with preferred rider and a stable per-attempt idempotency key; suppress repeated taps while pending.
- [ ] Render waiting, conflict, declined, expired, assigned, no-rider, offline, and retry states; use replacement endpoint after decline/expiry.
- [ ] Run contract test and Kotlin compilation.

### Task 7: Receiver, activity, and simultaneous shared tracking UI

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/receive/ReceiveScreen.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/activity/ActivityScreen.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/tracking/TrackingScreen.kt`
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/MainActivity.kt`
- Modify: `test/customer-android-contract.test.js`

**Interfaces:**
- Home refresh classifies sent/received active/recent deliveries.
- Tracker refreshes `/api/deliveries/:id/track` after every socket event and reconnect.

- [ ] Add failing contract assertions for automatic phone-matched Receive list, sent/received Activity filters, shared tracking map, freshness indicator, rider/vehicle details, timeline, safe dialer, driving navigation, role-appropriate OTP, rating/support entry points, and process-relaunch restoration.
- [ ] Implement Receive and Activity cards with explicit sender/receiver labels and empty/error/loading states.
- [ ] Implement tracker with pickup/destination/rider markers, status timeline, same-order checks, stale/reconnecting banner, and HTTP-authoritative refresh.
- [ ] Connect socket while tracking, subscribe to delivery, refresh on location/status/notification/reconnect, and disconnect on screen/session disposal.
- [ ] Add safe `ACTION_DIAL` and Google Maps driving-direction intents without automatic calling.
- [ ] Expose sender-only cancel/rating/support controls and receiver-only destination OTP at the authorized stage.
- [ ] Run contract test and Kotlin compilation.

### Task 8: Full verification and physical-device E2E

**Files:**
- Modify only files required by defects reproduced during verification.

- [ ] Run `npm run test` and `git diff --check`.
- [ ] Run `cd android && ./gradlew :customer-app:testDebugUnitTest :customer-app:assembleDebug :rider-app:testDebugUnitTest :rider-app:assembleDebug`.
- [ ] Restart PM2 backend and verify `/health` plus new customer mobile routes.
- [ ] Install both APKs on exact authorized ADB devices; use deterministic `am start`, never Monkey.
- [ ] Register/sign in distinct sender and receiver fixtures and preserve credentials outside reports.
- [ ] Verify sender GPS, nearby Test Rider, exclusive offer, rider request details, accept, and both customer authorization paths.
- [ ] Verify matching order/status/rider coordinates for sender and receiver using Android UI plus backend state.
- [ ] Advance the controlled rider lifecycle and confirm simultaneous customer status refresh and staged OTP visibility.
- [ ] Verify safe dialer/maps, screenshots, no clipping/wrapping, map tiles/markers, force-stop restore, no `FATAL EXCEPTION`, and bounded PM2 logs.
- [ ] Cancel/complete controlled fixtures and restore temporary timeout/config values.

## Requirement-to-task traceability

- Authentication/profile: Tasks 4–5.
- Sender GPS/request/quote: Task 6.
- Nearby rider scan and exclusive dispatch: Tasks 2 and 6.
- Phone-authorized receiver: Tasks 1 and 7.
- Simultaneous realtime tracking/security: Tasks 3 and 7.
- Maps, rider marker, call/navigation: Tasks 4, 6, 7.
- Idempotency/resilience: Tasks 2, 4, 6, 7.
- Automated and physical evidence: Task 8.

## Plan self-review

- Spec coverage: every included journey, endpoint, authorization boundary, failure state, and physical-device gate maps to a task above.
- Scope: production push/payment providers, in-app chat, iOS, and receiver delegation remain excluded.
- Type consistency: `preferred_rider_id`, `awaiting_rider_selection`, `subscribe_delivery`, and `/api/mobile/v1/customer/*` names are identical across backend and Android tasks.
- Execution policy: no task requires a commit or push; verification evidence replaces commit checkpoints in this dirty checkout.
