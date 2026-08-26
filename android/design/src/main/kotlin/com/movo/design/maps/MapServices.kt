package com.movo.design.maps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Map/geocoding/routing abstraction (spec §63).
 *
 * Both apps used to call osmdroid and a hardcoded OSRM URL directly wherever a
 * route or an address lookup was needed. That worked, but left `MAP_PROVIDER`
 * (see the server's `GET /api/config`) with nothing to actually switch: there was
 * no seam where "sandbox" could mean anything different from "osm" in the client.
 *
 * The distinction this abstraction makes real: [MapProvider.SANDBOX] never makes a
 * third-party network call (useful for CI, screenshots, demos, and respecting the
 * rate limits of OSRM's/Nominatim's shared public instances during development),
 * while [MapProvider.OSM] performs real routing/geocoding against those public
 * OpenStreetMap-ecosystem services. Map *tile rendering* itself is out of scope
 * here — both apps render tiles via osmdroid regardless of provider, since no
 * second tile backend is integrated; that would be the next seam to add if MOVO
 * ever needs one, not something worth faking today.
 */
enum class MapProvider {
    SANDBOX, OSM;

    companion object {
        fun from(value: String?): MapProvider = if (value.equals("osm", ignoreCase = true)) OSM else SANDBOX
    }
}

/** A plain lat/lng pair, independent of any map SDK's own point type (e.g. osmdroid's `GeoPoint`). */
data class LatLng(val latitude: Double, val longitude: Double)

/** A path between two points, with distance/duration when the provider reports them. */
data class RouteResult(val points: List<LatLng>, val distanceMeters: Double?, val durationSeconds: Double?)

/**
 * Turns two points into a path to draw on the map. Abstracted so a map screen
 * never calls a routing backend directly — swapping providers, or falling back
 * when the network is unavailable, means swapping the implementation, not
 * touching every call site.
 */
interface RoutingService {
    suspend fun route(from: LatLng, to: LatLng): RouteResult
}

/** A straight line between the two points — always available, no network required. */
object StraightLineRoutingService : RoutingService {
    override suspend fun route(from: LatLng, to: LatLng) =
        RouteResult(points = listOf(from, to), distanceMeters = null, durationSeconds = null)
}

/**
 * Real road routing via OSRM's public demo server — the same one the admin
 * portal's live map already calls for rider routes (`public/admin/index.html`'s
 * `addMapRoute`). No API key required, but it's a shared public instance:
 * acceptable for pilot volume, worth self-hosting before real scale. Falls back
 * to a straight line on any failure (timeout, no route found, rate limit) so a
 * flaky third-party call degrades the map, never breaks it.
 */
class OsrmRoutingService(
    private val fallback: RoutingService = StraightLineRoutingService,
    private val fetch: (URL) -> String = ::httpGet
) : RoutingService {
    override suspend fun route(from: LatLng, to: LatLng): RouteResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://router.project-osrm.org/route/v1/driving/" +
                    "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                    "?overview=full&geometries=geojson"
            )
            val body = fetch(url)
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = List(coordinates.length()) { index ->
                val pair = coordinates.getJSONArray(index)
                LatLng(latitude = pair.getDouble(1), longitude = pair.getDouble(0))
            }
            require(points.size > 1) { "OSRM returned no usable route geometry" }
            RouteResult(
                points = points,
                distanceMeters = route.optDouble("distance").takeIf { !it.isNaN() },
                durationSeconds = route.optDouble("duration").takeIf { !it.isNaN() }
            )
        }.getOrElse { fallback.route(from, to) }
    }
}

/** A geocoding match: a coordinate plus the human-readable address it resolves to. */
data class GeocodeResult(val latitude: Double, val longitude: Double, val displayName: String)

/** Forward (address to coordinate) and reverse (coordinate to address) geocoding. */
interface GeocodingService {
    suspend fun search(query: String): List<GeocodeResult>
    suspend fun reverseGeocode(point: LatLng): String?
}

/** No network lookups — used for [MapProvider.SANDBOX] and as a safe default before config loads. */
object SandboxGeocodingService : GeocodingService {
    override suspend fun search(query: String): List<GeocodeResult> = emptyList()
    override suspend fun reverseGeocode(point: LatLng): String? = null
}

/**
 * OpenStreetMap's Nominatim — free, no API key, but rate-limited and asks callers
 * to identify themselves; pass a real contact address in production rather than
 * the placeholder default, and self-host Nominatim before any real query volume
 * (see https://operations.osmfoundation.org/policies/nominatim/).
 */
class NominatimGeocodingService(
    private val contactEmail: String = "support@movo.example.com",
    private val fetch: (URL) -> String = ::httpGet
) : GeocodingService {
    override suspend fun search(query: String): List<GeocodeResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5&email=$contactEmail")
            val results = JSONArray(fetch(url))
            List(results.length()) { index ->
                val entry = results.getJSONObject(index)
                GeocodeResult(entry.getDouble("lat"), entry.getDouble("lon"), entry.optString("display_name"))
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun reverseGeocode(point: LatLng): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://nominatim.openstreetmap.org/reverse?lat=${point.latitude}&lon=${point.longitude}&format=json&email=$contactEmail")
            JSONObject(fetch(url)).optString("display_name").takeIf(String::isNotBlank)
        }.getOrNull()
    }
}

/** Shared plain-HTTP GET used by the real routing/geocoding implementations above. */
private fun httpGet(url: URL): String {
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
        setRequestProperty("User-Agent", "MOVO/1.0")
    }
    return try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/**
 * Resolves the [RoutingService]/[GeocodingService] pair for the active [MapProvider]
 * — this is the abstraction call sites actually use, so a screen depends on "the
 * configured map provider" rather than importing a concrete OSRM/Nominatim class.
 */
object MapServices {
    fun routing(provider: MapProvider): RoutingService = when (provider) {
        MapProvider.OSM -> OsrmRoutingService()
        MapProvider.SANDBOX -> StraightLineRoutingService
    }

    fun geocoding(provider: MapProvider): GeocodingService = when (provider) {
        MapProvider.OSM -> NominatimGeocodingService()
        MapProvider.SANDBOX -> SandboxGeocodingService
    }
}
