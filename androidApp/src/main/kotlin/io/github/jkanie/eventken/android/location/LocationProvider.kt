package io.github.jkanie.eventken.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** Where the user is. An interface, so tests use a fake with a fixed point. */
interface LocationProvider {
    fun hasPermission(): Boolean

    /** The last known position, or null when there is none or no permission. */
    suspend fun current(): GeoPoint?
}

/**
 * Uses the platform LocationManager, so the app needs no Google Play services
 * and runs on any Android device. The last known fix is enough to sort events
 * by distance, and it costs no battery.
 */
class AndroidLocationProvider(
    private val context: Context,
) : LocationProvider {
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager
            .getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }
}
