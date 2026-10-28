/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos

class StationElevationGeometryTest {
    @Test
    fun stationDirectlyUnderSatelliteHasNinetyDegreeElevation() {
        val satellite = OrbitalPos(
            latitude = Math.toRadians(22.5),
            longitude = Math.toRadians(113.6),
            altitude = 550.0
        )
        assertEquals(90.0, elevationAtGroundStation(satellite, GeoPos(22.5, 113.6))!!, 1e-6)
    }

    @Test
    fun horizonFootprintIsZeroElevation() {
        val height = 550.0
        val satellite = OrbitalPos(latitude = 0.0, longitude = 0.0, altitude = height)
        val horizonAngle = Math.toDegrees(acos(6370.0 / (6370.0 + height)))
        assertEquals(0.0, elevationAtGroundStation(satellite, GeoPos(0.0, horizonAngle))!!, 1e-6)
    }

    @Test
    fun farStationFallsBelowHorizon() {
        val satellite = OrbitalPos(latitude = 0.0, longitude = 0.0, altitude = 550.0)
        assertTrue(elevationAtGroundStation(satellite, GeoPos(0.0, 90.0))!! < 0.0)
    }

    @Test
    fun longitudesWrapAcrossTheDateLine() {
        val satellite = OrbitalPos(latitude = 0.0, longitude = Math.toRadians(179.0), altitude = 550.0)
        val west = elevationAtGroundStation(satellite, GeoPos(0.0, -179.0))
        val east = elevationAtGroundStation(satellite, GeoPos(0.0, 181.0))
        assertEquals(west!!, east!!, 1e-9)
        assertTrue(west > 0.0)
    }

    @Test
    fun missingMatchedStationDoesNotDrawAnElevation() {
        val satellite = OrbitalPos(altitude = 550.0)
        assertNull(elevationAtGroundStation(satellite, null))
        assertNull(elevationAtGroundStation(null, GeoPos(0.0, 0.0)))
    }
}
