# MOVO Customer Sender/Receiver Realtime Android App — Design Specification

Date: 2026-07-29
Status: Approved design; pending written-spec review

## 1. Goal

Replace the current customer Android placeholder with a complete native customer application in which one phone-first customer account can act as either:

- a **sender**, who chooses pickup and destination locations, scans eligible nearby riders, selects one rider, and sends that rider an exclusive delivery request; or
- a **receiver**, who automatically sees deliveries addressed to the phone number registered on the account.

The sender and receiver must see the same assigned rider, delivery state, and fresh rider location at the same time. The Express/SQLite backend remains authoritative for identity, authorization, dispatch, delivery state, price, and tracking.

This specification refines and supersedes the customer-flow portions of `2026-07-29-movo-native-android-apps-design.md`. Rider behavior remains governed by the existing rider implementation and its server contracts.

## 2. Approved product model

### 2.1 One account, two workspaces

The customer app has one authenticated session and a bottom-level product surface with:

1. **Send** — create a delivery and select a nearby rider.
2. **Receive** — deliveries where the normalized destination phone matches the authenticated customer's normalized phone.
3. **Activity** — sent and received delivery history.
4. **Profile** — identity, session, saved defaults, and sign-out.

A user does not permanently choose a sender or receiver role. The role is contextual per delivery.

### 2.2 Receiver access

Receiver access is automatic and server-authorized:

- The backend normalizes the authenticated customer's phone and each delivery `dest_phone` into one canonical Rwanda-compatible representation.
- A customer is an authorized receiver only when those normalized phone values match.
- The client cannot grant itself receiver access by passing a role, phone, delivery ID, or local flag.
- Unrelated customers receive a non-enumerating not-found response and no delivery socket events.

No share code is required for the approved first version.

### 2.3 Selected-rider dispatch

The sender chooses exactly one eligible rider:

- Only the selected rider receives a persisted offer and realtime notification.
- If the rider accepts, the delivery becomes assigned atomically.
- If the rider declines or the offer expires, the delivery becomes `awaiting_rider_selection` and returns to the sender's rider-selection UI.
- The server does not automatically broadcast that request to other riders.
- The sender may select another currently eligible rider without recreating the delivery or recalculating historical delivery identity.

## 3. Customer journeys

### 3.1 Authentication

- Phone-first registration with full name, optional email, password, and OTP verification.
- Phone/password login, with the existing OTP fallback contract where applicable.
- JWT session restoration followed by `/api/auth/me` validation.
- Structured expired-session handling and explicit sign-out.
- Passwords and OTPs are never persisted or logged.

### 3.2 Sender flow

1. Open Send on a real OSM map.
2. Request foreground location permission with an explanation.
3. Use the current GPS coordinate as the initial pickup point; permit map adjustment and manual address confirmation.
4. Select a destination on the map and confirm its human-readable address.
5. Enter pickup contact, recipient name and phone, parcel/document type, item details, instructions, and payment method.
6. Request a server quote and display distance, estimated time, customer price, and payment method.
7. Request eligible nearby riders for the confirmed pickup coordinate.
8. Display rider motorcycle markers and a list/card with name, rating, motorcycle plate/make/color, distance to pickup, and location freshness.
9. Select one rider and confirm the exclusive offer.
10. Show waiting state, offer expiry, rider decline, or assignment without creating duplicate deliveries.
11. After assignment, open shared live tracking.

### 3.3 Receiver flow

- Receive automatically lists active and recent deliveries addressed to the authenticated phone.
- Each item states sender, pickup, destination, order number, current status, and whether a rider is assigned.
- Opening a received delivery shows the authorized shared tracker.
- Receiver sees delivery OTP only when the server-authorized lifecycle stage requires handoff at destination.
- Receiver cannot cancel, pay for, rate, or mutate a sender-owned delivery in the first version.

### 3.4 Shared tracking

Sender and receiver see:

- the same delivery order and server-confirmed status;
- pickup and destination markers;
- assigned rider motorcycle marker, name, rating, vehicle details, and safe contact action;
- rider location timestamp/freshness and explicit reconnect/stale indicators;
- status timeline from request through delivery;
- road-navigation action to the relevant location through an external maps intent.

Socket events are update signals. Every reconnect, assignment event, status event, or sequence uncertainty triggers an authorized HTTP tracking refresh. The UI never invents movement between rider samples.

### 3.5 Activity and profile

- Activity separates sent and received history while allowing an all-deliveries view.
- Completed sender deliveries expose rating and support entry points using existing authorized routes.
- Profile displays authenticated identity, optional email, saved defaults, server connectivity state, and sign-out.

