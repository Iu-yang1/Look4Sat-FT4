package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationContourLabelTest {
    @Test
    fun anchorStaysOnEastSideWhenRingEnclosesNorthOrSouthPole() {
        // At 550 km altitude the 10° ring spans a pole once the subpoint
        // is sufficiently far north/south. The old max-longitude selection
        // jumped near the opposite meridian at those latitudes.
        for (satelliteLat in listOf(60.0, 74.0, 80.0, 82.0, 80.0, 74.0, 60.0,
            -60.0, -74.0, -80.0, -82.0, -80.0, -74.0, -60.0)) {
            val satellite = OrbitalPos(
                latitude = Math.toRadians(satelliteLat),
                longitude = 0.0,
                altitude = 550.0
            )
            val ring = elevationContour(satellite, 10.0)
            val anchorIndex = elevationContourLabelAnchorIndex(ring)
            assertEquals("latitude $satelliteLat", 90, anchorIndex)
            val anchor = ring[anchorIndex!!]
            val relativeLongitude = ((anchor.longitude + 540.0) % 360.0) - 180.0
            assertTrue(
                "latitude $satelliteLat: anchor shifted to $relativeLongitude°",
                relativeLongitude > 0.0 && relativeLongitude < 90.0
            )
        }
    }

    @Test
    fun anchorBearingRemainsEastAcrossDateLine() {
        for (satelliteLon in listOf(179.0, -179.0)) {
            val satellite = OrbitalPos(
                latitude = Math.toRadians(80.0),
                longitude = Math.toRadians(satelliteLon),
                altitude = 550.0
            )
            val ring = elevationContour(satellite, 10.0)
            val anchor = ring[elevationContourLabelAnchorIndex(ring)!!]
            val relativeLongitude = ((anchor.longitude - satelliteLon + 540.0) % 360.0) - 180.0
            assertTrue(
                "longitude $satelliteLon: anchor shifted to $relativeLongitude°",
                relativeLongitude > 0.0 && relativeLongitude < 90.0
            )
        }
    }
}
