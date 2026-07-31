package com.movo.customer.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.movo.customer.model.Coordinate

class CustomerLocation(private val context: Context) {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun requestCurrent(callback: (Result<Coordinate>) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { callback(Result.failure(SecurityException("Location permission is required"))); return }
        val priority = if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        client.getCurrentLocation(priority, null).addOnSuccessListener { location ->
            if (location == null) callback(Result.failure(IllegalStateException("Current location unavailable")))
            else callback(Result.success(Coordinate(location.latitude, location.longitude)))
        }.addOnFailureListener { callback(Result.failure(it)) }
    }
}
