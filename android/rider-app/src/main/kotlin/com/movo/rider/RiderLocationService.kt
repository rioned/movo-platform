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

/**
 * Shares the rider's position while they are online, so customers see a live map
 * and dispatch can match nearby work (spec §13.6). The notification states plainly
 * that location is being shared — riders must never be tracked silently.
 */
class RiderLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var heartbeat: Job? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { lastLocation = it; sendLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("MOVO Rider — you are online")
                .setContentText("Sharing your location so customers can track their delivery")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        // No displacement filter: a rider waiting at a junction is exactly when they
        // are most available, and the platform drops riders whose location goes stale.
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
                .build(),
            callback,
            mainLooper
        )
        startHeartbeat()
        return START_STICKY
    }

    /**
     * Re-posts the last fix on a timer. If the OS throttles updates for a stationary
     * phone, MOVO still sees this rider as live and keeps offering them work.
     */
    private fun startHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                lastLocation?.let { sendLocation(it) }
            }
        }
    }

    private fun sendLocation(location: Location) = scope.launch {
        runCatching {
            RiderApi(applicationContext).put(
                "/api/rider/location",
                JSONObject().put("lat", location.latitude).put("lng", location.longitude)
            )
        }
    }

    override fun onDestroy() {
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

    private companion object {
        const val CHANNEL_ID = "movo-rider-location"
        const val NOTIFICATION_ID = 44
        const val UPDATE_INTERVAL_MS = 10_000L
        const val HEARTBEAT_INTERVAL_MS = 45_000L
    }
}
