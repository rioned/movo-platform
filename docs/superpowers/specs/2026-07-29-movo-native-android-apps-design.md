# MOVO Native Android Customer and Rider Apps — Binding Design Specification

Date: 2026-07-29
Status: Approved for implementation planning

## 1. Goal and scope

Build two standalone Android applications for the controlled Kigali pilot:

- **MOVO Customer** (`com.movo.customer`) lets a customer register or sign in by phone, obtain a parcel/document quote, create and pay for a delivery, follow its authorized live status, access proof and history, rate a completed delivery, and open support tickets.
- **MOVO Rider** (`com.movo.rider`) lets an approved rider complete profile/onboarding requirements, control availability, receive and accept delivery offers, publish authorized live location, execute the delivery lifecycle, submit proof, and view earnings/performance.

The interaction quality takes inspiration from marketplace patterns popularized by Uber: focused map-led journeys, bottom-sheet task progression, clear service state, large thumb-reachable primary controls, rider-offer countdowns, and real-time updates. MOVO remains a parcel and document delivery marketplace; the apps must not model or claim passenger transportation.

The pilot excludes business and admin Android clients. The existing web portals remain the initial interfaces for those roles.

## 2. Platform and build constraints

- Android only; minimum API 29 (Android 10), target/compile SDK 35.
- Two standalone installable debug APKs and release-ready Gradle configurations.
- Native Kotlin, Jetpack Compose, Material 3, Navigation Compose, ViewModel/state flows, coroutines, Retrofit/OkHttp, Room, WorkManager, and Socket.IO client.
- Debug API base URL: `http://10.0.2.2:3000` for Android Emulator access to the local Express server.
- Release builds must obtain `MOVO_API_BASE_URL` at build time and reject non-HTTPS URLs. No release APK may ship with a localhost, private cleartext, or placeholder production endpoint.
- User tokens are stored through AndroidX Security encrypted storage. Passwords, OTPs, identity-document bytes, and delivery proof must never be written to logs, Room, or plaintext preferences.
- English is the initial resource bundle. UI strings are centralized so Kinyarwanda can be added without rewriting screens.

## 3. Visual system

Both apps use one MOVO design system while remaining role-specific.

- Brand: deep MOVO green as the action color, off-white map/surface background, charcoal text, and semantic status colors; colors meet WCAG contrast requirements.
- Type: readable sans-serif hierarchy, strong numeric pricing/ETA emphasis, and no body text below 14sp.
- Layout: edge-to-edge map where tracking or delivery selection is active; rounded Material bottom sheets and cards; 48dp-or-larger touch targets; predictable back behavior.
- States: skeletons for loading, clear empty states, explicit offline/error banners, and disabled/loading CTA states that explain why an action is unavailable.
- Maps: actual OpenStreetMap-backed mapping/routing integration, never decorative or synthetic map panels. Map attribution is preserved.

## 4. Customer app journeys

### 4.1 Authentication and profile

1. Splash reads encrypted session state and requests `/api/auth/me` when a token exists.
2. Signed-out users choose phone-first login or registration. Email remains optional.
3. Registration submits customer identity, verifies the OTP, persists only the returned JWT/session metadata, and opens the delivery home screen.
4. Session expiry clears secure local state and returns the user to sign-in with an explanatory message.

### 4.2 Request and quote

1. Home shows the current map, saved addresses, recent destinations, and a prominent "Send a parcel" action.
2. The request sheet gathers pickup/destination coordinates and addresses, recipient information, service type, and item description.
3. The app calls the quote endpoint before delivery creation and renders a quote review sheet: customer price, estimated time, terms, expiry, and sandbox-payment label when applicable.
4. Confirming a valid quote creates the delivery with an idempotency key. Repeated taps or a retry after network loss must not create duplicate deliveries.

### 4.3 Tracking and completion

