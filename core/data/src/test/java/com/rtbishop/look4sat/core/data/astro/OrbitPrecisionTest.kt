package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.model.TrajectoryPrecision
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class OrbitPrecisionTest {
    private val elements = OrbitalData("Vallado", 179.78495062, 10.82419157,
        .1859667, 34.2682, 348.7242, 331.7664, 19.3264, 5, .000028098, .00000023)
    private val observer = GeoPos(23.13, 113.26)
    private val start = Instant.parse("2000-06-28T00:10:00Z").toEpochMilli()

    @Test fun allLevelsKeepPreciseEventsForLeoDeepSpaceAndEccentricOrbits() {
        val targets = listOf(
            elements.copy(meanmo = 14.8, eccn = .001, incl = 98.5),
            elements,
            elements.copy(meanmo = 2.0, eccn = .65, incl = 63.4, bstar = 0.0),
            elements.copy(meanmo = 1.0027, eccn = .001, incl = .1, bstar = 0.0)
        )
        for (target in targets) {
            val baseline = OrekitOrbitEngine(AstroTestData.repository).openSession(target.getObject())
                .passes(observer, start, start + 86400000)
            for (level in TrajectoryPrecision.entries) {
                val session = OrekitOrbitEngine(AstroTestData.repository, precision = { level })
                    .openSession(target.getObject())
                val actual = session.passes(observer, start, start + 86400000)
                assertEquals("$target / $level", baseline.size, actual.size)
                baseline.zip(actual).forEach { (expected, found) ->
                    assertEquals(expected.aosTime.toDouble(), found.aosTime.toDouble(), 1000.0)
                    assertEquals(expected.losTime.toDouble(), found.losTime.toDouble(), 1000.0)
                    assertEquals(expected.tcaTime.toDouble(), found.tcaTime.toDouble(), 1000.0)
                    assertEquals(expected.maxElevation, found.maxElevation, .11)
                    assertEquals(expected.startClipped, found.startClipped)
                    assertEquals(expected.endClipped, found.endClipped)
                }
                // This preference must never change the live pointing or Doppler solution.
                assertEquals(baseline.firstOrNull()?.orbitalObject?.data ?: target, session.target.data)
                val reference = OrekitOrbitSession(session.target).position(observer, start)
                val position = session.position(observer, start)
                assertEquals(reference.azimuth, position.azimuth, 0.0)
                assertEquals(reference.distanceRate, position.distanceRate, 0.0)
            }
        }
    }

    @Test fun switchingLevelsSeparatesCachesAndFrozenSessionsKeepTheirLevel() = runBlocking {
        val directory = Files.createTempDirectory("precision-cache-").toFile()
        try {
            var level = TrajectoryPrecision.ULTRA
            val diagnostics = mutableListOf<String>()
            val engine = OrekitOrbitEngine(AstroTestData.repository, directory,
                precision = { level }, diagnostic = diagnostics::add)
            val target = engine.bind(elements)
            val frozen = engine.openSession(target)
            val ultra = engine.passes(target, listOf(observer), start, start + 3600000)
            diagnostics.clear()
            level = TrajectoryPrecision.LIGHT
            engine.passes(target, listOf(observer), start, start + 3600000)
            assertTrue(diagnostics.any { "sampling=LIGHT" in it })
            diagnostics.clear()
            assertEquals(ultra.single(), frozen.passes(observer, start, start + 3600000))
            assertTrue(diagnostics.none { "sampling=LIGHT" in it })
            level = TrajectoryPrecision.ULTRA
            assertEquals(ultra, engine.passes(target, listOf(observer), start, start + 3600000))
        } finally { directory.deleteRecursively() }
    }

    @Test fun ultraPreservesExistingSamplingAndLightUsesFewerPropagations() {
        fun calculate(level: TrajectoryPrecision): Pair<List<com.rtbishop.look4sat.core.domain.predict.OrbitalPass>, Long> {
            val diagnostics = mutableListOf<String>()
            val engine = OrekitOrbitEngine(AstroTestData.repository,
                precision = { level }, diagnostic = diagnostics::add)
            val result = engine.openSession(elements.getObject()).passes(observer, start, start + 86400000)
            val count = diagnostics.sumOf {
                Regex("propagations=(\\d+)").find(it)?.groupValues?.get(1)?.toLong() ?: 0L
            }
            return result to count
        }
        val (ultra, fullCount) = calculate(TrajectoryPrecision.ULTRA)
        val (light, lightCount) = calculate(TrajectoryPrecision.LIGHT)
        assertEquals(ultra.size, light.size)
        assertTrue("Light=$lightCount Ultra=$fullCount", lightCount < fullCount)
        println("Trajectory work: Light=$lightCount Ultra=$fullCount")
    }
}
