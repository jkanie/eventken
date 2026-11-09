package io.github.jkanie.eventken.shared

import io.github.jkanie.eventken.shared.geo.Geo
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    @Test
    fun samePointIsZero() {
        assertEquals(0.0, Geo.haversineKm(48.4284, -123.3656, 48.4284, -123.3656), 1e-9)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111.195, Geo.haversineKm(0.0, 0.0, 1.0, 0.0), 0.01)
    }

    @Test
    fun antipodesAreHalfTheCircumference() {
        assertEquals(kotlin.math.PI * Geo.EARTH_RADIUS_KM, Geo.haversineKm(0.0, 0.0, 0.0, 180.0), 1e-6)
    }
}