1. Active delivery uses an authorized map plus a bottom sheet that exposes only the delivery state, ETA, rider identity appropriate to state, and permitted contact/support actions.
2. Socket events update delivery state and rider position. HTTP refresh is the recovery path when a socket reconnects or a sequence gap occurs.
3. Completion renders the receipt/proof screen, then rating and support entry points.
4. History is paginated; each item leads to an ownership-authorized detail/proof/receipt view and a rebook action that begins a new quote rather than cloning a stale price.

## 5. Rider app journeys

### 5.1 Authentication, approval, and availability

1. Rider authentication is phone-first and reuses the existing rider registration and OTP endpoints.
2. The app obtains approval/profile state on each foreground session. Pending or suspended riders see a clear restricted screen and cannot publish availability or accept offers.
3. Approved riders explicitly toggle online/offline. Going online requests foreground location permissions and starts a foreground location service only while the rider is actively available or completing an assigned delivery.

### 5.2 Offer and lifecycle execution

1. An eligible rider receives a server-authorized offer event containing limited pickup/destination information, fee, distance/ETA, and an expiry timestamp.
2. An offer card overlays the map with a countdown and explicit Accept/Decline actions. Acceptance is conditional server-side; a lost race is presented as an unavailable offer rather than a client error.
3. The active-delivery task sheet exposes exactly the permitted next lifecycle action: going to pickup, arrived at pickup, pickup verification/proof, in transit, arrived at destination, and completion/proof.
4. Proof capture uses CameraX and uploads only through authenticated multipart endpoints. Local media remains in app-private cache until confirmed uploaded, then is deleted.
5. Earnings/performance display server-calculated figures only; client code must never calculate or alter payout values.

### 5.3 Connectivity resilience

1. Rider lifecycle submissions receive a generated idempotency key and are retained in Room when transport fails.
2. WorkManager retries queued operations with exponential backoff when connectivity returns.
3. The UI shows sync state, queue count, and last successful location update. It must not claim a lifecycle transition has succeeded until the server confirms it.
4. Location samples are throttled and sent only under the authorized availability/assigned-delivery policy.

## 6. Backend mobile API adaptation

The existing Express server remains the source of business rules and SQLite persistence. Existing web endpoints stay compatible. Add `/api/mobile/v1` only for contracts that need a stable mobile shape; do not duplicate core delivery rules.

### 6.1 Contract requirements

- JSON responses use one envelope: `{ success: true, data, requestId }` or `{ success: false, error: { code, message, fields? }, requestId }`.
- Every mutating mobile request accepts `Idempotency-Key`; the server persists request hash, actor, response status, and response body for a bounded retention interval. Replays by the same actor/key return the original outcome, while key reuse with a different request body is rejected.
- List endpoints accept validated `cursor` and `limit` parameters, return `{ items, nextCursor }`, and enforce role/ownership filtering server-side.
- All mobile contracts validate payload sizes, strings, coordinates, status transitions, and content types before database mutation.
- JWT auth remains bearer-token based. Mobile clients never receive administrative privileges.
- The server produces a request correlation ID for each request and includes it in response/event payloads and structured audit records.

### 6.2 New or adapted endpoints

- `GET /api/mobile/v1/customer/home` returns the authenticated customer’s active delivery summary, recent addresses, and current notification count.
- `GET /api/mobile/v1/customer/deliveries?cursor=&limit=` returns an ownership-filtered delivery history page.
- `GET /api/mobile/v1/rider/home` returns rider approval state, availability, active delivery, unread offers, earnings summary, and sync-relevant server time.
- `GET /api/mobile/v1/rider/offers` returns only valid offers for the authenticated rider.
- `POST /api/mobile/v1/push-tokens` registers a platform/device push token and timestamp; duplicate registration is idempotent.
- `DELETE /api/mobile/v1/push-tokens/:id` revokes the calling device’s registered token.
- Existing delivery quote/create, rider availability/location, rider active-delivery, delivery transition, notification, rating, and support routes are adapted to accept idempotency keys and stable error codes without weakening current web clients.

