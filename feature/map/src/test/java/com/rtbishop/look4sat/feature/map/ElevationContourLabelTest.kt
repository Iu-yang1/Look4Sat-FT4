package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import kotlin.math.abs

class ElevationContourLabelTest {
    @Test
    fun westLongitudeLabelIsPlacedNearMapCenterRatherThanAt180() {
        // OrbitalPos uses 0°..360°, while the map camera and GeoPos use −180°..180°.
        // Model the map centred on the satellite's canonical longitude.
        for (satelliteLon in listOf(181.0, 200.0, 240.0, 300.0, 350.0, 359.0)) {
            val satellite = OrbitalPos(
                latitude = Math.toRadians(10.0),
                longitude = Math.toRadians(satelliteLon),
                altitude = 550.0
            )
            val ring = elevationContour(satellite, 10.0)
            val anchor = ring[elevationContourLabelAnchorIndex(ring)!!]
            val mapCenterLongitude = if (satelliteLon > 180.0) satelliteLon - 360.0 else satelliteLon
            val mappedLongitude = elevationContourLongitudeInMapWorld(anchor.longitude, mapCenterLongitude)
            val eastwardDelta = mappedLongitude - mapCenterLongitude

            assertTrue(
                "satellite $satelliteLon°: label at $mappedLongitude°, map centre $mapCenterLongitude°",
                eastwardDelta > 0.0 && eastwardDelta < 90.0
            )
            if (mapCenterLongitude !in 170.0..180.0) {
                assertTrue(
                    "satellite $satelliteLon°: label incorrectly stuck at 180° ($mappedLongitude°)",
                    abs(mappedLongitude - 180.0) > 1.0
                )
            }
        }
    }

    @Test
    fun labelUsesNearestWorldCopyWhenMapViewIsNearAntimeridian() {
        assertEquals(181.0, elevationContourLongitudeInMapWorld(-179.0, 179.0), 0.0)
        assertEquals(-181.0, elevationContourLongitudeInMapWorld(179.0, -179.0), 0.0)
        assertEquals(-115.0, elevationContourLongitudeInMapWorld(245.0, -120.0), 0.0)
        assertEquals(4.0, elevationContourLongitudeInMapWorld(364.0, -1.0), 0.0)
    }

    @Test
    fun contourPolylineSplitsAtAntimeridianAndUsesCanonicalLongitudes() {
        val points = listOf(
            GeoPoint(0.0, 178.0),
            GeoPoint(1.0, 179.0),
            GeoPoint(2.0, 181.0),
            GeoPoint(3.0, 182.0)
        )

        val segments = elevationContourSplitAtAntimeridian(points)

        assertEquals(2, segments.size)
        assertEquals(listOf(178.0, 179.0), segments[0].map { it.longitude })
        assertEquals(listOf(-179.0, -178.0), segments[1].map { it.longitude })
        segments.forEach { segment ->
            assertTrue(
                "segment contains a jump across the antimeridian: ${segment.map { it.longitude }}",
                segment.zipWithNext().all { (a, b) -> abs(a.longitude - b.longitude) <= 180.0 }
            )
        }
    }

    @Test
    fun antimeridianArcsAreDuplicatedIntoNeighbouringWorldCopies() {
        val nearDateLine = listOf(GeoPoint(0.0, 178.0), GeoPoint(1.0, 179.0))
        val ordinary = listOf(GeoPoint(0.0, -120.0), GeoPoint(1.0, -119.0))

        assertEquals(listOf(-1, 0, 1), elevationContourTurnsForPoints(nearDateLine).toList())
        assertEquals(listOf(0), elevationContourTurnsForPoints(ordinary).toList())
    }
}
