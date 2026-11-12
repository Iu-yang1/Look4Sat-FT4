package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.TemeState
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.orekit.bodies.AnalyticalSolarPositionProvider
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.data.ClasspathCrawler
import org.orekit.data.LazyLoadedDataContext
import org.orekit.propagation.SpacecraftState
import org.orekit.propagation.events.EclipseDetector
import org.orekit.time.AbsoluteDate
import org.orekit.utils.AbsolutePVCoordinates
import org.orekit.utils.Constants
import org.orekit.utils.PVCoordinates
import java.util.Date

class OrekitShadowModelTest {
    private fun context() = LazyLoadedDataContext().apply {
        dataProvidersManager.addProvider(ClasspathCrawler("orekit/UTC-TAI.history"))
    }

    @Test fun offlineInitializationDoesNotReplaceTheGlobalContext() {
        val original = org.orekit.data.DataContext.getDefault()
        val model = OrekitShadowModel()
        val sample = model.sample(TemeState(1_799_366_400_000L, 7000.0, 200.0, 100.0, 0.0, 7.5, 0.0), 6000.0)
        assertTrue(sample.penumbra.isFinite())
        assertTrue(original === org.orekit.data.DataContext.getDefault())
    }

    @Test(expected = IllegalArgumentException::class)
    fun earthInteriorIsRejectedInsteadOfMarkedAsSunlight() {
        OrekitShadowModel().sample(TemeState(1_799_366_400_000L, 100.0, 100.0, 100.0, 0.0, 7.5, 0.0), 6000.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFinitePropagationIsRejected() {
        OrekitShadowModel().sample(TemeState(0L, Double.NaN, 1.0, 1.0, 1.0, 1.0, 1.0), 6000.0)
    }

    @Test fun analyticSunIsComparedWithIndependentJplVectorsWithoutCallingItJplPrecision() {
        val data = context()
        val sun = AnalyticalSolarPositionProvider(data)
        val rows = requireNotNull(javaClass.getResourceAsStream("/sunlight/jpl-de441-sun.csv"))
            .bufferedReader().use { reader -> reader.readLines().filter { it.isNotBlank() && !it.startsWith("#") } }
        rows.forEach { row ->
            val cells = row.split(",")
            val date = AbsoluteDate(cells[0], data.getTimeScales().getUTC())
            val expected = Vector3D(cells[1].toDouble(), cells[2].toDouble(), cells[3].toDouble())
            val actual = sun.getPosition(date, data.getFrames().getGCRF()).scalarMultiply(0.001)
            val angle = Vector3D.angle(expected, actual)
            val distance = Vector3D.distance(expected, actual)
            println("Sun reference ${cells[0]} angleRad=$angle distanceKm=$distance source=JPL-DE441")
            // A gross frame/units sanity check, not a claim about event timing or physical accuracy.
            assertTrue(angle < 0.005)
            assertTrue(distance < 1_000_000.0)
        }
    }

    @Test fun fo29AndRepresentativeOrbitsMatchNativeOrekitEventsWithinOneSecond() {
        // FO-29: SatNOGS TLE 2026-11-12 (Space-Track source); remaining orbits are synthetic cases.
        val fo29 = OrbitalData("FO-29", 26316.10526760, 13.53280858, 0.0350827,
            98.5106, 144.6672, 72.5798, 291.3377, 24278, 0.000027114, -0.00000006)
        val fixtures = listOf(
            fo29,
            fo29.copy(name = "Synthetic LEO", meanmo = 15.5, eccn = 0.001, incl = 51.6),
            fo29.copy(name = "Synthetic GEO", meanmo = 1.0027, eccn = 0.001, incl = 0.1, bstar = 0.0),
            fo29.copy(name = "Synthetic HEO", meanmo = 2.0, eccn = 0.65, incl = 63.4, bstar = 0.0)
        )
        fixtures.forEach { elements ->
            val data = context()
            val utc = data.getTimeScales().getUTC()
            val frame = data.getFrames().getTEME()
            val earth = OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS, Constants.WGS84_EARTH_FLATTENING, frame)
            val umbra = EclipseDetector(AnalyticalSolarPositionProvider(data), Constants.SUN_RADIUS, earth).withUmbra()
            val penumbra = umbra.withPenumbra()
            val start = AbsoluteDate("2026-11-12T00:00:00", utc).toDate(utc).time
            val nativeOrbit = elements.getObject()
            fun nativeState(seconds: Double): Illumination {
                val t = start + (seconds * 1000).toLong()
                val pv = nativeOrbit.getTemeState(t)
                val state = SpacecraftState(AbsolutePVCoordinates(frame, AbsoluteDate(Date(t), utc),
                    PVCoordinates(
                        Vector3D(pv.xKm * 1000, pv.yKm * 1000, pv.zKm * 1000),
                        Vector3D(pv.vxKmPerSecond * 1000, pv.vyKmPerSecond * 1000, pv.vzKmPerSecond * 1000)
                    )))
                return when {
                    umbra.g(state) < 0 -> Illumination.UMBRA
                    penumbra.g(state) < 0 -> Illumination.PENUMBRA
                    else -> Illumination.SUNLIGHT
                }
            }
            val orbit = elements.getObject()
            val model = OrekitShadowModel()
            val endSeconds = 10_800.0
            val result = ShadowEventSearch({
                model.sample(orbit.getTemeState(start + (it * 1000).toLong()), elements.orbitalPeriod * 60)
            }).search(start, start + (endSeconds * 1000).toLong()) {}
            assertEquals(null, result.failure)
            val expected = mutableListOf<Pair<Double, Illumination>>()
            var previous = nativeState(0.0)
            var t = 0.5
            while (t < endSeconds) {
                val state = nativeState(t)
                if (state != previous) expected += (t - 0.25) to state
                previous = state
                t += 0.5
            }
            val transitions = result.intervals.drop(1)
            assertEquals(elements.name, expected.size, transitions.size)
            expected.zip(transitions).forEach { (reference, actual) ->
                assertEquals(elements.name, reference.second, actual.state)
                assertEquals(elements.name, reference.first, (actual.startMillis - start) / 1000.0, 1.0)
            }
            assertEquals((endSeconds * 1000).toLong(), result.intervals.sumOf { it.durationMillis })
        }
    }
}
