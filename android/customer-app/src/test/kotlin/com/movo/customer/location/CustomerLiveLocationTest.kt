package com.movo.customer.location

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerLiveLocationTest {
    @Test fun uploadFailureDoesNotEndTracking() = runTest {
        var attempts = 0
        val errors = mutableListOf<String>()
        val received = mutableListOf<CustomerFix>()
        val fix = CustomerFix(-1.94, 30.1, null, 1_790_000_000_123)
        CustomerLiveLocation(
            upload = { if (++attempts == 1) error("Offline") else CustomerZone(null, null, false) },
            onLocation = { f, _ -> received += f }, onError = { errors += it }
        ).track(flowOf(fix, fix.copy(recordedAtMillis = fix.recordedAtMillis + 1000)))
        assertEquals(2, attempts)
        assertEquals(1, received.size)
        assertEquals(listOf("Offline"), errors)
    }

    @Test fun foregroundSessionPublishesOriginalFixAndServerZone() = runTest {
        val fixes = MutableSharedFlow<CustomerFix>()
        val sent = mutableListOf<CustomerFix>()
        val zones = mutableListOf<CustomerZone>()
        val session = CustomerLiveLocation(
            upload = { sent += it; CustomerZone("zone-a", "Kimironko", true) },
            onLocation = { _, zone -> zones += zone }, onError = { fail(it) }
        )
        val job = launch { session.track(fixes) }
        runCurrent()
        val fix = CustomerFix(-1.94, 30.1, 12.0, 1_790_000_000_123)
        fixes.emit(fix)
        runCurrent()
        assertEquals(listOf(fix), sent)
        assertEquals("Kimironko", zones.single().name)
        job.cancelAndJoin()
        fixes.emit(fix.copy(recordedAtMillis = fix.recordedAtMillis + 1000))
        assertEquals(1, sent.size, "Background/disposal stops uploads")
    }
}
