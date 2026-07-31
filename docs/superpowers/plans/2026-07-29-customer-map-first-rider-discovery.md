# MOVO Map-First Rider Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the customer Send form-first journey with an original MOVO map-first experience that scans eligible nearby riders before allowing destination/details entry, refreshes riders before exclusive selection, and preserves same-delivery reselection.

**Architecture:** Introduce a pure Kotlin discovery state machine and pickup-keyed controller, then compose it through a map-first screen with focused discovery, request-details, and rider-choice sheets. Reuse the existing authenticated nearby-rider endpoint, encrypted `SendJourney`, quote endpoint, selected-dispatch creation, replacement endpoint, and shared tracker; the server remains authoritative for eligibility and authorization.

**Tech Stack:** Kotlin/JVM 21, Jetpack Compose Material 3, coroutines 1.9.0, osmdroid 6.1.18, Google Fused Location 21.3.0, AndroidX encrypted preferences, Node source-contract tests.

## Global Constraints

- Continue is disabled until a finite pickup has produced at least one current server-returned eligible rider.
- No-rider, offline, and scan-error states must not present stale markers/counts as current.
- The opening scan proves availability but does not reserve or select a rider.
- Refresh nearby riders after quote and before final rider choice.
- Final creation sends one `preferred_rider_id` and the existing stable creation idempotency key.
- Decline/expiry replacement keeps the existing delivery ID and uses `PUT /api/deliveries/:id/select-rider`.
- Do not add Uber trademarks, logos, copy, fonts, assets, trade dress, or proprietary animation.
- Keep `Modifier.clipToBounds()` at the native map interop boundary.
- Preserve receiver phone authorization, socket room authorization, and HTTP-authoritative tracking.
- Target physical viewport: 720×1640; minimum touch target: 48dp.
- Do not commit, push, stash, reset, or clean unless the user explicitly requests it.

---

## File map

**Create**

- `android/customer-app/src/main/kotlin/com/movo/customer/send/SendDiscoveryState.kt` — discovery states and transition invariants.
- `android/customer-app/src/main/kotlin/com/movo/customer/send/RiderDiscoveryController.kt` — pickup-keyed scan orchestration, request versioning, and stale-response rejection.
- `android/customer-app/src/main/kotlin/com/movo/customer/send/DiscoverySheet.kt` — locating/scanning/found/empty/offline/error sheet.
- `android/customer-app/src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt` — destination and delivery fields only.
- `android/customer-app/src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt` — map/sheet journey orchestration.
- `android/customer-app/src/test/kotlin/com/movo/customer/send/SendDiscoveryStateTest.kt` — pure transition tests.
- `android/customer-app/src/test/kotlin/com/movo/customer/send/RiderDiscoveryControllerTest.kt` — scan concurrency/invalidation tests.

**Modify**

- `android/customer-app/build.gradle.kts` — Kotlin and coroutine test dependencies.
- `android/customer-app/src/main/kotlin/com/movo/customer/send/SendScreen.kt` — compatibility entry point delegating to map-first screen.
- `android/customer-app/src/main/kotlin/com/movo/customer/send/RiderSelectionScreen.kt` — consume refreshed riders and present bottom-sheet choice without an independent initial scan.
- `android/customer-app/src/main/kotlin/com/movo/customer/map/CustomerMap.kt` — availability halo and bounded motorcycle-marker rendering.
- `android/customer-app/src/main/kotlin/com/movo/customer/model/CustomerModels.kt` — persisted journey stage/pickup scan timestamp as needed.
- `android/customer-app/src/main/kotlin/com/movo/customer/session/CustomerSession.kt` — persist/restore non-authoritative journey stage while forcing fresh availability after restart.
- `android/customer-app/src/main/kotlin/com/movo/customer/MainActivity.kt` — MOVO visual tokens and quieter Send navigation treatment.
- `test/customer-android-contract.test.js` — source contracts for discovery-first gating, map clipping, refresh-before-choice, and same-delivery replacement.
- `.superpowers/sdd/verify-customer-remediation.sh` — no behavioral change unless needed to include the new unit-test task explicitly.

