/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.predict

data class OrbitalPos(
    var azimuth: Double = 0.0,
    var elevation: Double = 0.0,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var altitude: Double = 0.0,
    var distance: Double = 0.0,
    var distanceRate: Double = 0.0,
    var theta: Double = 0.0,
    var time: Long = 0L,
    var phase: Double = 0.0,
    var eclipseDepth: Double = 0.0,
    var eclipsed: Boolean = false,
    var aboveHorizon: Boolean = false,
    val velocityKmPerSecond: Double = 0.0,
    val rangeBoundary: List<GeoPos> = emptyList(),
    val dataVersion: String = "",
    val elementAgeDays: Double = 0.0,
    val approximateEarthOrientation: Boolean = false
) {

    fun getDownlinkFreq(freq: Long): Long {
        return (freq.toDouble() * (SPEED_OF_LIGHT - distanceRate * 1000.0) / SPEED_OF_LIGHT).toLong()
    }

    fun getUplinkFreq(freq: Long): Long {
        return (freq.toDouble() * (SPEED_OF_LIGHT + distanceRate * 1000.0) / SPEED_OF_LIGHT).toLong()
    }

    fun getOrbitalVelocity(): Double {
        return velocityKmPerSecond
    }

    fun getRangeCircle(): List<GeoPos> {
        return rangeBoundary
    }

}
