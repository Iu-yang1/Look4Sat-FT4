package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import org.hipparchus.CalculusFieldElement
import org.hipparchus.geometry.euclidean.threed.FieldVector3D
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.data.ClasspathCrawler
import org.orekit.data.LazyLoadedDataContext
import org.orekit.frames.Frame
import org.orekit.propagation.SpacecraftState
import org.orekit.propagation.events.EclipseDetector
import org.orekit.time.AbsoluteDate
import org.orekit.time.FieldAbsoluteDate
import org.orekit.utils.AbsolutePVCoordinates
import org.orekit.utils.CartesianDerivativesFilter
import org.orekit.utils.Constants
import org.orekit.utils.ExtendedPositionProvider
import org.orekit.utils.PVCoordinates
import org.orekit.utils.TimeStampedPVCoordinates
import org.orekit.utils.TimeStampedPVCoordinatesHermiteInterpolator
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToLong

class JplSunlightReferenceTest {
    @Test fun reportEventDifferencesAgainstIndependentJplSolarEphemeris() {
        val context = LazyLoadedDataContext().apply {
            dataProvidersManager.addProvider(ClasspathCrawler("orekit/UTC-TAI.history"))
        }
        val utc = context.getTimeScales().getUTC()
        val gcrf = context.getFrames().getGCRF()
        val teme = context.getFrames().getTEME()
        val rows = requireNotNull(javaClass.getResourceAsStream("/sunlight/jpl-de441-sun-hourly.csv"))
            .bufferedReader().use { reader -> reader.readLines().filter { it.isNotBlank() && !it.startsWith("#") } }
        val samples = rows.map { row ->
            val c = row.split(",")
            TimeStampedPVCoordinates(AbsoluteDate(c[0], utc), PVCoordinates(
                Vector3D(c[1].toDouble(), c[2].toDouble(), c[3].toDouble()).scalarMultiply(1000.0),
                Vector3D(c[4].toDouble(), c[5].toDouble(), c[6].toDouble()).scalarMultiply(1000.0)
            ))
        }
        val interpolator = TimeStampedPVCoordinatesHermiteInterpolator(2, CartesianDerivativesFilter.USE_PV)
        val jpl = object : ExtendedPositionProvider {
            override fun getPosition(date: AbsoluteDate, frame: Frame): Vector3D =
                gcrf.getStaticTransformTo(frame, date).transformPosition(interpolator.interpolate(date, samples).position)

            override fun <T : CalculusFieldElement<T>> getPosition(
                date: FieldAbsoluteDate<T>, frame: Frame
            ): FieldVector3D<T> {
                // This reference deliberately exercises only the scalar EclipseDetector API.
                throw UnsupportedOperationException("No field derivatives in the fixed JPL reference")
            }
        }
        val earth = OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING, teme)
        val umbra = EclipseDetector(jpl, Constants.SUN_RADIUS, earth).withUmbra()
        val penumbra = umbra.withPenumbra()
        val start = samples.first().date.toDate(utc).time
        val end = samples.last().date.toDate(utc).time
        // Equatorial LEO has non-grazing eclipses here, making event matching well-defined.
        val elements = OrbitalData("Synthetic equatorial LEO", 26316.0, 15.5, 0.001,
            0.1, 0.0, 0.0, 0.0, 99999, 0.0)
        val referenceOrbit = com.rtbishop.look4sat.core.data.astro.AstroTestData.engine.openSession(elements.getObject())
        fun referenceState(time: Long): Illumination {
            val pv = referenceOrbit.temeState(time)
            val state = SpacecraftState(AbsolutePVCoordinates(teme, AbsoluteDate(Date(time), utc), PVCoordinates(
                Vector3D(pv.xKm * 1000, pv.yKm * 1000, pv.zKm * 1000),
                Vector3D(pv.vxKmPerSecond * 1000, pv.vyKmPerSecond * 1000, pv.vzKmPerSecond * 1000)
            )))
            return when {
                umbra.g(state) < 0 -> Illumination.UMBRA
                penumbra.g(state) < 0 -> Illumination.PENUMBRA
                else -> Illumination.SUNLIGHT
            }
        }
        val reference = mutableListOf<Pair<Long, Illumination>>()
        var previous = referenceState(start)
        var time = start + 1000
        while (time < end) {
            val state = referenceState(time)
            if (state != previous) reference += (time - 500) to state
            previous = state
            time += 1000
        }
        val orbit = com.rtbishop.look4sat.core.data.astro.AstroTestData.engine.openSession(elements.getObject())
        val model = OrekitShadowModel(com.rtbishop.look4sat.core.data.astro.AstroTestData.runtime)
        val actual = ShadowEventSearch({
            model.sample(orbit.temeState(start + (it * 1000).roundToLong()), elements.orbitalPeriod * 60)
        }).search(start, end) {}
        assertEquals(null, actual.failure)
        assertTrue(reference.isNotEmpty())
        val transitions = actual.intervals.drop(1)
        assertEquals(reference.size, transitions.size)
        var maximumDifference = 0L
        reference.zip(transitions).forEach { (expected, interval) ->
            assertEquals(expected.second, interval.state)
            val difference = interval.startMillis - expected.first
            maximumDifference = maxOf(maximumDifference, abs(difference))
            println("Independent JPL event state=${interval.state} deltaMs=$difference")
        }
        println("Independent JPL maximumEventDifferenceMs=$maximumDifference referenceResolutionMs=1000")
        // This reports solar-model differences; the <=1 s identical-model criterion has its own test.
    }
}
