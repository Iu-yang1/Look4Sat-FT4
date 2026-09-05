/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.ft4.TxRequest
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.Ft4Settings
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.time.ClockSample
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioTrackingServiceTest {
    @Test
    fun radioCommandActorSerializesConcurrentCommands() = runTest {
        var active = 0
        var maximumActive = 0
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }

        List(12) {
            async {
                actor.execute {
                    active++
                    maximumActive = maxOf(maximumActive, active)
                    delay(10L)
                    active--
                }
            }
        }.awaitAll()

        assertEquals(1, maximumActive)
    }

    @Test
    fun latencyEstimatorUsesMeasuredP95InsteadOfFixedLead() {
        val estimator = RadioCommandLatencyEstimator()
        listOf(120L, 180L, 260L, 420L, 900L).forEach(estimator::record)
        assertEquals(1_000L, estimator.p95WithMargin())
    }

    @Test
    fun leaseSetsMidpointDopplerFreezesTxAndAlwaysReleasesPtt() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, rx)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        val lease = fixture.service.beginTransmit(fixture.request(generation = 7L))
        val expected = fixture.position.getUplinkFreq(fixture.nominalTxHz)
        assertEquals(expected, lease.effectiveTxFrequencyHz)
        assertEquals(expected - fixture.nominalTxHz, lease.txDopplerCorrectionHz)
        val txFrequencyCommands = tx.operations.count { it.startsWith("frequency:") }
        val rxFrequencyCommands = rx.operations.count { it.startsWith("frequency:") }

        fixture.service.confirmTransmitReady(lease)
        assertEquals(PttState.ON, fixture.service.state.value.pttState)
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(txFrequencyCommands, tx.operations.count { it.startsWith("frequency:") })
        assertTrue(rx.operations.count { it.startsWith("frequency:") } > rxFrequencyCommands)

        fixture.service.endTransmit(lease)
        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        assertTrue("ptt:on" in tx.operations)
        assertTrue("ptt:off" in tx.operations)
        fixture.close()
    }

    @Test
    fun watchdogForcesPttOff() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val lease = fixture.service.beginTransmit(fixture.request(generation = 8L, maximumPttMillis = 5_100L))
        fixture.service.confirmTransmitReady(lease)

        advanceTimeBy(5_101L)
        runCurrent()
        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        assertEquals(null, fixture.service.state.value.txLeaseId)
        assertTrue("ptt:off" in tx.operations)
        fixture.close()
    }

    @Test
    fun failedPttConfirmationCannotStartAudioLease() = runTest {
        val tx = FakeRadioController(pttOnSucceeds = false)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val lease = fixture.service.beginTransmit(fixture.request(generation = 9L))

        assertTrue(runCatching { fixture.service.confirmTransmitReady(lease) }.isFailure)
        assertEquals(PttState.ERROR, fixture.service.state.value.pttState)
        assertEquals(null, fixture.service.state.value.txLeaseId)
        assertTrue("ptt:off" in tx.operations)
        fixture.close()
    }

    @Test
    fun modeChangeEndsActiveLeaseBeforeSendingModeCommand() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val lease = fixture.service.beginTransmit(fixture.request(generation = 10L))
        fixture.service.confirmTransmitReady(lease)

        fixture.service.setMode("USB", "USB")
        runCurrent()

        assertTrue(tx.operations.lastIndexOf("ptt:off") < tx.operations.lastIndexOf("mode:USB"))
        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        fixture.close()
    }

    @Test
    fun trackingOrbitCalculationUsesDisciplinedUtc() = runTest {
        var disciplinedNow = 1_800_000_000_000L
        val fixture = Fixture(backgroundScope, FakeRadioController(), FakeRadioController()) { disciplinedNow }
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(disciplinedNow in fixture.satelliteRepo.requestedTimes)

        disciplinedNow += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(disciplinedNow in fixture.satelliteRepo.requestedTimes)
        fixture.close()
    }

    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        private val tx: FakeRadioController,
        private val rx: FakeRadioController,
        nowProvider: () -> Long = System::currentTimeMillis
    ) {
        val nominalTxHz = 145_900_000L
        val position = OrbitalPos(elevation = 0.5, distanceRate = 1.2, aboveHorizon = true)
        private val settings = FakeSettingsRepo()
        val satelliteRepo = FakeSatelliteRepo(position)
        private val now = nowProvider()
        private val satellite = OrbitalData(
            name = "TEST",
            epoch = 24_100.0,
            meanmo = 15.0,
            eccn = 0.001,
            incl = 51.6,
            raan = 0.0,
            argper = 0.0,
            meanan = 0.0,
            catnum = 12_345,
            bstar = 0.0
        ).getObject()
        private val pass = OrbitalPass(
            aosTime = now - 60_000L,
            losTime = now + 120_000L,
            orbitalObject = satellite
        )
        private val transponder = SatRadio(
            uuid = "test-transponder",
            info = "FT4",
            isAlive = true,
            downlinkLow = 435_100_000L,
            downlinkHigh = 435_110_000L,
            downlinkMode = "USB",
            uplinkLow = nominalTxHz,
            uplinkHigh = nominalTxHz,
            uplinkMode = "USB",
            isInverted = false,
            catnum = pass.catNum
        )
        val service = RadioTrackingService(
            appScope = scope,
            bluetoothManager = null,
            satelliteRepo = satelliteRepo,
            settingsRepo = settings,
            clock = FixedClock(nowProvider),
            controllerFactory = { _, address -> if (address == "TX") tx else rx }
        )

        fun startTracking() {
            service.startTracking(pass, transponder, nominalTxHz)
        }

        fun request(generation: Long, maximumPttMillis: Long = 8_500L) = TxRequest(
            sessionGeneration = generation,
            waveformStartUtcMillis = now + 5_000L,
            expectedSatelliteCatalogNumber = pass.catNum,
            expectedTransponderUuid = transponder.uuid,
            maximumPttMillis = maximumPttMillis
        )

        suspend fun close() {
            service.stopTracking()
            service.disconnectRadios()
        }
    }
}