## 4. Backend contracts

### 4.1 Schema additions

Add additive, idempotent migrations for:

- `deliveries.preferred_rider_id TEXT REFERENCES users(id)`;
- `deliveries.dispatch_mode TEXT NOT NULL DEFAULT 'automatic'` with supported values `automatic` and `selected`;
- `deliveries.idempotency_key TEXT` with a unique actor-scoped index or a dedicated idempotency record table;
- a status compatible with `awaiting_rider_selection` in server validation and UI state handling.

Existing web and rider flows remain backward-compatible. Deliveries without selected dispatch continue using existing automatic dispatch.

### 4.2 Phone normalization

Define one server function `normalizePhone(phone)` that:

- removes spaces, dashes, and parentheses;
- converts Rwanda local `07XXXXXXXX` to `+2507XXXXXXXX`;
- accepts canonical `2507XXXXXXXX` and `+2507XXXXXXXX` forms;
- rejects malformed or unsupported values rather than performing fuzzy matching.

All receiver authorization and received-delivery queries use canonical values. Existing stored delivery phones are normalized at comparison time or through a safe migration.

### 4.3 Mobile customer endpoints

- `GET /api/mobile/v1/customer/home`
  - Returns authenticated profile, active sent deliveries, active received deliveries, recent sent/received summaries, and server time.
- `GET /api/mobile/v1/customer/nearby-riders?lat=&lng=&radius_km=`
  - Returns approved, online, idle riders with finite fresh coordinates inside the server-capped radius, sorted by distance.
  - Exposes only operational profile and vehicle fields.
- `POST /api/deliveries`
  - Accepts optional `preferred_rider_id` and an `Idempotency-Key`.
  - Validates selected rider eligibility at the confirmed pickup location.
  - Creates one delivery and one selected-rider offer when preferred rider is supplied.
- `PUT /api/deliveries/:id/select-rider`
  - Sender-only; valid only from `awaiting_rider_selection` or a selected searching state with no valid offer.
  - Revalidates rider eligibility and creates one new offer.
- `GET /api/mobile/v1/customer/deliveries?role=sent|received|all`
  - Returns only sender-owned and/or phone-matched received deliveries.
- `GET /api/deliveries/:id` and `GET /api/deliveries/:id/track`
  - Authorize sender, phone-matched receiver, assigned rider, business owner, or admin.
  - Redact OTP fields according to participant role and lifecycle stage.

### 4.4 Offer state behavior

- Selected offers are persisted before Socket.IO emission.
- Accept atomically claims the searching delivery and invalidates other valid offers.
- Decline immediately changes a selected-dispatch delivery to `awaiting_rider_selection`.
- Expiry performs the same transition through a deterministic timeout/cleanup path.
- Selecting an unavailable or busy rider returns conflict and causes the app to refresh nearby riders.

### 4.5 Realtime authorization

- Socket authentication verifies JWT and stores user ID, role, and canonical phone.
- `subscribe_delivery(delivery_id)` checks the same server authorization function used by HTTP tracking before joining a delivery room.
- Assignment, lifecycle status, cancellation, and rider location emit to the authorized delivery room.
- Sender and phone-matched receiver can subscribe concurrently and receive the same event IDs and coordinates.
- A rider can publish location only when authenticated as the assigned approved online/busy rider for that delivery.
- Coordinates must be finite and in geographic range.
- Disconnect removes only that socket; multi-device users are supported through a set of sockets rather than one `userSockets[userId]` slot.

## 5. Android architecture

The `customer-app` is split into focused units:

- `session/CustomerSession.kt` — token and customer profile persistence.
- `network/CustomerApi.kt` — HTTP methods, idempotency headers, structured JSON errors.
- `realtime/CustomerRealtime.kt` — socket auth, delivery subscription, reconnect callbacks.
- `location/CustomerLocation.kt` — foreground-only location request and current coordinate.
- `model/CustomerModels.kt` — delivery, rider, quote, tracking, and UI state models.
- `map/CustomerMap.kt` — OSM map with customer, nearby rider, pickup, destination, and active-rider markers.
- `auth/AuthScreen.kt` — registration, verification, login.
- `send/SendScreen.kt` — request editor and quote.
- `send/RiderSelectionScreen.kt` — nearby-rider map/list and exclusive selection.
- `receive/ReceiveScreen.kt` — phone-matched incoming deliveries.
- `tracking/TrackingScreen.kt` — shared live tracker and status timeline.
- `activity/ActivityScreen.kt` — sent/received history.
- `profile/ProfileScreen.kt` — identity and session actions.
- `MainActivity.kt` — top-level navigation and state orchestration only.

