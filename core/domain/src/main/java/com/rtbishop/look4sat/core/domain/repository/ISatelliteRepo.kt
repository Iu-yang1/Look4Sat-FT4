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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.NominalFrequencies
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import kotlinx.coroutines.flow.StateFlow

interface ISatelliteRepo {
    val orbitFailures: StateFlow<Map<Int, String>> get() = noOrbitFailures
    val orbit: com.rtbishop.look4sat.core.domain.astro.IOrbitEngine
        get() = error("Orbit engine is not configured")
    val celestial: com.rtbishop.look4sat.core.domain.astro.ICelestialService
        get() = error("Celestial service is not configured")
    val astroData: com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
        get() = error("Astronomy data repository is not configured")
    /** All selected OrbitalObjects, updated when the selection changes. */
    val satellites: StateFlow<List<OrbitalObject>>

    /** Raw calculated passes (without live progress). Updated on selection/filter change. */
    val passes: StateFlow<List<OrbitalPass>>

    /** Whether the repo is currently calculating passes. */
    val isCalculating: StateFlow<Boolean>

    /** Currently selected pass (catNum + aosTime), persisted across screen navigations. */
    val selectedPass: StateFlow<Pair<Int, Long>>

    /** Set the currently selected pass. */
    fun selectPass(catNum: Int, aosTime: Long)

    /** Load satellite objects from DB based on the current selection. */
    suspend fun initRepository()

    /** Recalculate passes with the given filter parameters. */
    suspend fun calculatePasses(
        time: Long,
        hoursAhead: Int,
        minElevation: Double,
        aosStartMinute: Int,
        aosEndMinute: Int,
        invertAosTimeWindow: Boolean,
        modes: List<String>
    )

    /** Predict passes for the schedule without changing the shared main pass list or its filters. */
    suspend fun getSchedulePasses(
        catNums: Set<Int>,
        startTime: Long,
        hoursAhead: Int,
        minElevation: Double
    ): List<OrbitalPass>

    suspend fun getSchedulePasses(
        catNums: Set<Int>, startTime: Long, hoursAhead: Int, minElevation: Double,
        onProgress: (List<OrbitalPass>) -> Unit
    ): List<OrbitalPass> = getSchedulePasses(catNums, startTime, hoursAhead, minElevation)
        .also(onProgress)

    val passPrediction: StateFlow<PassPredictionState> get() = idlePassPrediction

    /** Get the current position of a single satellite. */
    suspend fun getPosition(sat: OrbitalObject, pos: GeoPos, time: Long): OrbitalPos
    suspend fun getCoverage(sat: OrbitalObject, time: Long): List<GeoPos> = emptyList()

    /** Get the ground track for a satellite over a time range. */
    suspend fun getTrack(sat: OrbitalObject, pos: GeoPos, start: Long, end: Long): List<OrbitalPos>

    /** Get Doppler-shifted radio frequencies for a satellite at the given time. */
    suspend fun getRadios(sat: OrbitalObject, pos: GeoPos, radios: List<SatRadio>, time: Long): List<SatRadio>

    /** Fetch radio transceivers for a satellite by its catalog number. */
    suspend fun getRadiosWithId(id: Int): List<SatRadio>

    /**
     * Nominal (no Doppler) uplink/downlink of the transceiver a contact on [name] is made
     * through: the FM voice repeater when the bird has one, else the first live transceiver
     * publishing both bands. Names match through satellite identities, so the tracker spelling
     * ("SAUDISAT 1C") finds the same entry as the ARRL name ("SO-50"). Null when the name
     * resolves to no satellite, or the satellite publishes no usable pair.
     */
    suspend fun nominalFrequencies(name: String): NominalFrequencies? = null

    /** Satellite catnums whose radios carry any of the given downlink modes.
     *  Empty modes -> empty result; used to filter the mutual-match satellite
     *  set to transponder satellites (FM voice / linear). */
    suspend fun getSatelliteIdsWithModes(modes: List<String>): List<Int>

    /** Like [getSatelliteIdsWithModes] but only matches records with an uplink
     *  (real transponders), excluding downlink-only beacons/telemetry that share
     *  the same mode label. Used by the FM/Linear fallback filter. */
    suspend fun getSatelliteIdsWithModesAndUplink(modes: List<String>): List<Int>

    /** Like [getSatelliteIdsWithModes] but only matches records whose service
     *  class is "Amateur", so SSTV never matches weather birds (TIROS),
     *  launcher debris or other non-amateur transmitters. */
    suspend fun getSatelliteIdsWithModesAndAmateur(modes: List<String>): List<Int>
}

private val noOrbitFailures: StateFlow<Map<Int, String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
data class PassPredictionState(
    val requestId: Long = 0, val resultRequestId: Long = -1, val complete: Boolean = false,
    val windows: List<OrbitalPass> = emptyList()
)
private val idlePassPrediction: StateFlow<PassPredictionState> =
    kotlinx.coroutines.flow.MutableStateFlow(PassPredictionState())
