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
package com.rtbishop.look4sat.feature.gridfinder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.ILocationRepo
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISensorsRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.LocationFix
import com.rtbishop.look4sat.core.domain.repository.LocationState
import com.rtbishop.look4sat.core.domain.utility.gridFixStatus
import com.rtbishop.look4sat.core.domain.utility.gridGeometry
import com.rtbishop.look4sat.core.domain.utility.vuccClaimableGrids
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Declination is only recomputed once the fix has moved this far (degrees). */
private const val DECLINATION_REFRESH_DEGREES = 0.01

/**
 * Drives the Grid Finder: streams the live fix, turns it into grid-line /
 * grid-corner geometry, and gates the VUCC claim on [PRECISE_FIXES_REQUIRED]
 * consecutive fixes good to the 20 ft rule.
 *
 * The live fix never touches the station position - [GridFinderAction.SetAsStation]
 * is the only thing that writes it, and only when the operator asks.
 */
class GridFinderViewModel(
    private val settingsRepo: ISettingsRepo,
    private val locationRepo: ILocationRepo,
    private val sensorsRepo: ISensorsRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(GridFinderState())
    val uiState: StateFlow<GridFinderState> = _uiState.asStateFlow()

    private var locationJob: Job? = null
    private var compassJob: Job? = null
    private var accuracyJob: Job? = null
    private var sensorsEnabledByUs = false
    private var declinationCache: Pair<GeoPos, Float>? = null
    private var lastSensorAzimuth: Float? = null

    fun onAction(action: GridFinderAction) {
        when (action) {
            GridFinderAction.StartUpdates -> startUpdates()
            GridFinderAction.StopUpdates -> stopUpdates()
            is GridFinderAction.LocationPermissionResult -> startUpdates()
            GridFinderAction.SetAsStation -> setAsStation()
            GridFinderAction.StationSavedShown -> _uiState.update { it.copy(isStationSaved = false) }
            is GridFinderAction.SetCompassOffset -> setCompassOffset(action.offsetDegrees)
        }
    }

    /**
     * Saves the compass trim shared with the radar page, then recomputes the
     * heading from the last raw sensor sample so the dial reacts immediately.
     */
    private fun setCompassOffset(offsetDegrees: Float) {
        settingsRepo.updateOtherSettings { it.copy(compassOffsetDegrees = offsetDegrees) }
        lastSensorAzimuth?.let { azimuth ->
            _uiState.update { it.copy(headingDegrees = correctedHeading(azimuth)) }
        }
    }

    private fun startUpdates() {
        val permissionGranted = locationRepo.hasPermission()
        _uiState.update { it.copy(permissionGranted = permissionGranted) }
        if (!permissionGranted) return
        locationRepo.startUpdates()
        collectLocation()
        collectCompass()
    }

    private fun collectLocation() {
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            locationRepo.state.collect { state ->
                when (state) {
                    is LocationState.Fix -> onFix(state.location)
                    is LocationState.Waiting -> _uiState.update {
                        it.copy(
                            permissionGranted = state.permissionGranted,
                            providerEnabled = state.providerEnabled
                        )
                    }
                }
            }
        }
    }

    private fun collectCompass() {
        val compassEnabled = settingsRepo.otherSettings.value.stateOfSensors
        _uiState.update {
            it.copy(isCompassEnabled = compassEnabled, compassAccuracy = sensorsRepo.compassAccuracy.value)
        }
        if (!compassEnabled || compassJob != null) return
        sensorsRepo.enableSensor()
        sensorsEnabledByUs = true
        accuracyJob = viewModelScope.launch {
            sensorsRepo.compassAccuracy.collect { accuracy ->
                _uiState.update { it.copy(compassAccuracy = accuracy) }
            }
        }
        compassJob = viewModelScope.launch {
            sensorsRepo.sensorData.collect { data ->
                lastSensorAzimuth = data.first
                _uiState.update { it.copy(headingDegrees = correctedHeading(data.first)) }
            }
        }
    }

    /** Same convention as the radar page: sensor azimuth + magnetic declination + manual trim. */
    private fun correctedHeading(sensorAzimuth: Float): Float {
        val offset = settingsRepo.otherSettings.value.compassOffsetDegrees
        val azimuth = sensorAzimuth + magDeclination() + offset
        return (azimuth % 360f + 360f) % 360f
    }

    private fun magDeclination(): Float {
        val position = _uiState.value.fix?.let { GeoPos(it.latitude, it.longitude, it.altitudeMeters) }
            ?: settingsRepo.stationPosition.value
        val cached = declinationCache
        if (cached != null &&
            abs(cached.first.latitude - position.latitude) < DECLINATION_REFRESH_DEGREES &&
            abs(cached.first.longitude - position.longitude) < DECLINATION_REFRESH_DEGREES
        ) {
            return cached.second
        }
        val declination = sensorsRepo.getMagDeclination(position)
        declinationCache = position to declination
        return declination
    }

    private fun onFix(fix: LocationFix) {
        val geometry = gridGeometry(fix.latitude, fix.longitude)
        val claimable = vuccClaimableGrids(fix.latitude, fix.longitude)
        val gate = nextFixGate(_uiState.value.preciseFixesInARow, fix)
        _uiState.update {
            it.copy(
                permissionGranted = true,
                providerReady = geometry != null,
                fix = fix,
                geometry = geometry,
                claimableGrids = claimable,
                status = if (claimable.isEmpty()) null else gridFixStatus(claimable),
                preciseFixesInARow = gate.streak,
                isStandingConfirmed = gate.isConfirmed
            )
        }
    }

    private fun setAsStation() {
        val fix = _uiState.value.fix ?: return
        val saved = settingsRepo.setStationPosition(fix.latitude, fix.longitude, fix.altitudeMeters)
        if (saved) _uiState.update { it.copy(isStationSaved = true) }
    }

    private fun stopUpdates() {
        locationRepo.stopUpdates()
        locationJob?.cancel()
        locationJob = null
        compassJob?.cancel()
        compassJob = null
        accuracyJob?.cancel()
        accuracyJob = null
        if (sensorsEnabledByUs) {
            sensorsRepo.disableSensor()
            sensorsEnabledByUs = false
        }
    }

    override fun onCleared() {
        stopUpdates()
        super.onCleared()
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                GridFinderViewModel(
                    settingsRepo = container.settingsRepo,
                    locationRepo = container.locationRepo,
                    sensorsRepo = container.provideSensorsRepo()
                )
            }
        }
    }
}
