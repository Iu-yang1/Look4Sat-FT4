package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.AstroDataState
import com.rtbishop.look4sat.core.domain.astro.AstroDataUnavailableException
import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.utility.round
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OrekitOrbitEngineTest {
    private val vallado = OrbitalData("Vallado 00005", 179.78495062, 10.82419157,
        .1859667, 34.2682, 348.7242, 331.7664, 19.3264, 5, .000028098, .00000023)
    private fun time(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun numericAdapterMatchesIndependentValladoEpochVector() {
        val engine = AstroTestData.engine
        val epoch = (vallado.epochDaynum * 86400000 + 315446400000L).toLong()
        val state = engine.openSession(engine.bind(vallado)).temeState(epoch)
        // Vallado et al., Revisiting Spacetrack Report #3, SGP4 verification case 00005.
        assertEquals(7022.46529266, state.xKm, .03)
        assertEquals(-1400.08296755, state.yKm, .03)
        assertEquals(.03995155, state.zKm, .03)
        assertEquals(1.893841015, state.vxKmPerSecond, .00003)
        assertEquals(6.405893759, state.vyKmPerSecond, .00003)
        assertEquals(4.534807250, state.vzKmPerSecond, .00003)
    }

    @Test fun largeOmmCatalogNumberDoesNotNeedFiveDigitTleSerialization() {
        val engine = AstroTestData.engine
        val orbit = engine.openSession(engine.bind(vallado.copy(catnum = 1200001)))
        assertTrue(orbit.temeState(time("2000-06-27T18:50:19Z")).xKm.isFinite())
    }

    @Test fun independentSessionsDoNotShareDeepSpaceWorkspaces() {
        val engine = AstroTestData.engine
        val target = engine.bind(vallado.copy(meanmo = 1.0027, eccn = .001, incl = .1, bstar = 0.0))
        val first = engine.openSession(target)
        val second = engine.openSession(target)
        val date = time("2000-06-28T00:00:00Z")
        val expected = second.temeState(date)
        first.temeState(date + 365L * 86400000)
        first.temeState(date - 3600000)
        assertEquals(expected, second.temeState(date))
        assertSame(target.snapshot, second.target.snapshot)
    }

    @Test fun rangeRateAndDopplerHaveTheExistingSignsAndUnits() {
        val session = AstroTestData.engine.openSession(AstroTestData.engine.bind(vallado))
        val date = time("2000-06-27T19:00:00Z")
        val observer = GeoPos(51.5, -.1, 120.0)
        val actual = session.position(observer, date)
        val derivative = (session.position(observer, date + 500).distance -
            session.position(observer, date - 500).distance)
        assertEquals(derivative, actual.distanceRate, .001)
        assertEquals(435000000L * (1 - actual.distanceRate * 1000 / 299792458.0),
            actual.getDownlinkFreq(435000000L).toDouble(), 1.0)
        assertEquals(145000000L * (1 + actual.distanceRate * 1000 / 299792458.0),
            actual.getUplinkFreq(145000000L).toDouble(), 1.0)
        assertTrue(actual.velocityKmPerSecond > 0)
    }

    @Test fun realWindowsRespectTheHorizonAndClipFlags() {
        val session = AstroTestData.engine.openSession(AstroTestData.engine.bind(vallado))
        val start = time("2000-06-28T00:00:00Z")
        val observer = GeoPos(23.13, 113.26)
        val windows = session.passes(observer, start, start + 86400000)
        assertTrue(windows.isNotEmpty())
        windows.forEach { window ->
            assertTrue(window.aosTime < window.losTime)
            assertTrue(window.tcaTime in window.aosTime..window.losTime)
            assertEquals(window.maxElevation.round(1), window.maxElevation, 0.0)
            assertEquals(window.aosAzimuth.round(1), window.aosAzimuth, 0.0)
            assertEquals(window.losAzimuth.round(1), window.losAzimuth, 0.0)
            assertEquals(Math.toDegrees(session.elevation(observer, window.tcaTime)).round(1),
                window.maxElevation, 0.0)
            if (!window.startClipped) assertEquals(0.0, session.elevation(observer, window.aosTime), .0005)
            if (!window.endClipped) assertEquals(0.0, session.elevation(observer, window.losTime), .0005)
        }
        val first = windows.first { it.losTime - it.aosTime > 30000 }
        val clipped = session.passes(observer, first.aosTime + 10000, first.losTime - 10000).single()
        assertTrue(clipped.startClipped && clipped.endClipped && clipped.continuousVisibility)
    }

    @Test fun missingDataDoesNotFallBackToTheHistoricalPropagator() {
        val repository = object : IAstroDataRepository {
            override val state = MutableStateFlow(AstroDataState())
            override fun snapshot(): com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot =
                throw AstroDataUnavailableException()
            override fun setDownloadUrl(url: String) = Unit
            override fun download() = Unit
            override fun cancel() = Unit
        }
        assertThrows(AstroDataUnavailableException::class.java) {
            OrekitOrbitEngine(repository).openSession(vallado.getObject())
        }
    }

    @Test fun searchesCanBeCancelledWithoutPublishingAPartialWindow() {
        val session = AstroTestData.engine.openSession(AstroTestData.engine.bind(vallado))
        var calls = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            session.passes(GeoPos(0.0, 0.0), time("2000-06-28T00:00:00Z"),
                time("2000-06-29T00:00:00Z")) {
                if (++calls > 30) throw kotlinx.coroutines.CancellationException()
            }
        }
    }

    @Test fun mapSurfaceContoursUseWgs84AndTheSameTopocentricElevationAsTracking() {
        val runtime = AstroTestData.runtime
        val date = runtime.date(time("2026-11-12T12:00:00Z"))
        for (latitude in listOf(0.0, 65.0, 89.9, -89.9)) {
            val bodyPosition = runtime.earth.transform(org.orekit.bodies.GeodeticPoint(
                Math.toRadians(latitude), Math.toRadians(179.0), 550000.0))
            val surface = OrekitSurfaceGeometry(runtime, bodyPosition, date)
            assertEquals(90.0, surface.elevationDegrees(GeoPos(latitude, 179.0)), 1e-7)
            for (elevation in listOf(0.0, 10.0, 45.0, 89.9)) {
                val points = surface.contour(elevation)
                assertEquals(361, points.size)
                points.forEach { point ->
                    val expected = runtime.station(point).getElevation(bodyPosition, runtime.terrestrial, date)
                    assertEquals(elevation, Math.toDegrees(expected), 1e-6)
                }
            }
        }
    }
}
