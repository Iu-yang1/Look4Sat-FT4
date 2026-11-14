package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class OrbitCoverageTest {
    private val elements = OrbitalData("coverage", 179.78495062, 10.82419157,
        .1859667, 34.2682, 348.7242, 331.7664, 19.3264, 5, .000028098, .00000023)
    private val time = Instant.parse("2000-06-28T00:00:00Z").toEpochMilli()

    @Test fun horizonBoundaryStaysOnTheSelectedSideAndClosesWithoutJumps() {
        val runtime = AstroTestData.runtime
        for (orbit in listOf(elements, elements.copy(meanmo = 13.53, incl = 98.5, eccn = .035),
            elements.copy(meanmo = 1.0027, incl = .1, eccn = .001, bstar = 0.0))) {
            val session = AstroTestData.engine.openSession(AstroTestData.engine.bind(orbit)) as OrekitOrbitSession
            for (offset in listOf(0L, 1234000L, 3600000L)) {
                val instant = time + offset
                val date = runtime.date(instant)
                val satellite = session.state(instant).getPVCoordinates(runtime.terrestrial).position
                val firstAxis = satellite.orthogonal()
                val secondAxis = Vector3D.crossProduct(satellite.normalize(), firstAxis)
                val boundary = session.coverage(instant)
                val vectors = boundary.map { point ->
                    runtime.earth.transform(org.orekit.bodies.GeodeticPoint(
                        Math.toRadians(point.latitude), Math.toRadians(point.longitude), 0.0))
                }
                boundary.forEachIndexed { index, point ->
                    val angle = 2 * PI * index / (boundary.size - 1)
                    val direction = Vector3D(cos(angle), firstAxis, sin(angle), secondAxis)
                    assertTrue("Limb switched to the opposite side at $index",
                        vectors[index].dotProduct(direction) > 0.0)
                    assertEquals("Footprint must be the zero-elevation horizon", 0.0,
                        runtime.station(point).getElevation(satellite, runtime.terrestrial, date), 1e-10)
                    assertTrue(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
                }
                vectors.zipWithNext().forEach { (a, b) ->
                    assertTrue("Discontinuous footprint", Vector3D.angle(a, b) < Math.toRadians(5.0))
                }
                assertTrue(Vector3D.distance(vectors.first(), vectors.last()) < 1e-6)
            }
        }
    }
}