---

### Task 1: Pure discovery state and transition invariants

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/send/SendDiscoveryState.kt`
- Create: `android/customer-app/src/test/kotlin/com/movo/customer/send/SendDiscoveryStateTest.kt`
- Modify: `android/customer-app/build.gradle.kts:17-32`

**Interfaces:**
- Produces: `sealed interface DiscoveryPhase`, `data class DiscoverySnapshot`, `fun DiscoverySnapshot.canContinue(): Boolean`, `fun DiscoverySnapshot.invalidateForPickup(Coordinate?): DiscoverySnapshot`.
- Consumes: existing `Coordinate` and `NearbyRider` models.

- [ ] **Step 1: Add test dependencies and write failing transition tests**

Add:

```kotlin
testImplementation(kotlin("test"))
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

Write tests proving:

```kotlin
@Test fun continue_requires_current_non_empty_results() {
    val pickup = Coordinate(-1.9441, 30.0619)
    assertFalse(DiscoverySnapshot(DiscoveryPhase.Scanning, pickup).canContinue())
    assertFalse(DiscoverySnapshot(DiscoveryPhase.NoRiders, pickup).canContinue())
    assertTrue(DiscoverySnapshot(DiscoveryPhase.Available, pickup, listOf(rider())).canContinue())
}

@Test fun pickup_change_clears_riders_and_returns_to_scanning() {
    val old = DiscoverySnapshot(DiscoveryPhase.Available, pickupA, listOf(rider()))
    val changed = old.invalidateForPickup(pickupB)
    assertEquals(DiscoveryPhase.Scanning, changed.phase)
    assertEquals(pickupB, changed.pickup)
    assertTrue(changed.riders.isEmpty())
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
cd android && ./gradlew :customer-app:testDebugUnitTest --tests 'com.movo.customer.send.SendDiscoveryStateTest'
```

Expected: FAIL because `DiscoveryPhase` and `DiscoverySnapshot` do not exist.

- [ ] **Step 3: Implement minimal pure state model**

```kotlin
sealed interface DiscoveryPhase {
    data object Locating : DiscoveryPhase
    data object ManualPickupRequired : DiscoveryPhase
    data object Scanning : DiscoveryPhase
    data object Available : DiscoveryPhase
    data object NoRiders : DiscoveryPhase
    data object Offline : DiscoveryPhase
    data class Error(val message: String) : DiscoveryPhase
}

data class DiscoverySnapshot(
    val phase: DiscoveryPhase,
    val pickup: Coordinate? = null,
    val riders: List<NearbyRider> = emptyList()
) {
    fun canContinue() = phase == DiscoveryPhase.Available && pickup?.isFinite == true && riders.isNotEmpty()
    fun invalidateForPickup(next: Coordinate?) = DiscoverySnapshot(
        phase = if (next?.isFinite == true) DiscoveryPhase.Scanning else DiscoveryPhase.ManualPickupRequired,
        pickup = next?.takeIf { it.isFinite }
    )
}
```

- [ ] **Step 4: Run GREEN and compile**

```bash
cd android && ./gradlew :customer-app:testDebugUnitTest --tests 'com.movo.customer.send.SendDiscoveryStateTest' :customer-app:compileDebugKotlin
```

Expected: PASS and BUILD SUCCESSFUL.

---

