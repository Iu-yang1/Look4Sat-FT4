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

data class Ft4SlotBoundary(
    val index: Long,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val sequence: Int
)

class DisciplinedFt4SlotScheduler {
    fun boundaryAt(utcMillis: Long): Ft4SlotBoundary {
        val index = Math.floorDiv(utcMillis, PERIOD_MILLIS)
        val start = index * PERIOD_MILLIS
        return Ft4SlotBoundary(
            index = index,
            startUtcMillis = start,
            endUtcMillis = start + PERIOD_MILLIS,
            sequence = Math.floorMod(index, 2L).toInt()
        )
    }

    fun nextBoundaryAfter(utcMillis: Long): Ft4SlotBoundary {
        val current = boundaryAt(utcMillis)
        return boundaryAt(current.endUtcMillis)
    }

    fun progress(utcMillis: Long): Float {
        val boundary = boundaryAt(utcMillis)
        return ((utcMillis - boundary.startUtcMillis).toDouble() / PERIOD_MILLIS)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    companion object {
        const val PERIOD_MILLIS = 7_500L
    }
}
