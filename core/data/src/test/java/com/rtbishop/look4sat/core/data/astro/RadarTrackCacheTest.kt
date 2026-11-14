package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RadarTrackCacheTest {
    private val satellite = OrbitalData("Test", 24100.0, 15.0, .001,
        51.6, 0.0, 0.0, 0.0, 1, 0.0).getObject()
    private val observer = GeoPos(23.13, 113.26, 100.0)

    @Test fun timestampChangesAndRevisitingHistoricalWindowsDoNotRecalculateOrShortenTracks() = runBlocking {
        val cache = RadarTrackCache()
        var calls = 0
        suspend fun calculate() = listOf(OrbitalPos(time = 1000), OrbitalPos(time = 61000)).also { calls++ }
        val original = cache.get(satellite, observer, 1000, 61000, ::calculate)
        repeat(20) { clock ->
            val revisited = cache.get(satellite, observer.copy(timestamp = clock * 1000L),
                1000, 61000, ::calculate)
            assertTrue(revisited.second)
            assertSame(original.first, revisited.first)
            assertEquals(listOf(1000L, 61000L), revisited.first.map { it.time })
        }
        assertEquals(1, calls)
    }

    @Test fun coordinatesElementsAndWindowChangesDoNotReuseUnrelatedTracks() = runBlocking {
        val cache = RadarTrackCache()
        suspend fun calculate() = listOf(OrbitalPos())
        assertFalse(cache.get(satellite, observer, 0, 1000, ::calculate).second)
        assertFalse(cache.get(satellite, observer.copy(altitude = 200.0), 0, 1000, ::calculate).second)
        assertFalse(cache.get(satellite.copy(data = satellite.data.copy(epoch = 24101.0)),
            observer, 0, 1000, ::calculate).second)
        assertFalse(cache.get(satellite, observer, 0, 2000, ::calculate).second)
    }

    @Test fun pointBudgetEvictsOldestAndCancellationDoesNotPublishPartialResults() = runBlocking {
        val cache = RadarTrackCache(maximumPoints = 3)
        suspend fun calculate() = listOf(OrbitalPos(), OrbitalPos())
        cache.get(satellite, observer, 0, 1000, ::calculate)
        cache.get(satellite, observer, 1000, 2000, ::calculate)
        assertFalse(cache.get(satellite, observer, 0, 1000, ::calculate).second)
        try {
            cache.get(satellite, observer, 3000, 4000) { throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(cache.get(satellite, observer, 3000, 4000, ::calculate).second)
    }

    @Test fun concurrentConsumersShareOneSuccessfulCalculation() = runBlocking {
        val cache = RadarTrackCache()
        var calls = 0
        val results = coroutineScope {
            List(4) {
                async {
                    cache.get(satellite, observer, 0, 1000) {
                        calls++
                        delay(10)
                        listOf(OrbitalPos())
                    }
                }
            }.map { it.await() }
        }
        assertEquals(1, calls)
        assertEquals(3, results.count { it.second })
    }
}
