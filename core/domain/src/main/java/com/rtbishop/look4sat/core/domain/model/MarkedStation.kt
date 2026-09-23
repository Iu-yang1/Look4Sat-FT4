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
package com.rtbishop.look4sat.core.domain.model

/**
 * A station the user marked in a gridsquare they have NOT yet worked — a
 * reminder to try to contact that callsign. Stored per 4-char grid (one mark
 * per grid).
 *
 * @param call      the callsign the user wants to work (uppercase)
 * @param epochMs   the moment the mark was created, UTC milliseconds
 */
data class MarkedStation(
    val call: String,
    val epochMs: Long
)
