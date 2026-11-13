/*
 * Look4Sat. Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.data.astro.orbitParallelMap
import com.rtbishop.look4sat.core.data.astro.orbitWorkerCount
import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
import com.rtbishop.look4sat.core.domain.astro.ICelestialService
import com.rtbishop.look4sat.core.domain.astro.IOrbitEngine
import com.rtbishop.look4sat.core.domain.model.NominalFrequencies
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.utility.catNumForName
import com.rtbishop.look4sat.core.domain.utility.downlinkHz
import com.rtbishop.look4sat.core.domain.utility.nominalTransceiver
import com.rtbishop.look4sat.core.domain.utility.uplinkHz
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

internal class PassCalculationGeneration {
    private val next = AtomicLong()
    fun begin(): Long = next.incrementAndGet()
    fun isLatest(generation: Long): Boolean = next.get() == generation
}

class SatelliteRepo(
    private val dispatcher: CoroutineDispatcher,
    private val localStorage: ILocalSource,
    private val settingsRepo: ISettingsRepo,
    override val orbit: IOrbitEngine,
    override val celestial: ICelestialService,
    override val astroData: IAstroDataRepository,
    private val diagnostics: com.rtbishop.look4sat.core.domain.control.IControlDiagnostics? = null
) : ISatelliteRepo {
    private val failures = MutableStateFlow<Map<Int, String>>(emptyMap())
    override val orbitFailures: StateFlow<Map<Int, String>> = failures
    private val calculationGeneration = PassCalculationGeneration()
    private val mutablePasses = MutableStateFlow<List<OrbitalPass>>(emptyList())
    override val passes: StateFlow<List<OrbitalPass>> = mutablePasses
    private val calculating = MutableStateFlow(false)
    override val isCalculating: StateFlow<Boolean> = calculating
    private val targets = MutableStateFlow<List<OrbitalObject>>(emptyList())
    override val satellites: StateFlow<List<OrbitalObject>> = targets
    private val selection = MutableStateFlow(0 to 0L)
    override val selectedPass: StateFlow<Pair<Int, Long>> = selection
    private val calculationLock = Any()
    private var calculationJob: Job? = null
    private val workers = Semaphore(orbitWorkerCount())
    private val realtimeSessions = java.util.WeakHashMap<Job,
        LinkedHashMap<OrbitalObject, com.rtbishop.look4sat.core.domain.astro.IOrbitSession>>()

    private fun recordFailure(satellite: OrbitalObject, error: Exception) {
        val message = error.toString()
        synchronized(failures) {
            if (failures.value[satellite.data.catnum] == message) return
            failures.value = failures.value + (satellite.data.catnum to message)
        }
        diagnostics?.record(
            com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource.RADIO,
            "orbit/prediction", "cat=${satellite.data.catnum} error=$error",
            com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity.ERROR)
    }

    private fun <T> calculate(satellite: OrbitalObject, block: () -> T): T = try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        recordFailure(satellite, error)
        throw error
    }

    private fun session(owner: Job?, satellite: OrbitalObject): com.rtbishop.look4sat.core.domain.astro.IOrbitSession {
        if (owner == null) return orbit.openSession(satellite)
        return synchronized(realtimeSessions) {
            val cache = realtimeSessions.getOrPut(owner) { LinkedHashMap() }
            cache[satellite] ?: orbit.openSession(satellite).also {
                if (cache.size >= 16) cache.remove(cache.keys.first())
                cache[satellite] = it
            }
        }
    }

    override fun selectPass(catNum: Int, aosTime: Long) { selection.value = catNum to aosTime }
    override suspend fun getRadiosWithId(id: Int) = localStorage.getRadiosWithId(id)
    override suspend fun nominalFrequencies(name: String): NominalFrequencies? {
        val catnum = localStorage.getEntriesNames().catNumForName(name) ?: return null
        val radio = localStorage.getRadiosWithId(catnum).nominalTransceiver() ?: return null
        return NominalFrequencies(radio.uplinkHz(), radio.downlinkHz())
    }
    override suspend fun getSatelliteIdsWithModes(modes: List<String>) = localStorage.getIdsWithModes(modes)
    override suspend fun getSatelliteIdsWithModesAndUplink(modes: List<String>) =
        localStorage.getIdsWithModesAndUplink(modes)
    override suspend fun getSatelliteIdsWithModesAndAmateur(modes: List<String>) =
        localStorage.getIdsWithModesAndAmateur(modes)

    override suspend fun initRepository() = withContext(dispatcher) {
        combine(
            settingsRepo.selectedIds,
            settingsRepo.stationPosition.map {
                GeoPos(it.latitude, it.longitude, it.altitude)
            }.distinctUntilChanged(),
            settingsRepo.databaseState,
            settingsRepo.otherSettings.map { it.stateOfUtc }.distinctUntilChanged(),
            astroData.state.map { it.generation }.distinctUntilChanged()
        ) { ids, position, database, utc, generation ->
            listOf(ids, position, database.contentVersion, utc, generation)
        }.distinctUntilChanged()
            .collectLatest {
                if (!astroData.state.value.ready) {
                    targets.value = emptyList()
                    mutablePasses.value = emptyList()
                    return@collectLatest
                }
                val snapshot = astroData.snapshot()
                targets.value = localStorage.getEntriesWithIds(settingsRepo.selectedIds.value)
                    .map { target -> OrbitalObject(target.data, snapshot) }
                val settings = settingsRepo.passesSettings.value
                calculatePasses(System.currentTimeMillis() - settings.hoursBefore * 3600000L,
                    settings.hoursBefore + settings.hoursAhead, settings.minElevation,
                    settings.aosStartMinute, settings.aosEndMinute, settings.invertAosTimeWindow,
                    settings.selectedModes)
            }
    }

    override suspend fun getPosition(sat: OrbitalObject, pos: GeoPos, time: Long): OrbitalPos {
        val owner = coroutineContext[Job]
        return withContext(dispatcher) {
            calculate(sat) {
                val session = session(owner, sat)
                synchronized(session) { session.position(pos, time) }
            }
        }
    }
    override suspend fun getCoverage(sat: OrbitalObject, time: Long): List<GeoPos> {
        val owner = coroutineContext[Job]
        return withContext(dispatcher) {
            calculate(sat) {
                val session = session(owner, sat)
                synchronized(session) { session.coverage(time) }
            }
        }
    }

    override suspend fun getTrack(sat: OrbitalObject, pos: GeoPos, start: Long, end: Long): List<OrbitalPos> =
        withContext(dispatcher) {
            val context = coroutineContext
            calculate(sat) {
                val session = orbit.openSession(sat)
                val step = ((end - start) / 1200 + 1).coerceAtLeast(15000)
                buildList {
                    var time = start
                    while (time < end) {
                        context.ensureActive()
                        add(session.position(pos, time))
                        time += step
                    }
                }
            }
        }

    override suspend fun getRadios(sat: OrbitalObject, pos: GeoPos, radios: List<SatRadio>, time: Long): List<SatRadio> {
        val position = getPosition(sat, pos, time)
        return radios.map { it.copy(
            downlinkLow = it.downlinkLow?.let(position::getDownlinkFreq),
            downlinkHigh = it.downlinkHigh?.let(position::getDownlinkFreq),
            uplinkLow = it.uplinkLow?.let(position::getUplinkFreq),
            uplinkHigh = it.uplinkHigh?.let(position::getUplinkFreq)
        ) }
    }

    override suspend fun calculatePasses(time: Long, hoursAhead: Int, minElevation: Double,
        aosStartMinute: Int, aosEndMinute: Int, invertAosTimeWindow: Boolean, modes: List<String>
    ) = coroutineScope {
        val (request, job) = synchronized(calculationLock) {
            coroutineContext.ensureActive()
            val generation = calculationGeneration.begin()
            val next = launch(start = CoroutineStart.LAZY) {
                val started = System.nanoTime()
                calculating.value = true
                try {
                    if (!astroData.state.value.ready) {
                        mutablePasses.value = emptyList()
                        return@launch
                    }
                    val activeModes = modes.map(String::trim).filter(String::isNotEmpty)
                    val ids = if (activeModes.isEmpty()) emptyList() else localStorage.getIdsWithModes(activeModes)
                    fun matches(pass: OrbitalPass) = pass.maxElevation > minElevation &&
                        (pass.startClipped || isAosInRange(pass.aosTime, aosStartMinute,
                            aosEndMinute, invertAosTimeWindow))
                    // Cold loads can show completed windows while the remaining satellites
                    // are still being predicted. Keep the refresh indicator until all finish.
                    val partial = mutableListOf<OrbitalPass>()
                    val publishPartial = mutablePasses.value.isEmpty()
                    var lastPublication = 0L
                    val result = predict(filterSatellitesByModes(targets.value, activeModes, ids),
                        settingsRepo.stationPosition.value, time, time + hoursAhead * 3600000L,
                        onProgress = if (!publishPartial) null else { windows ->
                            synchronized(partial) {
                                partial.addAll(windows.filter(::matches))
                                val now = System.nanoTime()
                                if (partial.isNotEmpty() && now - lastPublication >= 250_000_000 &&
                                    calculationGeneration.isLatest(generation)) {
                                    mutablePasses.value = partial.sortedBy { it.aosTime }
                                    lastPublication = now
                                }
                            }
                        }
                    ).filter(::matches)
                    if (calculationGeneration.isLatest(generation)) {
                        mutablePasses.value = result
                        diagnostics?.record(
                            com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource.RADIO,
                            "orbit/loading",
                            "page=passes targets=${targets.value.size} windows=${result.size} " +
                                "hours=$hoursAhead elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                        android.util.Log.i("Look4SatStartup",
                            "page=passes predictionComplete targets=${targets.value.size} windows=${result.size} " +
                                "elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                    }
                } finally {
                    if (calculationGeneration.isLatest(generation)) calculating.value = false
                }
            }
            calculationJob?.cancel()
            calculationJob = next
            next.start()
            generation to next
        }
        try { job.join() } finally {
            synchronized(calculationLock) { if (calculationJob === job) calculationJob = null }
            if (calculationGeneration.isLatest(request)) calculating.value = false
        }
    }

    private suspend fun predict(
        satellites: List<OrbitalObject>, observer: GeoPos, start: Long, end: Long,
        onProgress: ((List<OrbitalPass>) -> Unit)? = null
    ) =
        withContext(dispatcher) {
            if (end <= start) return@withContext emptyList<OrbitalPass>()
            satellites.orbitParallelMap { satellite ->
                workers.withPermit {
                    val context = coroutineContext
                    val windows = try {
                        orbit.openSession(satellite).passes(observer, start, end) { context.ensureActive() }
                            .also { synchronized(failures) {
                                failures.value = failures.value - satellite.data.catnum
                            } }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (partial: com.rtbishop.look4sat.core.domain.astro.OrbitPredictionException) {
                        recordFailure(satellite, partial)
                        partial.validPasses
                    } catch (error: Exception) {
                        recordFailure(satellite, error)
                        emptyList()
                    }
                    onProgress?.invoke(windows)
                    windows
                }
            }.flatten().sortedBy { it.aosTime }
        }

    override suspend fun getSchedulePasses(catNums: Set<Int>, startTime: Long, hoursAhead: Int,
        minElevation: Double
    ): List<OrbitalPass> {
        if (!astroData.state.value.ready) return emptyList()
        val started = System.nanoTime()
        val result = predict(targets.value.filter { it.data.catnum in catNums },
            settingsRepo.stationPosition.value, startTime, startTime + hoursAhead * 3600000L)
            .filter { it.maxElevation >= minElevation }
        diagnostics?.record(
            com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource.RADIO, "orbit/loading",
            "page=schedule targets=${catNums.size} windows=${result.size} hours=$hoursAhead " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        return result
    }

    private fun isAosInRange(time: Long, start: Int, end: Int, invert: Boolean): Boolean {
        val local = Math.floorMod(time + TimeZone.getDefault().getOffset(time), 86400000L)
        val minute = (local / 60000).toInt()
        val inside = if (start <= end) minute in start..end else minute >= start || minute <= end
        return if (invert) !inside else inside
    }
}

internal fun filterSatellitesByModes(satellites: List<OrbitalObject>, selectedModes: List<String>,
    matchingCatalogNumbers: List<Int>
): List<OrbitalObject> = if (selectedModes.isEmpty()) satellites
else satellites.filter { it.data.catnum in matchingCatalogNumbers }
