package com.movo.rider

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.movo.rider.network.RiderApi
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant

/**
 * Shares the rider's position while they are online, so customers see a live map
 * and dispatch can match nearby work (spec §13.6). The notification states plainly
 * that location is being shared — riders must never be tracked silently.
 *
 * Tracking is tiered: a rider mid-delivery is polled tightly so the customer's live
 * map stays accurate, while a rider who is merely available-and-idle is polled much
 * less often to save battery — dispatch only needs to know roughly where they are
 * until an offer is on the table. The server hands down the exact thresholds on
 * every location PUT (and on the initial /rider/home fetch) so ops can retune
 * cadence without a client release; the constants below are just the pre-connection
 * fallback for the very first fix.
 */
class RiderLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentRequest: com.google.android.gms.tasks.CancellationTokenSource? = null
    private lateinit var client: FusedLocationProviderClient
    private var lastSent: Location? = null
    private var lastAcceptedElapsedMs = 0L
    private var heartbeat: Job? = null
    private var hasActiveWork = false

    private var intervalMs = IDLE_INTERVAL_MS
    private var minDistanceM = DEFAULT_MIN_DISTANCE_M
    private var minAccuracyM = DEFAULT_MIN_ACCURACY_M

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::maybeSend)
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        hasActiveWork = intent?.getBooleanExtra(EXTRA_ACTIVE_WORK, hasActiveWork) ?: hasActiveWork
        intervalMs = if (hasActiveWork) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        requestUpdates()
        primeWithLastKnownLocation()
        acquireFreshLocation()
        startHeartbeat()
        return START_STICKY
    }

    private fun requestUpdates() {
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .setMinUpdateDistanceMeters(0f)
                .build(),
            callback,
            mainLooper
        )
    }

    /**
     * Publishes the OS's last known fix immediately on going online, instead of
     * waiting for the first fresh callback. A cold GPS lock can take a minute or
     * more; without this the rider is online but carries a stale (or absent)
     * position on the backend for that whole window, which is exactly the state
     * that makes dispatch skip them and the customer see no riders at all.
     */
    private fun primeWithLastKnownLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            client.lastLocation.addOnSuccessListener { location ->
                if (location != null && lastSent == null) maybeSend(location, force = true)
            }
        }
    }

    /**
     * Drops fixes too imprecise to trust outright, then dedupes ones that haven't
     * moved far enough from the last accepted point — a rider standing still with
     * a jittery GPS lock shouldn't spam the server (or the customer's live map)
     * with no-op updates. `force` (the heartbeat) bypasses both filters to resend
     * the last known-good fix so a stationary rider doesn't go stale.
     *
     * A coarse fix is never dropped outright when MOVO has nothing better: indoors
     * or under Kigali cloud cover the first fixes come from the network provider at
     * 100-500 m, and discarding those made the rider invisible to dispatch for as
     * long as GPS took to lock — the customer saw "no riders nearby" while the
     * rider's own app showed them online. A low-confidence position that reaches
     * the backend still resolves to the right zone; silence resolves to nothing.
     */
    private fun maybeSend(location: Location, force: Boolean = false) {
        if (!isFresh(location)) return
        if (!force && !isAcceptable(location)) return
        val last = lastSent
        if (!force && !RiderGpsFreshness.shouldPublish(
                location.elapsedRealtimeNanos / 1_000_000, last?.elapsedRealtimeNanos?.div(1_000_000),
                last?.let { location.distanceTo(it) } ?: 0f, minDistanceM, intervalMs
            )) return
        lastSent = location
        lastAcceptedElapsedMs = location.elapsedRealtimeNanos / 1_000_000
        sendLocation(location)
    }

    /**
     * Accuracy gate with a fallback: a fix inside [minAccuracyM] is always good, and
     * a coarser one is accepted anyway once [COARSE_FIX_GRACE_MS] has passed since
     * the last accepted fix (or since the service started with none at all), as long
     * as it is inside [MAX_USABLE_ACCURACY_M]. That keeps precision high when GPS is
     * healthy without ever letting the rider silently fall out of dispatch.
     */
    private fun isFresh(location: Location): Boolean = RiderGpsFreshness.isFresh(
        location.time, location.elapsedRealtimeNanos / 1_000_000,
        System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()
    )

    private fun isAcceptable(location: Location): Boolean {
        if (!location.hasAccuracy()) return true
        if (location.accuracy <= minAccuracyM) return true
        if (location.accuracy > MAX_USABLE_ACCURACY_M) return false
        val since = android.os.SystemClock.elapsedRealtime() - lastAcceptedElapsedMs
        return lastSent == null || since >= COARSE_FIX_GRACE_MS
    }

    /**
     * Re-posts the last fix on a timer scaled to the same tier as live updates.
     * If the OS throttles updates for a stationary phone, MOVO still sees this
     * rider as live and keeps offering them work.
     *
     * When no fix has been accepted yet the heartbeat re-primes from the OS's last
     * known location rather than idling: otherwise a rider whose first fixes were
     * all rejected would never send anything at all, and the loop that was meant to
     * keep them visible would be the thing keeping them invisible.
     */
    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(intervalMs * HEARTBEAT_MULTIPLIER)
                val last = lastSent
                if (last != null && isFresh(last)) maybeSend(last, force = true)
                else primeWithLastKnownLocation()
                acquireFreshLocation()
            }
        }
    }

    private fun sendLocation(location: Location) = scope.launch {
        runCatching {
            val body = JSONObject().put("lat", location.latitude).put("lng", location.longitude)
                .put("recorded_at", Instant.ofEpochMilli(location.time).toString())
            if (location.hasAccuracy()) body.put("accuracy", location.accuracy)
            RiderApi(applicationContext).put("/api/rider/location", body)
        }.onSuccess { response ->
            response.optJSONObject("tracking")?.let(::applyTracking)
        }
    }

    private fun acquireFreshLocation() {
        if (currentRequest != null) return
        val token = com.google.android.gms.tasks.CancellationTokenSource()
        currentRequest = token
        runCatching {
            client.getCurrentLocation(
                CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0).setDurationMillis(15_000).build(), token.token
            ).addOnSuccessListener { location ->
                if (scope.isActive && location != null) maybeSend(location)
            }.addOnCompleteListener { currentRequest = null }
        }.onFailure { currentRequest = null }
    }

    /** Adopts the cadence the backend says this rider should be polled at right now. */
    private fun applyTracking(tracking: JSONObject) {
        val newInterval = tracking.optLong("interval_ms", intervalMs).coerceAtLeast(MIN_ALLOWED_INTERVAL_MS)
        val newDistance = tracking.optDouble("min_distance_m", minDistanceM.toDouble()).toFloat()
        val newAccuracy = tracking.optDouble("min_accuracy_m", minAccuracyM.toDouble()).toFloat()
        val changed = newInterval != intervalMs || newDistance != minDistanceM
        intervalMs = newInterval
        minDistanceM = newDistance
        minAccuracyM = newAccuracy
        if (changed) {
            requestUpdates()
            startHeartbeat()
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MOVO Rider — you are online")
            .setContentText("Sharing your location so customers can track their delivery")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        currentRequest?.cancel()
        client.removeLocationUpdates(callback)
        heartbeat?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Rider location sharing", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while you are online and sharing your location with MOVO"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        /** Pass true when the rider has an active delivery/ride assigned. */
        const val EXTRA_ACTIVE_WORK = "active_work"

        private const val CHANNEL_ID = "movo-rider-location"
        private const val NOTIFICATION_ID = 44

        // Pre-connection fallbacks only — the server's response to the first location
        // PUT (or the /rider/home fetch that runs before this service starts) overrides
        // these via applyTracking().
        private const val ACTIVE_INTERVAL_MS = 8_000L
        private const val IDLE_INTERVAL_MS = 30_000L
        private const val MIN_ALLOWED_INTERVAL_MS = 3_000L
        private const val DEFAULT_MIN_DISTANCE_M = 25f
        private const val DEFAULT_MIN_ACCURACY_M = 50f
        private const val HEARTBEAT_MULTIPLIER = 2L

        /**
         * Upper bound on a fix MOVO will still dispatch against. Network-provider
         * fixes in Kigali land around 100-500 m, which is far tighter than a service
         * zone, so they place the rider in the right zone even though they are too
         * coarse to draw a live route with.
         */
        private const val MAX_USABLE_ACCURACY_M = 1_000f

        /** How long to hold out for a precise fix before accepting a coarse one. */
        private const val COARSE_FIX_GRACE_MS = 20_000L
    }
}
