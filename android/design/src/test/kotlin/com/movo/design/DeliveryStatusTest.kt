package com.movo.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryStageTest {

    @Test
    fun `every platform delivery status maps to a stage`() {
        val statuses = listOf(
            "scheduled", "created", "searching", "awaiting_rider_selection", "assigned",
            "going_pickup", "arrived_pickup", "picked_up", "in_transit", "arrived_dest",
            "delivered", "cancelled", "failed"
        )
        statuses.forEach { status ->
            assertEquals(status, MovoDeliveryStage.from(status).apiValue, "status $status must have its own stage")
        }
    }

    @Test
    fun `an unknown status degrades instead of crashing the screen`() {
        assertEquals(MovoDeliveryStage.Created, MovoDeliveryStage.from("teleported"))
        assertEquals(MovoDeliveryStage.Created, MovoDeliveryStage.from(null))
    }

    @Test
    fun `terminal stages are the three the platform never leaves`() {
        val terminal = MovoDeliveryStage.entries.filter { it.isTerminal }.map { it.apiValue }.toSet()
        assertEquals(setOf("delivered", "cancelled", "failed"), terminal)
        assertTrue(MovoDeliveryStage.InTransit.isActive)
        assertFalse(MovoDeliveryStage.Delivered.isActive)
    }

    @Test
    fun `progress never runs backwards through the lifecycle`() {
        val order = listOf(
            MovoDeliveryStage.Created, MovoDeliveryStage.Searching, MovoDeliveryStage.Assigned,
            MovoDeliveryStage.GoingPickup, MovoDeliveryStage.PickedUp, MovoDeliveryStage.InTransit,
            MovoDeliveryStage.Delivered
        )
        val steps = order.map { it.trackedStep }
        assertEquals(steps.sorted(), steps, "each stage must sit at or after the previous one")
        assertTrue(steps.last() < MovoDeliveryStage.trackedSteps.size)
    }

    @Test
    fun `both apps describe the same stage in their own voice`() {
        assertEquals("Rider heading to pickup", MovoDeliveryStage.GoingPickup.customerLabel)
        assertEquals("Heading to pickup", MovoDeliveryStage.GoingPickup.riderLabel)
    }
}

class FormattingTest {

    @Test
    fun `francs are grouped in thousands with the currency code`() {
        assertEquals("1,500 RWF", formatRwf(1500))
        assertEquals("12,500 RWF", formatRwf(12_500.4))
        assertEquals("950 RWF", formatRwf(950.0))
        assertEquals("0 RWF", formatRwf(0))
        assertEquals("—", formatRwf(null))
    }

    @Test
    fun `short distances are shown in metres and longer ones in kilometres`() {
        assertEquals("400 m", formatDistance(0.4))
        assertEquals("5.2 km", formatDistance(5.24))
        assertEquals("—", formatDistance(null))
    }

    @Test
    fun `durations read naturally past an hour`() {
        assertEquals("45 min", formatMinutes(45))
        assertEquals("1 h", formatMinutes(60))
        assertEquals("1 h 25 min", formatMinutes(85))
        assertEquals("—", formatMinutes(null))
    }

    @Test
    fun `service labels stay human`() {
        assertEquals("Parcel", serviceLabel("parcel"))
        assertEquals("Document", serviceLabel("document"))
        assertEquals("Parcel", serviceLabel(null))
    }
}

class TimestampTest {

    @Test
    fun `both platform timestamp shapes are parsed instead of shown raw`() {
        // SQLite UTC and ISO-with-zone both used to leak to the screen unformatted.
        for (value in listOf("2026-07-30 09:15:00", "2026-07-30T09:15:00Z", "2026-07-30T09:15:00.737Z")) {
            val rendered = formatTimestamp(value)
            assertFalse(rendered.contains("T"), "$value must not render as an ISO string: $rendered")
            assertTrue(rendered.contains("09:15") || rendered.contains("11:15") || Regex("\\d{2}:\\d{2}").containsMatchIn(rendered))
        }
    }

    @Test
    fun `unusable timestamps degrade to the original text rather than crashing`() {
        assertEquals("—", formatTimestamp(null))
        assertEquals("—", formatTimestamp("  "))
        assertEquals("not-a-date", formatTimestamp("not-a-date"))
    }

    @Test
    fun `counts read as sentences`() {
        assertEquals("1 delivery", plural(1, "delivery", "deliveries"))
        assertEquals("3 deliveries", plural(3, "delivery", "deliveries"))
        assertEquals("0 riders", plural(0, "rider"))
        assertEquals("1 rider", plural(1, "rider"))
    }
}
