# MOVO Customer Map-First Rider Discovery — Design Specification

Date: 2026-07-29
Status: Approved conversational design; pending written-spec review

## 1. Purpose

Redesign the native MOVO customer sender journey as a map-first experience in which the app proves that eligible riders are available near the pickup before the sender can continue to destination and delivery details.

The interaction may use the established map-first discovery technique common to ride-hailing products, but it must remain an original MOVO interface. It must not reproduce Uber trademarks, logos, wording, proprietary artwork, fonts, animations, or trade dress.

This specification extends `docs/superpowers/specs/2026-07-29-customer-sender-receiver-realtime-design.md`. It does not replace that specification's server authority, phone-matched receiver authorization, exclusive selected-rider dispatch, durable idempotency, offer expiry/reselection, or shared realtime tracking requirements.

## 2. Approved product decisions

1. The Send workspace opens directly on a live OSM map and resolves a pickup coordinate.
2. The app scans eligible nearby riders before showing delivery-detail entry as the next step.
3. Continue remains disabled until the server returns at least one currently eligible nearby rider.
4. If no rider is available, the sender may adjust pickup and retry but may not continue.
5. The first scan proves current availability only. It does not reserve or select a rider.
6. The sender enters destination and request details after availability is confirmed.
7. After obtaining a quote, the app refreshes nearby riders and the sender explicitly chooses one rider.
8. Delivery creation remains selected dispatch using `preferred_rider_id` and a stable actor-scoped idempotency key.
9. Decline or expiry returns to replacement discovery on the same delivery ID; no duplicate delivery is created.

## 3. Journey architecture

The sender journey has these authoritative UI states:

`LocatingPickup → Scanning → RidersAvailable → EnteringRequest → Quoted → ChoosingRider → WaitingForRider → Tracking`

Supporting states are `ManualPickupRequired`, `NoRiders`, `Offline`, `ScanError`, and `AwaitingReplacement`.

### 3.1 Locating pickup

- Open Send as a full-height OSM map with an anchored bottom sheet.
- Explain foreground location use before requesting fine and coarse permission.
- If permission is granted, resolve one current location and use it as the initial pickup.
- If permission is denied or location times out, require manual pickup placement on the map.
- The app must never scan with null, non-finite, or out-of-range coordinates.

### 3.2 Scanning nearby riders

- As soon as pickup is valid, call:
  `GET /api/mobile/v1/customer/nearby-riders?lat=&lng=&radius_km=`.
- Display a restrained concentric search pulse around the pickup marker.
- Show explicit text: `Finding riders near you`.
- Render one MOVO motorcycle marker per fresh rider returned by the server.
- Do not animate invented rider movement or interpolate coordinates.
- Ignore or reject malformed rider coordinates defensively even though the server filters them.

### 3.3 Riders available

When one or more riders are returned:

- Stop the active scan pulse and retain a solid pickup halo.
- Show the exact available count.
- Show nearest rider distance and an ETA only when derived from an approved calculation; do not invent ETA from distance labels.
- Enable `Continue`.
- Provide `Adjust pickup`; moving pickup immediately clears old results and returns to `Scanning`.
- Motorcycle markers are availability indicators only and are not reserved.

### 3.4 No riders

When no eligible riders are returned:

- Show `No riders near this pickup`.
- Keep `Continue` disabled.
- Provide `Adjust pickup` and `Scan again`.
- Preserve no stale count or stale marker as current availability.
- Do not create a delivery or background automatic-dispatch request.

### 3.5 Request details

After Continue:

- Keep the same map context and open a destination-first bottom sheet.
- Collect destination coordinate/address, sender contact, receiver name/phone, parcel/document type, item description, delivery instructions, and Cash/Mobile money choice.
- Preserve the draft through recomposition, rotation, and process recreation using the existing encrypted journey state.
- Editing pickup invalidates discovery and returns to `Scanning`.
- Validation focuses the relevant field and preserves all valid input.

### 3.6 Quote and refreshed rider choice

