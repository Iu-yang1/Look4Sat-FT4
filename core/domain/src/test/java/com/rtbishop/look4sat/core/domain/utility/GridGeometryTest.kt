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
package com.rtbishop.look4sat.core.domain.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden values were produced by a line-by-line port of the reference
 * implementation (OrbitDeck for iOS, MIT) covering four hemispheres/quadrant
 * cases plus the two awkward longitudes: the Greenwich meridian and the
 * antimeridian.
 */
class GridGeometryTest {

    private val meterDelta = 0.5
    private val bearingDelta = 0.05

    @Test
    fun `station at home square reports both boundaries and the corner`() {
        val geometry = requireNotNull(gridGeometry(22.3542, 113.6250))
        assertEquals("OL62", geometry.grid4)
        assertEquals("OL62TI", geometry.grid6)
        assertEquals("OL62TI55", geometry.grid8)
        assertEquals(39429.5, geometry.latLineMeters, meterDelta)
        assertEquals(GridBoundaryDirection.SOUTH, geometry.latLineDirection)
        assertEquals(38607.9, geometry.lonLineMeters, meterDelta)
        assertEquals(GridBoundaryDirection.EAST, geometry.lonLineDirection)
        assertEquals(55183.8, geometry.cornerMeters, meterDelta)
        assertEquals(135.60, geometry.cornerBearingDegrees, bearingDelta)
        assertEquals(listOf("OL61", "OL62", "OL71", "OL72"), geometry.cornerGrids)
        assertEquals(38607.9, geometry.nearestLineMeters, meterDelta)
        assertEquals(90.0, geometry.nearestLineBearingDegrees, bearingDelta)
        assertEquals(false, geometry.nearestLineIsLatitude)
        assertEquals(listOf("OL62", "OL72"), geometry.nearestLineGrids)
    }

    @Test
    fun `fix on the 114 east line sits on the boundary itself`() {
        val geometry = requireNotNull(gridGeometry(22.3542, 114.0000))
        assertEquals("OL72", geometry.grid4)
        assertEquals("OL72AI", geometry.grid6)
        assertEquals("OL72AI05", geometry.grid8)
        assertEquals(39429.5, geometry.latLineMeters, meterDelta)
        assertEquals(0.0, geometry.lonLineMeters, meterDelta)
        assertEquals(39429.5, geometry.cornerMeters, meterDelta)
        assertEquals(180.00, geometry.cornerBearingDegrees, bearingDelta)
        assertEquals(listOf("OL62", "OL72"), geometry.nearestLineGrids)
    }

    @Test
    fun `fix on the 22 north 114 east corner names all four squares`() {
        val geometry = requireNotNull(gridGeometry(22.00002, 114.00003))
        assertEquals("OL72", geometry.grid4)
        assertEquals(2.2, geometry.latLineMeters, meterDelta)
        assertEquals(3.1, geometry.lonLineMeters, meterDelta)
        assertEquals(3.8, geometry.cornerMeters, meterDelta)
        assertEquals(234.28, geometry.cornerBearingDegrees, bearingDelta)
        assertEquals(listOf("OL61", "OL62", "OL71", "OL72"), geometry.cornerGrids)
        assertEquals("OL71", geometry.nearestLineGrids.first())
    }

    @Test
    fun `greenwich meridian fix reports a zero distance latitude line to the north`() {
        val geometry = requireNotNull(gridGeometry(45.0000, -0.0004))
        assertEquals("IN95", geometry.grid4)
        assertEquals("IN95XA", geometry.grid6)
        assertEquals("IN95XA90", geometry.grid8)
        assertEquals(0.0, geometry.latLineMeters, meterDelta)
        assertEquals(GridBoundaryDirection.NORTH, geometry.latLineDirection)
        assertEquals(31.5, geometry.lonLineMeters, meterDelta)
        assertEquals(listOf("IN94", "IN95", "JN04", "JN05"), geometry.cornerGrids)
        assertEquals(true, geometry.nearestLineIsLatitude)
        assertEquals(listOf("IN94", "IN95"), geometry.nearestLineGrids)
    }

