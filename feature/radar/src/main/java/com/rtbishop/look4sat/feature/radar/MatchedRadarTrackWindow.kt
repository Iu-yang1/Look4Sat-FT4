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
package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.MutualPassData

/** Only the position inside this station's own above-horizon interval is live. */
internal fun isWithinMatchedRadarWindow(time: Long, window: Pair<Long, Long>?): Boolean =
    window != null && time >= window.first && time < window.second

/** Use the station A coordinates that generated this match, if supplied. */
internal fun matchedRadarObserverPosition(session: MutualPassData, fallback: GeoPos): GeoPos {
    val lat = session.stationALatitude ?: return fallback
    val lon = session.stationALongitude ?: return fallback
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
        return fallback
    }
    return GeoPos(lat, lon)
}
