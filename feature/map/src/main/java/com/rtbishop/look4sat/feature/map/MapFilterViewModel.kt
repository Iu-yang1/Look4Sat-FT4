/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
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

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.model.AwardType

/**
 * Session-scoped holder for the map's award filter selection.
 *
 * Scoped to the Activity (not the navigation entry) so the selection survives
 * switching to another page and back; it resets to VUCC only when the process
 * starts fresh (cold start), which is what the user expects as the default.
 */
class MapFilterViewModel : ViewModel() {

    /** Last chosen award chip; null means "All". Initialized to VUCC once per process. */
    val selectedAward: MutableState<AwardType?> = mutableStateOf(AwardType.VUCC)

    /** Last map viewport (center + zoom), saved when leaving the page. Null until first visit. */
    var mapCenterLat: Double? = null
    var mapCenterLon: Double? = null
    var mapZoom: Double? = null

    /** Persists the current viewport so a later re-entry restores exactly where the user left off. */
    fun saveMapViewState(centerLat: Double, centerLon: Double, zoom: Double) {
        mapCenterLat = centerLat
        mapCenterLon = centerLon
        mapZoom = zoom
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer { MapFilterViewModel() }
        }
    }
}