    @Test
    fun `antimeridian fix wraps into the eastern square`() {
        val geometry = requireNotNull(gridGeometry(0.05, 179.9998))
        assertEquals("RJ90", geometry.grid4)
        assertEquals("RJ90XB", geometry.grid6)
        assertEquals("RJ90XB92", geometry.grid8)
        assertEquals(22.3, geometry.lonLineMeters, meterDelta)
        assertEquals(GridBoundaryDirection.EAST, geometry.lonLineDirection)
        assertEquals(179.77, geometry.cornerBearingDegrees, bearingDelta)
        // The corner's four squares straddle the antimeridian, so the western
        // pair rolls over to the A-series fields.
        assertEquals(listOf("AI09", "AJ00", "RI99", "RJ90"), geometry.cornerGrids)
        assertEquals(listOf("AJ00", "RJ90"), geometry.nearestLineGrids)
    }

    @Test
    fun `southern hemisphere fix keeps its bearings right way round`() {
        val geometry = requireNotNull(gridGeometry(-33.86, 151.21))
        assertEquals("QF56", geometry.grid4)
        assertEquals("QF56OD", geometry.grid6)
        assertEquals(15584.8, geometry.latLineMeters, meterDelta)
        assertEquals(GridBoundaryDirection.SOUTH, geometry.latLineDirection)
        assertEquals(73027.8, geometry.lonLineMeters, meterDelta)
        assertEquals(GridBoundaryDirection.EAST, geometry.lonLineDirection)
        assertEquals(102.05, geometry.cornerBearingDegrees, bearingDelta)
        assertEquals(180.0, geometry.nearestLineBearingDegrees, bearingDelta)
    }

    @Test
    fun `out of range positions yield no geometry`() {
        assertNull(gridGeometry(90.0, 0.0))
        assertNull(gridGeometry(-91.0, 0.0))
    }

    @Test
    fun `vucc claims one square when the fix is inside`() {
        assertEquals(listOf("OL62"), vuccClaimableGrids(22.3542, 113.6250))
        assertEquals(GridFixStatus.INSIDE_GRID, gridFixStatus(vuccClaimableGrids(22.3542, 113.6250)))
    }

    @Test
    fun `vucc claims both squares on a line`() {
        val claimed = vuccClaimableGrids(22.3542, 114.0000)
        assertEquals(listOf("OL62", "OL72"), claimed)
        assertEquals(GridFixStatus.ON_GRID_LINE, gridFixStatus(claimed))
    }

    @Test
    fun `vucc claims all four squares on a corner`() {
        val claimed = vuccClaimableGrids(22.00002, 114.00003)
        assertEquals(listOf("OL61", "OL62", "OL71", "OL72"), claimed)
        assertEquals(GridFixStatus.ON_GRID_CORNER, gridFixStatus(claimed))
    }

    @Test
    fun `vucc claims nothing extra just outside the 20 foot tolerance`() {
        // Same corner, but ~38 m away: outside the 20 ft (6.096 m) rule.
        val claimed = vuccClaimableGrids(22.0002, 114.0003)
        assertEquals(listOf("OL72"), claimed)
        assertEquals(GridFixStatus.INSIDE_GRID, gridFixStatus(claimed))
    }

    @Test
    fun `vucc tolerance boundary is 20 feet`() {
        assertEquals(6.096, VUCC_BOUNDARY_TOLERANCE_METERS, 0.0001)
        // 5 m north of the 22 N line: inside the tolerance for a custom, tighter
        // call, outside it when the caller demands sub-metre precision.
        assertTrue(vuccClaimableGrids(22.00005, 113.62).size >= 2)
        assertEquals(1, vuccClaimableGrids(22.00005, 113.62, toleranceMeters = 1.0).size)
    }

    @Test
    fun `grid4 ignores longitudes beyond the antimeridian`() {
        assertNull(positionToGrid4(91.0, 0.0))
        // A longitude of 200 degrees is the same meridian as -160 degrees.
        assertEquals(positionToGrid4(30.0, -160.0), positionToGrid4(30.0, 200.0))
    }
}
