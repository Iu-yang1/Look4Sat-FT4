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
