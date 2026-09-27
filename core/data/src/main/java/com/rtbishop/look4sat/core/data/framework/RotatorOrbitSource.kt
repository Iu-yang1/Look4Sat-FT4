/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.RAD2DEG
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.rotator.RotatorLook
import kotlin.math.abs

interface RotatorOrbitSource {
    suspend fun look(pass: OrbitalPass, timeMillis: Long): RotatorLook

    suspend fun passRequiresFlip(pass: OrbitalPass, azimuthOffsetDegrees: Double): Boolean
}

class RepositoryRotatorOrbitSource(
    private val satelliteRepo: ISatelliteRepo,
    private val stationPosition: () -> GeoPos
) : RotatorOrbitSource {
    override suspend fun look(pass: OrbitalPass, timeMillis: Long): RotatorLook {
        val position = satelliteRepo.getPosition(pass.orbitalObject, stationPosition(), timeMillis)
        return RotatorLook(
            azimuthDegrees = position.azimuth * RAD2DEG,
            elevationDegrees = position.elevation * RAD2DEG,
            sampleTimeMillis = timeMillis
        )
    }

    override suspend fun passRequiresFlip(pass: OrbitalPass, azimuthOffsetDegrees: Double): Boolean {
        val track = satelliteRepo.getTrack(
            pass.orbitalObject,
            stationPosition(),
            pass.aosTime,
            pass.losTime
        )
        var previous: Double? = null
        for (position in track) {
            val azimuth = wrap360(position.azimuth * RAD2DEG + azimuthOffsetDegrees)
            if (previous != null && abs(azimuth - previous) > 180.0) return true
            previous = azimuth
        }
        return false
    }

    private fun wrap360(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }
}