### 6.3 Realtime contract

- Socket authentication uses the existing JWT mechanism and returns an explicit `authenticated` or `authentication_error` event.
- Server emits versioned event names and payloads: `mobile.delivery.updated`, `mobile.delivery.location`, `mobile.rider.offer`, `mobile.rider.offer.expired`, and `mobile.notification.created`.
- Every event includes `eventId`, `occurredAt`, `requestId` where applicable, and an authorized minimal payload.
- A customer receives only events for deliveries they own; a rider receives only their own offers/assigned deliveries; no phone number, proof URL, or identity document is broadcast unnecessarily.
- Mobile clients use HTTP resynchronization after reconnect; socket events do not become the sole data store.

## 7. Maps, routing, and location policy

- Development and pilot mapping use OSM-compatible tiles and routing as configured by the server/provider adapter.
- The map client must expose attribution, resilient tile errors, route fallback, and location-permission denial states.
- Rider background location requires visible foreground-service notification, explicit user consent, and an online/active-delivery state. It stops on logout, offline selection, approval loss, delivery completion when offline, or permission revocation.
- The server rejects rider location updates when role, approval, availability, coordinate validity, and delivery association policy fail.
- Customer location is used only to choose a pickup point and is not background-tracked.

## 8. Security, safety, and privacy

- Release traffic is HTTPS-only with certificate-system validation; no trust-all TLS or disabled hostname checks.
- Network security configuration permits debug cleartext only for the emulator development host; it prohibits cleartext in release.
- Android runtime permission disclosure explains each permission before invoking the system dialog.
- Customer-facing tracking limits rider information to name, appropriate vehicle details, and authorized delivery status. It never exposes rider documents, private phone numbers, or exact historical location trails.
- Rider proof/identity media is private, authenticated, size-limited, type-validated, and excluded from backups/logs.
- Backend audit records capture actor, request ID, old/new delivery state, reason, timestamp, and idempotency key for mutable operational actions.

## 9. Test and release gates

### Android

- JVM tests cover reducers, validation, URL policy, state-machine UI models, idempotency queue behavior, and repository error mapping.
- Instrumented tests verify sign-in/session restoration, quote creation, offline queue messaging, rider approval gating, and lifecycle CTA progression against a controlled fake server.
- Compose UI tests verify large actions, loading/error states, accessibility labels, and no duplicate submit while a request is pending.
- Debug builds for both apps assemble successfully and produce inspectable APKs.

### Server

- Node integration tests cover mobile authorization boundaries, cursor paging, idempotency replay/body mismatch, location authorization, mobile event visibility, and existing web-client regression paths.
- Test databases are isolated temporary paths and never use `movo.db`.
- Existing `npm run test` and syntax checks remain green.

### Manual pilot acceptance

- Customer: registration → quote → delivery creation → real-time status/map → receipt/proof/rating.
- Rider: approved sign-in → online → location permission/foreground notification → offer countdown → full lifecycle/proof → earnings.
- Negative checks: unapproved rider cannot go online; customer cannot read another delivery; background location stops after offline/logout; release build rejects insecure base URL.

## 10. Delivery order

1. Establish a shared multi-module Android Gradle workspace, secure build configuration, visual design system, and fake-server test harness.
2. Add mobile API contract primitives on the server: correlation IDs, error codes, idempotency, cursor paging, and mobile home endpoints with tests.
3. Implement the customer authentication, home, request/quote, and active-tracking flows.
4. Implement rider authentication, approval state, availability, offers, lifecycle actions, secure proof capture, foreground location, and offline queue.
5. Add end-to-end test coverage, build both debug APKs, test them against the local server/emulator configuration, and document release configuration.

No public rollout is implied by this work. Production launch remains blocked on real provider integrations, security assessment, monitored backups, policy/legal review, and controlled pilot acceptance.