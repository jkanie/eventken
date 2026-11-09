package io.github.jkanie.eventken.shared.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Mean Earth radius in km, the value the server's SQL uses too. */
    const val EARTH_RADIUS_KM = 6371.0088

    /**
     * Great-circle distance by the haversine formula. It treats the Earth as a
     * sphere, which is off by at most 0.5 %, well inside what an event search needs.
     */
    fun haversineKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    private fun Double.toRadians(): Double = this * kotlin.math.PI / 180.0
}
