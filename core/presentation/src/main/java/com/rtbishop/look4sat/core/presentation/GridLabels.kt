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
package com.rtbishop.look4sat.core.presentation

import java.util.Locale

/**
 * Grid sets abbreviated the way operators read them: a grid is shortened to its
 * last two characters while its field (first two characters) stays the same as
 * the previous grid's; a field change is spelled out — "OL61/62" (same field)
 * but "OM91/PM01" (a shared prefix would read as OM01, the wrong square).
 * Used for the station grids a QSO was uploaded under (logbook rows and the
 * radar log page) and for the opposite station's confirmed grid set.
 */
fun gridsLabel(grids: List<String>): String {
    val clean = grids.map { it.trim().uppercase(Locale.US).take(4) }
        .filter { it.length >= 4 }.distinct().sorted()
    if (clean.isEmpty()) return ""
    return buildString {
        append(clean.first())
        for (index in 1 until clean.size) {
            val grid = clean[index]
            if (grid.take(2) == clean[index - 1].take(2)) append('/').append(grid.takeLast(2))
            else append('/').append(grid)
        }
    }
}
