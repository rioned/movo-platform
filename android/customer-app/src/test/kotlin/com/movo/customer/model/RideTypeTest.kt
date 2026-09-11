package com.movo.customer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideTypeTest {
    private fun quote(key: String, name: String = "Provider category", description: String = "Provider description") = RideType(
        id = "provider-$key",
        key = key,
        name = name,
        description = description,
        capacity = 1,
        fare = 1500.0,
        distanceKm = 3.0,
        estimatedMinutes = 12,
        currency = "RWF"
    )

    @Test
    fun genuine_motorcycle_keys_are_accepted_case_insensitively() {
        listOf("moto", "motorcycle", "MOTO", "MOTORCYCLE", "Motorcycle").forEach { key ->
            assertTrue(quote(key).isMotorcycle, "Expected motorcycle category: $key")
        }
    }

    @Test
    fun car_and_unknown_keys_are_excluded_even_with_motorcycle_marketing_copy() {
        listOf("economy", "standard", "comfort", "car", "", "unknown", "motorcycle-car").forEach { key ->
            assertFalse(quote(key, name = "MOVO Moto", description = "Motorcycle rides in Kigali").isMotorcycle, "Must not relabel category: $key")
        }
    }

    @Test
    fun filtering_preserves_provider_ids_fares_and_currencies_without_fabricating_options() {
        val motorcycle = quote("moto").copy(id = "server-moto-id", fare = 2400.0, currency = "USD")
        val mixed = listOf(quote("economy"), motorcycle, quote("standard"), quote("comfort"))
        assertEquals(listOf(motorcycle), mixed.filter { it.isMotorcycle })
        assertTrue(mixed.filterNot { it === motorcycle }.filter { it.isMotorcycle }.isEmpty())
        assertTrue(emptyList<RideType>().filter { it.isMotorcycle }.isEmpty())
    }
}
