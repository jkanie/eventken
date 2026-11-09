package io.github.jkanie.eventken.android

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jkanie.eventken.android.location.AndroidLocationProvider
import io.github.jkanie.eventken.android.location.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class LocationProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun withoutPermissionThereIsNoLocation() =
        runBlocking {
            val provider = AndroidLocationProvider(app)
            assertFalse(provider.hasPermission())
            assertNull(provider.current())
        }

    @Test
    fun theNewestLastKnownFixWins() =
        runBlocking {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val manager = app.getSystemService(LocationManager::class.java)
            val shadow = shadowOf(manager)
            shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            shadow.simulateLocation(location(LocationManager.NETWORK_PROVIDER, 48.40, -123.30, time = 1_000))
            shadow.simulateLocation(location(LocationManager.GPS_PROVIDER, 48.4236, -123.3702, time = 2_000))
            assertEquals(GeoPoint(48.4236, -123.3702), AndroidLocationProvider(app).current())
        }

    private fun location(
        provider: String,
        latitude: Double,
        longitude: Double,
        time: Long,
    ) = Location(provider).apply {
        this.latitude = latitude
        this.longitude = longitude
        this.time = time
    }
}
