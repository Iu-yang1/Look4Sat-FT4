package com.rtbishop.look4sat.core.domain.predict

/** Display angles are degrees; Sun range rate is km/s and Moon radial velocity is m/s. */
object CelestialState {
    data class SunPosition(
        val azimuth: Double, val elevation: Double, val distance: Double, val rangeRate: Double,
        val latitude: Double, val longitude: Double, val rightAscension: Double, val declination: Double
    )

    data class MoonPosition(
        val azimuth: Double, val elevation: Double, val rightAscension: Double, val declination: Double,
        val gha: Double, val angularDiameter: Double, val radialVelocity: Double,
        val latitude: Double, val longitude: Double
    )

    data class RiseSetTimes(
        val riseTimeMillis: Long = 0, val setTimeMillis: Long = 0,
        val alwaysAbove: Boolean = false, val alwaysBelow: Boolean = false
    )
}
