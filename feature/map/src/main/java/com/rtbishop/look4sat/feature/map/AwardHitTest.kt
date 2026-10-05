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

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Tap hit-testing for the award boundary overlay: which region sits under the
 * user's finger in DXCC/WAPC/WAJA/WAZ/WAS view. Pure geometry (no osmdroid
 * types) so it is unit-testable; the overlay delegates to [hitRegion].
 *
 * Three hit shapes, mirroring how the overlay draws:
 *  - a small region (projects to <= [MIN_TAP_TARGET_PX] at the current zoom —
 *    including the dot-rendered ones) is hit by an inflated square target of
 *    [MIN_TAP_TARGET_PX] around its bbox centre, so finger taps land even on
 *    dot-sized islands (user request 2026-10-05);
 *  - a normal region is hit by an even-odd point-in-polygon test on its rings.
 * Small targets win over polygon hits (Macao sits inside Guangdong's outline),
 * and among polygon hits the smallest region wins (Hong Kong over China).
 *
 *  - a scattered sea archipelago (>= 2 disjoint island rings, every island
 *    small — see [ARCHIPELAGO_MAX_RING_DEG]) is additionally hit anywhere in
 *    its overall envelope, so tapping open sea between the islands lands on
 *    the entity (user request 2026-10-06: 海上的群岛按照整体包络来判断 hit).
 * Priority: small target > land polygon > archipelago envelope — tapping the
 * Spanish coast that happens to lie in the Balearic envelope still opens
 * Spain; only sea inside the envelope belongs to the archipelago.
 */
object AwardHitTest {

    /** Projected size (max of width/height, px) below which a region is
     *  rendered as a minimum-size dot instead of its own shape. */
    const val MIN_SHAPE_PX = 8f

    /** Minimum tap target (px, square) for SMALL regions: any region whose
     *  projected size is below this gets an inflated square hit area of this
     *  size around its bbox centre (user request 2026-10-05 — small DXCCs
     *  like Hong Kong are hard to hit with a finger at their own size). */
    const val MIN_TAP_TARGET_PX = 80f

    /** Largest per-island span (deg) for a region to count as a scattered sea
     *  archipelago: every ring must be a small island, never a mainland
     *  (Japan/UK/Indonesia stay polygon-only). Archipelago islands are
     *  finger-proof at low zoom, so their hit region is the overall envelope. */
    const val ARCHIPELAGO_MAX_RING_DEG = 2.5

    /**
     * The region under a tap at [tapLat]/[tapLon] on the map at [zoom], or
     * null when the tap lands on empty space. [bounds] holds each region's
     * `[minLon, minLat, maxLon, maxLat]` in [regions] order
     * (AwardBoundaryOverlay.regionBounds).
     */
    fun hitRegion(
        regions: List<AwardRegion>,
        bounds: List<DoubleArray>,
        tapLat: Double,
        tapLon: Double,
        zoom: Double
    ): AwardRegion? {
        val worldWidthPx = 256.0 * 2.0.pow(zoom)
        val lonDegPerPx = 360.0 / worldWidthPx
        val latDegPerPx = (lonDegPerPx * cos(Math.toRadians(tapLat))).coerceAtLeast(1e-9)
        var bestSmall: AwardRegion? = null
        var bestSmallDist = Double.MAX_VALUE
        var bestPoly: AwardRegion? = null
        var bestPolyArea = Double.MAX_VALUE
        var bestArch: AwardRegion? = null
        var bestArchArea = Double.MAX_VALUE
        for (i in regions.indices) {
            val b = bounds.getOrNull(i) ?: continue
            // Cheap bbox prefilter with the tap-target slack (longitudes wrap).
            val slack = MIN_TAP_TARGET_PX / 2.0
            if (tapLat < b[1] - slack * latDegPerPx || tapLat > b[3] + slack * latDegPerPx) continue
            val cLon = (b[0] + b[2]) / 2.0
            val dLon = wrap180(tapLon - cLon)
            if (abs(dLon) > (b[2] - b[0]) / 2.0 + slack * lonDegPerPx) continue
            val w = (b[2] - b[0]) / lonDegPerPx
            val h = (b[3] - b[1]) / latDegPerPx
            val cLat = (b[1] + b[3]) / 2.0
            val dx = dLon / lonDegPerPx
            val dy = (tapLat - cLat) / latDegPerPx
            val dist = hypot(dx, dy)
            val region = regions[i]
            if (max(w, h) <= MIN_TAP_TARGET_PX) {
                // Small region: hit by an inflated square target around the
                // bbox centre — at least MIN_TAP_TARGET_PX on each side — so
                // finger taps land even on dot-sized entities.
                if (abs(dx) <= slack && abs(dy) <= slack && dist < bestSmallDist) {
                    bestSmall = region
                    bestSmallDist = dist
                }
            } else if (contains(region, tapLat, tapLon)) {
                // Land polygon hit: the smallest enclosing region wins.
                if (w * h < bestPolyArea) {
                    bestPoly = region
                    bestPolyArea = w * h
                }
            } else if (isArchipelago(region)) {
                // Scattered sea archipelago: the overall envelope is the hit
                // region (open sea between the islands belongs to the entity).
                // The envelope is measured in one unwrapped longitude frame so
                // antimeridian groups (Fiji-style) keep a contiguous box.
                val env = envelope(region)
                val halfW = (env[2] - env[1]) / 2.0
                val halfH = (env[4] - env[3]) / 2.0
                val envLon = env[0] + wrap180(tapLon - env[0])
                val inEnvelope =
                    abs(envLon - (env[1] + env[2]) / 2.0) <= halfW + slack * lonDegPerPx &&
                        abs(tapLat - (env[3] + env[4]) / 2.0) <= halfH + slack * latDegPerPx
                val area = 4.0 * halfW * halfH
                if (inEnvelope && area < bestArchArea) {
                    bestArch = region
                    bestArchArea = area
                }
            }
        }
        return bestSmall ?: bestPoly ?: bestArch
    }

    /**
     * Scattered sea archipelago: at least two disjoint island rings and every
     * ring small (<= [ARCHIPELAGO_MAX_RING_DEG] in both lon and lat, measured
     * in an unwrapped longitude frame) — no dominant mainland. Mainland
     * countries with stray islands (Japan, UK, Indonesia) fail the size test
     * and keep plain polygon hits.
     */
    fun isArchipelago(region: AwardRegion): Boolean {
        if (region.rings.size < 2) return false
        for (ring in region.rings) {
            if (ring.isEmpty()) continue
            val anchor = ring[0][0]
            var minX = 0.0
            var maxX = 0.0
            var minY = ring[0][1]
            var maxY = ring[0][1]
            for (p in ring) {
                val x = wrap180(p[0] - anchor)
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (p[1] < minY) minY = p[1]
                if (p[1] > maxY) maxY = p[1]
            }
            if (max(maxX - minX, maxY - minY) > ARCHIPELAGO_MAX_RING_DEG) return false
        }
        return true
    }

    /**
     * Envelope of all rings in one longitude frame anchored at the first ring's
     * first point: `[anchorLon, minX, maxX, minY, maxY]`. Longitudes past ±180
     * are unwrapped into the anchor's frame, keeping antimeridian groups whole.
     */
    private fun envelope(region: AwardRegion): DoubleArray {
        val first = region.rings.firstOrNull { it.isNotEmpty() } ?: return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
        val anchor = first[0][0]
        var minX = 0.0
        var maxX = 0.0
        var minY = first[0][1]
        var maxY = first[0][1]
        for (ring in region.rings) {
            for (p in ring) {
                val x = wrap180(p[0] - anchor)
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (p[1] < minY) minY = p[1]
                if (p[1] > maxY) maxY = p[1]
            }
        }
        return doubleArrayOf(anchor, anchor + minX, anchor + maxX, minY, maxY)
    }

    /** Even-odd point-in-polygon across all rings (rings are disjoint parts). */
    fun contains(region: AwardRegion, lat: Double, lon: Double): Boolean =
        region.rings.any { ringContains(it, lat, lon) }

    /**
     * Point-in-ring with the ring unwrapped into one continuous longitude
     * frame (same trick as the overlay's tracer). Rings that cross the
     * antimeridian (Fiji, the Pacific CQ zones) are stored with raw lons past
     * ±180 or folded; testing them in raw coordinates would split the polygon
     * at the date line and miss taps inside it. The tap is shifted to the
     * frame's nearest world copy first.
     */
    private fun ringContains(ring: List<DoubleArray>, lat: Double, lon: Double): Boolean {
        val n = ring.size
        if (n < 3) return false
        val anchor = ring[0][0]
        val lons = DoubleArray(n)
        var prevDelta = 0.0
        for (i in 0 until n) {
            var delta = ring[i][0] - anchor
            if (i > 0) {
                while (delta - prevDelta > 180.0) delta -= 360.0
                while (prevDelta - delta > 180.0) delta += 360.0
            }
            lons[i] = anchor + delta
            prevDelta = delta
        }
        val tapLon = anchor + wrap180(lon - anchor)
        var inside = false
        for (j in 0 until n) {
            val k = (j + 1) % n
            val y1 = ring[j][1]
            val y2 = ring[k][1]
            if ((y1 > lat) != (y2 > lat)) {
                val x = lons[j] + (lat - y1) / (y2 - y1) * (lons[k] - lons[j])
                if (x > tapLon) inside = !inside
            }
        }
        return inside
    }

    /** Shortest signed longitude difference a - b, in (-180, 180]. */
    private fun wrap180(deg: Double): Double {
        var d = deg % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }
}
