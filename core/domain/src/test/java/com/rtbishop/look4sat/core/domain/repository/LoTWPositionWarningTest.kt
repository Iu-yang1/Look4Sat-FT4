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
package com.rtbishop.look4sat.core.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoTWPositionWarningTest {

    @Test
    fun coveredPositionProducesNoWarning() {
        assertNull(positionWarning("OL62aa", listOf("OL62")))
    }

    @Test
    fun positionInsideAnyStationGridIsCovered() {
        assertNull(positionWarning("OL61", listOf("OL61", "OL62", "OM60", "OM61")))
    }

    @Test
    fun positionOutsideTheStationWarns() {
        val warning = positionWarning("om91", listOf("OL62"))
        assertEquals("OM91", warning?.currentGrid)
        assertEquals(listOf("OL62"), warning?.stationGrids)
    }

    @Test
    fun stationGridsAreNormalizedForComparison() {
        assertNull(positionWarning("OL62", listOf("ol62aa")))
        assertNull(positionWarning("ol62", listOf("OL62")))
    }

    @Test
    fun unknownPositionOrStationProducesNoWarning() {
        assertNull(positionWarning(null, listOf("OL62")))
        assertNull(positionWarning("", listOf("OL62")))
        assertNull(positionWarning("OM91", emptyList()))
        assertNull(positionWarning("OM91", listOf("")))
    }
}
