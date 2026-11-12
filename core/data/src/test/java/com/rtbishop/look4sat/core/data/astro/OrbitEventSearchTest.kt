package com.rtbishop.look4sat.core.data.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitEventSearchTest {
    @Test fun extremaFindShortEventsEvenWhenBothStepEndpointsAreBelowHorizon() {
        val roots = eventBoundaries(0, 30000,
            { t -> 1.0 - ((t - 15000) / 1000.0).let { it * it } },
            { t -> -2.0 * (t - 15000) }, { 30000 }, {})
        assertEquals(2, roots.size)
        assertTrue(kotlin.math.abs(roots[0] - 14000) < 1000)
        assertTrue(kotlin.math.abs(roots[1] - 16000) < 1000)
    }

    @Test fun noEventIsDifferentFromAnEventAtTheQueryBoundary() {
        assertTrue(eventBoundaries(0, 30000, { 1.0 }, { 0.0 }, { 10000 }, {}).isEmpty())
        assertEquals(listOf(15000L),
            eventBoundaries(0, 30000, { it - 15000.0 }, { 1.0 }, { 10000 }, {}))
    }

    @Test fun maximumAtAScanBoundaryIsIncludedInPeakSelection() {
        val extrema = mutableSetOf<Long>()
        eventBoundaries(0, 20000,
            { t -> 2.0 - ((t - 10000) / 10000.0).let { it * it } },
            { t -> -2.0 * (t - 10000) }, { 10000 }, {}, { extrema.add(it) })
        assertEquals(setOf(10000L), extrema)
    }

    @Test fun distinctShortWindowBoundariesAreNotMerged() {
        val roots = eventBoundaries(0, 30000,
            { t -> 75.0 - kotlin.math.abs(t - 15000.0) },
            { t -> -kotlin.math.sign(t - 15000.0) }, { 30000 }, {})
        assertEquals(2, roots.size)
        assertTrue(roots[1] > roots[0])
    }

    @Test fun failureRetainsOnlyTheCompletelyScannedRange() {
        val failure = org.junit.Assert.assertThrows(OrbitEventSearchException::class.java) {
            eventBoundaries(0, 30000,
                { t -> t - 5000.0 },
                { t -> if (t >= 20000) error("Invalid propagation") else 1.0 },
                { 10000 }, {})
        }
        assertEquals(10000L, failure.validUntilMillis)
        assertEquals(listOf(5000L), failure.boundaries)
    }
}
