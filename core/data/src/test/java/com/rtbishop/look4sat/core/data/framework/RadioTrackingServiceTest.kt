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
import com.rtbishop.look4sat.core.domain.repository.TrackingPhase
import com.rtbishop.look4sat.core.domain.time.ClockSample
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
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
    fun txCtcssSequenceIsOneActorCommand() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val delegate = FakeRadioController(ctcssStepDelayMillis = 1L)
        val radio = SerialRadioController(delegate, actor)

        val configuring = async { radio.configureTxCtcss(88.5) }
        runCurrent()
        val competing = async { radio.setFrequency(145_900_000L) }

        assertTrue(configuring.await())
        assertTrue(competing.await())
        assertEquals(
            listOf(
                "vfo:tx",
                "tone:88.5",
                "ctcss:true",
                "vfo:rx",
                "frequency:145900000"
            ),
            delegate.operations
        )
    }

    @Test
    fun urgentPttOffFinishesActiveTransactionThenRunsBeforeQueuedNormalCommands() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val delegate = FakeRadioController()
        val radio = SerialRadioController(delegate, actor)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = async {
            actor.execute {
                delegate.operations += "slow:start"
                started.complete(Unit)
                release.await()
                delegate.operations += "slow:end"
            }
        }
        started.await()
        val pttOn = async { radio.pttOn() }
        runCurrent()
        val normal = async { radio.setMode("USB") }
        runCurrent()
        pttOn.cancelAndJoin()
        val pttOff = async { radio.pttOff() }
        release.complete(Unit)

        blocker.await()
        assertTrue(pttOff.await())
        assertTrue(normal.await())
        assertTrue("slow:end" in delegate.operations)
        assertFalse("ptt:on" in delegate.operations)
        assertTrue(delegate.operations.indexOf("ptt:off") < delegate.operations.indexOf("mode:USB"))
    }

    @Test
    fun pttPermitCapturedBeforeEmergencyStopCannotBeRearmed() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val delegate = FakeRadioController()
        val radio = SerialRadioController(delegate, actor)
        val permit = radio.pttSafetyGeneration()

        radio.invalidatePendingPttOn()
        val result = runCatching { radio.pttOnIfGeneration(permit) }

        assertTrue(result.isFailure)
        assertFalse("ptt:on" in delegate.operations)
    }

    @Test
    fun urgentPttOffSurvivesCallerCancellationWhileQueued() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val delegate = FakeRadioController()
        val radio = SerialRadioController(delegate, actor)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstPttOff = async {
            actor.executePttOff {
                started.complete(Unit)
                release.await()
                true
            }
        }
        started.await()
        val pttOff = async { radio.pttOff() }
        runCurrent()

        pttOff.cancelAndJoin()
        release.complete(Unit)
        assertTrue(firstPttOff.await())
        runCurrent()

        assertTrue("ptt:off" in delegate.operations)
    }

    @Test
    fun latencyEstimatorUsesMeasuredP95InsteadOfFixedLead() {
        val estimator = RadioCommandLatencyEstimator()
        listOf(120L, 180L, 260L, 420L, 900L).forEach(estimator::record)
        assertEquals(1_000L, estimator.p95WithMargin())
    }

    @Test
    fun transponderCanBeSelectedBeforeRadioConnection() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, rx)

        fixture.service.setTransponder(fixture.transponder)
        runCurrent()

        assertEquals(fixture.transponder.uuid, fixture.service.state.value.selectedTransponder?.uuid)
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)
        assertTrue(tx.operations.isEmpty())
        assertTrue(rx.operations.isEmpty())
        fixture.close()
    }

    @Test
    fun leaseSetsInitialDopplerContinuesTrackingAndAlwaysReleasesPtt() = runTest {
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
        assertTrue(tx.operations.count { it.startsWith("frequency:") } > txFrequencyCommands)
        assertTrue(rx.operations.count { it.startsWith("frequency:") } > rxFrequencyCommands)

        fixture.service.endTransmit(lease)
        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        assertTrue("ptt:on" in tx.operations)
        assertTrue("ptt:off" in tx.operations)
        fixture.close()
    }

    @Test
    fun leaseAllowsMidpointOutsideSelectedPass() = runTest {
        val fixture = Fixture(backgroundScope, FakeRadioController(), FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        val request = fixture.request(generation = 11L, waveformStartUtcMillis = fixture.afterPassStart)
        val lease = fixture.service.beginTransmit(request)

        assertEquals(
            request.waveformStartUtcMillis + request.waveformDurationMillis / 2L,
            lease.waveformMidpointUtcMillis
        )
        assertTrue(lease.waveformMidpointUtcMillis in fixture.satelliteRepo.requestedTimes)
        fixture.service.endTransmit(lease)
        fixture.close()
    }

    @Test
    fun ft857DefersOnlyTxWritesWhileKeyedAndAppliesLatestDopplerAfterPttOff() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(
            backgroundScope,
            tx,
            rx,
            radioModel = RadioControlSettings.MODEL_YAESU_FT857
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        val lease = fixture.service.beginTransmit(fixture.request(generation = 8L))
        fixture.service.confirmTransmitReady(lease)
        val txWritesWhileKeyed = tx.operations.count { it.startsWith("frequency:") }
        val rxWritesBeforeCycle = rx.operations.count { it.startsWith("frequency:") }
        val positionSamplesBeforeCycle = fixture.satelliteRepo.requestedTimes.size

        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(txWritesWhileKeyed, tx.operations.count { it.startsWith("frequency:") })
        assertTrue(rx.operations.count { it.startsWith("frequency:") } > rxWritesBeforeCycle)
        assertTrue(fixture.satelliteRepo.requestedTimes.size > positionSamplesBeforeCycle)
        assertEquals(PttState.ON, fixture.service.state.value.pttState)

        fixture.service.endTransmit(lease)
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(tx.operations.count { it.startsWith("frequency:") } > txWritesWhileKeyed)
        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        fixture.close()
    }

    @Test
    fun splitTrackingAndFt4LeaseShareOneRadioWithoutFrequencyCollision() = runTest {
        val radio = FakeRadioController()
        val fixture = Fixture(
            scope = backgroundScope,
            tx = radio,
            rx = FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705,
            splitMode = true
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(fixture.service.state.value.splitMode)
        assertEquals(1, fixture.service.state.value.physicalConnectionCount)

        val lease = fixture.service.beginTransmit(fixture.request(generation = 71L))
        val txUpdatesAfterPrepare = radio.operations.count { it.startsWith("tx-frequency:") }
        val rxUpdatesBeforePtt = radio.operations.count { it.startsWith("rx-frequency:") }
        fixture.service.confirmTransmitReady(lease)

        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(radio.operations.count { it.startsWith("tx-frequency:") } > txUpdatesAfterPrepare)
        assertTrue(radio.operations.count { it.startsWith("rx-frequency:") } > rxUpdatesBeforePtt)

        fixture.service.endTransmit(lease)
        assertTrue(radio.operations.indexOf("ptt:on") < radio.operations.lastIndexOf("ptt:off"))
        fixture.close()
    }

    @Test
    fun satelliteCapableRadioEnablesDedicatedModeBeforeMainSubSetup() = runTest {
        val radio = FakeRadioController()
        val fixture = Fixture(
            scope = backgroundScope,
            tx = radio,
            rx = FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC910,
            splitMode = true,
            duplexMode = RadioControlSettings.DUPLEX_MODE_SATELLITE
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        assertEquals(TrackingPhase.READY, fixture.service.state.value.trackingPhase)
        assertTrue(fixture.service.state.value.satelliteMode)
        assertTrue(radio.operations.indexOf("split:true") < radio.operations.indexOf("vfo:rx"))
        assertTrue(radio.operations.indexOf("split:true") < radio.operations.indexOf("vfo:tx"))
        fixture.close()
    }

    @Test
    fun satelliteCapableRadioCanUseOrdinarySplitFallback() = runTest {
        val radio = FakeRadioController()
        val fixture = Fixture(
            scope = backgroundScope,
            tx = radio,
            rx = FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC9700,
            splitMode = true,
            duplexMode = RadioControlSettings.DUPLEX_MODE_SPLIT
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        assertEquals(TrackingPhase.READY, fixture.service.state.value.trackingPhase)
        assertFalse(fixture.service.state.value.satelliteMode)
        assertTrue(radio.operations.indexOf("split:true") > radio.operations.indexOf("vfo:tx"))
        fixture.close()
    }

    @Test
    fun automaticLeaseRequiresReadyTrackingAndEntireWaveformInsidePass() = runTest {
        val fixture = Fixture(backgroundScope, FakeRadioController(), FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertEquals(TrackingPhase.READY, fixture.service.state.value.trackingPhase)

        val failure = runCatching {
            fixture.service.beginTransmit(
                fixture.request(
                    generation = 12L,
                    waveformStartUtcMillis = fixture.afterPassStart,
                    automatic = true
                )
            )
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("outside the selected pass") == true)
        fixture.close()
    }

    @Test
    fun failedInitializationNeverPublishesReadyOrActive() = runTest {
        val fixture = Fixture(
            backgroundScope,
            FakeRadioController(setModeSucceeds = false),
            FakeRadioController()
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        assertFalse(fixture.service.state.value.isActive)
        assertEquals(TrackingPhase.ERROR, fixture.service.state.value.trackingPhase)
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
        val fixture = Fixture(
            backgroundScope,
            FakeRadioController(),
            FakeRadioController(),
            nowProvider = { disciplinedNow }
        )
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

    @Test
    fun dualTrackingDefaultsToNominalUplinkCenter() = runTest {
        val fixture = Fixture(backgroundScope, FakeRadioController(), FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking(null)
        runCurrent()
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test
    fun ft4MidpointWriteIsNotMistakenForManualTuningAfterLease() = runTest {
        for (split in listOf(false, true)) {
            val fixture = Fixture(
                backgroundScope, FakeRadioController(), FakeRadioController(),
                nowProvider = { 1_800_000_000_000L + testScheduler.currentTime },
                radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = split
            )
            fixture.satelliteRepo.positionAt = { fixture.position.copy(distanceRate = (it % 100_000L) / 1_000.0) }
            fixture.service.connectRadios()
            fixture.startTracking()
            runCurrent()
            val lease = fixture.service.beginTransmit(fixture.request(91L))
            fixture.service.confirmTransmitReady(lease)
            advanceTimeBy(5_000L)
            fixture.service.endTransmit(lease)
            advanceTimeBy(4_000L)
            runCurrent()
            assertEquals("split=$split", fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)
            fixture.close()
        }
    }

    @Test
    fun missingUplinkModeIsDerivedFromInvertedDownlink() = runTest {
        val fixture = Fixture(backgroundScope, FakeRadioController(), FakeRadioController())
        fixture.transponder = fixture.transponder.copy(uplinkMode = null, isInverted = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertEquals("LSB", fixture.service.state.value.txMode)
        fixture.close()
    }

    @Test
    fun ft4RejectsFmAndModeReadbackMismatchBeforePtt() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        tx.modeReadbackOverride = "FM"
        assertTrue(runCatching { fixture.service.beginTransmit(fixture.request(92L)) }.isFailure)
        tx.modeReadbackOverride = null
        fixture.service.setMode("FM", "FM")
        runCurrent()
        assertTrue(runCatching { fixture.service.beginTransmit(fixture.request(93L)) }.isFailure)
        assertFalse("ptt:on" in tx.operations)
        fixture.close()
    }

    @Test
    fun satelliteOffsetAndRxDialTuningRoundTripForBothInversionDirections() = runTest {
        for (split in listOf(false, true)) for (inverted in listOf(false, true)) {
            val tx = FakeRadioController()
            val rx = FakeRadioController()
            val fixture = Fixture(backgroundScope, tx, rx,
                radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = split)
            fixture.transponder = fixture.transponder.copy(
                uplinkHigh = fixture.nominalTxHz + 10_000L, isInverted = inverted,
                uplinkMode = if (inverted) "LSB" else "USB")
            fixture.satelliteRepo.positionAt = { fixture.position.copy(distanceRate = 0.0) }
            fixture.settings.setSatelliteOffset(12_345, "1.25")
            fixture.service.connectRadios()
            fixture.startTracking()
            runCurrent()
            val initialRx = (if (inverted) 435_110_000L else 435_100_000L) + 1_250L
            assertEquals(initialRx, fixture.service.state.value.rxFrequencyHz)
            val receiver = if (split) tx else rx
            receiver.frequencyHz += 1_000L
            advanceTimeBy(3_100L)
            runCurrent()
            assertEquals(fixture.nominalTxHz + if (inverted) -1_000L else 1_000L,
                fixture.service.state.value.txBaseFrequencyHz)
            assertEquals(initialRx + 1_000L, fixture.service.state.value.rxFrequencyHz)
            fixture.settings.setSatelliteOffset(12_345, "-2")
            advanceTimeBy(1_100L)
            runCurrent()
            assertEquals(initialRx + 1_000L - 3_250L, fixture.service.state.value.rxFrequencyHz)
            fixture.close()
        }
    }

    @Test
    fun suspendedSplitReadCannotOverlapTransmitPreparationAndTrackingResumesDuringPtt() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        tx.beforeReadTx = { started.complete(Unit); release.await() }
        advanceTimeBy(1_000L)
        runCurrent()
        started.await()
        val preparing = async { fixture.service.beginTransmit(fixture.request(94L)) }
        runCurrent()
        assertFalse(preparing.isCompleted)
        release.complete(Unit)
        val lease = preparing.await()
        fixture.service.confirmTransmitReady(lease)
        val operationCount = tx.operations.size
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(tx.operations.size > operationCount)
        fixture.service.endTransmit(lease)
        fixture.close()
    }

    @Test
    fun fineTuningDuringSuspendedReadWinsAndMultipleStepsAccumulate() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        tx.beforeReadTx = { started.complete(Unit); release.await() }
        advanceTimeBy(1_000L)
        runCurrent()
        started.await()
        fixture.service.setTxBaseFrequency(fixture.nominalTxHz + 100L)
        repeat(5) { fixture.service.adjustTxBaseFrequency(10L) }
        release.complete(Unit)
        advanceTimeBy(4_100L)
        runCurrent()
        assertEquals(fixture.nominalTxHz + 150L, fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test
    fun failedFrequencyWriteKeepsLastAcknowledgedFrequency() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val previous = fixture.service.state.value.txFrequencyHz
        tx.frequencySucceeds = false
        fixture.service.adjustTxBaseFrequency(1_000L)
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(previous, fixture.service.state.value.txFrequencyHz)
        assertTrue(fixture.service.state.value.lastCommandError?.contains("frequency") == true)
        fixture.close()
    }

    @Test
    fun urgentPttOffPreemptsSlowRxButTrackingResumes() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, rx)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val lease = fixture.service.beginTransmit(fixture.request(95L))
        fixture.service.confirmTransmitReady(lease)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rx.beforeSetFrequency = { started.complete(Unit); release.await() }
        advanceTimeBy(1_000L)
        runCurrent()
        started.await()
        val ending = async { fixture.service.endTransmit(lease) }
        runCurrent()
        assertFalse(ending.isCompleted)
        release.complete(Unit)
        ending.await()
        val commands = tx.operations.count { it.startsWith("frequency:") }
        advanceTimeBy(2_100L)
        runCurrent()
        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        assertTrue(fixture.service.state.value.isActive)
        assertTrue(tx.operations.count { it.startsWith("frequency:") } > commands)
        fixture.close()
    }

    @Test
    fun ft4ReportsActualFrequencyAndRejectsMismatchedReadback() = runTest {
        for (split in listOf(false, true)) {
            val tx = FakeRadioController()
            val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
                radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = split)
            fixture.service.connectRadios()
            fixture.startTracking()
            runCurrent()
            tx.frequencyReadbackOffset = -5L
            val lease = fixture.service.beginTransmit(fixture.request(96L))
            assertEquals(fixture.position.getUplinkFreq(fixture.nominalTxHz) - 5L, lease.effectiveTxFrequencyHz)
            fixture.service.endTransmit(lease)
            tx.frequencyReadbackOffset = 200L
            assertTrue(runCatching { fixture.service.beginTransmit(fixture.request(97L)) }.isFailure)
            assertFalse("ptt:on" in tx.operations)
            fixture.close()
        }
    }

    @Test
    fun ft4RejectsSidebandsThatDisagreeWithInversion() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.transponder = fixture.transponder.copy(isInverted = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val failure = runCatching { fixture.service.beginTransmit(fixture.request(98L)) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("inversion") == true)
        assertFalse("ptt:on" in tx.operations)
        fixture.close()
    }

    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        private val tx: FakeRadioController,
        private val rx: FakeRadioController,
        nowProvider: () -> Long = System::currentTimeMillis,
        radioModel: String = RadioControlSettings.MODEL_YAESU_FT817,
        splitMode: Boolean = false,
        duplexMode: String = RadioControlSettings.DUPLEX_MODE_SPLIT
    ) {
        val nominalTxHz = 145_900_000L
        val position = OrbitalPos(elevation = 0.5, distanceRate = 1.2, aboveHorizon = true)
        val settings = FakeSettingsRepo(radioModel, splitMode, duplexMode)
        val satelliteRepo = FakeSatelliteRepo(position)
        private val now = nowProvider()
        val afterPassStart = now + 180_000L
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
        var transponder = SatRadio(
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

        fun startTracking(base: Long? = nominalTxHz) {
            service.startTracking(pass, transponder, base)
        }

        fun request(
            generation: Long,
            maximumPttMillis: Long = 8_500L,
            waveformStartUtcMillis: Long = now + 5_000L,
            automatic: Boolean = false
        ) = TxRequest(
            sessionGeneration = generation,
            waveformStartUtcMillis = waveformStartUtcMillis,
            expectedSatelliteCatalogNumber = pass.catNum,
            expectedTransponderUuid = transponder.uuid,
            automatic = automatic,
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
    private val pttOnSucceeds: Boolean = true,
    private val setModeSucceeds: Boolean = true,
    private val ctcssStepDelayMillis: Long = 0L
) : IRadioController {
    val operations = mutableListOf<String>()
    var frequencyHz = 0L
    var txFrequencyHz = 0L
    var mode = "USB"
    var modeReadbackOverride: String? = null
    var frequencySucceeds = true
    var frequencyReadbackOffset = 0L
    var beforeReadTx: (suspend () -> Unit)? = null
    var beforeSetFrequency: (suspend () -> Unit)? = null
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
        beforeSetFrequency?.invoke()
        operations += "frequency:$frequencyHz"
        if (!frequencySucceeds) return false
        this.frequencyHz = frequencyHz
        return true
    }

    override suspend fun setMode(mode: String): Boolean {
        operations += "mode:$mode"
        if (setModeSucceeds) this.mode = mode
        return setModeSucceeds
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean {
        operations += "ctcss:$enabled"
        if (ctcssStepDelayMillis > 0L) delay(ctcssStepDelayMillis)
        return true
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean {
        operations += "tone:$toneHz"
        if (ctcssStepDelayMillis > 0L) delay(ctcssStepDelayMillis)
        return true
    }
    override suspend fun readFrequencyAndMode(): Pair<Long, String> =
        (frequencyHz + frequencyReadbackOffset) to (modeReadbackOverride ?: mode)
    override suspend fun readTxVfoFrequency(): Long {
        beforeReadTx?.invoke()
        return txFrequencyHz + frequencyReadbackOffset
    }

    override suspend fun setBand(frequencyHz: Long): Boolean {
        operations += "band:$frequencyHz"
        return true
    }

    override suspend fun setVfo(vfoA: Boolean): Boolean {
        operations += "vfo:${if (vfoA) "rx" else "tx"}"
        if (ctcssStepDelayMillis > 0L) delay(ctcssStepDelayMillis)
        return true
    }

    override suspend fun setSplitMode(enabled: Boolean): Boolean {
        operations += "split:$enabled"
        return true
    }

    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean {
        operations += "split-modes:$rxMode:$txMode"
        return true
    }

    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean {
        operations += "rx-frequency:$frequencyHz"
        this.frequencyHz = frequencyHz
        return true
    }

    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean {
        operations += "tx-frequency:$frequencyHz"
        txFrequencyHz = frequencyHz
        return true
    }

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
    var positionAt: ((Long) -> OrbitalPos)? = null
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
        return (positionAt?.invoke(time) ?: position).copy(time = time)
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

private class FakeSettingsRepo(
    radioModel: String = RadioControlSettings.MODEL_YAESU_FT817,
    splitMode: Boolean = false,
    duplexMode: String = RadioControlSettings.DUPLEX_MODE_SPLIT
) : ISettingsRepo {
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
            radioModel = radioModel,
            txRadioAddress = "TX",
            rxRadioAddress = "RX",
            txRadioName = "TX",
            rxRadioName = "RX",
            baudRate = 9_600,
            splitMode = splitMode,
            duplexMode = duplexMode
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
    private val offsets = mutableMapOf<Int, String>()
    override fun getSatelliteOffset(catnum: Int) = offsets[catnum].orEmpty()
    override fun setSatelliteOffset(catnum: Int, offset: String) { offsets[catnum] = offset }
    override fun getAmSatCallsign() = ""
    override fun setAmSatCallsign(callsign: String) = Unit
}
