package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ShadowEventSearchTest {
    @Test fun fullSunlightRemainsOneClippedIntervalWithExactAccounting() {
        val result = ShadowEventSearch({ ShadowSample(1.0, 2.0) }).search(1000, 86_401_000) {}
        assertNull(result.failure)
        assertEquals(1, result.intervals.size)
        assertEquals(Illumination.SUNLIGHT, result.intervals.single().state)
        assertEquals(86_400_000L, result.intervals.sumOf { it.durationMillis })
        assertTrue(result.intervals.single().startClipped)
        assertTrue(result.intervals.single().endClipped)
    }

    @Test fun twoBoundariesProduceSunlightPenumbraUmbraAndBackWithoutGaps() {
        val result = ShadowEventSearch({
            ShadowSample((it - 50).pow(2) - 400, (it - 50).pow(2) - 100)
        }).search(0, 100_000) {}
        assertNull(result.failure)
        assertEquals(listOf(
            Illumination.SUNLIGHT, Illumination.PENUMBRA, Illumination.UMBRA,
            Illumination.PENUMBRA, Illumination.SUNLIGHT
        ), result.intervals.map { it.state })
        val expected = listOf(0L, 30_000, 40_000, 60_000, 70_000)
        result.intervals.zip(expected).forEach { (interval, start) ->
            assertEquals(start.toDouble(), interval.startMillis.toDouble(), 100.0)
        }
        assertEquals(100_000L, result.intervals.sumOf { it.durationMillis })
        result.intervals.zipWithNext().forEach { (a, b) -> assertEquals(a.endMillis, b.startMillis) }
    }

    @Test fun grazingShadowBetweenEqualSignSamplesIsNotMissed() {
        val result = ShadowEventSearch({
            ShadowSample((it - 47).pow(2) - 0.25, (it - 47).pow(2) + 1)
        }).search(0, 120_000) {}
        assertNull(result.failure)
        val penumbra = result.intervals.single { it.state == Illumination.PENUMBRA }
        assertEquals(46_500.0, penumbra.startMillis.toDouble(), 100.0)
        assertEquals(47_500.0, penumbra.endMillis.toDouble(), 100.0)
        assertFalse(result.intervals.any { it.state == Illumination.UMBRA })
    }

    @Test fun shortEventsAtEitherWindowEdgeAreFound() {
        for (center in listOf(1.5, 118.5)) {
            val result = ShadowEventSearch({
                ShadowSample((it - center).pow(2) - 0.25, 1.0)
            }).search(0, 120_000) {}
            assertNull(result.failure)
            assertEquals(1, result.intervals.count { it.state == Illumination.PENUMBRA })
        }
    }

    @Test fun tangencyHasNoPositiveDurationEclipse() {
        val result = ShadowEventSearch({
            ShadowSample((it - 47).pow(2), (it - 47).pow(2) + 1)
        }).search(0, 120_000) {}
        assertNull(result.failure)
        assertTrue(result.intervals.all { it.state == Illumination.SUNLIGHT })
        assertEquals(120_000L, result.intervals.sumOf { it.durationMillis })
    }

    @Test fun invalidPropagationEndsKnownRangeWithoutInventingSunlight() {
        val result = ShadowEventSearch({
            require(it < 100) { "invalid orbit" }
            ShadowSample(1.0, 2.0)
        }).search(0, 180_000) {}
        assertNotNull(result.failure)
        assertTrue(result.validUntilMillis < 100_000)
        assertEquals(result.validUntilMillis, result.intervals.sumOf { it.durationMillis })
        assertFalse(result.intervals.any { it.endClipped })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedToACompletedOrPartialResult() {
        var checks = 0
        ShadowEventSearch({ ShadowSample(1.0, 2.0) }, {
            if (++checks == 5) throw CancellationException("cancel")
        }).search(0, 365L * 86_400_000) {}
    }

    @Test fun boundedOrbitStepsAreRespectedAndProgressIsMonotonic() {
        val progress = mutableListOf<Double>()
        val result = ShadowEventSearch({ ShadowSample(-1.0, -0.5, 2.0) })
            .search(0, 40_000, progress::add)
        assertNull(result.failure)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(listOf(Illumination.UMBRA), result.intervals.map { it.state })
    }
}
