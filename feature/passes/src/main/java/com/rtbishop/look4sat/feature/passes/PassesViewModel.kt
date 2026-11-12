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
package com.rtbishop.look4sat.feature.passes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.predict.CelestialState
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.round
import com.rtbishop.look4sat.core.domain.utility.toTimerString
import com.rtbishop.look4sat.core.presentation.getDefaultPass
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PassesViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo
) : ViewModel() {
    private var sunTimesKey: List<Any?>? = null
    private var sunTimesCache: Map<String, Pair<String, String>> = emptyMap()

    private val defaultPass = getDefaultPass()
    private val _uiState = MutableStateFlow(
        PassesState(
            isUtc = settingsRepo.otherSettings.value.stateOfUtc,
            nextPass = defaultPass,
            hours = settingsRepo.passesSettings.value.hoursAhead,
            hoursBefore = settingsRepo.passesSettings.value.hoursBefore,
            elevation = settingsRepo.passesSettings.value.minElevation,
            lowElevation = settingsRepo.otherSettings.value.lowElevation,
            highElevation = settingsRepo.otherSettings.value.highElevation,
            aosStartMinute = settingsRepo.passesSettings.value.aosStartMinute,
            aosEndMinute = settingsRepo.passesSettings.value.aosEndMinute,
            invertAosTimeWindow = settingsRepo.passesSettings.value.invertAosTimeWindow,
            showDeepSpace = settingsRepo.passesSettings.value.showDeepSpace,
            modes = settingsRepo.passesSettings.value.selectedModes,
            shouldSeeWhatsNew = settingsRepo.otherSettings.value.shouldSeeWhatsNew
        )
    )
    val uiState: StateFlow<PassesState> = _uiState

    init {
        // Refresh indicator: mirrors the repo's isCalculating state
        viewModelScope.launch {
            satelliteRepo.isCalculating.collect { calculating ->
                _uiState.update { it.copy(isRefreshing = calculating) }
            }
        }
        // React to settings changes: update UTC flag and whatsNew
        viewModelScope.launch {
            settingsRepo.otherSettings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        isUtc = settings.stateOfUtc,
                        shouldSeeWhatsNew = settings.shouldSeeWhatsNew,
                        lowElevation = settings.lowElevation,
                        highElevation = settings.highElevation
                    )
                }
            }
        }
        // Rebuild the visible list whenever orbital passes change and on every clock tick.
        // Combining the source StateFlow with an independent ticker avoids tying the
        // LOS expiry refresh to the lifetime of a nested collectLatest loop.
        viewModelScope.launch {
            val clockTicks = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(1_000L)
                }
            }
            combine(satelliteRepo.passes, clockTicks) { allPasses, timeNow ->
                allPasses to timeNow
            }.collect { (allPasses, timeNow) ->
                val current = _uiState.value
                val isUtc = current.isUtc
                val showDeepSpace = current.showDeepSpace
                // Keep past passes only for the configured history window. With
                // hoursBefore=0, a pass disappears automatically as soon as LOS passes.
                val historyCutoff = timeNow - current.hoursBefore * 3_600_000L
                val filtered = allPasses
                    .let { if (showDeepSpace) it else it.filter { pass -> !pass.isDeepSpace } }
                    .filter { pass -> pass.losTime >= historyCutoff }
                val processed = computePassProgress(filtered, timeNow)
                val (nextPass, nextTime, isAos) = resolveNextPass(processed, timeNow)
                val sunTimes = withContext(Dispatchers.Default) { computeSunTimes(processed, isUtc) }
                val grouped = groupPasses(processed, isUtc)
                _uiState.update {
                    it.copy(
                        itemsList = processed,
                        groupedPasses = grouped,
                        sunTimes = sunTimes,
                        nextPass = nextPass,
                        nextTime = nextTime,
                        isNextTimeAos = isAos,
                        focusedCatNum = it.focusedCatNum?.takeIf { catNum ->
                            processed.any { pass -> pass.catNum == catNum }
                        }
                    )
                }
            }
        }
    }

    fun onAction(action: PassesAction) {
        when (action) {
            PassesAction.DismissWhatsNew -> settingsRepo.setWhatsNewDismissed()
            is PassesAction.FilterPasses ->
                applyFilter(
                    hoursAhead = action.hoursAhead,
                    hoursBefore = action.hoursBefore,
                    minElevation = action.minElevation,
                    lowElevation = action.lowElevation,
                    highElevation = action.highElevation,
                    aosStartMinute = action.aosStartMinute,
                    aosEndMinute = action.aosEndMinute,
                    invertAosTimeWindow = action.invertAosTimeWindow,
                    showDeepSpace = action.showDeepSpace,
                    modes = _uiState.value.modes
                )
            is PassesAction.FilterRadios ->
                applyFilter(
                    hoursAhead = _uiState.value.hours,
                    hoursBefore = _uiState.value.hoursBefore,
                    minElevation = _uiState.value.elevation,
                    lowElevation = _uiState.value.lowElevation,
                    highElevation = _uiState.value.highElevation,
                    aosStartMinute = _uiState.value.aosStartMinute,
                    aosEndMinute = _uiState.value.aosEndMinute,
                    invertAosTimeWindow = _uiState.value.invertAosTimeWindow,
                    showDeepSpace = _uiState.value.showDeepSpace,
                    modes = action.modes
                )
            PassesAction.RefreshPasses -> refreshPasses()
            PassesAction.TogglePassesDialog ->
                _uiState.update { it.copy(isPassesDialogShown = !it.isPassesDialogShown) }
            PassesAction.ToggleRadiosDialog ->
                _uiState.update { it.copy(isRadiosDialogShown = !it.isRadiosDialogShown) }
            is PassesAction.FocusCatNum -> _uiState.update { it.copy(focusedCatNum = action.catNum) }
            PassesAction.ClearFocus -> _uiState.update { it.copy(focusedCatNum = null) }
        }
    }

    /** Returns a locale-appropriate date format for pass grouping headers. */
    private fun dateFormat(tz: TimeZone): SimpleDateFormat {
        val pattern = if (Locale.getDefault().language == "zh") {
            "yyyy'年'M'月'd'日' EEEE"
        } else {
            "EEE, dd MMM yyyy"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).also { it.timeZone = tz }
    }

    // Cache per display day; never scan celestial events on each clock tick.
    private suspend fun computeSunTimes(passes: List<OrbitalPass>, isUtc: Boolean): Map<String, Pair<String, String>> {
        if (passes.isEmpty()) return emptyMap()
        val snapshot = passes.first().orbitalObject.snapshot ?: satelliteRepo.astroData.snapshot()
        val stationPos = settingsRepo.stationPosition.value
        val tz = if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val sdfDate = dateFormat(tz)
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault()).also { it.timeZone = tz }
        val key = listOf(stationPos, tz.id, Locale.getDefault(),
            passes.map { sdfDate.format(Date(it.aosTime)) }.distinct(),
            sdfDate.format(Date()), snapshot)
        if (sunTimesKey == key) return sunTimesCache
        val result = LinkedHashMap<String, Pair<String, String>>()
        for (pass in passes) {
            val label = sdfDate.format(Date(pass.aosTime))
            if (label in result) continue
            val riseSet = findTodaySunRiseSet(stationPos, startOfDayMillis(pass.aosTime, tz), tz, snapshot)
            val rise = if (riseSet.riseTimeMillis > 0) sdfTime.format(Date(riseSet.riseTimeMillis)) else "--:--"
            val set = if (riseSet.setTimeMillis > 0) sdfTime.format(Date(riseSet.setTimeMillis)) else "--:--"
            result[label] = rise to set
        }
        sunTimesKey = key
        sunTimesCache = result
        return result
    }

    /** Calendar arithmetic preserves 23/25-hour days across daylight-saving changes. */
    private suspend fun findTodaySunRiseSet(
        observer: GeoPos, localMidnightMillis: Long, timezone: TimeZone,
        snapshot: com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot
    ): CelestialState.RiseSetTimes {
        val end = Calendar.getInstance(timezone).apply {
            timeInMillis = localMidnightMillis
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val context = kotlin.coroutines.coroutineContext
        return satelliteRepo.celestial.riseSet(observer, localMidnightMillis, end, snapshot = snapshot,
            checkCancelled = { context.ensureActive() })
    }

    private fun startOfDayMillis(timeMillis: Long, tz: TimeZone): Long {
        return Calendar.getInstance(tz, Locale.getDefault()).apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun groupPasses(passes: List<OrbitalPass>, isUtc: Boolean): Map<String, List<OrbitalPass>> {
        val tz = if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val sdfDate = dateFormat(tz)
        val ordered = LinkedHashMap<String, List<OrbitalPass>>()
        passes.groupByTo(LinkedHashMap()) { sdfDate.format(Date(it.aosTime)) }
            .forEach { (k, v) -> ordered[k] = v }
        return ordered
    }

    /** Computes live progress while retaining completed passes in the requested history window. */
    private fun computePassProgress(passList: List<OrbitalPass>, time: Long): List<OrbitalPass> {
        val result = ArrayList<OrbitalPass>(passList.size)
        for (pass in passList) {
            if (!pass.continuousVisibility && time > pass.aosTime) {
                val deltaNow = time.minus(pass.aosTime).toFloat()
                val deltaTotal = pass.losTime.minus(pass.aosTime).toFloat()
                val newProgress = (deltaNow / deltaTotal).round(2)
                val boundedProgress = newProgress.coerceIn(0f, 1f)
                if (boundedProgress != pass.progress) {
                    result.add(pass.copy(progress = boundedProgress))
                } else {
                    result.add(pass)
                }
            } else {
                result.add(pass)
            }
        }
        return result
    }

    /** Resolves the next upcoming or active pass and its countdown timer. */
    private fun resolveNextPass(
        passes: List<OrbitalPass>,
        timeNow: Long
    ): Triple<OrbitalPass, String, Boolean> {
        val upcoming = passes.firstOrNull { it.aosTime > timeNow }
        if (upcoming != null) {
            return Triple(upcoming, (upcoming.aosTime - timeNow).toTimerString(), true)
        }
        if (passes.isNotEmpty()) {
            val lastPass = passes.last()
            return Triple(lastPass, (lastPass.losTime - timeNow).toTimerString(), false)
        }
        return Triple(defaultPass, "00:00:00", true)
    }

    private fun applyFilter(
        hoursAhead: Int,
        hoursBefore: Int,
        minElevation: Double,
        lowElevation: Double,
        highElevation: Double,
        aosStartMinute: Int,
        aosEndMinute: Int,
        invertAosTimeWindow: Boolean,
        showDeepSpace: Boolean,
        modes: List<String>
    ) = viewModelScope.launch {
        settingsRepo.setPassesSettings(
            PassesSettings(
                showDeepSpace,
                hoursAhead,
                minElevation,
                aosStartMinute,
                aosEndMinute,
                invertAosTimeWindow,
                modes,
                hoursBefore
            )
        )
        settingsRepo.updateOtherSettings { it.copy(lowElevation = lowElevation, highElevation = highElevation) }
        _uiState.update {
            it.copy(
                hours = hoursAhead,
                hoursBefore = hoursBefore,
                elevation = minElevation,
                lowElevation = lowElevation,
                highElevation = highElevation,
                aosStartMinute = aosStartMinute,
                aosEndMinute = aosEndMinute,
                invertAosTimeWindow = invertAosTimeWindow,
                showDeepSpace = showDeepSpace,
                modes = modes
            )
        }
        val now = System.currentTimeMillis()
        satelliteRepo.calculatePasses(
            time = now - hoursBefore * 60L * 60L * 1000L,
            hoursAhead = hoursBefore + hoursAhead,
            minElevation = minElevation,
            aosStartMinute = aosStartMinute,
            aosEndMinute = aosEndMinute,
            invertAosTimeWindow = invertAosTimeWindow,
            modes = modes
        )
    }

    private fun refreshPasses() = viewModelScope.launch {
        val settings = settingsRepo.passesSettings.value
        val now = System.currentTimeMillis()
        satelliteRepo.calculatePasses(
            time = now - settings.hoursBefore * 60L * 60L * 1000L,
            hoursAhead = settings.hoursBefore + settings.hoursAhead,
            minElevation = settings.minElevation,
            aosStartMinute = settings.aosStartMinute,
            aosEndMinute = settings.aosEndMinute,
            invertAosTimeWindow = settings.invertAosTimeWindow,
            modes = settings.selectedModes
        )
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                PassesViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo
                )
            }
        }
    }
}
