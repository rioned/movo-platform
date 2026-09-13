# MOVO customer Android — parcel redesign

Open `android/` in Android Studio. The existing `:customer-app` package/application ID is retained, and the rider app and shared design module are not redesigned by this change. The customer launcher no longer exposes passenger booking. Legacy customer screens remain in source for compatibility but are unreachable from the new launcher.

## Build and run

```sh
cd /home/kali/movo-platform/android
./gradlew :customer-app:assembleDebug :customer-app:testDebugUnitTest :customer-app:lintDebug --max-workers=2
adb install -r customer-app/build/outputs/apk/debug/customer-app-debug.apk
adb shell am start -n com.movo.customer/.MainActivity
```

Use **Explore demo** on phone sign-in for the offline sandbox. It does not submit orders, authenticate to production, contact a Rider or charge money. Search Kigali, Kimironko or Airport. A created demo parcel moves from searching through assignment, pickup, transit and delivery over roughly 70 seconds. Completed deliveries can be rated. Demo state is intentionally in-memory and resets when the process ends.

The live origin is fixed to `https://movo-vervice.tech/`; there is no API-origin override. No production OTP requests, bookings, payments, support submissions or deployments are performed by build/test commands.

## Architecture

- `parcel/ParcelApp.kt`: Navigation Compose graph, lifecycle-aware tracking, explicit demo/live UI.
- `parcel/ParcelViewModel.kt`: Hilt ViewModel and delivery/auth state coordination.
- `parcel/domain`: repository boundary, immutable parcel models and validation.
- `parcel/data`: Retrofit/OkHttp/Moshi live adapter; separate demo implementation.
- `parcel/auth`, `parcel/delivery`, `parcel/account`: Compose feature screens.
- `parcel/ui`: Inter typography, named brand tokens and reusable controls.
- `parcel/push`: safe FCM routing, permission/category gating, deduplication and device-encrypted token storage.
- `JourneyStore`: DataStore onboarding, current draft, request key, active-order ID and last known location.
- Auth JWT remains in Android Keystore-backed EncryptedSharedPreferences. Ordinary new preferences use DataStore. Backup is disabled. No bearer-token logging or cleartext transport is enabled.

## Confirmed backend contracts

These paths were checked against the local `server.js`, not guessed:

| Capability | Contract |
| --- | --- |
| Request login OTP | `POST /api/auth/login` with phone only |
| Register and request OTP | `POST /api/auth/register` with phone, full_name, role=customer |
| Verify | `POST /api/auth/verify-otp` with phone and otp |
| Restore / edit profile | `GET /api/auth/me`, `PUT /api/profile` |
| Service-zone check | `GET /api/mobile/v1/customer/nearby-riders?lat=…&lng=…`, check in_service_area |
| Quote | `POST /api/deliveries/price` |
| Create | `POST /api/deliveries`, durable Idempotency-Key |
| History | `GET /api/mobile/v1/customer/deliveries?role=all` (server caps at 200) |
| Detail / tracking | `GET /api/deliveries/{id}`, `GET /api/deliveries/{id}/track` |
| Cancel | `PUT /api/deliveries/{id}/cancel` |
| Rate | `POST /api/ratings` with delivery_id, score, review |
| Addresses | `GET/POST /api/addresses`, `DELETE /api/addresses/{id}` |
| Notifications | `GET /api/notifications`, `PUT /api/notifications/{id}/read` |
| Support | `POST /api/tickets`; selected order ID is included in description |

The backend has no JWT refresh endpoint: HTTP 401 clears the session and requires OTP sign-in. Transport failures are not converted into demo success. Live creation is limited to standard small parcel/document, one destination and cash: unsupported options are disabled/rejected rather than silently dropped. Cash is an order payment-method selection, not a claim that cash was collected.

## Google Maps / Places

Map *display* in the delivery flow (pickup/route/tracking screens) renders on osmdroid — free OpenStreetMap tiles by default, or MapTiler-hosted tiles when `maptilerApiKey` is configured (same key the server already documents as `MAPTILER_API_KEY`). This mirrors the server's `MAP_PROVIDER=osm` path (`android/design`'s `MapServices`/`MapTileSources`) and avoids paying for the Google Maps SDK just to draw pins and a route overview — no Google Maps key is required for the app to show a map at all.