The app uses Kotlin, Jetpack Compose Material 3, coroutines, Socket.IO client, Google fused foreground location, and osmdroid, matching dependencies already proven in the rider app. Debug uses the current project LAN API base URL. Release endpoint hardening remains required before public rollout.

## 6. Visual and interaction requirements

- Map-first, phone-sized layouts with bottom cards/sheets and 48dp minimum touch targets.
- MOVO dark green, off-white surfaces, readable status colors, and centralized strings.
- Nearby riders use a motorcycle marker rather than a generic person/dot.
- Sender and receiver roles are visibly labelled on delivery cards.
- Loading, empty, offline, stale-location, declined, expired, busy-rider conflict, and retry states are explicit.
- Primary actions cannot wrap or clip on the target 720×1640 physical device.
- System Back navigates within the app and closes overlays before finishing the activity.
- External navigation uses a real maps driving-directions intent; customer calls use `ACTION_DIAL`, never automatic calling.

## 7. Security and privacy

- Receiver authorization is enforced server-side by canonical phone match.
- Nearby-rider responses exclude identity documents, insurance, private history, and precise historical trails.
- JWTs are not logged. Passwords and OTPs are never persisted.
- Customer GPS is foreground-only and used for pickup selection, not background tracking.
- Tracking reveals only the current operational rider location for an authorized active delivery.
- Delivery location subscriptions are re-authorized on every socket connection.
- HTTP errors shown in Android are parsed and bounded; raw JSON/HTML is not rendered.

## 8. Failure handling

- No GPS permission: allow manual pickup selection and explain reduced nearby accuracy.
- No nearby riders: keep request data and permit refresh/radius retry within server limits.
- Selected rider conflict: return to rider selection without duplicate delivery creation.
- Decline/expiry: show exact outcome and refresh eligible riders.
- Socket disconnected: display reconnecting/stale state and poll authorized tracking.
- Process death/relaunch: restore session and active sent/received deliveries from customer home.
- Server validation error: preserve form state and focus the relevant field.

## 9. Test and acceptance gates

### 9.1 Backend automated tests

- Canonical phone variants match the intended receiver; malformed and unrelated phones do not.
- Received lists and tracking enforce receiver authorization.
- Nearby riders exclude pending, offline, busy, stale, invalid-coordinate, and out-of-radius records.
- Only the selected rider gets a persisted offer and realtime event.
- Decline/expiry transitions to `awaiting_rider_selection`; replacement selection succeeds.
- Sender and receiver sockets receive the same assignment, lifecycle, and location events.
- Unauthorized subscriptions and forged rider location are rejected.
- Replayed idempotency keys return the original delivery and changed-body reuse conflicts.
- Existing automatic dispatch and web/rider tests remain green.

### 9.2 Android automated gates

- Unit/contract tests cover auth/session restoration, request validation, quote state, duplicate confirmation prevention, rider selection, received classification, tracking freshness, and error parsing.
- Source/UI contract tests cover Send, Receive, Activity, Profile, rider markers, phone matching messaging, and shared tracking controls.
- `:customer-app:testDebugUnitTest`, `:customer-app:assembleDebug`, and the full Node suite pass.

### 9.3 Physical-device E2E

Using exact authorized ADB serials:

1. Install latest customer and rider debug APKs.
2. Sign in as sender and receiver customer fixtures with distinct phones.
3. Confirm sender GPS and nearby approved Test Rider appear.
4. Create a selected-rider request addressed to the receiver phone.
5. Verify only Test Rider receives the offer with sender phone, pickup, and destination.
6. Accept on rider; verify sender and receiver both show assignment.
7. Send fresh rider GPS samples; verify both authorized customer sessions observe matching coordinates and timestamps.
8. Advance the complete rider lifecycle; verify simultaneous status updates and correctly staged OTP visibility.
9. Verify safe dialer and external navigation without placing a real call.
10. Force-stop/relaunch customer app and verify active tracking restores through HTTP.
11. Inspect screenshots for tiles, markers, clipping, overlap, and wrapped labels.
12. Inspect AndroidRuntime/app logs and bounded PM2 logs for crashes or API errors.
13. Clean controlled deliveries and restore temporary dispatch settings.

## 10. Scope boundaries

Included: customer authentication, sender request/quote, nearby selected-rider dispatch, phone-matched receiver view, shared realtime tracking, activity, profile, rating/support entry points, and physical-device verification.

Excluded from this implementation: public production rollout, push notification provider integration, production payment provider, in-app chat, receiver forwarding/delegation, iOS, and unrestricted rider browsing outside an active sender request.