### Task 2: Pickup-keyed scan controller with stale-response rejection

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/send/RiderDiscoveryController.kt`
- Create: `android/customer-app/src/test/kotlin/com/movo/customer/send/RiderDiscoveryControllerTest.kt`

**Interfaces:**
- Consumes: `CustomerApi.get(path)`, `JSONObject.toNearbyRider()`, `DiscoverySnapshot`.
- Produces:

```kotlin
fun interface NearbyRiderSource { suspend fun scan(pickup: Coordinate): List<NearbyRider> }
class RiderDiscoveryController(private val source: NearbyRiderSource) {
    val snapshot: StateFlow<DiscoverySnapshot>
    fun invalidate(pickup: Coordinate?)
    suspend fun scan(pickup: Coordinate, online: Boolean)
}
fun customerNearbyRiderSource(api: CustomerApi, radiusKm: Int = 10): NearbyRiderSource
```

- [ ] **Step 1: Write failing controller tests**

Cover exactly:

```kotlin
@Test fun valid_pickup_runs_one_scan_and_enables_continue() = runTest { ... }
@Test fun invalid_pickup_never_calls_source() = runTest { ... }
@Test fun empty_results_block_continue() = runTest { ... }
@Test fun offline_clears_current_availability_without_calling_source() = runTest { ... }
@Test fun late_response_for_old_pickup_is_discarded() = runTest { ... }
@Test fun duplicate_scan_for_same_inflight_pickup_is_coalesced() = runTest { ... }
```

Use `CompletableDeferred<List<NearbyRider>>` in the late-response test: start scan A, invalidate/start B, complete A, and assert A never repopulates `snapshot.riders`.

- [ ] **Step 2: Run RED**

```bash
cd android && ./gradlew :customer-app:testDebugUnitTest --tests 'com.movo.customer.send.RiderDiscoveryControllerTest'
```

Expected: FAIL because the controller/source interfaces do not exist.

- [ ] **Step 3: Implement request-versioned controller**

Use a `MutableStateFlow`, a monotonically increasing `requestVersion`, and an `inFlightPickup`. On every invalidate, increment the version and clear riders. Before publishing a response, compare both the captured version and pickup with current values. Filter `location.isFinite` defensively.

The API adapter must call:

```kotlin
api.get("/api/mobile/v1/customer/nearby-riders?lat=${pickup.latitude}&lng=${pickup.longitude}&radius_km=$radiusKm")
    .dataObject()
    .optJSONArray("riders")
