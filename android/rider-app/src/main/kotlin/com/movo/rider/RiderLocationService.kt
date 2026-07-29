package com.movo.rider

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*

class RiderLocationService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var client: FusedLocationProviderClient
  private val callback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      result.lastLocation?.let { sendLocation(it) }
    }
  }
  override fun onCreate() {
    super.onCreate(); client = LocationServices.getFusedLocationProviderClient(this)
    createChannel()
    startForeground(44, NotificationCompat.Builder(this, "movo-rider-location").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("MOVO Rider location sharing").setContentText("Sharing your location while you are online").setOngoing(true).build())
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
    client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).setMinUpdateIntervalMillis(10_000).build(), callback, mainLooper)
    return START_NOT_STICKY
  }
  private fun sendLocation(location: Location) = scope.launch {
    try { RiderApi(applicationContext).put("/api/rider/location", "{\"lat\":${location.latitude},\"lng\":${location.longitude}}") } catch (_: Exception) { }
  }
  override fun onDestroy() { client.removeLocationUpdates(callback); scope.cancel(); super.onDestroy() }
  override fun onBind(intent: Intent?): IBinder? = null
  private fun createChannel() { val channel = NotificationChannel("movo-rider-location", "Rider location", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java).createNotificationChannel(channel) }
}
