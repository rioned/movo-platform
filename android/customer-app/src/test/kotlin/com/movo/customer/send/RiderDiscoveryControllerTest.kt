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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NearbyRiderSource] reports which riders MOVO can offer a pickup to. The customer
 * chooses one, so these tests exercise the controller's scan/coalesce/race-safety
 * logic against a real rider list — and cover how a choice survives a rescan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RiderDiscoveryControllerTest {
    private val pickupA = Coordinate(-1.9441, 30.0619)
    private val pickupB = Coordinate(-1.9500, 30.0700)

    /** A stand-in rider list, sized to whatever a given test needs to observe. */
    private fun riders(count: Int): List<NearbyRider> =
        (1..count).map { index ->
            NearbyRider(
                id = "rider-$index",
                plate = "RAZ${index.toString().padStart(3, '0')}C",
                rating = 4.5,
                ratingCount = index,
                distanceKm = index.toDouble(),
                etaMinutes = index,
                coordinate = Coordinate(-1.9441 + index * 0.001, 30.0619)
            )
        }

    @Test
    fun valid_pickup_runs_one_scan_and_enables_continue() = runTest {
        var calls = 0
        val controller = RiderDiscoveryController {
            calls += 1
            riders(2)
        }

        controller.scan(pickupA, online = true)

        assertEquals(1, calls)
        assertEquals(DiscoveryPhase.Available, controller.snapshot.value.phase)
        assertEquals(pickupA, controller.snapshot.value.pickup)
        assertEquals(2, controller.snapshot.value.riderCount)
        assertEquals(listOf("rider-1", "rider-2"), controller.snapshot.value.riders.map { it.id })
        assertTrue(controller.snapshot.value.canContinue())
    }

    @Test
    fun invalid_pickup_never_calls_source() = runTest {
        var calls = 0
        val controller = RiderDiscoveryController {
            calls += 1
            riders(2)
        }

        controller.scan(Coordinate(Double.NaN, 30.0619), online = true)

        assertEquals(0, calls)
        assertEquals(DiscoveryPhase.ManualPickupRequired, controller.snapshot.value.phase)
        assertEquals(null, controller.snapshot.value.pickup)
        assertEquals(0, controller.snapshot.value.riderCount)
    }

    @Test
    fun empty_results_block_continue() = runTest {
        val controller = RiderDiscoveryController { emptyList() }

        controller.scan(pickupA, online = true)

        assertEquals(DiscoveryPhase.NoRiders, controller.snapshot.value.phase)
        assertEquals(0, controller.snapshot.value.riderCount)
        assertTrue(controller.snapshot.value.riders.isEmpty())
        assertTrue(!controller.snapshot.value.canContinue())
    }

    @Test
    fun out_of_service_area_is_a_distinct_phase_that_blocks_continue() = runTest {
        val controller = RiderDiscoveryController { throw OutOfServiceAreaException("MOVO is not currently available at this location.") }

        controller.scan(pickupA, online = true)

        assertEquals(DiscoveryPhase.OutOfServiceArea, controller.snapshot.value.phase)
        assertEquals(0, controller.snapshot.value.riderCount)
        assertTrue(!controller.snapshot.value.canContinue())
    }

    @Test
    fun offline_clears_current_availability_without_calling_source() = runTest {
        var calls = 0
        val controller = RiderDiscoveryController {
            calls += 1
            riders(2)
        }
        controller.scan(pickupA, online = true)

        controller.scan(pickupA, online = false)

        assertEquals(1, calls)
        assertEquals(DiscoveryPhase.Offline, controller.snapshot.value.phase)
        assertEquals(pickupA, controller.snapshot.value.pickup)
        assertEquals(0, controller.snapshot.value.riderCount)
    }

    @Test
    fun choosing_a_rider_records_the_choice_and_any_rider_clears_it() = runTest {
        val controller = RiderDiscoveryController { riders(3) }
        controller.scan(pickupA, online = true)

        controller.select("rider-2")

        assertEquals("rider-2", controller.snapshot.value.selectedRiderId)
        assertEquals("RAZ002C", controller.snapshot.value.selectedRider?.label)

        controller.select(null)

        assertNull(controller.snapshot.value.selectedRiderId)
        assertNull(controller.snapshot.value.selectedRider)
    }

    @Test
    fun selecting_a_rider_who_is_not_in_the_list_is_ignored() = runTest {
        val controller = RiderDiscoveryController { riders(2) }
        controller.scan(pickupA, online = true)

        controller.select("rider-999")

        assertNull(controller.snapshot.value.selectedRiderId, "a choice must never point at a rider the customer was not shown")
    }

    @Test
    fun a_rescan_keeps_the_choice_while_that_rider_is_still_around() = runTest {
        var next = riders(2)
        val controller = RiderDiscoveryController { next }
        controller.scan(pickupA, online = true)
        controller.select("rider-2")

        controller.scan(pickupB, online = true)

        assertEquals("rider-2", controller.snapshot.value.selectedRiderId, "the chosen rider travels with the customer, not the pickup")
    }

    @Test
    fun a_rider_who_drops_out_of_the_next_scan_releases_the_choice() = runTest {
        var next = riders(2)
        val controller = RiderDiscoveryController { next }
        controller.scan(pickupA, online = true)
        controller.select("rider-2")

        // rider-2 went offline between scans: the stale choice must not survive and be
        // sent as a preference for a rider who cannot be offered the job.
        next = riders(1)
        controller.scan(pickupA, online = true)

        assertNull(controller.snapshot.value.selectedRiderId)
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

        responseA.complete(riders(1))
        runCurrent()

        assertEquals(DiscoveryPhase.Scanning, controller.snapshot.value.phase)
        assertEquals(pickupB, controller.snapshot.value.pickup)
        assertEquals(0, controller.snapshot.value.riderCount)

        responseB.complete(riders(4))
        scanA.join()
        scanB.join()
        assertEquals(4, controller.snapshot.value.riderCount)
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
            response.complete(riders(1))
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
        newResponse.complete(riders(3))
        newScan.join()
        assertEquals(3, controller.snapshot.value.riderCount)
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
        newResponse.complete(riders(3))
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
        newResponse.complete(riders(3))
        newScan.join()
    }

    @Test
    fun source_exception_produces_bounded_error_and_resets_count() = runTest {
        var shouldFail = false
        val controller = RiderDiscoveryController {
            if (shouldFail) throw IllegalStateException("x".repeat(500))
            riders(2)
        }
        controller.scan(pickupA, online = true)
        shouldFail = true

        controller.scan(pickupA, online = true)

        val phase = controller.snapshot.value.phase
        assertTrue(phase is DiscoveryPhase.Error)
        assertEquals(240, phase.message.length)
        assertEquals(0, controller.snapshot.value.riderCount)
        assertTrue(controller.snapshot.value.riders.isEmpty())
        assertEquals(pickupA, controller.snapshot.value.pickup)
    }

    private suspend fun waitUntil(predicate: () -> Boolean): Boolean {
        repeat(10_000) {
            if (predicate()) return true
            kotlinx.coroutines.yield()
        }
        return predicate()
    }
}
