package com.rtbishop.look4sat.core.domain.predict

/** Immutable true-equator, mean-equinox state. Position in km; velocity in km/s. */
data class TemeState(
    val timeMillis: Long,
    val xKm: Double,
    val yKm: Double,
    val zKm: Double,
    val vxKmPerSecond: Double,
    val vyKmPerSecond: Double,
    val vzKmPerSecond: Double
)
