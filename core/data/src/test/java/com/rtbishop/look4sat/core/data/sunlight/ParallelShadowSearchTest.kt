package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class ParallelShadowSearchTest {
    private val end = 2 * 86_400_000L

    @Test fun grazingEclipseAtWorkerSeamMatchesSingleSearchWithinOneSecond() = runBlocking {
        val seamSeconds = end / 2000.0
        fun sample(time: Double) = ShadowSample((time - seamSeconds).pow(2) - .25, 1.0)
        val serial = ShadowEventSearch(::sample).search(0, end) {}
        val progress = mutableListOf<Double>()
        val parallel = parallelShadowSearch(0, end, progress::add, 2) { start, stop, callback ->
            ShadowEventSearch({ sample(start / 1000.0 + it) }).search(start, stop, callback)
        }
        assertNull(parallel.failure)
        assertEquals(serial.intervals.map { it.state }, parallel.intervals.map { it.state })
        serial.intervals.zip(parallel.intervals).forEach { (a, b) ->
            assertTrue(abs(a.startMillis - b.startMillis) <= 1000)
            assertTrue(abs(a.endMillis - b.endMillis) <= 1000)
        }
        assertEquals(end, parallel.intervals.sumOf { it.durationMillis })
        assertTrue(parallel.intervals.first().startClipped)
        assertTrue(parallel.intervals.last().endClipped)
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun continuousSunlightMergesSeamsWithoutInventingEvents() = runBlocking {
        val result = parallelShadowSearch(0, end, {}, 8) { start, stop, progress ->
            progress(1.0)
            ShadowSearchResult(listOf(IlluminationInterval(
                Illumination.SUNLIGHT, start, stop, startClipped = true, endClipped = true)), stop)
        }
        assertEquals(1, result.intervals.size)
        assertEquals(end, result.intervals.single().durationMillis)
        assertTrue(result.intervals.single().startClipped && result.intervals.single().endClipped)
    }

    @Test fun invalidMiddleChunkRetainsOnlyContiguousKnownPrefix() = runBlocking {
        val failure = IllegalArgumentException("decayed")
        val result = parallelShadowSearch(0, end * 2, {}, 4) { start, stop, _ ->
            val validEnd = if (start in 1..end) start + 10_000 else stop
            ShadowSearchResult(listOf(IlluminationInterval(Illumination.SUNLIGHT, start, validEnd)),
                validEnd, failure.takeIf { validEnd < stop })
        }
        assertSame(failure, result.failure)
        assertEquals(result.validUntilMillis, result.intervals.sumOf { it.durationMillis })
        assertTrue(result.validUntilMillis < end)
        assertFalse(result.intervals.last().endClipped)
    }
}
