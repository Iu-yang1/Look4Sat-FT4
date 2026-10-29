package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
            val anchorLongitude = elevationContourLongitudeInSatelliteWorld(anchor.longitude, 0.0)
            val relativeLongitude = ((anchorLongitude + 540.0) % 360.0) - 180.0
            assertTrue(
                "latitude $satelliteLat: anchor shifted to $relativeLongitude°",
                relativeLongitude > 0.0 && relativeLongitude < 90.0
            )
        }
    }

    @Test
    fun anchorBearingRemainsEastAcrossDateLineInOrbitalLongitudeConvention() {
        // OrbitalPos.longitude is normally 0°..360°, so west longitudes are >180°.
        for (satelliteLon in listOf(1.0, 179.0, 181.0, 240.0, 359.0)) {
            val satellite = OrbitalPos(
                latitude = Math.toRadians(80.0),
                longitude = Math.toRadians(satelliteLon),
                altitude = 550.0
            )
            val ring = elevationContour(satellite, 10.0)
            val anchor = ring[elevationContourLabelAnchorIndex(ring)!!]
            val anchorLongitude = elevationContourLongitudeInSatelliteWorld(anchor.longitude, satelliteLon)
            val eastwardDelta = anchorLongitude - satelliteLon
            assertTrue(
                "longitude $satelliteLon: anchor shifted by $eastwardDelta° to $anchorLongitude°",
                eastwardDelta > 0.0 && eastwardDelta < 90.0
            )
        }
    }

    @Test
    fun westLongitudeLabelStaysInTheSameWorldCopyAsSatellite() {
        for (satelliteLon in listOf(181.0, 200.0, 240.0, 300.0, 350.0, 359.0)) {
            val satellite = OrbitalPos(
                latitude = Math.toRadians(10.0),
                longitude = Math.toRadians(satelliteLon),
                altitude = 550.0
            )
            val ring = elevationContour(satellite, 10.0)
            val anchor = ring[elevationContourLabelAnchorIndex(ring)!!]
            val anchorLongitude = elevationContourLongitudeInSatelliteWorld(anchor.longitude, satelliteLon)
            val eastwardDelta = anchorLongitude - satelliteLon

            assertTrue("longitude $satelliteLon: anchor at $anchorLongitude°", eastwardDelta > 0.0)
            assertTrue("longitude $satelliteLon: anchor shifted by $eastwardDelta°", eastwardDelta < 90.0)
            assertTrue(
                "longitude $satelliteLon: label was left on the 180° meridian",
                abs(anchorLongitude - 180.0) > 1.0
            )
        }
    }

    @Test
    fun longitudeUsesTheWorldCopyNearestToSatellite() {
        assertEquals(240.0, elevationContourLongitudeInSatelliteWorld(-120.0, 240.0), 0.0)
        assertEquals(370.0, elevationContourLongitudeInSatelliteWorld(10.0, 350.0), 0.0)
        assertEquals(-20.0, elevationContourLongitudeInSatelliteWorld(-20.0, 5.0), 0.0)
    }
}
