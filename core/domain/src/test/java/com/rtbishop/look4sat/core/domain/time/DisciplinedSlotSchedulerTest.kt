/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.time

import org.junit.Assert.assertEquals
import org.junit.Test

class DisciplinedSlotSchedulerTest {
    private val scheduler = DisciplinedFt4SlotScheduler()

    @Test
    fun nextBoundaryWithLeadSkipsSameParitySlotThatIsTooClose() {
        val next = scheduler.nextBoundaryAfter(
            utcMillis = 6_675L,
            minimumLeadMillis = 2_500L,
            sequence = 1
        )

        assertEquals(3L, next.index)
        assertEquals(22_500L, next.startUtcMillis)
        assertEquals(1, next.sequence)
    }

    @Test
    fun ft4UsesSevenPointFiveSecondBoundariesAndAlternatingSequences() {
        assertEquals(0L, scheduler.boundaryAt(7_499L).index)
        assertEquals(1L, scheduler.boundaryAt(7_500L).index)
        assertEquals(0, scheduler.boundaryAt(15_001L).sequence)
        assertEquals(1, scheduler.boundaryAt(22_501L).sequence)
    }

    @Test
    fun negativeUtcUsesFloorDivision() {
        val boundary = scheduler.boundaryAt(-1L)
        assertEquals(-1L, boundary.index)
        assertEquals(-7_500L, boundary.startUtcMillis)
        assertEquals(0L, scheduler.nextBoundaryAfter(-1L).index)
    }

    @Test
    fun longBoundarySequenceNeverDuplicatesOrSkips() {
        var boundary = scheduler.boundaryAt(1_700_000_000_000L)
        repeat(1_000) {
            val next = scheduler.nextBoundaryAfter(boundary.startUtcMillis)
            assertEquals(boundary.index + 1, next.index)
            assertEquals(boundary.endUtcMillis, next.startUtcMillis)
            boundary = next
        }
    }
}