```

Map each JSON object through `toNearbyRider()`.

- [ ] **Step 4: Run GREEN and focused contract**

```bash
cd android && ./gradlew :customer-app:testDebugUnitTest --tests 'com.movo.customer.send.RiderDiscoveryControllerTest' :customer-app:compileDebugKotlin
cd .. && node --test test/customer-android-contract.test.js
```

Expected: controller tests PASS, Kotlin BUILD SUCCESSFUL, existing contracts PASS.

---

### Task 3: Discovery sheet and real map availability presentation

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/send/DiscoverySheet.kt`
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/map/CustomerMap.kt:27-69`
- Modify: `test/customer-android-contract.test.js:157-170`

**Interfaces:**
- `DiscoverySheet(snapshot, nearestSummary, onContinue, onAdjustPickup, onRetry)` renders state only.
- Extend `CustomerMap` with optional `discoveryActive: Boolean = false` and `showPickupHalo: Boolean = false`; preserve all existing call sites via defaults.

- [ ] **Step 1: Add failing source contract**

Add a test requiring:

```javascript
source('src/main/kotlin/com/movo/customer/send/DiscoverySheet.kt', [
  /Finding riders near you/, /No riders near this pickup/, /Scan again/,
  /Adjust pickup/, /canContinue\(\)/, /Riders nearby|riders nearby/i
]);
source('src/main/kotlin/com/movo/customer/map/CustomerMap.kt', [
  /clipToBounds\(\)/, /ic_movo_motorcycle/, /discoveryActive/, /showPickupHalo/
]);
```

- [ ] **Step 2: Run RED**

```bash
node --test --test-name-pattern='map-first discovery sheet' test/customer-android-contract.test.js
```

Expected: FAIL because `DiscoverySheet.kt` and halo parameters do not exist.

- [ ] **Step 3: Implement the state sheet**

Use `Surface`/`ElevatedCard`, 24dp top corners, Route White, explicit state copy, and one primary action. The Continue button must use `enabled = snapshot.canContinue()`.

State copy:

- Locating: `Finding your pickup`.
- Scanning: `Finding riders near you`.
- Available: `${snapshot.riders.size} riders nearby`.
- NoRiders: `No riders near this pickup`.
- Offline: `Rider availability needs a connection`.
- Error: bounded controller message.

- [ ] **Step 4: Add map halo without fake movement**

Use an osmdroid `Polygon` or `Polyline`-backed circle centered on the real pickup coordinate. During discovery, vary only the halo radius/alpha; motorcycle marker coordinates remain exactly server-returned. Remove prior halo overlays by type/tag before rebuilding overlays. Keep `modifier.clipToBounds()` on `AndroidView`.

- [ ] **Step 5: Run GREEN and compile**

```bash
node --test test/customer-android-contract.test.js
cd android && ./gradlew :customer-app:compileDebugKotlin
```

Expected: all Android contracts PASS and BUILD SUCCESSFUL.

---

### Task 4: Map-first orchestration and request-details sheet

**Files:**
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt`
- Create: `android/customer-app/src/main/kotlin/com/movo/customer/send/RequestDetailsSheet.kt`
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/send/SendScreen.kt:28-115`
- Modify: `test/customer-android-contract.test.js:68-80,157-170`

**Interfaces:**
- `MapFirstSendScreen(api, profile, session, online, onTracking)` owns `SendDraft`, quote, delivery ID, idempotency keys, discovery controller, and sheet stage.
- `RequestDetailsSheet(draft, onDraftChange, onBackToDiscovery, onGetQuote)` renders fields but performs no network calls.
- `SendScreen(...)` remains the public compatibility entry point and delegates to `MapFirstSendScreen`.

- [ ] **Step 1: Write failing map-first entry contract**

Require:

```javascript
source('src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt', [
  /RiderDiscoveryController/, /DiscoverySheet/, /CustomerMap/,
  /snapshot\.canContinue\(\)/, /RequestDetailsSheet/, /invalidate/
]);
source('src/main/kotlin/com/movo/customer/send/SendScreen.kt', [/MapFirstSendScreen/]);
```

Also assert the opening path does not render the full sender/receiver field set before a discovery Continue transition.

- [ ] **Step 2: Run RED**

```bash
node --test --test-name-pattern='Send opens with rider discovery' test/customer-android-contract.test.js
```

Expected: FAIL because map-first files do not exist.

- [ ] **Step 3: Implement map-first stage orchestration**

Define:

```kotlin
private enum class SendStage { Discovery, RequestDetails, QuoteAndRider, Waiting }
```

On valid location/manual pickup:

```kotlin
controller.invalidate(pickup)
scope.launch { controller.scan(pickup, online) }
```

Continue is the only transition from Discovery to RequestDetails and is guarded by `snapshot.canContinue()`.

Changing pickup calls `controller.invalidate(newPickup)`, clears quote, and returns to Discovery.

- [ ] **Step 4: Extract request fields without changing payload semantics**

Move destination, contacts, item type, description, instructions, and payment controls to `RequestDetailsSheet`. Keep finite-coordinate and required-contact validation. Keep quote request field names exactly:

```kotlin
pickup_lat, pickup_lng, dest_lat, dest_lng, service_type
```

- [ ] **Step 5: Pass connectivity into Send**

Modify `MainActivity.kt` call to:

```kotlin
SendScreen(api, profile, session, online, onTracking = ::openTracking)
```

Transient offline discovery must render Offline without clearing customer session or draft.

- [ ] **Step 6: Run GREEN**

```bash
node --test test/customer-android-contract.test.js
cd android && ./gradlew :customer-app:testDebugUnitTest :customer-app:compileDebugKotlin
```

Expected: contracts/unit tests PASS and BUILD SUCCESSFUL.

---

### Task 5: Refresh-before-choice and exclusive rider selection sheet

**Files:**
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/send/RiderSelectionScreen.kt:21-99`
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/send/MapFirstSendScreen.kt`
- Modify: `test/customer-android-contract.test.js:68-80,104-117`

**Interfaces:**
- Change rider screen input from internal initial scan to explicit `riders: List<NearbyRider>` plus `onRefreshRiders: suspend () -> List<NearbyRider>`.
- Preserve `selectReplacement(api, existingDeliveryId, riderId, idempotencyKey)` signature.

- [ ] **Step 1: Write failing refreshed-choice contract**

Require the map-first orchestrator to call the discovery source after quote success and before rendering rider choice. Require RiderSelection to consume passed refreshed riders rather than treating opening discovery results as reserved.

Keep existing assertions for:

```text
preferred_rider_id
creation idempotency key
POST /api/deliveries only when existingDeliveryId == null
PUT /api/deliveries/:id/select-rider when existingDeliveryId != null
```

- [ ] **Step 2: Run RED**

```bash
node --test --test-name-pattern='refreshes riders after quote' test/customer-android-contract.test.js
```

Expected: FAIL because rider choice still performs its own uncoordinated initial scan.

- [ ] **Step 3: Implement quote-triggered refresh**

After quote success:

1. keep the quote and draft persisted;
2. transition to a refreshing choice state;
3. call the same pickup-keyed source;
4. render choice only with the returned current list;
5. show no-riders/conflict recovery without discarding quote.

A 409 selected-rider conflict clears only `selected`, refreshes riders, and shows `That rider is no longer available. Choose another rider.`

- [ ] **Step 4: Preserve selected creation/reselection**

Creation body remains the existing exact payload and includes one `preferred_rider_id`. Duplicate taps remain blocked by `submitting`. Replacement rotates only the replacement idempotency key after confirmed success and preserves delivery ID.

- [ ] **Step 5: Run GREEN plus backend selected-dispatch tests**

```bash
node --test test/customer-android-contract.test.js
node --test --test-name-pattern='selected dispatch|persisted selected offers|idempotency' test/customer-mobile-api.test.js
cd android && ./gradlew :customer-app:testDebugUnitTest :customer-app:compileDebugKotlin
```

Expected: all focused tests PASS and BUILD SUCCESSFUL.

---

### Task 6: MOVO visual tokens, quiet navigation, and persistence rules

**Files:**
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/MainActivity.kt:99-140`
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/model/CustomerModels.kt:31-42`
- Modify: `android/customer-app/src/main/kotlin/com/movo/customer/session/CustomerSession.kt:32-64`
- Modify: `test/customer-android-contract.test.js`

**Interfaces:**
- Persist draft, quote, delivery ID, and idempotency keys.
- Do not persist rider availability as authoritative state.
- On restore, pickup may be restored but discovery always begins stale/Scanning.

- [ ] **Step 1: Write failing persistence/visual contracts**

Require theme tokens `0xFFFCFCFA`, `0xFF086B4D`, `0xFF19A974`, `0xFF151817`, and `0xFFF5A623`. Require restore logic to rescan rather than restore a `RidersAvailable` list.

- [ ] **Step 2: Run RED**

```bash
node --test --test-name-pattern='MOVO map-first visual tokens|restored journey rescans' test/customer-android-contract.test.js
```

Expected: FAIL because tokens/rescan contract are absent.

- [ ] **Step 3: Implement theme and navigation treatment**

Centralize colors in `MovoTheme`. Keep Send bottom navigation available but lower visual emphasis than the active discovery sheet. Use sentence-case copy and exact state wording from the design spec.

- [ ] **Step 4: Implement restore semantics**

Continue persisting `SendJourney`; do not serialize current nearby rider list/count. On process restore with finite pickup, initialize discovery as Scanning and call the endpoint when online. Preserve same-delivery replacement path when `deliveryId` exists.

- [ ] **Step 5: Run GREEN and full compile**

```bash
node --test test/customer-android-contract.test.js
cd android && ./gradlew :customer-app:testDebugUnitTest :customer-app:compileDebugKotlin :customer-app:assembleDebug
```

Expected: PASS and BUILD SUCCESSFUL.

---

### Task 7: Full regression, deployment, and physical-device E2E

**Files:**
- Modify only if evidence finds a defect: files owning that defect plus a failing regression test first.
- Write evidence: `.superpowers/evidence/customer-map-first/**`
- Update report: `.superpowers/sdd/customer-android-remediation-report.md`

**Interfaces:**
- Consumes final APK and deployed PM2 backend.
- Produces reproducible automated/device evidence and exact cleanup results.

- [ ] **Step 1: Run final automated quality gate**

```bash
npm run test
npm run test:syntax
git diff --check
cd android && ./gradlew \
  :customer-app:testDebugUnitTest :customer-app:assembleDebug \
  :rider-app:testDebugUnitTest :rider-app:assembleDebug
```

Expected: all Node tests PASS, syntax/diff checks exit 0, Android BUILD SUCCESSFUL.

- [ ] **Step 2: Record APK integrity**

```bash
sha256sum android/customer-app/build/outputs/apk/debug/customer-app-debug.apk
stat android/customer-app/build/outputs/apk/debug/customer-app-debug.apk
```

Record checksum, size, and modification time in evidence.

- [ ] **Step 3: Restart and verify PM2 deployment**

```bash
pm2 restart movo --update-env
pm2 jlist
pm2 logs movo --lines 40 --nostream
```

Require `movo` status `online` and no new startup exception. Do not print credentials or tokens.

- [ ] **Step 4: Install on authorized physical device**

Use the connected authorized serial discovered by `adb devices -l`; do not guess a changed serial. Install with `adb -s <serial> install -r <apk>` and launch `com.movo.customer/.MainActivity`.

- [ ] **Step 5: Verify discovery-first pixels and behavior**

Capture screenshots/UI hierarchies for:

1. locating/manual pickup;
2. scanning pulse and disabled Continue;
3. riders found with real motorcycle markers and enabled Continue;
4. no-rider blocked state;
5. pickup change clearing old count/markers;
6. destination/details sheet;
7. refreshed quote/rider-choice sheet;
8. selected offer waiting;
9. shared tracking.

Inspect every screenshot visually at 720×1640 for map overdraw, clipping, overlap, unreadable labels, hidden OSM attribution, and bottom-sheet/nav conflicts.

- [ ] **Step 6: Verify operational lifecycle**

Use isolated QA customers and an eligible controlled rider. Confirm server records and realtime events for selected creation, exclusive offer, acceptance, sender/receiver assignment, identical rider coordinate, decline/expiry, and same-delivery replacement. Clean all QA delivery/account/event/location records and restore rider state afterward; verify zero residue.

- [ ] **Step 7: Inspect diagnostics**

Collect bounded customer logcat and PM2 logs. Fail verification on FATAL EXCEPTION, ANR, repeated socket reconnect loop, authorization error for valid participants, duplicate delivery creation, or stale old-pickup scan publication.

- [ ] **Step 8: Update evidence report**

Record exact commands, pass/fail counts, APK checksum, PM2 PID/status, device serial, screenshots, deployed lifecycle outcomes, cleanup counts, and any external device blocker. Do not claim receiver rendered-pixel completion without a real screenshot.

---

## Self-review checklist

- Every approved design state maps to Tasks 1–4.
- Refresh-before-choice and exclusive selection map to Task 5.
- Process restore without stale availability maps to Task 6.
- Original MOVO visual identity and no-Uber-copy constraint are global and covered by Tasks 3/6/7.
- Server authority, idempotency, replacement, receiver authorization, and realtime regression gates are preserved.
- No backend endpoint/schema change is required.
- No task contains a placeholder implementation step.
- Signatures are consistent: `DiscoverySnapshot`, `RiderDiscoveryController`, `NearbyRiderSource`, `MapFirstSendScreen`, and existing `selectReplacement`.
