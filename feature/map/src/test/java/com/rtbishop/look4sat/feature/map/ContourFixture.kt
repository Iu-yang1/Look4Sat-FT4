package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic rings for projection/label tests; physical geometry is tested in core:data. */
internal fun contourFixture(latitude: Double, longitude: Double) = OrbitalPos(
    latitude = Math.toRadians(latitude), longitude = Math.toRadians(longitude), altitude = 550.0,
    elevationContours = listOf(10.0, 20.0).associateWith {
        (0..360).map { step ->
            val angle = Math.toRadians(step.toDouble())
            GeoPos(latitude + 10 * cos(angle), longitude + 10 * sin(angle))
        }
    }
)
