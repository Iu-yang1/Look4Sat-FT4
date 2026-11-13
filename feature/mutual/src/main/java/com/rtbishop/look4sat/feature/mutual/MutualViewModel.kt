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
package com.rtbishop.look4sat.feature.mutual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.repository.TrackSampleData
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt

private const val TAG = "Look4SatMutual"

/** Ready-to-open matched radar session, including the other station's grid-centre pin. */
data class RadarMatchSelection(
    val catNum: Int,
    val aosTime: Long,
    val stationBGrid: String,
    val mutualPassData: MutualPassData
)

data class MutualUiState(
    val stationALat: String = "",
    val stationALon: String = "",
    val stationAGrid: String = "",
    val stationAGridInvalid: Boolean = false,
    val stationAMinElev: Double = 10.0,
    val stationBLat: String = "",
    val stationBLon: String = "",
    val stationBGrid: String = "",
    val stationBGridInvalid: Boolean = false,
    val stationBMinElev: Double = 10.0,
    val hoursAhead: Int = 24,
    /** 历史过境回看窗口(小时), 跟随主页面 Passes 设置; 匹配查询从
     *  now-hoursBefore 开始, 使设置过历史回看时匹配页也包含历史过境. */
    val hoursBefore: Int = 0,
    /** 转发器类型筛选: 只匹配搭载 FM 话音转发器的卫星. 默认开启. */
    val filterFM: Boolean = true,
    /** 转发器类型筛选: 只匹配搭载线性(USB/LSB/CW/SSB)转发器的卫星. 默认开启. */
    val filterLinear: Boolean = true,
    val mutualPasses: List<MutualPass> = emptyList(),
    /** Optional satellite focus applied by swiping right on a result card. */
    val focusedCatNum: Int? = null,
    val isCalculating: Boolean = false,
    val hasSearched: Boolean = false,
    val selectedPassIndex: Int = -1,
    val isUtc: Boolean = false,
    val errorMessage: String? = null,
    // One-shot flag set by prefillMatchFromGrid() (map grid → Match button):
    // the page scrolls to the time-range card after the first layout, then
    // consumeScrollToTimeRange() clears it.
    val scrollToTimeRange: Boolean = false,
    // One-shot flag set by prefillMatchFromGrid(): the map page waits for the
    // pre-filled query to finish, then navigates to the match page so the very
    // first frame already contains the results (time-range card + pass curves,
    // no scroll flicker). consumePendingNavigation() clears it.
    val pendingNavigation: Boolean = false,
    /** Incremented when returning from the grid picker so the exact list viewport is restored. */
    val scrollRestoreGeneration: Long = 0L
)

class MutualViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    // Injected so unit tests can run the pass computation on a test dispatcher;
    // production callers keep the CPU-bound pool default.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Injected so unit tests can pin the wall clock to the fixture TLE epoch and
    // stay deterministic; production callers keep the real clock default.
    private val nowMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _uiState = MutableStateFlow(MutualUiState())
    val uiState: StateFlow<MutualUiState> = _uiState.asStateFlow()

    // Session-scoped scroll position of the pass list (Activity-scoped VM, so it
    // survives switching to another page and back), including across re-queries.
    var listScrollIndex: Int = 0
    var listScrollOffset: Int = 0

    /** Bumped on every query so the list state rebuilds from the stored position. */
    var queryGeneration: Int = 0
    private var queryJob: kotlinx.coroutines.Job? = null

    init {
        // Pre-fill station A with the user's current station position (as grid),
        // and default min elevation to the same value used by the main radar passes
        val pos = settingsRepo.stationPosition.value
        val grid = positionToQth(pos.latitude, pos.longitude) ?: ""
        _uiState.update { it.copy(
            stationAGrid = grid,
            stationALat = "%.4f".format(Locale.US, pos.latitude),
            stationALon = "%.4f".format(Locale.US, pos.longitude),
            stationAMinElev = 0.0,
            stationBMinElev = 0.0,
            isUtc = settingsRepo.otherSettings.value.stateOfUtc
        ) }
        // Keep the time display in sync with the UTC toggle in Settings
        viewModelScope.launch {
            settingsRepo.otherSettings.collectLatest { settings ->
                _uiState.update { it.copy(isUtc = settings.stateOfUtc) }
            }
        }
        // 历史过境回看窗口跟随主页面 Passes 设置 (设置里改小时数, 匹配页同步生效)
        viewModelScope.launch {
            settingsRepo.passesSettings.collectLatest { settings ->
                _uiState.update { it.copy(hoursBefore = settings.hoursBefore) }
            }
        }
    }

    fun onStationALat(value: String) {
        _uiState.update { it.copy(stationALat = value) }
        val lat = value.toDoubleOrNull()
        val lon = _uiState.value.stationALon.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationAGrid = grid, stationAGridInvalid = false) }
            }
        }
    }

    fun onStationALon(value: String) {
        _uiState.update { it.copy(stationALon = value) }
        val lat = _uiState.value.stationALat.toDoubleOrNull()
        val lon = value.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationAGrid = grid, stationAGridInvalid = false) }
            }
        }
    }

    fun onStationAGrid(value: String) {
        val newGrid = value.trim().uppercase()
        val pos = qthToPosition(newGrid)
        _uiState.update { state ->
            if (pos != null) state.copy(
                stationAGrid = newGrid, stationAGridInvalid = false,
                stationALat = "%.4f".format(Locale.US, pos.latitude),
                stationALon = "%.4f".format(Locale.US, pos.longitude)
            ) else state.copy(stationAGrid = newGrid, stationAGridInvalid = true)
            // Keep the previous precise coordinate inputs through partial edits,
            // but block searching until the grid is valid again.
        }
    }

    fun onStationBLat(value: String) {
        _uiState.update { it.copy(stationBLat = value) }
        val lat = value.toDoubleOrNull()
        val lon = _uiState.value.stationBLon.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationBGrid = grid, stationBGridInvalid = false) }
            }
        }
    }

    fun onStationBLon(value: String) {
        _uiState.update { it.copy(stationBLon = value) }
        val lat = _uiState.value.stationBLat.toDoubleOrNull()
        val lon = value.toDoubleOrNull()
        if (lat != null && lon != null) {
            positionToQth(lat, lon)?.let { grid ->
                _uiState.update { it.copy(stationBGrid = grid, stationBGridInvalid = false) }
            }
        }
    }

    fun onStationBGrid(value: String) {
        val newGrid = value.trim().uppercase()
        val pos = qthToPosition(newGrid)
        _uiState.update { state ->
            if (pos != null) state.copy(
                stationBGrid = newGrid, stationBGridInvalid = false,
                stationBLat = "%.4f".format(Locale.US, pos.latitude),
                stationBLon = "%.4f".format(Locale.US, pos.longitude)
            ) else state.copy(stationBGrid = newGrid, stationBGridInvalid = true)
        }
    }
    fun onStationAMinElev(value: Double) = _uiState.update { it.copy(stationAMinElev = value) }
    fun onStationBMinElev(value: Double) = _uiState.update { it.copy(stationBMinElev = value) }
    fun onUseCurrentPosition() {
        val pos = settingsRepo.stationPosition.value
        _uiState.update { it.copy(
            stationALat = "%.4f".format(Locale.US, pos.latitude),
            stationALon = "%.4f".format(Locale.US, pos.longitude),
            stationAGrid = positionToQth(pos.latitude, pos.longitude) ?: "",
            stationAGridInvalid = false
        ) }
    }
    fun onHoursAhead(value: Int) = _uiState.update { it.copy(hoursAhead = value) }
    fun onFilterFM(value: Boolean) = _uiState.update { it.copy(filterFM = value) }
    fun onFilterLinear(value: Boolean) = _uiState.update { it.copy(filterLinear = value) }
    fun onSelectPass(index: Int) = _uiState.update { it.copy(selectedPassIndex = index) }

    /** Show only mutual passes for the swiped satellite. */
    fun onFocusCatNum(catNum: Int) = _uiState.update {
        it.copy(focusedCatNum = catNum, selectedPassIndex = -1)
    }

    /** Clear the satellite focus and show mutual passes for all satellites again. */
    fun onClearFocus() = _uiState.update {
        it.copy(focusedCatNum = null, selectedPassIndex = -1)
    }

    /**
     * Run the existing two-station pass calculation from the radar overflow menu,
     * then return the mutual pass overlapping the radar's selected satellite pass.
     * This does not request navigation to the Match tab.
     */
    fun prepareRadarMatchFromGrid(
        grid: String,
        catNum: Int,
        aos: Long,
        los: Long,
        onComplete: (RadarMatchSelection?) -> Unit
    ) {
        val normalized = grid.trim().uppercase()
        val target = qthToPosition(normalized)
        if (target == null) {
            onComplete(null)
            return
        }
        _uiState.update { state ->
            val enableBothFilters = !state.filterFM && !state.filterLinear
            // A match launched from a physical radar must use that radar's
            // actual station A, not a stale custom position from the Match tab.
            val local = settingsRepo.stationPosition.value
            state.copy(
                stationALat = "%.6f".format(Locale.US, local.latitude),
                stationALon = "%.6f".format(Locale.US, local.longitude),
                stationAGrid = positionToQth(local.latitude, local.longitude) ?: state.stationAGrid,
                stationAGridInvalid = false,
                stationBGrid = normalized,
                stationBGridInvalid = false,
                stationBLat = "%.4f".format(Locale.US, target.latitude),
                stationBLon = "%.4f".format(Locale.US, target.longitude),
                hoursAhead = 24,
                pendingNavigation = false,
                scrollToTimeRange = false,
                filterFM = state.filterFM || enableBothFilters,
                filterLinear = state.filterLinear || enableBothFilters
            )
        }
        queryMutualPasses(radarPass = Triple(catNum, aos, los), onCompleted = { passes ->
            val selected = mutualPassForRadar(passes, catNum, aos, los)
            val selection = selected?.let { pass ->
                RadarMatchSelection(
                    catNum = pass.catNum,
                    aosTime = pass.startTime,
                    stationBGrid = normalized,
                    mutualPassData = MutualPassData(
                        samples = pass.elevationSamples,
                        trackSamples = pass.trackSamples.map { sample ->
                            TrackSampleData(
                                time = sample.time,
                                azimuthA = sample.azimuthA,
                                elevationA = sample.elevationA,
                                azimuthB = sample.azimuthB,
                                elevationB = sample.elevationB
                            )
                        },
                        startTime = pass.startTime,
                        endTime = pass.endTime,
                        maxElev = maxOf(pass.maxElevationA, pass.maxElevationB, 10.0),
                        labelA = "你",
                        labelB = "友台",
                        stationBGrid = normalized,
                        stationBLatitude = target.latitude,
                        stationBLongitude = target.longitude,
                        stationALatitude = settingsRepo.stationPosition.value.latitude,
                        stationALongitude = settingsRepo.stationPosition.value.longitude
                    )
                )
            }
            onComplete(selection)
        })
    }

    /**
     * Pre-fill the match page for a target grid picked from the map's grid-QSO
     * dialog ("Match" button): set the opposite-station grid (with its
     * coordinates), reset the time range to 24h, start the query immediately
     * so results are ready when the page opens, and flag the page to scroll
     * straight to the time-range card once it is laid out.
     */
    fun prefillMatchFromGrid(grid: String) {
        val g = grid.trim().uppercase()
        val pos = qthToPosition(g)
        _uiState.update {
            it.copy(
                stationBGrid = g,
                stationBGridInvalid = pos == null,
                stationBLat = pos?.let { p -> "%.4f".format(Locale.US, p.latitude) } ?: it.stationBLat,
                stationBLon = pos?.let { p -> "%.4f".format(Locale.US, p.longitude) } ?: it.stationBLon,
                hoursAhead = 24,
                scrollToTimeRange = true,
                pendingNavigation = true
            )
        }
        queryMutualPasses()
    }

    fun consumePendingNavigation() {
        _uiState.update { it.copy(pendingNavigation = false) }
    }

    fun consumeScrollToTimeRange() {
        _uiState.update { it.copy(scrollToTimeRange = false) }
    }

    /** Request an explicit scroll restoration after the Match page returns from a picker. */
    fun requestListScrollRestore() {
        _uiState.update { it.copy(scrollRestoreGeneration = it.scrollRestoreGeneration + 1L) }
    }

    /**
     * Run a mutual-pass query while preserving the current scroll position.
     * Previous results remain visible during calculation so the list does not
     * shrink and clamp the user's position; fresh results replace them when
     * the query completes.
     */
    fun queryMutualPasses(
        initialScrollIndex: Int? = null,
        onCompleted: ((List<MutualPass>) -> Unit)? = null,
        radarPass: Triple<Int, Long, Long>? = null
    ) {
        val state = _uiState.value

        // Resolve positions from lat/lon or grid
        var posA = resolvePosition(state.stationALat, state.stationALon, state.stationAGrid)
        var posB = resolvePosition(state.stationBLat, state.stationBLon, state.stationBGrid)

        if (state.stationAGridInvalid || state.stationBGridInvalid || posA == null || posB == null) {
            _uiState.update { it.copy(isCalculating = false, errorMessage = "Please enter valid coordinates or a Maidenhead grid (4/6/8 chars)") }
            onCompleted?.invoke(emptyList())
            return
        }

        val satellites = satelliteRepo.satellites.value
        if (satellites.isEmpty()) {
            _uiState.update { it.copy(isCalculating = false, errorMessage = "No satellite data. Select satellites from the list first.") }
            onCompleted?.invoke(emptyList())
            return
        }

        // 转发器类型筛选: 至少勾选一种(默认 FM + Linear 都开), 否则提示不查询.
        // 防止用户选入大量无转发器卫星占用匹配页.
        if (!state.filterFM && !state.filterLinear) {
            _uiState.update { it.copy(isCalculating = false, errorMessage = "Select at least one transponder type (FM / Linear) to run the match.") }
            onCompleted?.invoke(emptyList())
            return
        }
        // AMSAT Live 清单(同步自 AMSAT "Live FM/Linear Satellites" 页面)是本过滤的
        // 权威来源: 只匹配当前在轨且话音/线性转发器实际工作的卫星. 清单为空
        // (从未成功同步过)时回退到数据库 downlinkMode 过滤, 避免首启匹配页空结果.
        // 过滤本身在下面的协程里执行 (getSatelliteIdsWithModes 是 suspend).

        _uiState.update {
            it.copy(
                isCalculating = true,
                hasSearched = true,
                errorMessage = null,
                // Deliberately keep mutualPasses: clearing it would shrink the
                // list below one screen and clamp the scroll position away.
                // The stale results are replaced when the new ones arrive.
                selectedPassIndex = -1
            )
        }
        if (initialScrollIndex != null) {
            listScrollIndex = initialScrollIndex
            listScrollOffset = 0
        }
        queryGeneration += 1
        val generation = queryGeneration
        queryJob?.cancel()

        queryJob = viewModelScope.launch {
            val started = System.nanoTime()
            val now = nowMillis()
            // 转发器类型筛选: 使用硬编码 AMSAT Live 清单 (见 Sources):
            // FM = {SO-50, ISS ZARYA, AO-123}, Linear = {RS-44, FO-29,
            // AO-7, AO-73, JO-97}. 不再依赖 AMSAT 网页同步: 任何网络下都
            // 稳定, 没有旧脏列表/超时/同步失败清空等问题.
            val allowed = buildSet {
                if (state.filterFM) addAll(Sources.amSatFmCatnums)
                if (state.filterLinear) addAll(Sources.amSatLinearCatnums)
            }
            val filteredSatellites = satellites.filter { satellite ->
                if (radarPass != null) satellite.data.catnum == radarPass.first
                else satellite.data.catnum in allowed
            }
            // 历史回看: 查询窗口起点 = now - hoursBefore (跟随主页面 Passes 设置),
            // 终点 = now + hoursAhead 不变.
            val time = radarPass?.let { it.second - 30L * 60L * 1000L }
                ?: (now - state.hoursBefore * 60L * 60L * 1000L)
            val minElevA = state.stationAMinElev
            val minElevB = state.stationBMinElev
            val hours = radarPass?.let { pass ->
                ((pass.third + 30L * 60L * 1000L - time + 3_600_000L - 1L) / 3_600_000L)
                    .coerceAtLeast(1L).toInt()
            } ?: state.hoursAhead
            val hoursBefore = if (radarPass != null) 0 else state.hoursBefore

            val results = try {
                withContext(computeDispatcher) {
                    findMutualPasses(filteredSatellites, posA, posB, minElevA, minElevB, time, hours, hoursBefore)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                // Never leave the query stuck in "calculating" (which would also
                // block the pending map→match navigation forever).
                android.util.Log.e(TAG, "findMutualPasses threw", t)
                emptyList()
            }

            if (generation != queryGeneration) return@launch
            val errorMsg = if (results.isEmpty()) {
                when {
                    filteredSatellites.isEmpty() -> "No satellites match the selected transponder filters."
                    filteredSatellites.size == 1 -> "No mutual passes found (1 satellite)."
                    else -> "No mutual passes found (${filteredSatellites.size} satellites)."
                }
            } else null

            val sortedResults = results.sortedBy { mp -> mp.startTime }
            _uiState.update {
                it.copy(
                    mutualPasses = sortedResults,
                    isCalculating = false,
                    errorMessage = errorMsg
                )
            }
            android.util.Log.i("Look4SatStartup",
                "page=mutual predictionComplete targets=${filteredSatellites.size} " +
                    "windows=${sortedResults.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            onCompleted?.invoke(sortedResults)
        }
    }

    /** The coordinate fields are authoritative. Entering/picking a grid fills them
     * with its centre; editing either coordinate preserves the exact input instead
     * of silently snapping back to the six-character Maidenhead centre.
     * Invalid coordinates must not fall back to a stale grid value.
     */
    private fun resolvePosition(latText: String, lonText: String, gridText: String): GeoPos? {
        val lat = latText.toDoubleOrNull() ?: return null
        val lon = lonText.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPos(lat, lon)
    }

    private suspend fun findMutualPasses(
        satellites: List<OrbitalObject>,
        posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        time: Long, hours: Int, hoursBefore: Int
    ): List<MutualPass> {
        // The main Passes list is filtered by its own minimum elevation, AOS
        // window and radio settings, and is calculated for the device location.
        // Reusing it can silently omit mutual passes that satisfy the two
        // station-specific elevation controls on this page. Search independently
        // so Match results depend only on this page's inputs and satellite filter.
        val endTime = time + (hoursBefore + hours) * 60L * 60L * 1000L
        val sampleInterval = 5_000L
        return findMutualPassesIndependently(
            satellites, posA, posB, minElevADeg, minElevBDeg,
            time, endTime, sampleInterval
        )
    }

    /** Independent pass search, unaffected by the main Passes page's filters. */
    private suspend fun findMutualPassesIndependently(
        satellites: List<OrbitalObject>,
        posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        time: Long, endTime: Long, sampleInterval: Long
    ): List<MutualPass> = coroutineScope {
        val permits = Semaphore((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4))
        satellites.map { sat ->
            async {
                permits.withPermit {
                    findSatelliteMutualPasses(sat, posA, posB, minElevADeg, minElevBDeg,
                        time, endTime, sampleInterval)
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun findSatelliteMutualPasses(
        sat: OrbitalObject, posA: GeoPos, posB: GeoPos,
        minElevADeg: Double, minElevBDeg: Double,
        time: Long, endTime: Long, sampleInterval: Long
    ): List<MutualPass> {
        val results = mutableListOf<MutualPass>()
        val context = kotlin.coroutines.coroutineContext
        val session = satelliteRepo.orbit.openSession(sat)
        // Recover real boundaries of a pass already underway at query start.
        val margin = (sat.data.orbitalPeriod * 60000).toLong()
        val searchStart = maxOf(time - margin, sat.snapshot?.coverageStartMillis ?: Long.MIN_VALUE)
        val searchEnd = minOf(endTime + margin, sat.snapshot?.coverageEndMillis?.minus(1) ?: Long.MAX_VALUE)
        val windowsA = session.passes(posA, searchStart, searchEnd) { context.ensureActive() }
        val windowsB = session.passes(posB, searchStart, searchEnd) { context.ensureActive() }
        var indexA = 0
        var indexB = 0
        while (indexA < windowsA.size && indexB < windowsB.size) {
            context.ensureActive()
            val a = windowsA[indexA]
            val b = windowsB[indexB]
            val start = maxOf(a.aosTime, b.aosTime)
            val end = minOf(a.losTime, b.losTime)
            if (end > start && end > time && start < endTime) {
                sampleMutualPass(session, posA, posB, start, end,
                    minElevADeg, minElevBDeg, sampleInterval)?.let(results::add)
            }
            if (a.losTime <= b.losTime) indexA++ else indexB++
        }
        return results
    }

    /** Sample elevation/azimuth data for one mutual pass window. */
    private suspend fun sampleMutualPass(
        session: com.rtbishop.look4sat.core.domain.astro.IOrbitSession, posA: GeoPos, posB: GeoPos,
        refinedAos: Long, refinedLos: Long,
        minElevADeg: Double, minElevBDeg: Double,
        sampleInterval: Long
    ): MutualPass? {
        val samples = mutableListOf<Pair<Long, Pair<Double, Double>>>()
        val tracks = mutableListOf<TrackSample>()
        var maxElevA = 0.0
        var maxElevB = 0.0
        val observers = listOf(posA, posB)

        var tSample = refinedAos
        val displayStep = maxOf(sampleInterval, (refinedLos - refinedAos) / 2000 + 1)
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            // Curves only need station directions, not eclipse, ground footprint,
            // velocity or solar ephemerides. Propagate once for both stations.
            val (fullA, fullB) = session.directions(observers, tSample)
            val elevA = Math.toDegrees(fullA.elevation)
            val elevB = Math.toDegrees(fullB.elevation)
            if (elevA > maxElevA) maxElevA = elevA
            if (elevB > maxElevB) maxElevB = elevB
            samples.add(tSample to (elevA to elevB))
            tracks.add(
                TrackSample(
                    time = tSample,
                    azimuthA = fullA.azimuth * 180.0 / PI,
                    elevationA = elevA,
                    azimuthB = fullB.azimuth * 180.0 / PI,
                    elevationB = elevB
                )
            )
            // Always include the refined LOS as the final chart/radar sample,
            // even when the 5-second sampling interval does not divide the pass.
            if (tSample == refinedLos) break
            tSample = minOf(tSample + displayStep, refinedLos)
        }

        if (maxElevA > minElevADeg && maxElevB > minElevBDeg) {
            return MutualPass(
                catNum = session.target.data.catnum,
                name = session.target.data.name,
                startTime = refinedAos,
                endTime = refinedLos,
                maxElevationA = (maxElevA * 10).roundToInt() / 10.0,
                maxElevationB = (maxElevB * 10).roundToInt() / 10.0,
                elevationSamples = samples,
                trackSamples = tracks
            )
        }
        return null
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    companion object {
        fun factory(container: IMainContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MutualViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo
                ) as T
        }
    }
}