private class FixedClock(private val now: () -> Long) : IDisciplinedClock {
    override val state = MutableStateFlow(snapshot())
    override fun snapshot() = ClockSnapshot(
        utcMillis = now(), monotonicNanos = now() * 1_000_000L,
        offsetMillis = 0.0, driftPpm = 0.0, uncertaintyMillis = 10.0,
        source = ClockSource.NTP, sampleAgeMillis = 0L, healthy = true
    )
    override fun nowMillis() = now()
    override fun utcMillisAt(monotonicNanos: Long) = monotonicNanos / 1_000_000L
    override fun submitSample(sample: ClockSample) = false
    override fun refresh() = snapshot()
    override fun automaticFt4TransmitAllowed() = true
    override fun automaticFt4TransmitBlockReason() = ""
}

private class FakeRadioController(
    private val pttOnSucceeds: Boolean = true
) : IRadioController {
    val operations = mutableListOf<String>()
    override var isConnected: Boolean = false

    override suspend fun connect(): Boolean {
        operations += "connect"
        isConnected = true
        return true
    }

    override suspend fun disconnect() {
        operations += "disconnect"
        isConnected = false
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean {
        operations += "frequency:$frequencyHz"
        return true
    }

    override suspend fun setMode(mode: String): Boolean {
        operations += "mode:$mode"
        return true
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = true
    override suspend fun setCtcssTone(toneHz: Double): Boolean = true
    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = null

    override suspend fun pttOn(): Boolean {
        operations += "ptt:on"
        return pttOnSucceeds
    }

    override suspend fun pttOff(): Boolean {
        operations += "ptt:off"
        return true
    }
}

private class FakeSatelliteRepo(private val position: OrbitalPos) : ISatelliteRepo {
    val requestedTimes = mutableListOf<Long>()
    override val satellites = MutableStateFlow<List<OrbitalObject>>(emptyList())
    override val passes = MutableStateFlow<List<OrbitalPass>>(emptyList())
    override val isCalculating = MutableStateFlow(false)
    override val selectedPass = MutableStateFlow(0 to 0L)
    override fun selectPass(catNum: Int, aosTime: Long) = Unit
    override suspend fun initRepository() = Unit
    override suspend fun calculatePasses(
        time: Long,
        hoursAhead: Int,
        minElevation: Double,
        aosStartMinute: Int,
        aosEndMinute: Int,
        invertAosTimeWindow: Boolean,
        modes: List<String>
    ) = Unit

    override suspend fun getPosition(sat: OrbitalObject, pos: GeoPos, time: Long): OrbitalPos {
        requestedTimes += time
        return position.copy(time = time)
    }
    override suspend fun getTrack(sat: OrbitalObject, pos: GeoPos, start: Long, end: Long) = emptyList<OrbitalPos>()
    override suspend fun getRadios(
        sat: OrbitalObject,
        pos: GeoPos,
        radios: List<SatRadio>,
        time: Long
    ) = radios
    override suspend fun getRadiosWithId(id: Int) = emptyList<SatRadio>()
}

private class FakeSettingsRepo : ISettingsRepo {
    override val appVersionName = "test"
    override val selectedIds = MutableStateFlow<List<Int>>(emptyList())
    override val selectedTypes = MutableStateFlow<List<String>>(emptyList())
    override val passesSettings = MutableStateFlow(
        PassesSettings(hoursAhead = 24, minElevation = 0.0, selectedModes = emptyList())
    )
    override val stationPosition = MutableStateFlow(GeoPos(0.0, 0.0, qthLocator = "AA00"))
    override val databaseState = MutableStateFlow(DatabaseState(0, 0, 0L))
    override val rcSettings = MutableStateFlow(
        RCSettings(false, "", "", "", false, "", "", "", 0L, false, "", "", "", false, "", "")
    )
    override val otherSettings = MutableStateFlow(
        OtherSettings(false, false, false, false, false, false, false, false)
    )
    override val ft4Settings = MutableStateFlow(Ft4Settings(decodeEnabled = true))
    override val dataSourcesSettings = MutableStateFlow(DataSourcesSettings(emptyList(), emptyList()))
    override val dataSourcesStatus = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val radioControlSettings = MutableStateFlow(
        RadioControlSettings(
            enabled = true,
            radioModel = RadioControlSettings.MODEL_YAESU_FT817,
            txRadioAddress = "TX",
            rxRadioAddress = "RX",
            txRadioName = "TX",
            rxRadioName = "RX",
            baudRate = 9_600
        )
    )

    override fun setSelectedIds(ids: List<Int>) = Unit
    override fun setSelectedTypes(types: List<String>) = Unit
    override fun setPassesSettings(settings: PassesSettings) = Unit
    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double) = true
    override fun setStationPosition() = true
    override fun setStationPosition(locator: String) = true
    override fun getSatelliteTypesIds(types: List<String>) = emptyList<Int>()
    override fun setSatelliteTypeIds(type: String, ids: List<Int>) = Unit
    override fun updateDatabaseState(state: DatabaseState) { databaseState.value = state }
    override fun updateRCSettings(settings: RCSettings) { rcSettings.value = settings }
    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) {
        otherSettings.value = transform(otherSettings.value)
    }
    override fun updateFt4Settings(transform: (Ft4Settings) -> Ft4Settings) {
        ft4Settings.value = transform(ft4Settings.value)
    }
    override fun updateDataSourcesSettings(settings: DataSourcesSettings) {
        dataSourcesSettings.value = settings
    }
    override fun updateDataSourcesStatus(status: Map<String, Int>) {
        dataSourcesStatus.value = status
    }
    override fun updateRadioControlSettings(settings: RadioControlSettings) {
        radioControlSettings.value = settings
    }
    override fun getSatelliteOffset(catnum: Int) = ""
    override fun setSatelliteOffset(catnum: Int, offset: String) = Unit
    override fun getAmSatCallsign() = ""
    override fun setAmSatCallsign(callsign: String) = Unit
}