- Request the existing server quote using finite pickup/destination coordinates and service type.
- Show customer price, distance, estimated duration, and payment method.
- Refresh nearby riders from the confirmed pickup before rider choice because discovery results may have become stale.
- Display refreshed motorcycle markers and rider cards with server-provided:
  - name;
  - rating;
  - motorcycle make/model/plate/color;
  - distance to pickup;
  - location freshness.
- The sender chooses exactly one rider.
- The server remains responsible for final eligibility validation.
- A busy/offline/stale conflict preserves the draft and quote, clears the unavailable selection, refreshes results, and gives a precise retry message.

### 3.7 Offer waiting and replacement

- Create one selected-dispatch delivery and one exclusive offer.
- Show `Request sent` with server-backed offer expiry information when available.
- Rider acceptance opens shared tracking.
- Rider decline or offer expiry moves the existing delivery to `awaiting_rider_selection`.
- Replacement discovery uses the existing pickup, draft, quote, delivery ID, and replacement idempotency state.
- Replacement calls `PUT /api/deliveries/:id/select-rider`; it must not call `POST /api/deliveries`.

## 4. Visual system

### 4.1 Direction

The visual direction is `Kigali movement`: a calm operational map, clear road/vehicle data, and restrained motion that communicates live availability without pretending to know more than the server reports.

### 4.2 Tokens

- Route White: `#FCFCFA` — primary sheet and card surface.
- Map Mist: `#EEF2EF` — neutral background and unloaded-map surface.
- MOVO Forest: `#086B4D` — primary actions and selected state.
- Signal Green: `#19A974` — live availability.
- Road Ink: `#151817` — primary text.
- Moto Amber: `#F5A623` — expiry and attention state.

Use high-contrast sans-serif typography with a clear hierarchy. Keep utility text for distance, freshness, and status compact but readable. Do not add a font dependency unless licensing, APK impact, and offline behavior are explicitly reviewed.

### 4.3 Layout

- Map remains the dominant canvas.
- Discovery uses an anchored bottom sheet with 20–24dp top corners.
- Rider cards use 14–16dp corners and 48dp minimum targets.
- The map is clipped at the Compose/AndroidView boundary using `Modifier.clipToBounds()`.
- Sheets must not cover required attribution or hide selected pickup/destination controls.
- Bottom navigation remains accessible but visually quiet while Send is active.
- All primary actions fit without wrapping or clipping on the target 720×1640 device.

### 4.4 Signature motion

- During `Scanning`, draw a bounded concentric pulse around pickup.
- Respect reduced-motion/device animator settings by falling back to a static halo plus progress indicator.
- Markers appear only for real returned riders.
- On `RidersAvailable`, the pulse settles into a solid halo.
- Do not simulate moving motorcycles between server samples.

## 5. Component boundaries

Target focused units rather than growing the current request form:

- `send/SendDiscoveryState.kt` — explicit discovery/journey state and transition rules.
- `send/RiderDiscoveryController.kt` — pickup-keyed scan, cancellation, retry, result invalidation, and refresh-before-choice.
- `send/MapFirstSendScreen.kt` — journey orchestration and map/sheet composition.
- `send/DiscoverySheet.kt` — locating, scanning, available, empty, offline, and error sheets.
- `send/RequestDetailsSheet.kt` — destination and delivery fields.
- `send/RiderSelectionSheet.kt` — quote and refreshed exclusive rider choice.
- `map/CustomerMap.kt` — pickup/destination/rider markers and bounded map interaction.
- `session/CustomerSession.kt` — existing encrypted draft, quote, delivery ID, and idempotency persistence.

`MainActivity.kt` remains top-level navigation only. The backend nearby-rider endpoint remains authoritative and should not be duplicated in client-side eligibility logic.

## 6. Data and concurrency rules

