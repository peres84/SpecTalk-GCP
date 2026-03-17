package com.spectalk.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

class UserLocationRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    suspend fun getLocationContext(): UserLocationContext? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null

        val location = runCatching { fusedLocationClient.lastLocation.await() }.getOrNull()
            ?: runCatching {
                val tokenSource = CancellationTokenSource()
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    tokenSource.token,
                ).await()
            }.getOrNull()
            ?: return@withContext null

        UserLocationContext(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { it > 0f },
            locationLabel = reverseGeocode(location.latitude, location.longitude),
            capturedAt = Instant.ofEpochMilli(
                location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            ).toString(),
        )
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(latitude: Double, longitude: Double): String {
        val fallback = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        if (!Geocoder.isPresent()) return fallback

        val address = runCatching {
            Geocoder(appContext, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
        }.getOrNull() ?: return fallback

        val parts = listOfNotNull(
            address.locality,
            address.adminArea,
            address.countryName,
        ).distinct()

        return parts.joinToString(", ").ifBlank { fallback }
    }
}
