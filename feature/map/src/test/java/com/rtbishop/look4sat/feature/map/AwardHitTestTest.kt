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
package com.rtbishop.look4sat.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pin the award-region tap rules: tiny regions (rendered as dots) are hit by a
 * tap radius around their bbox centre, normal regions by point-in-polygon,
 * tiny dots win over enclosing polygons (Macao inside Guangdong), and rings
 * crossing the antimeridian stay tappable on both sides of the date line.
 */
class AwardHitTestTest {

    /** Axis-aligned box region: [lon1, lon2] x [lat1, lat2] as one ring. */
    private fun box(code: String, lon1: Double, lat1: Double, lon2: Double, lat2: Double) = AwardRegion(
        code = code,
        name = code,
        labelLon = (lon1 + lon2) / 2.0,
        labelLat = (lat1 + lat2) / 2.0,
        rings = listOf(
            listOf(
                doubleArrayOf(lon1, lat1), doubleArrayOf(lon2, lat1),
                doubleArrayOf(lon2, lat2), doubleArrayOf(lon1, lat2)
            )
        )
    )

    private fun boundsOf(regions: List<AwardRegion>): List<DoubleArray> = regions.map { r ->
        val lons = r.rings.flatten().map { it[0] }
        val lats = r.rings.flatten().map { it[1] }
        doubleArrayOf(lons.min(), lats.min(), lons.max(), lats.max())
    }

    private fun hit(regions: List<AwardRegion>, lat: Double, lon: Double, zoom: Double): AwardRegion? =
        AwardHitTest.hitRegion(regions, boundsOf(regions), lat, lon, zoom)

    @Test
    fun `tiny region is hit inside the inflated target and missed beyond it`() {
        // Macao-sized box (0.07 x 0.10 deg): 1.6 px wide at zoom 5 -> a dot,
        // but its tap target is the MIN_TAP_TARGET_PX square around the centre.
        val regions = listOf(box("MO", 113.535, 22.108, 113.604, 22.212))
        assertEquals("MO", hit(regions, 22.16, 113.57, 5.0)?.code)
        // half a degree away is still inside the 40 px half-target at zoom 5
        assertEquals("MO", hit(regions, 22.16, 114.07, 5.0)?.code)
        // two degrees away (~45 px) is outside it
        assertNull(hit(regions, 22.16, 115.57, 5.0))
    }

    @Test
    fun `small region gets an inflated tap target beyond its own size`() {
        // Hong Kong-sized box: 13 x 9 px at zoom 5 — too small for a finger
        // at its own size, so the tap target inflates to 80 px square
        // (user request 2026-10-05).
        val regions = listOf(box("HK", 113.843, 22.178, 114.406, 22.559))
        assertEquals("HK", hit(regions, 22.37, 114.77, 5.0)?.code) // ~0.6 deg off = 14 px
        assertEquals("HK", hit(regions, 22.37, 115.37, 5.0)?.code) // ~1.2 deg off = 27 px
        assertNull(hit(regions, 22.37, 116.37, 5.0))               // ~2.2 deg off = 50 px
    }

    @Test
    fun `normal region is hit by point in polygon only`() {
        val regions = listOf(box("GD", 109.5, 20.2, 117.5, 25.6))
        assertEquals("GD", hit(regions, 23.1, 113.3, 5.0)?.code)
        assertNull(hit(regions, 30.0, 113.3, 5.0))
    }

    @Test
    fun `tiny region wins over the polygon that encloses it`() {
        val regions = listOf(
            box("GD", 109.5, 20.2, 117.5, 25.6),
            box("MO", 113.535, 22.108, 113.604, 22.212)
        )
        assertEquals("MO", hit(regions, 22.16, 113.57, 5.0)?.code)
        // away from the dot the enclosing province answers for its own area
        assertEquals("GD", hit(regions, 24.5, 110.0, 5.0)?.code)
    }

    @Test
    fun `smallest enclosing polygon wins`() {
        val regions = listOf(
            box("CN", 73.0, 18.0, 135.0, 53.6),
            box("HK", 113.8, 22.1, 114.5, 22.6)
        )
        assertEquals("HK", hit(regions, 22.3, 114.1, 5.0)?.code)
    }

