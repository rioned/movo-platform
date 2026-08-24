package com.movo.customer.send

import com.movo.customer.model.Coordinate
import com.movo.customer.model.NearbyRider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RiderDiscoveryControllerTest {
    private val pickupA = Coordinate(-1.9441, 30.0619)
    private val pickupB = Coordinate(-1.9500, 30.0700)

    @Test
    fun valid_pickup_runs_one_scan_and_enables_continue() = runTest {
        var calls = 0
        val controller = RiderDiscoveryController {
            calls += 1
            listOf(rider())
        }

        controller.scan(pickupA, online = true)

        assertEquals(1, calls)
        assertEquals(DiscoveryPhase.Available, controller.snapshot.value.phase)
        assertEquals(pickupA, controller.snapshot.value.pickup)
        assertEquals(listOf(rider()), controller.snapshot.value.riders)
        assertTrue(controller.snapshot.value.canContinue())
    }

    @Test
    fun invalid_pickup_never_calls_source() = runTest {
        var calls = 0
        val controller = RiderDiscoveryController {
            calls += 1
            listOf(rider())
        }

        controller.scan(Coordinate(Double.NaN, 30.0619), online = true)

        assertEquals(0, calls)
        assertEquals(DiscoveryPhase.ManualPickupRequired, controller.snapshot.value.phase)
        assertEquals(null, controller.snapshot.value.pickup)
        assertTrue(controller.snapshot.value.riders.isEmpty())
    }

    @Test
    fun empty_results_block_continue() = runTest {
        val controller = RiderDiscoveryController { emptyList() }

        controller.scan(pickupA, online = true)

        assertEquals(DiscoveryPhase.NoRiders, controller.snapshot.value.phase)
        assertTrue(controller.snapshot.value.riders.isEmpty())
        assertTrue(!controller.snapshot.value.canContinue())
    }

    @Test
    fun out_of_service_area_is_a_distinct_phase_that_blocks_continue() = runTest {
        val controller = RiderDiscoveryController { throw OutOfServiceAreaException("MOVO is not currently available at this location.") }

        controller.scan(pickupA, online = true)

        assertEquals(DiscoveryPhase.OutOfServiceArea, controller.snapshot.value.phase)
        assertTrue(controller.snapshot.value.riders.isEmpty())
        assertTrue(!controller.snapshot.value.canContinue())
    }

    @Test
    fun offline_clears_current_availability_without_calling_source() = runTest {
        var calls = 0
        val controller = RiderDiscoveryController {
            calls += 1
            listOf(rider())
        }
        controller.scan(pickupA, online = true)

        controller.scan(pickupA, online = false)

        assertEquals(1, calls)
        assertEquals(DiscoveryPhase.Offline, controller.snapshot.value.phase)
        assertEquals(pickupA, controller.snapshot.value.pickup)
        assertTrue(controller.snapshot.value.riders.isEmpty())
    }

    @Test
    fun late_response_for_old_pickup_is_discarded() = runTest {
        val responseA = CompletableDeferred<List<NearbyRider>>()
        val responseB = CompletableDeferred<List<NearbyRider>>()
        val controller = RiderDiscoveryController { pickup ->
            if (pickup == pickupA) responseA.await() else responseB.await()
        }

        val scanA = launch { controller.scan(pickupA, online = true) }
        runCurrent()
        controller.invalidate(pickupB)
        val scanB = launch { controller.scan(pickupB, online = true) }
        runCurrent()

        responseA.complete(listOf(rider(id = "old-rider")))
        runCurrent()

        assertEquals(DiscoveryPhase.Scanning, controller.snapshot.value.phase)
        assertEquals(pickupB, controller.snapshot.value.pickup)
        assertTrue(controller.snapshot.value.riders.isEmpty())

        responseB.complete(listOf(rider(id = "new-rider")))
        scanA.join()
        scanB.join()
        assertEquals(listOf("new-rider"), controller.snapshot.value.riders.map { it.id })
    }

    @Test
    fun duplicate_scan_for_same_inflight_pickup_is_coalesced() = runTest {
        val calls = AtomicInteger()
        val response = CompletableDeferred<List<NearbyRider>>()
        val controller = RiderDiscoveryController {
            calls.incrementAndGet()
            response.await()
        }
        val callerCount = 8
        val executor = Executors.newFixedThreadPool(callerCount)
        executor.asCoroutineDispatcher().use { dispatcher ->
            val start = CyclicBarrier(callerCount)
            val scans = List(callerCount) {
                launch(dispatcher) {
                    start.await(5, TimeUnit.SECONDS)
                    controller.scan(pickupA, online = true)
                }
            }

            assertTrue(waitUntil { calls.get() > 0 })
            assertTrue(waitUntil { scans.count { it.isCompleted } == callerCount - 1 })
            assertEquals(1, calls.get())
            response.complete(listOf(rider()))
            scans.forEach { it.join() }
        }
        assertEquals(DiscoveryPhase.Available, controller.snapshot.value.phase)
    }

    @Test
    fun invalidate_and_new_scan_win_race_after_old_completion_check() = runTest {
        val allowOldFailure = CompletableDeferred<Unit>()
        val oldSourceStarted = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<List<NearbyRider>>()
        val messageRead = CountDownLatch(1)
        val allowMessage = CountDownLatch(1)
        val controller = RiderDiscoveryController { pickup ->
            if (pickup == pickupA) {
                oldSourceStarted.complete(Unit)
                allowOldFailure.await()
                throw object : RuntimeException() {
                    override val message: String
                        get() {
                            messageRead.countDown()
                            check(allowMessage.await(5, TimeUnit.SECONDS))
                            return "old pickup failed"
                        }
                }
            }
            newResponse.await()
        }

        val oldScan = launch(Dispatchers.Default) { controller.scan(pickupA, online = true) }
        oldSourceStarted.await()
        allowOldFailure.complete(Unit)
        assertTrue(messageRead.await(5, TimeUnit.SECONDS))

        controller.invalidate(pickupB)
        val newScan = launch(Dispatchers.Default) { controller.scan(pickupB, online = true) }
        assertTrue(waitUntil {
            controller.snapshot.value.phase == DiscoveryPhase.Scanning &&
                controller.snapshot.value.pickup == pickupB
        })
        allowMessage.countDown()
        oldScan.join()

        assertEquals(DiscoveryPhase.Scanning, controller.snapshot.value.phase)
        assertEquals(pickupB, controller.snapshot.value.pickup)
        newResponse.complete(listOf(rider(id = "new-rider")))
        newScan.join()
        assertEquals(listOf("new-rider"), controller.snapshot.value.riders.map { it.id })
    }

    @Test
    fun late_exception_for_old_pickup_preserves_new_request_ownership() = runTest {
        val oldResponse = CompletableDeferred<List<NearbyRider>>()
        val newResponse = CompletableDeferred<List<NearbyRider>>()
        val callsForNewPickup = AtomicInteger()
        val controller = RiderDiscoveryController { pickup ->
            if (pickup == pickupA) oldResponse.await()
            else {
                callsForNewPickup.incrementAndGet()
                newResponse.await()
            }
        }

        val oldScan = launch(Dispatchers.Default) { controller.scan(pickupA, online = true) }
        assertTrue(waitUntil { controller.snapshot.value.pickup == pickupA })
        controller.invalidate(pickupB)
        val newScan = launch(Dispatchers.Default) { controller.scan(pickupB, online = true) }
        assertTrue(waitUntil { callsForNewPickup.get() == 1 })

        oldResponse.completeExceptionally(IllegalStateException("old failure"))
        oldScan.join()
        controller.scan(pickupB, online = true)

        assertEquals(1, callsForNewPickup.get())
        assertEquals(DiscoveryPhase.Scanning, controller.snapshot.value.phase)
        assertEquals(pickupB, controller.snapshot.value.pickup)
        newResponse.complete(listOf(rider(id = "new-rider")))
        newScan.join()
    }

    @Test
    fun late_cancellation_for_old_pickup_preserves_new_request_ownership() = runTest {
        val oldSourceStarted = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<List<NearbyRider>>()
        val callsForNewPickup = AtomicInteger()
        val controller = RiderDiscoveryController { pickup ->
            if (pickup == pickupA) {
                oldSourceStarted.complete(Unit)
                awaitCancellation()
            } else {
                callsForNewPickup.incrementAndGet()
                newResponse.await()
            }
        }

        val oldScan = launch(Dispatchers.Default) { controller.scan(pickupA, online = true) }
        oldSourceStarted.await()
        controller.invalidate(pickupB)
        val newScan = launch(Dispatchers.Default) { controller.scan(pickupB, online = true) }
        assertTrue(waitUntil { callsForNewPickup.get() == 1 })

        oldScan.cancel(CancellationException("old request cancelled"))
        oldScan.join()
        controller.scan(pickupB, online = true)

        assertEquals(1, callsForNewPickup.get())
        assertEquals(DiscoveryPhase.Scanning, controller.snapshot.value.phase)
        assertEquals(pickupB, controller.snapshot.value.pickup)
        newResponse.complete(listOf(rider(id = "new-rider")))
        newScan.join()
    }

    @Test
    fun malformed_rider_coordinates_are_removed() = runTest {
        val controller = RiderDiscoveryController {
            listOf(
                rider(id = "valid"),
                rider(id = "nan", location = Coordinate(Double.NaN, 30.0)),
                rider(id = "out-of-range", location = Coordinate(91.0, 30.0))
            )
        }

        controller.scan(pickupA, online = true)

        assertEquals(listOf("valid"), controller.snapshot.value.riders.map { it.id })
        assertEquals(DiscoveryPhase.Available, controller.snapshot.value.phase)
    }

    @Test
    fun production_parser_rejects_missing_coordinates() {
        val riders = parseProductionRiders(mapOf("id" to "missing"))

        assertTrue(riders.isEmpty())
    }

    @Test
    fun production_parser_rejects_nonnumeric_coordinates() {
        val riders = parseProductionRiders(riderJson("nonnumeric", latitude = "north", longitude = "east"))

        assertTrue(riders.isEmpty())
    }

    @Test
    fun production_parser_rejects_nonfinite_coordinates() {
        val riders = parseProductionRiders(
            riderJson("nan", latitude = "NaN", longitude = 30.0),
            riderJson("infinity", latitude = -1.9, longitude = "Infinity")
        )

        assertTrue(riders.isEmpty())
    }

    @Test
    fun production_parser_rejects_out_of_range_coordinates() {
        val riders = parseProductionRiders(
            riderJson("latitude", latitude = 90.1, longitude = 30.0),
            riderJson("longitude", latitude = -1.9, longitude = -180.1)
        )

        assertTrue(riders.isEmpty())
    }

    @Test
    fun production_parser_accepts_and_maps_valid_objects() {
        val riders = parseProductionRiders(riderJson("valid", latitude = -1.943, longitude = 30.0625))

        assertEquals(listOf("valid"), riders)
    }

    @Test
    fun source_exception_produces_bounded_error_and_no_stale_riders() = runTest {
        var shouldFail = false
        val controller = RiderDiscoveryController {
            if (shouldFail) throw IllegalStateException("x".repeat(500))
            listOf(rider())
        }
        controller.scan(pickupA, online = true)
        shouldFail = true

        controller.scan(pickupA, online = true)

        val phase = controller.snapshot.value.phase
        assertTrue(phase is DiscoveryPhase.Error)
        assertEquals(240, phase.message.length)
        assertTrue(controller.snapshot.value.riders.isEmpty())
        assertEquals(pickupA, controller.snapshot.value.pickup)
    }

    private fun rider(
        id: String = "rider-1",
        location: Coordinate = Coordinate(-1.9430, 30.0625)
    ) = NearbyRider(
        id = id,
        name = "Aline",
        rating = 4.9,
        distanceKm = 0.8,
        location = location,
        locationUpdatedAt = "2026-07-29T12:00:00Z",
        vehicleMake = "TVS",
        vehicleModel = "HLX",
        vehiclePlate = "RAA 001A",
        vehicleColor = "Black"
    )

    private fun riderJson(id: String, latitude: Any, longitude: Any): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to "Aline",
        "latitude" to latitude,
        "longitude" to longitude
    )

    private fun parseProductionRiders(vararg riders: Map<String, Any?>): List<String> =
        mapValidNearbyRiders(
            riders = riders.asIterable(),
            valueAt = { rider, name -> rider[name] },
            mapper = { rider -> rider.getValue("id").toString() }
        )

    private suspend fun waitUntil(predicate: () -> Boolean): Boolean {
        repeat(10_000) {
            if (predicate()) return true
            kotlinx.coroutines.yield()
        }
        return predicate()
    }
}
