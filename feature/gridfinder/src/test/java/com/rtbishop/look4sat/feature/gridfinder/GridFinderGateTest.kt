/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.gridfinder

import com.rtbishop.look4sat.core.domain.repository.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridFinderGateTest {

    @Test
    fun `fix within the 20 ft rule is precise enough`() {
        assertTrue(fix(accuracy = 6.0f).isPreciseEnough(VUCC_FIX_ACCURACY_LIMIT_METERS))
    }

    @Test
    fun `fix worse than the 20 ft rule is not precise enough`() {
        assertFalse(fix(accuracy = 12.0f).isPreciseEnough(VUCC_FIX_ACCURACY_LIMIT_METERS))
    }

    @Test
    fun `streak grows on good fixes and confirms on the third`() {
        val first = nextFixGate(0, fix(accuracy = 4.0f))
        assertEquals(1, first.streak)
        assertFalse(first.isConfirmed)

        val second = nextFixGate(first.streak, fix(accuracy = 4.0f))
        assertEquals(2, second.streak)
        assertFalse(second.isConfirmed)

        val third = nextFixGate(second.streak, fix(accuracy = 4.0f))
        assertEquals(PRECISE_FIXES_REQUIRED, third.streak)
        assertTrue(third.isConfirmed)
    }

    @Test
    fun `streak stays capped once confirmed`() {
        val capped = nextFixGate(PRECISE_FIXES_REQUIRED, fix(accuracy = 1.0f))
        assertEquals(PRECISE_FIXES_REQUIRED, capped.streak)
        assertTrue(capped.isConfirmed)
    }

    @Test
    fun `one coarse fix resets a confirmed streak`() {
        val reset = nextFixGate(PRECISE_FIXES_REQUIRED, fix(accuracy = 25.0f))
        assertEquals(0, reset.streak)
        assertFalse(reset.isConfirmed)
    }

    private fun fix(accuracy: Float) = LocationFix(
        latitude = 22.0,
        longitude = 114.0,
        altitudeMeters = 30.0,
        accuracyMeters = accuracy,
        epochMs = 0L
    )
}
