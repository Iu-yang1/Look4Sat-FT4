package com.rtbishop.look4sat.feature.mutual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MutualHorizonEdgeTest {
    // Both stations can see the satellite from 21.5s until 54.5s.
    private val mutuallyVisible: (Long) -> Boolean = { time ->
        time >= 21_500L && time < 54_500L
    }

    @Test
    fun `AOS starts at the first above-horizon sample`() {
        val aos = refineMutualHorizonEdge(60_000L, 1_000L, true, mutuallyVisible)
        assertEquals(22_000L, aos)
        assertFalse(mutuallyVisible(aos!! - 1_000L))
        assertTrue(mutuallyVisible(aos))
    }

    @Test
    fun `AOS before rising falls back to forward search`() {
        val aos = refineMutualHorizonEdge(20_000L, 1_000L, true, mutuallyVisible)
        assertEquals(22_000L, aos)
    }

    @Test
    fun `AOS coarse sample inside visibility refines backwards`() {
        val aos = refineMutualHorizonEdge(40_000L, 1_000L, true, mutuallyVisible)
        assertEquals(22_000L, aos)
    }

    @Test
    fun `LOS coarse sample after pass finds the falling edge`() {
        val los = refineMutualHorizonEdge(60_000L, 1_000L, false, mutuallyVisible)
        assertEquals(54_000L, los)
    }

    @Test
    fun `LOS coarse sample below horizon refines backwards rather than further below`() {
        val los = refineMutualHorizonEdge(80_000L, 1_000L, false, mutuallyVisible)
        assertEquals(54_000L, los)
        assertTrue(mutuallyVisible(los!!))
        assertFalse(mutuallyVisible(los + 1_000L))
    }

    @Test
    fun `LOS also handles a coarse sample still above horizon`() {
        val los = refineMutualHorizonEdge(40_000L, 1_000L, false, mutuallyVisible)
        assertEquals(54_000L, los)
    }

    @Test
    fun `missing crossing never invents a time below horizon`() {
        val noCrossing = refineMutualHorizonEdge(80_000L, 1_000L, false) { false }
        assertNull(noCrossing)
    }
}
