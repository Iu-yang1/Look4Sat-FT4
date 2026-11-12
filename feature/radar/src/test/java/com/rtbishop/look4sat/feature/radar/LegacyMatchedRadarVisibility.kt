/*
 * Look4Sat. Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.feature.radar

/** Historical window expansion, retained only for offline regression comparisons. */
internal fun expandMatchedRadarLocalVisibilityWindow(
    mutualStart: Long,
    mutualEnd: Long,
    isAboveHorizon: (Long) -> Boolean
): Pair<Long, Long> {
    if (mutualEnd <= mutualStart ||
        !isAboveHorizon(mutualStart) || !isAboveHorizon(mutualEnd)
    ) return mutualStart to mutualEnd

    val stepMs = 15_000L
    val maxSteps = 60 * 60_000L / stepMs

    fun findEdge(anchor: Long, direction: Long): Long {
        var inside = anchor
        repeat(maxSteps.toInt()) {
            val outside = inside + direction * stepMs
            if (!isAboveHorizon(outside)) {
                var lower = minOf(inside, outside)
                var upper = maxOf(inside, outside)
                while (upper - lower > 1_000L) {
                    val mid = lower + (upper - lower) / 2L
                    if (isAboveHorizon(mid) == (direction < 0L)) {
                        upper = mid
                    } else {
                        lower = mid
                    }
                }
                return if (direction < 0L) upper else lower
            }
            inside = outside
        }
        return anchor
    }

    return findEdge(mutualStart, -1L) to findEdge(mutualEnd, 1L)
}