Address search (`HybridPlacesSearch`) merges two sources so suggestions stay useful without requiring Google: OpenStreetMap/MapTiler geocoding (Nominatim is free; MapTiler geocoding is used instead when `MAPTILER_API_KEY` is set) always runs, and Google Places autocomplete is added on top only when `googleMapsApiKey` is configured, for richer named-business/landmark matches. Results are deduplicated by rounded coordinate into one ranked list. Set `googleMapsApiKey` in your user-level Gradle properties (not tracked source), restrict it to this Android package and signing certificate, and enable the Places API in the authorized Google project if you want it; for CI supply the Gradle property through the CI secret mechanism. The AndroidManifest's Maps `API_KEY` meta-data is only consumed by the Places SDK initialization, not by map rendering.

Search screens also surface strong default suggestions before the customer types anything: saved addresses first, then GPS pickup (on the pickup screen), then recently used delivery destinations — so there is always something useful to tap. Pin connectors are labeled route overviews, not road-following directions. Recenter requests foreground permission; holding recenter targets a saved Home. The app does not currently request or transmit ongoing background device location.

## Firebase

FCM classes and manifest service are present. Supply the authorized Firebase Android configuration using generated Android resources (`google_app_id`, `gcm_defaultSenderId`, `google_api_key`, `project_id`) or add the Google Services Gradle plugin/configuration in your release environment. Missing Firebase options do not crash the app. Configure server **data messages** with order_id, title, body and category=delivery/promotions/offers to use app-side preferences and deduplication. System-rendered notification messages do not run the foreground data-message handler in the background.

There is no push-token registration endpoint in this backend. Tokens are stored encrypted on-device only; server registration and actual push delivery remain an integration requirement. Never call a fabricated registration endpoint. Notification taps carry an order_id into authenticated order detail; invalid identifiers are discarded.

## Deliberate accessibility adjustment

Primary green `#1FAE59` against background is 6.44:1 and against cards is 5.76:1. White on this green is only 2.89:1, failing AA even for large text. Primary buttons therefore use the supplied pressed green `#16874A` with white text (4.57:1). Selected circles use the requested green and checkmark plus semantic radio state. Controls use 48dp or larger interactive targets; headings and prices are bold Inter.

## Remaining scope — do not label this release production-complete

The new navigation includes all 21 requested screen families, but several requested behaviors are not complete:

- MoMo/Airtel provider initiation/confirmation, multi-recipient server dispatch, COD settlement, package-photo upload, recipient-link SMS, tips, promo validation, public tracking links, in-app Rider chat, support live chat and account-deletion API need backend contracts. Demo options are not production integrations.
- Multi-stop demo currently stores extra locations, not separate contact records per recipient.
- Maps need project credentials; route overview is not a Directions API polyline; search-match highlighting, distance-ranked results and draggable-pin refinement remain UI follow-up.
- Optional package photos use gallery/system camera preview in demo. Optional profile photo is a disclosed local preview, not uploaded.
- Live tracking uses authorized 5-second HTTP polling while its screen is STARTED, not smooth socket-position interpolation.
- Live location sharing with Riders is explicitly unavailable. SOS opens the emergency dialer; sharing last-known location is a separate user-initiated share action, never an automatic emergency dispatch.
- Saved addresses are cached and support add/delete; full edit and swipe-to-delete UX still need follow-up. The backend does not expose address PUT.
- Account screens contain English/Kinyarwanda/French strings, but full localization of booking/auth copy remains incomplete.
- Full approved legal documents, real support phone configuration and the published Play listing must come from MOVO. No policy text or phone number was invented.
- Some requested visual details (full-screen map sheets, exact Profile avatar/shortcut styling, logo assets, every advanced filter) require a further UI pass. No reference screenshots were supplied.

Use this as a buildable parcel-first foundation and verified demo, not as proof that the entire supplied production brief is finished.
