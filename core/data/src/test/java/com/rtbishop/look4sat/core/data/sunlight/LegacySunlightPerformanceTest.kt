package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.TemeState
import com.rtbishop.look4sat.core.domain.predict.legacy.LegacyOrbitEngine
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
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
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToLong

/** 14.03 shadow model retained only for the migration's same-host performance comparison. */
class LegacySunlightPerformanceTest {
    @Test fun optInPreviousBackendThirtyAnd365DayAccounting() {
        assumeTrue(System.getenv("SUNLIGHT_BENCHMARK") == "1")
        val context = LazyLoadedDataContext().apply {
            dataProvidersManager.addProvider(ClasspathCrawler("orekit/UTC-TAI.history"))
        }
        val utc = context.timeScales.utc
        val teme = context.frames.teme
        val earth = OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
            Constants.WGS84_EARTH_FLATTENING, teme)
        val umbra = EclipseDetector(AnalyticalSolarPositionProvider(context), Constants.SUN_RADIUS, earth).withUmbra()
        val penumbra = umbra.withPenumbra()
        val elements = OrbitalData("FO-29", 26316.10526760, 13.53280858, .0350827,
            98.5106, 144.6672, 72.5798, 291.3377, 24278, .000027114, -.00000006)
        val engine = LegacyOrbitEngine()
        val start = 1794441600000L
        fun sample(snapshot: TemeState): ShadowSample {
            val position = Vector3D(snapshot.xKm * 1000, snapshot.yKm * 1000, snapshot.zKm * 1000)
            require(!earth.isInside(position))
            val velocity = Vector3D(snapshot.vxKmPerSecond * 1000, snapshot.vyKmPerSecond * 1000,
                snapshot.vzKmPerSecond * 1000)
            val state = SpacecraftState(AbsolutePVCoordinates(teme,
                AbsoluteDate(Date(snapshot.timeMillis), utc), PVCoordinates(position, velocity)))
            val rate = Vector3D.crossProduct(position, velocity).norm / position.normSq
            val step = min(30.0, min(elements.orbitalPeriod * 60 / 180, (PI / 180) / rate)).coerceAtLeast(.25)
            return ShadowSample(penumbra.g(state), umbra.g(state), step)
        }
        for (days in listOf(30, 365)) {
            val session = engine.openSession(engine.bind(elements))
            val started = System.nanoTime()
            val result = ShadowEventSearch({
                sample(session.temeState(start + (it * 1000).roundToLong()))
            }).search(start, start + days * 86400000L) {}
            assertNull(result.failure)
            assertEquals(days * 86400000L, result.intervals.sumOf { it.durationMillis })
            println("LEGACY_SUNLIGHT_PERFORMANCE days=$days elapsedMs=${(System.nanoTime() - started) / 1000000} " +
                "environment=host-jvm model=14.03-analytical-sun")
        }
    }
}
