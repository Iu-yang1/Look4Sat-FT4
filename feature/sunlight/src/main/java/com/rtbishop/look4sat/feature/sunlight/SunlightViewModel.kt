package com.rtbishop.look4sat.feature.sunlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.sunlight.ISunlightPredictor
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import com.rtbishop.look4sat.core.domain.sunlight.SunlightException
import com.rtbishop.look4sat.core.domain.sunlight.SunlightPrediction
import com.rtbishop.look4sat.core.domain.sunlight.SunlightProblem
import com.rtbishop.look4sat.core.domain.sunlight.SunlightRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

enum class SunlightSort { START, DURATION }
enum class SunlightPanelPage { NONE, CONDITIONS, SATELLITES, DATE, FILTER, DETAIL }

data class SunlightPageState(
    val panel: SunlightPanelPage = SunlightPanelPage.NONE,
    val draftCatalog: Int? = null,
    val draftStart: Long = 0,
    val draftDays: String = "30",
    val detailStart: Long? = null,
    val statisticsExpanded: Boolean = false
)

data class SunlightState(
    val satellites: List<SatItem> = emptyList(),
    val loadingSatellites: Boolean = true,
    val catalogNumber: Int? = null,
    val startUtcMillis: Long = utcDayStart(System.currentTimeMillis()),
    val days: String = "30",
    val calculating: Boolean = false,
    val progress: Double = 0.0,
    val cancelled: Boolean = false,
    val problem: SunlightProblem? = null,
    val prediction: SunlightPrediction? = null,
    val displayUtc: Boolean = false,
    val filter: Illumination? = Illumination.SUNLIGHT,
    val minimumMinutes: Long = 0,
    val sort: SunlightSort = SunlightSort.DURATION,
    val visibleIntervals: List<IlluminationInterval> = emptyList(),
    val page: SunlightPageState = SunlightPageState(),
    // Retained with this ViewModel, never restored from the previous process.
    val panelStateKey: String = UUID.randomUUID().toString()
) {
    val daysValid: Boolean get() = days.toIntOrNull()?.let { it in 1..365 } == true
    val selectedSatellite: SatItem? get() = satellites.firstOrNull { it.catnum == catalogNumber }
}

sealed interface SunlightAction {
    data object OpenConditions : SunlightAction
    data class ChangePanel(val page: SunlightPanelPage) : SunlightAction
    data class DraftCatalog(val catalogNumber: Int) : SunlightAction
    data class DraftStart(val utcMillis: Long) : SunlightAction
    data class DraftDays(val text: String) : SunlightAction
    data class OpenDetails(val startMillis: Long) : SunlightAction
    data object ToggleStatistics : SunlightAction
    data class SubmitConditions(val catalogNumber: Int, val startUtcMillis: Long, val days: Int) : SunlightAction
    data class Filter(val state: Illumination?, val minimumMinutes: Long, val sort: SunlightSort) : SunlightAction
    data object Predict : SunlightAction
    data object Cancel : SunlightAction
    data object ReloadSatellites : SunlightAction
}

