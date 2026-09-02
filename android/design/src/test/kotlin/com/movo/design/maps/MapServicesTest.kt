package com.movo.design.maps

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapServicesTest {

    @Test
    fun map_provider_only_recognizes_osm_case_insensitively_and_otherwise_defaults_to_sandbox() {
        assertEquals(MapProvider.OSM, MapProvider.from("osm"))
        assertEquals(MapProvider.OSM, MapProvider.from("OSM"))
        assertEquals(MapProvider.SANDBOX, MapProvider.from("sandbox"))
        assertEquals(MapProvider.SANDBOX, MapProvider.from(null))
        assertEquals(MapProvider.SANDBOX, MapProvider.from("mtn-momo"))
    }

    @Test
    fun straight_line_routing_never_touches_the_network_and_just_connects_the_two_points() = runTest {
        val from = LatLng(-1.9441, 30.0619)
        val to = LatLng(-1.9367, 30.0867)
        val route = StraightLineRoutingService.route(from, to)
        assertEquals(listOf(from, to), route.points)
        assertNull(route.distanceMeters)
        assertNull(route.durationSeconds)
    }

    @Test
    fun sandbox_geocoding_never_resolves_anything() = runTest {
        assertTrue(SandboxGeocodingService.search("Kigali").isEmpty())
        assertNull(SandboxGeocodingService.reverseGeocode(LatLng(-1.9441, 30.0619)))
    }

    @Test
    fun map_services_resolves_sandbox_implementations_for_sandbox_and_osm_implementations_for_osm() {
        assertEquals(StraightLineRoutingService, MapServices.routing(MapProvider.SANDBOX))
        assertEquals(SandboxGeocodingService, MapServices.geocoding(MapProvider.SANDBOX))
        assertTrue(MapServices.routing(MapProvider.OSM) is OsrmRoutingService)
        assertTrue(MapServices.geocoding(MapProvider.OSM) is NominatimGeocodingService)
    }

    @Test
    fun osrm_routing_falls_back_to_the_straight_line_when_the_network_call_fails() = runTest {
        // A broken fetch (timeout, DNS failure, rate limit — anything) must be
        // swallowed and turned into the same two-point path StraightLineRoutingService
        // would produce, never propagated as an exception: a flaky third-party call
        // must degrade the map, never break it.
        val from = LatLng(-1.9441, 30.0619)
        val to = LatLng(-1.9367, 30.0867)
        val brokenFetch: (java.net.URL) -> String = { throw java.io.IOException("simulated network failure") }
        val route = OsrmRoutingService(fallback = StraightLineRoutingService, fetch = brokenFetch).route(from, to)
        assertEquals(listOf(from, to), route.points)
    }

    @Test
    fun osrm_routing_parses_a_real_geometry_response() = runTest {
        val geojson = """
            {"routes":[{"geometry":{"coordinates":[[30.0619,-1.9441],[30.07,-1.94],[30.0867,-1.9367]]},"distance":2500.5,"duration":420.0}]}
        """.trimIndent()
        val route = OsrmRoutingService(fetch = { geojson }).route(LatLng(-1.9441, 30.0619), LatLng(-1.9367, 30.0867))
        assertEquals(3, route.points.size)
        assertEquals(LatLng(-1.9441, 30.0619), route.points.first())
        assertEquals(LatLng(-1.9367, 30.0867), route.points.last())
        assertEquals(2500.5, route.distanceMeters)
        assertEquals(420.0, route.durationSeconds)
    }

    @Test
    fun map_services_prefers_maptiler_geocoding_over_nominatim_when_a_key_is_configured() {
        assertTrue(MapServices.geocoding(MapProvider.OSM) is NominatimGeocodingService)
        assertTrue(MapServices.geocoding(MapProvider.OSM, maptilerApiKey = "") is NominatimGeocodingService)
        assertTrue(MapServices.geocoding(MapProvider.OSM, maptilerApiKey = "test-key") is MapTilerGeocodingService)
    }

    @Test
    fun maptiler_geocoding_parses_a_real_feature_collection_response_as_lng_lat() = runTest {
        // MapTiler/GeoJSON coordinates are [lng, lat] — the reverse of this file's LatLng field order.
        val geojson = """
            {"features":[{"place_name":"Kigali, Rwanda","geometry":{"coordinates":[30.0619,-1.9441]}}]}
        """.trimIndent()
        val results = MapTilerGeocodingService(apiKey = "test-key", fetch = { geojson }).search("Kigali")
        assertEquals(1, results.size)
        assertEquals(-1.9441, results.first().latitude)
        assertEquals(30.0619, results.first().longitude)
        assertEquals("Kigali, Rwanda", results.first().displayName)
    }

    @Test
    fun maptiler_geocoding_falls_back_on_failure() = runTest {
        // A broken fetch (quota exceeded, timeout, DNS failure) must be swallowed
        // and handed off to the fallback service, never propagated as an exception —
        // the same contract osrm_routing_falls_back_to_the_straight_line_when_the_network_call_fails
        // enforces for routing.
        val brokenFetch: (java.net.URL) -> String = { throw java.io.IOException("simulated network failure") }
        val service = MapTilerGeocodingService(apiKey = "test-key", fallback = SandboxGeocodingService, fetch = brokenFetch)
        assertTrue(service.search("Kigali").isEmpty())
        assertNull(service.reverseGeocode(LatLng(-1.9441, 30.0619)))
    }
}
