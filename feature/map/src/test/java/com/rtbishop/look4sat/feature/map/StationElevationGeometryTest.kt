package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.astro.IOrbitSurfaceGeometry
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationElevationGeometryTest {
    @Test fun matchedStationUsesThePinnedBackendGeometryIncludingItsAltitude() {
        val observer = GeoPos(22.5, 113.6, 750.0)
        val geometry = object : IOrbitSurfaceGeometry {
            override fun elevationDegrees(observer: GeoPos): Double {
                assertEquals(GeoPos(22.5, 113.6, 750.0), observer)
                return 18.75
            }
            override fun contour(elevationDegrees: Double, checkCancelled: () -> Unit) = emptyList<GeoPos>()
        }
        assertEquals(18.75, elevationAtGroundStation(OrbitalPos(surfaceGeometry = geometry), observer)!!, 0.0)
    }

    @Test fun missingGeometryOrStationDoesNotProduceAnApproximateReading() {
        assertNull(elevationAtGroundStation(OrbitalPos(altitude = 550.0), GeoPos(0.0, 0.0)))
        assertNull(elevationAtGroundStation(OrbitalPos(), null))
        assertNull(elevationAtGroundStation(null, GeoPos(0.0, 0.0)))
    }
}