- Each scan is keyed by the pickup coordinate used for the request.
- A pickup change cancels or supersedes the previous request and clears its results immediately.
- Late responses for an old pickup must be discarded.
- Only one scan is active at a time.
- Retry must not overlap an in-flight request.
- Availability is not treated as a reservation.
- Results are refreshed before final rider selection.
- Selected delivery submission remains duplicate-tap protected and idempotent.
- Socket events remain update signals; HTTP refresh remains authoritative.

## 7. Failure handling

### Location

- Permission denied: manual pickup selection with clear guidance.
- GPS timeout/unavailable: manual map fallback; no endless spinner.
- Invalid coordinate: block scan and explain how to set pickup.

### Network and server

- Offline: retain pickup/draft but display no rider count as current.
- Scan timeout/error: show bounded message plus `Scan again`.
- Authentication failure: return through existing session handling.
- No riders: block Continue and preserve pickup adjustment/retry.
- Rider conflict after quote: clear selection, refresh, preserve draft and quote.

### Lifecycle

- Rotation: preserve state without duplicate scans where a valid in-flight/result state can be retained safely.
- Process restart: restore pickup and request draft, but rescan current rider availability.
- App resume after a meaningful delay: mark old availability stale and rescan.
- Decline/expiry: preserve existing delivery identity and enter replacement discovery.

## 8. Privacy and product integrity

- Nearby results expose only approved operational rider/vehicle fields.
- Do not expose rider phone, documents, insurance, historical trails, or exact off-duty location.
- Customer location remains foreground-only.
- Do not log JWTs, passwords, OTPs, customer coordinates, or full server bodies.
- UI language must describe real state. `Available` means returned by the current server scan, not guaranteed acceptance.
- The design must remain visibly MOVO and must not use Uber names, logos, proprietary assets, exact copy, or trade dress.

## 9. TDD and acceptance gates

### 9.1 Android RED/GREEN tests

Add failing tests before production changes for:

1. Send opens in discovery rather than the full request form.
2. Scan does not start without a finite pickup.
3. Valid pickup starts one nearby-rider request.
4. Continue is disabled while locating/scanning and when results are empty.
5. Continue enables only after one or more valid riders are returned.
6. Pickup changes clear results and invalidate late responses.
7. Offline/error states never present stale availability as current.
8. Continue opens destination/request details.
9. Quote completion refreshes riders before final choice.
10. Final submission includes one `preferred_rider_id` and stable idempotency key.
11. Decline/expiry replacement retains the delivery ID and never creates a second delivery.
12. Native map remains clipped below discovery controls on 720×1640.

Each vertical slice must pass both focused tests and `:customer-app:compileDebugKotlin` before proceeding.

### 9.2 Existing regression gates

- Full `npm run test` remains green.
- `npm run test:syntax` remains green.
- `:customer-app:testDebugUnitTest` and `:customer-app:assembleDebug` pass.
- Rider app build remains green.
- `git diff --check` passes.

### 9.3 Physical-device acceptance

On the authorized 720×1640 device:

1. Install the rebuilt customer APK.
2. Confirm Send opens map-first.
3. Confirm permission education and manual fallback.
4. Confirm scanning state is visible and not clipped.
5. Confirm real returned riders render as motorcycle markers.
6. Confirm Continue is disabled before results and for zero results.
7. Confirm Continue enables after eligible results.
8. Change pickup and confirm old markers/count clear before rescan.
9. Complete details and quote; confirm refreshed rider list.
10. Select one rider and confirm only that rider receives the offer.
11. Accept and verify sender/receiver shared assignment and rider coordinates.
12. Force decline/expiry and verify same-delivery replacement.
13. Inspect customer logcat and bounded server logs for crashes, ANRs, retry loops, authorization failures, and duplicate requests.

## 10. Out of scope

- Automatic server-selected matching that removes sender choice.
- Rider reservation during the opening availability scan.
- Background customer location tracking.
- Fake rider movement or predictive interpolation.
- Uber branding, assets, exact styling, copy, or proprietary behavior.
- Changes to receiver authorization or selected-dispatch server authority already defined by the parent specification.
