package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class OrbitPassCacheTest {
    private val elements = OrbitalData("Vallado", 179.78495062, 10.82419157,
        .1859667, 34.2682, 348.7242, 331.7664, 19.3264, 5, .000028098, .00000023)
    private val observer = GeoPos(23.13, 113.26)
    private val start = Instant.parse("2000-06-28T00:10:00Z").toEpochMilli()

    @Test fun diskReuseMatchesUncachedWindowsAndAvoidsAnotherScan() {
        val directory = Files.createTempDirectory("passes-cache-").toFile()
        try {
            val engine = OrekitOrbitEngine(AstroTestData.repository, directory)
            val target = engine.bind(elements)
            engine.openSession(target).passes(observer, start, start + 86400000)
            var work = 0
            val reopened = OrekitOrbitEngine(AstroTestData.repository, directory).openSession(target)
                .passes(observer, start + 60000, start + 86400000 + 60000) { work++ }
            val reference = AstroTestData.engine.openSession(target)
                .passes(observer, start + 60000, start + 86400000 + 60000)
            assertEquivalent(reference, reopened)
            assertTrue("A cache hit must not scan another whole day", work < 100)
        } finally { directory.deleteRecursively() }
    }

    @Test fun cropsAroundAndAwayFromThePeakRetainTheCorrectClippedMaximum() {
        val directory = Files.createTempDirectory("passes-crop-").toFile()
        try {
            val raw = AstroTestData.engine
            val cached = OrekitOrbitEngine(AstroTestData.repository, directory)
            val target = cached.bind(elements)
            val referenceSession = raw.openSession(target)
            val pass = cached.openSession(target).passes(observer, start, start + 86400000)
                .first { it.tcaTime - it.aosTime > 60000 && it.losTime - it.tcaTime > 60000 }
            val ranges = listOf(
                pass.aosTime + 10000 to pass.losTime - 10000,
                pass.aosTime + 10000 to pass.tcaTime - 10000,
                pass.tcaTime + 10000 to pass.losTime - 10000
            )
            for ((from, to) in ranges) {
                val actual = cached.openSession(target).passes(observer, from, to)
                assertEquivalent(referenceSession.passes(observer, from, to), actual)
                assertTrue(actual.single().startClipped)
                assertTrue(actual.single().endClipped)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun dataObserverAndGenerationAreIndependentCacheKeys() {
        val directory = Files.createTempDirectory("passes-keys-").toFile()
        try {
            val cache = OrbitPassCache(directory)
            val target = AstroTestData.engine.bind(elements)
            val key = cache.key(target, observer, "generation-1")
            assertNotEquals(key, cache.key(target.copy(data = elements.copy(ndot = .0001)), observer, "generation-1"))
            assertNotEquals(key, cache.key(target, observer.copy(altitude = 100.0), "generation-1"))
            assertEquals(key, cache.key(target, observer.copy(timestamp = 123L, qthLocator = "OL63"), "generation-1"))
            assertNotEquals(key, cache.key(target, observer, "generation-2"))
            cache.put(key, start, start + 10000, emptyList())
            assertNotNull(OrbitPassCache(directory).get(key, start, start + 10000))
            assertNull(cache.get(key, start - 1, start + 10000))
            assertNull(cache.get(key, start, start + 10001))
        } finally { directory.deleteRecursively() }
    }

    @Test fun differentQueryHorizonsSurviveAlternatingQueriesAndProcessRestarts() {
        val directory = Files.createTempDirectory("passes-horizons-").toFile()
        try {
            val engine = OrekitOrbitEngine(AstroTestData.repository, directory)
            val target = engine.bind(elements)
            val session = engine.openSession(target)
            val shortStart = start - 3600000
            val shortEnd = start + 12 * 3600000
            val longEnd = start + 72 * 3600000
            val short = session.passes(observer, shortStart, shortEnd)
            val long = session.passes(observer, start, longEnd)
            val reopened = OrekitOrbitEngine(AstroTestData.repository, directory).openSession(target)
            var work = 0
            assertEquivalent(short, reopened.passes(observer, shortStart, shortEnd) { work++ })
            assertEquivalent(long, reopened.passes(observer, start, longEnd) { work++ })
            assertTrue("Both horizons must remain reusable", work < 150)
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptCacheTriggersRealComputationAndCacheHitsRemainCancellable() {
        val directory = Files.createTempDirectory("passes-corrupt-").toFile()
        try {
            val engine = OrekitOrbitEngine(AstroTestData.repository, directory)
            val target = engine.bind(elements)
            val expected = engine.openSession(target).passes(observer, start, start + 86400000)
            directory.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
            val session = OrekitOrbitEngine(AstroTestData.repository, directory).openSession(target)
            assertEquivalent(expected, session.passes(observer, start, start + 86400000))
            assertThrows(CancellationException::class.java) {
                session.passes(observer, start, start + 86400000) { throw CancellationException() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun continuousDeepSpaceWindowKeepsItsRealMaximumAfterCropping() {
        val directory = Files.createTempDirectory("passes-geo-").toFile()
        try {
            val engine = OrekitOrbitEngine(AstroTestData.repository, directory)
            val target = engine.bind(elements.copy(meanmo = 1.0027, eccn = .001, incl = .1, bstar = 0.0))
            val raw = AstroTestData.engine.openSession(target)
            val position = raw.position(observer, start)
            val local = GeoPos(Math.toDegrees(position.latitude) + 5, Math.toDegrees(position.longitude))
            engine.openSession(target).passes(local, start, start + 86400000)
            val from = start + 60000
            val to = start + 86400000 - 60000
            val actual = engine.openSession(target).passes(local, from, to)
            assertEquivalent(raw.passes(local, from, to), actual)
            assertTrue(actual.single().continuousVisibility)
        } finally { directory.deleteRecursively() }
    }

    private fun assertEquivalent(expected: List<OrbitalPass>, actual: List<OrbitalPass>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertTrue(kotlin.math.abs(a.aosTime - b.aosTime) <= 1000)
            assertTrue(kotlin.math.abs(a.losTime - b.losTime) <= 1000)
            assertEquals(a.maxElevation, b.maxElevation, .1)
            assertEquals(a.aosAzimuth, b.aosAzimuth, .1)
            assertEquals(a.losAzimuth, b.losAzimuth, .1)
            assertEquals(a.startClipped, b.startClipped)
            assertEquals(a.endClipped, b.endClipped)
            assertEquals(a.continuousVisibility, b.continuousVisibility)
        }
    }
}