    @Test
    fun `ring crossing the antimeridian is tappable on both sides`() {
        // Fiji-style ring whose raw lons run past +180 (wide enough to stay in
        // the polygon-branch of the hit test).
        val region = AwardRegion(
            code = "176",
            name = "Fiji",
            labelLon = 180.0,
            labelLat = -18.0,
            rings = listOf(
                listOf(
                    doubleArrayOf(172.0, -22.0), doubleArrayOf(188.0, -22.0),
                    doubleArrayOf(188.0, -14.0), doubleArrayOf(172.0, -14.0)
                )
            )
        )
        val regions = listOf(region)
        assertEquals("176", hit(regions, -18.0, 175.0, 4.0)?.code)
        assertEquals("176", hit(regions, -18.0, -175.0, 4.0)?.code)
        assertNull(hit(regions, -18.0, 165.0, 4.0))
    }

    /** Two 0.2 x 0.2 deg island boxes [lon] apart, as one archipelago entity. */
    private fun archipelago(code: String, lon1: Double, lon2: Double, lat: Double = -10.0) =
        AwardRegion(
            code = code,
            name = code,
            labelLon = (lon1 + lon2) / 2.0,
            labelLat = lat,
            rings = listOf(
                listOf(
                    doubleArrayOf(lon1 - 0.1, lat - 0.1), doubleArrayOf(lon1 + 0.1, lat - 0.1),
                    doubleArrayOf(lon1 + 0.1, lat + 0.1), doubleArrayOf(lon1 - 0.1, lat + 0.1)
                ),
                listOf(
                    doubleArrayOf(lon2 - 0.1, lat - 0.1), doubleArrayOf(lon2 + 0.1, lat - 0.1),
                    doubleArrayOf(lon2 + 0.1, lat + 0.1), doubleArrayOf(lon2 - 0.1, lat + 0.1)
                )
            )
        )

    @Test
    fun `sea archipelago is hit by its overall envelope between the islands`() {
        // Two tiny islands 10 deg apart: at zoom 5 the bbox is ~230 px, so the
        // per-region inflated target does not apply — the whole envelope does
        // (user request 2026-10-06: 海上的群岛按照整体包络来判断 hit).
        val regions = listOf(archipelago("191", -165.0, -155.0))
        assertEquals("191", hit(regions, -10.0, -160.0, 5.0)?.code) // open sea in the middle
        assertEquals("191", hit(regions, -10.0, -156.0, 5.0)?.code) // near the east island
        assertNull(hit(regions, -10.0, -152.0, 5.0))                // ~2.8 deg past the envelope
    }

    @Test
    fun `small entity inside the archipelago envelope keeps its own target`() {
        val regions = listOf(
            archipelago("191", -165.0, -155.0),
            box("KS", -161.1, -9.6, -160.9, -9.4) // tiny standalone island in the gap
        )
        // Near KS the small target (80 px) wins over the archipelago envelope.
        assertEquals("KS", hit(regions, -9.5, -160.4, 5.0)?.code)
        // Away from it the open sea belongs to the archipelago.
        assertEquals("191", hit(regions, -10.0, -156.0, 5.0)?.code)
    }

    @Test
    fun `mainland country with a stray island is not envelope hit`() {
        // Big mainland ring + one small island ring: sea inside the bbox is NOT
        // a tap target (the group is not a scattered archipelago).
        val regions = listOf(
            AwardRegion(
                code = "JP",
                name = "JP",
                labelLon = 133.0,
                labelLat = 37.0,
                rings = listOf(
                    listOf(
                        doubleArrayOf(130.0, 31.0), doubleArrayOf(135.0, 31.0),
                        doubleArrayOf(135.0, 42.0), doubleArrayOf(130.0, 42.0)
                    ),
                    listOf(
                        doubleArrayOf(138.0, 34.2), doubleArrayOf(138.3, 34.2),
                        doubleArrayOf(138.3, 34.5), doubleArrayOf(138.0, 34.5)
                    )
                )
            )
        )
        assertNull(hit(regions, 35.0, 137.0, 5.0))              // sea inside the bbox
        assertEquals("JP", hit(regions, 37.0, 132.0, 5.0)?.code) // on the mainland
        assertEquals("JP", hit(regions, 34.35, 138.15, 5.0)?.code) // on the island itself
    }

    @Test
    fun `archipelago envelope crossing the antimeridian stays contiguous`() {
        // Two islands straddling 180: the raw bbox is 357 deg wide, but the
        // unwrapped envelope is a tight 3 deg box around the date line.
        val regions = listOf(archipelago("FJ", 178.65, -178.65, lat = -18.0))
        assertEquals("FJ", hit(regions, -18.0, 180.0, 5.0)?.code)  // sea gap at the date line
        assertEquals("FJ", hit(regions, -18.0, -180.0, 5.0)?.code) // same, other sign
        assertNull(hit(regions, -18.0, 176.0, 5.0))                // well west of the group
    }
}