class SunlightViewModel(
    private val predictor: ISunlightPredictor,
    private val selectedIds: StateFlow<List<Int>>,
    displayUtc: Flow<Boolean>,
    private val saved: SavedStateHandle
) : ViewModel() {
    private val mutableState = MutableStateFlow(SunlightState(
        catalogNumber = saved["catalog"],
        startUtcMillis = saved["start"] ?: utcDayStart(System.currentTimeMillis()),
        days = saved["days"] ?: "30"
    ))
    val uiState = mutableState.asStateFlow()
    private var calculation: Job? = null
    private var catalogJob: Job? = null
    @Volatile private var generation = 0L

    init {
        reloadSatellites()
        viewModelScope.launch {
            displayUtc.collect { utc -> mutableState.update { it.copy(displayUtc = utc) } }
        }
        viewModelScope.launch {
            selectedIds.collect { ids ->
                mutableState.update { it.copy(satellites = orderSatellites(it.satellites, ids)) }
            }
        }
    }

    fun onAction(action: SunlightAction) {
        when (action) {
            SunlightAction.OpenConditions -> mutableState.update {
                it.copy(page = it.page.copy(panel = SunlightPanelPage.CONDITIONS,
                    draftCatalog = it.catalogNumber, draftStart = it.startUtcMillis, draftDays = it.days))
            }
            is SunlightAction.ChangePanel -> mutableState.update {
                val page = if (action.page == SunlightPanelPage.NONE) {
                    SunlightPageState(statisticsExpanded = it.page.statisticsExpanded)
                } else it.page.copy(panel = action.page)
                it.copy(page = page)
            }
            is SunlightAction.DraftCatalog -> mutableState.update {
                it.copy(page = it.page.copy(draftCatalog = action.catalogNumber))
            }
            is SunlightAction.DraftStart -> mutableState.update {
                it.copy(page = it.page.copy(draftStart = utcDayStart(action.utcMillis)))
            }
            is SunlightAction.DraftDays -> mutableState.update {
                it.copy(page = it.page.copy(draftDays = action.text))
            }
            is SunlightAction.OpenDetails -> mutableState.update {
                if (it.prediction?.intervals?.any { interval -> interval.startMillis == action.startMillis } == true) {
                    it.copy(page = it.page.copy(panel = SunlightPanelPage.DETAIL, detailStart = action.startMillis))
                } else it
            }
            SunlightAction.ToggleStatistics -> mutableState.update {
                it.copy(page = it.page.copy(statisticsExpanded = !it.page.statisticsExpanded))
            }
            is SunlightAction.SubmitConditions -> {
                if (action.days !in 1..365 ||
                    uiState.value.satellites.none { it.catnum == action.catalogNumber }) return
                val request = SunlightRequest(action.catalogNumber, utcDayStart(action.startUtcMillis), action.days)
                saved["catalog"] = request.catalogNumber
                saved["start"] = request.startUtcMillis
                saved["days"] = request.days.toString()
                startPrediction(request)
            }
            is SunlightAction.Filter -> {
                require(action.minimumMinutes >= 0)
                mutableState.update { state ->
                    val updated = state.copy(
                        filter = action.state, minimumMinutes = action.minimumMinutes, sort = action.sort
                    )
                    updated.copy(visibleIntervals = filterIntervals(updated))
                }
            }
            SunlightAction.Predict -> predict()
            SunlightAction.Cancel -> {
                generation++
                calculation?.cancel()
                mutableState.update { it.copy(calculating = false, cancelled = true) }
            }
            SunlightAction.ReloadSatellites -> reloadSatellites()
        }
    }

    private fun invalidate() {
        generation++
        calculation?.cancel()
        mutableState.update {
            it.copy(calculating = false, cancelled = false, problem = null, prediction = null,
                visibleIntervals = emptyList(), page = SunlightPageState(
                    statisticsExpanded = it.page.statisticsExpanded))
        }
    }

    private fun reloadSatellites() {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingSatellites = true) }
            try {
                val list = orderSatellites(predictor.satellites(), selectedIds.value)
                val currentId = uiState.value.catalogNumber
                val id = currentId?.takeIf { id -> list.any { it.catnum == id } } ?: list.firstOrNull()?.catnum
                if (id != currentId) invalidate()
                saved["catalog"] = id
                mutableState.update { state ->
                    state.copy(satellites = list, catalogNumber = id, loadingSatellites = false, problem = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loadingSatellites = false, problem = SunlightProblem.ELEMENTS_UNAVAILABLE) }
            }
        }
    }

    private fun predict() {
        val input = uiState.value
        val id = input.catalogNumber ?: return
        if (!input.daysValid) return
        startPrediction(SunlightRequest(id, input.startUtcMillis, input.days.toInt()))
    }

    private fun startPrediction(request: SunlightRequest) {
        val previous = calculation
        previous?.cancel()
        val token = ++generation
        mutableState.update {
            it.copy(catalogNumber = request.catalogNumber, startUtcMillis = request.startUtcMillis,
                days = request.days.toString(), calculating = true, cancelled = false, progress = 0.0, problem = null,
                prediction = null, visibleIntervals = emptyList(),
                page = SunlightPageState(statisticsExpanded = it.page.statisticsExpanded))
        }
        calculation = viewModelScope.launch {
            previous?.join()
            try {
                val result = predictor.predict(request) { progress ->
                    mutableState.update {
                        if (generation == token) it.copy(progress = progress.coerceIn(0.0, 1.0)) else it
                    }
                }
                mutableState.update {
                    if (generation == token) {
                        val updated = it.copy(calculating = false, prediction = result, problem = result.problem)
                        updated.copy(visibleIntervals = filterIntervals(updated))
                    } else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    if (generation == token) it.copy(calculating = false,
                        problem = (error as? SunlightException)?.problem ?: SunlightProblem.COMPUTATION_FAILED)
                    else it
                }
            }
        }
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                SunlightViewModel(
                    container.sunlightPredictor, container.settingsRepo.selectedIds,
                    container.settingsRepo.otherSettings.map { it.stateOfUtc }, createSavedStateHandle()
                )
            }
        }
    }
}

internal fun utcDayStart(timeMillis: Long): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun orderSatellites(list: List<SatItem>, selected: List<Int>): List<SatItem> {
    val ids = selected.toSet()
    return list.map { it.copy(isSelected = it.catnum in ids) }
        .sortedWith(compareByDescending<SatItem> { it.isSelected }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

internal fun filterIntervals(state: SunlightState): List<IlluminationInterval> {
    val filtered = state.prediction?.intervals.orEmpty().filter {
        (state.filter == null || it.state == state.filter) &&
            it.durationMillis / 60_000L >= state.minimumMinutes
    }
    return when (state.sort) {
        SunlightSort.START -> filtered.sortedBy { it.startMillis }
        SunlightSort.DURATION -> filtered.sortedWith(
            compareByDescending<IlluminationInterval> { it.durationMillis }.thenBy { it.startMillis }
        )
    }
}
