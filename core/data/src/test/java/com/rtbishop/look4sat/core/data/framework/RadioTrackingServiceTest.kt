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
import com.rtbishop.look4sat.core.domain.model.GridQso
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.TrackingPhase
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioCapabilities
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner
import com.rtbishop.look4sat.core.domain.repository.CwTransmitState
import com.rtbishop.look4sat.core.domain.utility.CalculatorTuning
import com.rtbishop.look4sat.core.domain.utility.CalculatorFrequencyAnchor
import com.rtbishop.look4sat.core.domain.utility.frequencies
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
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioTrackingServiceTest {
    @Test fun n76UsesAtomicNativeTrackingAndDoesNotApplyTheL4s250HzQuantizer() = runTest {
        val remote = remoteFixtureState().also { flow ->
            flow.value = flow.value.copy(capabilities = flow.value.capabilities!!.copy(
                cwText = false, voicePtt = false, satelliteProfiles = false, nativeSettings = false))
        }
        val tx = FakeRadioController(remote = remote, pttReadbackSupported = true)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.transponder = fixture.transponder.copy(uplinkMode = "FM", downlinkMode = "FM")
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(fixture.service.state.value.splitMode)
        assertTrue(fixture.service.state.value.isActive)
        assertTrue(tx.operations.any { it.startsWith("pair:") })
        assertFalse(tx.operations.any { it.startsWith("frequency:") || it.startsWith("tx-frequency:") || it.startsWith("rx-frequency:") })
        val current = fixture.service.state.value
        assertEquals(fixture.position.getDownlinkFreq(current.nominalRxFrequencyHz!!), current.targetRxFrequencyHz)
        assertEquals(fixture.position.getUplinkFreq(current.nominalTxFrequencyHz!!), current.targetTxFrequencyHz)
        fixture.close()
        assertTrue("split:false" in tx.operations)
    }

    @Test fun n76KeepsFmForTelemetrySelectionAndExplicitModeRequests() = runTest {
        val remote = remoteFixtureState().also { flow ->
            flow.value = flow.value.copy(capabilities = flow.value.capabilities!!.copy(
                satelliteProfiles = false, nativeSettings = false))
        }
        val tx = FakeRadioController(remote = remote, pttReadbackSupported = true)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.service.connectRadios()
        fixture.transponder = fixture.transponder.copy(uplinkLow = null, uplinkHigh = null,
            uplinkMode = null, downlinkMode = "BPSK")
        fixture.service.setTransponder(fixture.transponder)
        runCurrent()
        fixture.startTracking(null)
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        fixture.service.setMode("CW", "USB")
        runCurrent()
        assertEquals("FM", fixture.service.state.value.txMode)
        assertEquals("FM", fixture.service.state.value.rxMode)
        assertTrue(tx.operations.filter { it.startsWith("split-modes:") }.all { it == "split-modes:FM:FM" })
        assertNull(fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun recoveredRemoteErrorClearsThePanelWithoutErasingALaterCommandFailure() = runTest {
        val remote = remoteFixtureState()
        val tx = FakeRadioController(remote = remote, setModeSucceeds = false)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.service.connectRadios()
        runCurrent()
        remote.value = remote.value.copy(error = "Recording source unavailable")
        runCurrent()
        assertEquals(remote.value.error, fixture.service.state.value.errorMessage)
        remote.value = remote.value.copy(error = null, recording = true)
        runCurrent()
        assertNull(fixture.service.state.value.errorMessage)
        assertNull(fixture.service.state.value.lastCommandError)
        remote.value = remote.value.copy(error = "Old recording error")
        runCurrent()
        fixture.service.setMode("FM", "FM")
        runCurrent()
        assertEquals("Radio mode command was not acknowledged", fixture.service.state.value.errorMessage)
        remote.value = remote.value.copy(error = null)
        runCurrent()
        assertEquals("Radio mode command was not acknowledged", fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun recordingAndExportErrorsDoNotBecomeCatErrorsOrStopTracking() = runTest {
        val remote = remoteFixtureState().also { flow ->
            flow.value = flow.value.copy(isN76 = true, capabilities = flow.value.capabilities!!.copy(
                satelliteProfiles = false, nativeSettings = false))
        }
        val tx = FakeRadioController(remote = remote, pttReadbackSupported = true)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76, transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.transponder = fixture.transponder.copy(uplinkMode = "FM", downlinkMode = "FM")
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        remote.value = remote.value.copy(recordingError = "Microphone permission required", recordingExportError = "Folder access expired")
        fixture.service.recordingAction(com.rtbishop.look4sat.core.domain.model.RadioRecordingAction.START)
        fixture.service.saveRecording("content://new-provider/recording.wav")
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertNull(fixture.service.state.value.errorMessage)
        assertEquals("Folder access expired", fixture.service.state.value.remoteRadio?.recordingExportError)
        remote.value = remote.value.copy(recordingError = null, recordingExportError = null)
        runCurrent()
        assertNull(fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun enablingN76AudioRecreatesTheConnectionOnNextConnect() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.service.connectRadios()
        assertTrue(fixture.service.applyRadioControlSettings(fixture.settings.radioControlSettings.value.copy(n76BluetoothAudio = true)))
        assertFalse(fixture.service.state.value.txConnected)
        assertTrue("disconnect" in tx.operations)
        fixture.service.connectRadios()
        assertTrue(fixture.service.state.value.txConnected)
        fixture.close()
    }

    @Test fun n76RejectsUnsupportedUsbBeforeConstructingAnyController() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(), radioModel = RadioControlSettings.MODEL_VERO_N76)
        fixture.service.connectRadios()
        assertFalse(fixture.service.state.value.txConnected)
        assertTrue(tx.operations.isEmpty())
        assertEquals("N76 requires Bluetooth", fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun ic705DopplerChangesBothFrequenciesWithoutSwitchingToTxVfo() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertTrue(tx.operations.any { it.startsWith("tx-frequency:") })
        tx.operations.clear()
        repeat(4) { index ->
            fixture.position.distanceRate = 1.2 + index * 0.4
            advanceTimeBy(1_100L)
            runCurrent()
        }
        assertTrue(tx.operations.count { it.startsWith("tx-frequency:") } >= 2)
        assertTrue(tx.operations.count { it.startsWith("rx-frequency:") } >= 2)
        assertFalse(tx.operations.any { it.startsWith("vfo:") })
        assertFalse(tx.operations.any { it.startsWith("mode:") || it.startsWith("split-modes:") })
        fixture.close()
    }

    @Test fun ic705ReceiveOnlyTrackingLeavesSplitOffAndOldTxVfoUntouched() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = true)
        fixture.transponder = fixture.transponder.copy(uplinkLow = null, uplinkHigh = null)
        fixture.service.connectRadios()
        fixture.startTracking(null)
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertFalse(tx.operations.contains("split:true"))
        assertFalse(tx.operations.contains("vfo:tx"))
        assertFalse(tx.operations.any { it.startsWith("tx-frequency:") })
        fixture.close()
    }

    private val legacyModels=listOf(RadioControlSettings.MODEL_YAESU_FT817,RadioControlSettings.MODEL_YAESU_FT857,RadioControlSettings.MODEL_YAESU_FTX1,
        RadioControlSettings.MODEL_ICOM_IC705,RadioControlSettings.MODEL_ICOM_IC9700,
        RadioControlSettings.MODEL_ICOM_IC910,RadioControlSettings.MODEL_ICOM_IC820)

    @Test fun connectionSettingsChangeClosesOldControllersAndRequiresExplicitReconnect()=runTest {
        val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertTrue(fixture.service.applyRadioControlSettings(fixture.settings.radioControlSettings.value.copy(baudRate=38400)))
        assertFalse(fixture.service.state.value.txConnected)
        assertFalse(fixture.service.state.value.rxConnected)
        assertFalse(fixture.service.state.value.isActive)
        assertNull(fixture.service.state.value.nominalRxFrequencyHz)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertTrue(fixture.service.state.value.isActive);fixture.close()
    }

    @Test fun receiveOnlyConnectionQueriesOnlyTheConfiguredRadio()=runTest {
        val tx=FakeRadioController(pttReadbackSupported=true)
        val rx=FakeRadioController(pttReadbackSupported=true)
        val fixture=Fixture(backgroundScope,tx,rx)
        fixture.settings.radioControlSettings.value=fixture.settings.radioControlSettings.value.copy(txRadioAddress="")
        fixture.transponder=fixture.transponder.copy(uplinkLow=null,uplinkHigh=null)
        fixture.service.connectRadios();fixture.startTracking(null);runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertTrue(fixture.service.state.value.catTransmitStateKnown)
        assertNull(fixture.service.state.value.errorMessage)
        rx.physicalPtt=true;advanceTimeBy(1100);runCurrent()
        assertTrue(fixture.service.state.value.frequencyEditingLocked);fixture.close()
    }

    @Test fun noConnectedRadioCannotReportTrackingReady()=runTest {
        val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        fixture.startTracking();runCurrent()
        assertFalse(fixture.service.state.value.isActive)
        assertEquals(TrackingPhase.ERROR,fixture.service.state.value.trackingPhase)
        fixture.close()
    }

    @Test fun independentBasesAndExplicitCalculatorLinkWorkForEveryLegacyModel()=runTest {
        for(model in legacyModels) for(split in listOf(false,true)) {
            val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController(),radioModel=model,splitMode=split)
            fixture.transponder=fixture.transponder.copy(uplinkHigh=fixture.nominalTxHz+10000)
            fixture.service.connectRadios();fixture.startTracking();runCurrent()
            val rx=fixture.service.state.value.nominalRxFrequencyHz
            fixture.service.adjustTxBaseFrequency(1000);runCurrent()
            assertEquals("$model RX must not follow TX",rx,fixture.service.state.value.nominalRxFrequencyHz)
            val tx=fixture.service.state.value.txBaseFrequencyHz
            fixture.service.setRxBaseFrequency(435108000);runCurrent()
            assertEquals(tx,fixture.service.state.value.txBaseFrequencyHz)
            val tuning=CalculatorTuning(fixture.transponder.uuid,CalculatorFrequencyAnchor.GROUND_RX,435105000)
            fixture.service.tuneFromCalculator(fixture.transponder,tuning);runCurrent()
            assertTrue("$model calculator must explicitly link",fixture.service.state.value.calculatorLinked)
            val linkedRx=fixture.service.state.value.nominalRxFrequencyHz
            fixture.service.adjustTxBaseFrequency(500);runCurrent()
            assertFalse(fixture.service.state.value.calculatorLinked)
            assertEquals(linkedRx,fixture.service.state.value.nominalRxFrequencyHz)
            fixture.close()
        }
    }

    @Test fun legacyTransmitLeaseRejectsAllEditsWithoutStoppingOrDeferringThem()=runTest {
        for(model in legacyModels.filter {it!=RadioControlSettings.MODEL_ICOM_IC820}) {
            val tx=FakeRadioController();val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=model)
            fixture.transponder=fixture.transponder.copy(uplinkHigh=fixture.nominalTxHz+10000)
            fixture.service.connectRadios();fixture.startTracking();runCurrent()
            val before=fixture.service.state.value
            val lease=fixture.service.beginTransmit(fixture.request(701))
            assertTrue(fixture.service.state.value.frequencyEditingLocked)
            val off=tx.operations.count {it=="ptt:off"}
            fixture.service.setTxBaseFrequency(145905000);fixture.service.setRxBaseFrequency(435109000)
            fixture.service.setMode("CW","CW");fixture.service.setCtcssTone(88.5)
            fixture.service.tuneFromCalculator(fixture.transponder,CalculatorTuning(fixture.transponder.uuid,
                CalculatorFrequencyAnchor.GROUND_RX,435105000))
            assertFalse(fixture.service.applyRadioControlSettings(fixture.settings.radioControlSettings.value.copy(baudRate=9600)))
            runCurrent()
            assertEquals(off,tx.operations.count {it=="ptt:off"})
            assertEquals(before.txBaseFrequencyHz,fixture.service.state.value.txBaseFrequencyHz)
            fixture.service.confirmTransmitReady(lease);fixture.service.endTransmit(lease)
            advanceTimeBy(1100);runCurrent()
            assertEquals(before.txBaseFrequencyHz,fixture.service.state.value.txBaseFrequencyHz)
            assertFalse(fixture.service.state.value.calculatorLinked)
            fixture.close()
        }
    }

    @Test fun legacyPhysicalPttAndUnknownStatusFreezeTuningThenResumeAtCurrentTime()=runTest {
        val tx=FakeRadioController(pttReadbackSupported=true)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_ICOM_IC705,splitMode=true)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val base=fixture.service.state.value.txBaseFrequencyHz
        tx.physicalPtt=true;advanceTimeBy(1100);runCurrent()
        assertTrue(fixture.service.state.value.frequencyEditingLocked)
        val writes=tx.operations.size
        fixture.service.adjustTxBaseFrequency(1000);runCurrent();advanceTimeBy(1100);runCurrent()
        assertEquals(writes,tx.operations.size)
        tx.physicalPtt=null;advanceTimeBy(1100);runCurrent()
        assertFalse(fixture.service.state.value.catTransmitStateKnown)
        assertEquals(writes,tx.operations.size)
        tx.physicalPtt=false;advanceTimeBy(1100);runCurrent()
        assertFalse(fixture.service.state.value.frequencyEditingLocked)
        assertTrue(tx.operations.size>writes)
        assertEquals(base,fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test fun receiveOnlyAndReconnectionNeverReuseAnOldTransmitTarget()=runTest {
        for(model in legacyModels) {
            val tx=FakeRadioController();val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=model,splitMode=true)
            fixture.service.connectRadios();fixture.startTracking();runCurrent()
            fixture.service.setRxBaseFrequency(435108000);runCurrent()
            fixture.service.connectRadios();runCurrent()
            assertEquals(435108000L,fixture.service.state.value.nominalRxFrequencyHz)
            assertNull(fixture.service.state.value.targetTxFrequencyHz)
            assertFalse(fixture.service.state.value.isActive)
            fixture.transponder=fixture.transponder.copy(uuid="beacon",uplinkLow=null,uplinkHigh=null,downlinkMode="FM")
            val writes=tx.operations.count {it.startsWith("frequency:") || it.startsWith("tx-frequency:")}
            fixture.startTracking();runCurrent()
            assertNull(fixture.service.state.value.txBaseFrequencyHz)
            fixture.service.setTxBaseFrequency(145905000);runCurrent()
            assertNull(fixture.service.state.value.txBaseFrequencyHz)
            if(!fixture.service.state.value.splitMode)assertEquals(writes,tx.operations.count {it.startsWith("frequency:") || it.startsWith("tx-frequency:")})
            fixture.close()
        }
    }
    @Test fun localPhysicalPttIsReleasedAtLosWithoutASeparateRemoteOwner()=runTest {
        var now=System.currentTimeMillis()
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),nowProvider={now},
            radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        remote.value=remote.value.copy(txSource=3);runCurrent()
        val releases=tx.operations.count {it=="ptt:off"}
        now=fixture.afterPassStart;advanceTimeBy(1100);runCurrent()
        assertTrue(tx.operations.count {it=="ptt:off"}>releases)
        assertEquals(0,remote.value.txSource);assertFalse(fixture.service.state.value.isActive)
        fixture.close()
    }
    @Test fun voiceTransmissionRejectsBaseModeToneAndCalculatorEditsWithoutPttOffOrQueue()=runTest {
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        fixture.service.setVoicePtt(true);runCurrent()
        val before=fixture.service.state.value;val off=tx.operations.count {it=="ptt:off"};val pairs=tx.operations.count {it.startsWith("pair:")}
        fixture.service.adjustTxBaseFrequency(1000);fixture.service.setRxBaseFrequency(435150000)
        fixture.service.setMode("CW","CW");fixture.service.setCtcssTone(88.5)
        runCurrent();advanceTimeBy(1100);runCurrent()
        assertEquals(RadioTransmitOwner.VOICE,fixture.service.state.value.transmitOwner)
        assertEquals(off,tx.operations.count {it=="ptt:off"});assertEquals(pairs,tx.operations.count {it.startsWith("pair:")})
        assertEquals(before.txBaseFrequencyHz,fixture.service.state.value.txBaseFrequencyHz)
        fixture.service.cancelRemoteTransmit();runCurrent()
        assertEquals(before.txBaseFrequencyHz,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(before.nominalRxFrequencyHz,fixture.service.state.value.nominalRxFrequencyHz)
        fixture.close()
    }
    @Test fun queuedEditCannotOvertakePttArmingAndUnknownStateCannotEdit()=runTest {
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val base=fixture.service.state.value.txBaseFrequencyHz
        remote.value=remote.value.copy(transmitStateKnown=false)
        fixture.service.adjustTxBaseFrequency(1000);runCurrent()
        assertEquals(base,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals("Radio transmit state is unknown", fixture.service.state.value.lastCommandError)
        remote.value=remote.value.copy(transmitStateKnown=true);runCurrent()
        fixture.service.adjustTxBaseFrequency(1000);fixture.service.setVoicePtt(true);runCurrent()
        assertEquals(base,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(RadioTransmitOwner.VOICE,fixture.service.state.value.transmitOwner)
        fixture.service.cancelRemoteTransmit();runCurrent();fixture.close()
    }
    @Test fun repeatedConnectDuringHandshakeDoesNotQueueAnotherDisconnect() = runTest {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val tx = FakeRadioController().apply { beforeConnect = { started.complete(Unit); proceed.await() } }
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        val first = async { fixture.service.connectRadios() }
        runCurrent()
        assertTrue(started.isCompleted)
        val duplicate = async { fixture.service.connectRadios() }
        runCurrent()
        assertTrue(duplicate.isCompleted)
        proceed.complete(Unit)
        first.await()
        duplicate.await()
        assertEquals(1, tx.operations.count { it == "connect" })
        assertTrue(fixture.service.state.value.txConnected)
        fixture.close()
    }

    @Test fun disconnectedRemoteDoesNotReportAnActiveTransmission() = runTest {
        val remote = remoteFixtureState()
        val fixture = Fixture(backgroundScope, FakeRadioController(remote = remote), FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true)
        fixture.service.connectRadios()
        remote.value = remote.value.copy(connected = false, transmitStateKnown = false)
        runCurrent()
        fixture.service.setMode("FM", "FM")
        runCurrent()
        assertEquals("Radio is not connected", fixture.service.state.value.lastCommandError)
        fixture.close()
    }
    @Test fun independentlyEditingEitherBaseKeepsTheOtherAndReceiveOnlyProfileNeverAddsTx()=runTest {
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val rx=fixture.service.state.value.nominalRxFrequencyHz
        fixture.service.adjustTxBaseFrequency(1000);runCurrent()
        assertEquals(rx,fixture.service.state.value.nominalRxFrequencyHz)
        val newTx=fixture.service.state.value.txBaseFrequencyHz
        fixture.service.setRxBaseFrequency(435150000);runCurrent()
        assertEquals(newTx,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(435150000L,fixture.service.state.value.nominalRxFrequencyHz)
        fixture.close()
        val secondRemote=remoteFixtureState()
        secondRemote.value=secondRemote.value.copy(capabilities=secondRemote.value.capabilities!!.copy(nativeSettings=true,satelliteProfiles=true),
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey("TEST","test-transponder"),
            nativeSavedMode=0,nativeBaseFrequencies=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,null))
        val receiver=FakeRadioController(remote=secondRemote)
        val second=Fixture(backgroundScope,receiver,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        second.transponder=second.transponder.copy(uplinkLow=null,uplinkHigh=null,downlinkMode="FM",uplinkMode="FM")
        second.service.connectRadios();second.startTracking();runCurrent()
        assertNull(second.service.state.value.txBaseFrequencyHz);assertFalse(secondRemote.value.txEnabled)
        second.service.setVoicePtt(true);runCurrent();assertFalse(receiver.operations.contains("ptt:on"));second.close()
    }
    @Test fun satelliteProfileRestoresIndependentBasesForTrackingAndPttPreparation()=runTest {
        val remote=remoteFixtureState()
        val bases=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,145920000)
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true,satelliteProfiles=true),
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey("TEST","test-transponder"),
            nativeBaseFrequencies=bases,nativeProfileGeneration=1,settingsSequence=1,nativeSavedMode=0)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertEquals(145920000L,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(fixture.position.getDownlinkFreq(bases.rxHz),tx.frequencyHz)
        assertEquals(fixture.position.getUplinkFreq(bases.txHz!!),tx.txFrequencyHz)
        assertTrue(tx.operations.contains("bases:435150000:145920000"))
        fixture.service.setVoicePtt(true);runCurrent()
        assertEquals(fixture.position.getDownlinkFreq(bases.rxHz),tx.frequencyHz)
        assertEquals(1,remote.value.txSource)
        fixture.service.setVoicePtt(false);runCurrent();fixture.close()
    }

    @Test fun nativeSaveUpdatesBothBasesAndPhoneEditsSyncBackToMenu()=runTest {
        val remote=remoteFixtureState()
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true,satelliteProfiles=true),
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey("TEST","test-transponder"),
            nativeBaseFrequencies=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,145920000),
            nativeProfileGeneration=1,settingsSequence=1,nativeSavedMode=0)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        remote.value=remote.value.copy(nativeProfileGeneration=2,settingsSequence=2,nativeSettingsModified=true,
            nativeBaseFrequencies=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435160000,145925000))
        runCurrent()
        assertEquals(145925000L,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(fixture.position.getDownlinkFreq(435160000),tx.frequencyHz)
        fixture.service.setTxBaseFrequency(145930000);runCurrent()
        assertEquals(145930000L,remote.value.nativeBaseFrequencies!!.txHz)
        assertEquals(145930000L,fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }
    @Test fun fmMetadataDefaultsSurviveConnectionAndApplyModeAndTxCtcss()=runTest {
        val remote=remoteFixtureState()
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true),settingsSequence=1)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(info="Mode V/U FM Voice CTCSS 67.0 Hz",uplinkMode="FM",downlinkMode="FM")
        fixture.service.setTransponder(fixture.transponder);runCurrent()
        fixture.service.connectRadios();runCurrent()
        assertEquals(67.0,fixture.service.state.value.ctcssTone!!,0.0)
        fixture.startTracking();runCurrent()
        assertTrue(tx.operations.contains("split-modes:FM:FM"))
        assertTrue(tx.operations.contains("tone:67.0"))
        fixture.close()
    }

    @Test fun savedNativeProfileForThisTransponderOverridesOnlyItsDefaults()=runTest {
        val remote=remoteFixtureState()
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true),settingsSequence=1,
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioContextKey("test-transponder"),
            nativeSavedMode=0,nativeSavedTxCtcssHz=88.5,txCtcssHz=88.5)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(info="FM CTCSS 67.0 Hz",uplinkMode="FM",downlinkMode="FM")
        fixture.service.setTransponder(fixture.transponder);runCurrent();fixture.service.connectRadios();runCurrent()
        fixture.startTracking();runCurrent()
        assertTrue(tx.operations.contains("tone:88.5"))
        tx.operations.clear()
        fixture.service.setTransponder(fixture.transponder.copy(uuid="other",info="FM Voice"));runCurrent()
        assertNull(fixture.service.state.value.ctcssTone)
        assertTrue(tx.operations.contains("ctcss:false"))
        fixture.close()
    }
    @Test fun calculatorTxEditImmediatelyAppliesAtomicPairWithoutDoubleDoppler() = runTest {
        val tx=FakeRadioController(remote=remoteFixtureState())
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkHigh=146000000,downlinkHigh=435200000,isInverted=true)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val tuning=CalculatorTuning(fixture.transponder.uuid,CalculatorFrequencyAnchor.GROUND_TX,145950000)
        fixture.service.tuneFromCalculator(fixture.transponder,tuning);runCurrent()
        val expected=tuning.frequencies(fixture.transponder,fixture.position,0)!!
        assertEquals(expected.txHz,tx.txFrequencyHz);assertEquals(expected.rxHz,tx.frequencyHz)
        assertEquals(145950000,tx.txFrequencyHz)
        fixture.service.setTxBaseFrequency(145920000);runCurrent()
        assertEquals(fixture.position.getUplinkFreq(145920000),tx.txFrequencyHz)
        fixture.close()
    }

    @Test fun calibratedCalculatorReferenceSurvivesTrackingTicksAndManualEditsExitLinking() = runTest {
        val tx=FakeRadioController(remote=remoteFixtureState())
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkHigh=146000000,downlinkHigh=435200000,isInverted=true)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val reference=com.rtbishop.look4sat.core.domain.utility.CalculatorReference(145970000,435165000)
        fixture.service.setBaseFrequencies(com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(reference.rxHz,reference.txHz))
        runCurrent()
        val tuning=CalculatorTuning(fixture.transponder.uuid,CalculatorFrequencyAnchor.SATELLITE,reference.txHz+1000,reference)
        fixture.service.tuneFromCalculator(fixture.transponder,tuning);runCurrent()
        repeat(3) {
            advanceTimeBy(1100);runCurrent()
            val expected=tuning.frequencies(fixture.transponder,fixture.position,0)!!
            assertEquals(expected.txHz,tx.txFrequencyHz)
            assertEquals(expected.rxHz,tx.frequencyHz)
            assertEquals(reference,fixture.service.state.value.calculatorReference)
        }
        fixture.service.setRxBaseFrequency(435167000);runCurrent()
        assertFalse(fixture.service.state.value.calculatorLinked)
        assertEquals(435167000L,fixture.service.state.value.nominalRxFrequencyHz)
        fixture.close()
    }

    @Test fun restartedServiceRestoresManualReferencesBeforeTrackingWithoutDefaultOverwrite() = runTest {
        val first=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        first.service.setTransponder(first.transponder);runCurrent()
        val expected=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435107000,145906000)
        first.service.setBaseFrequencies(expected);runCurrent()
        first.service.setTxBaseFrequency(145907000);runCurrent()
        first.service.setRxBaseFrequency(435108000);runCurrent()
        first.service.adjustTxBaseFrequency(1000);runCurrent()
        val saved=first.settings.savedBases.toMap()
        assertEquals(expected.copy(rxHz=435108000,txHz=145908000),saved[first.transponder.uuid])
        first.close()
        val second=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        second.settings.savedBases.putAll(saved)
        second.service.setTransponder(second.transponder);runCurrent()
        assertEquals(145908000L,second.service.state.value.txBaseFrequencyHz)
        assertEquals(435108000L,second.service.state.value.nominalRxFrequencyHz)
        second.service.connectRadios();second.startTracking();runCurrent()
        assertEquals(145908000L,second.service.state.value.nominalTxFrequencyHz)
        assertEquals(435108000L,second.service.state.value.nominalRxFrequencyHz)
        assertFalse(second.service.state.value.calculatorLinked)
        second.close()
    }

    @Test fun savedReferencesAreIsolatedAndNeverRestoreAnUplinkForReceiveOnlyTransponders() = runTest {
        val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        val bases=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435108000,145908000)
        fixture.settings.savedBases[fixture.transponder.uuid]=bases
        fixture.service.setTransponder(fixture.transponder.copy(uplinkLow=null,uplinkHigh=null));runCurrent()
        assertEquals(bases.rxHz,fixture.service.state.value.nominalRxFrequencyHz)
        assertNull(fixture.service.state.value.txBaseFrequencyHz)
        fixture.service.setTransponder(fixture.transponder.copy(uuid="other"));runCurrent()
        assertEquals(fixture.nominalTxHz,fixture.service.state.value.txBaseFrequencyHz)
        assertFalse(fixture.service.state.value.nominalRxFrequencyHz==bases.rxHz)
        fixture.close()
    }

    @Test fun restoredL4sReferencesTakePriorityOverTheInitialHandsetSnapshot() = runTest {
        val remote=remoteFixtureState()
        remote.value=remote.value.copy(nativeProfileValid=true,nativeSettingsModified=true,nativeProfileGeneration=1,
            nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioContextKey("test-transponder"),
            nativeBaseFrequencies=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435100000,145900000),
            capabilities=remote.value.capabilities!!.copy(nativeSettings=true,satelliteProfiles=true))
        val fixture=Fixture(backgroundScope,FakeRadioController(remote=remote),FakeRadioController(),
            radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,splitMode=true)
        val expected=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435108000,145908000)
        fixture.settings.savedBases[fixture.transponder.uuid]=expected
        fixture.service.setTransponder(fixture.transponder);runCurrent()
        fixture.service.connectRadios();runCurrent()
        assertEquals(expected.rxHz,fixture.service.state.value.nominalRxFrequencyHz)
        assertEquals(expected.txHz,fixture.service.state.value.txBaseFrequencyHz)
        fixture.startTracking();runCurrent()
        assertEquals(expected.rxHz,fixture.service.state.value.nominalRxFrequencyHz)
        assertEquals(expected.txHz,fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test fun savedDisabledUplinkIsNotReplacedByTheTransponderDefaultOnTrackingStart() = runTest {
        val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        fixture.settings.savedBases[fixture.transponder.uuid]=
            com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435108000,null)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertNull(fixture.service.state.value.txBaseFrequencyHz)
        assertNull(fixture.service.state.value.targetTxFrequencyHz)
        assertEquals(435108000L,fixture.service.state.value.nominalRxFrequencyHz)
        fixture.close()
    }

    @Test fun failedReferenceSaveDoesNotChangeTheAcceptedFrequencyPair() = runTest {
        val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        fixture.service.setTransponder(fixture.transponder);runCurrent()
        val before=fixture.service.state.value
        fixture.settings.saveBasesSucceeds=false
        fixture.service.setBaseFrequencies(com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435108000,145908000))
        runCurrent()
        assertEquals(before.txBaseFrequencyHz,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(before.nominalRxFrequencyHz,fixture.service.state.value.nominalRxFrequencyHz)
        assertTrue(fixture.service.state.value.errorMessage!!.contains("Could not save"))
        fixture.close()
    }

    @Test fun calculatorRxEditDuringCwIsRejectedWithoutStoppingOrQueuing() = runTest {
        val tx=FakeRadioController(remote=remoteFixtureState())
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkHigh=146000000,downlinkHigh=435200000,isInverted=true)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        fixture.service.sendCw("CQ TEST",20);runCurrent()
        val originalRx=tx.frequencyHz
        val writes=tx.operations.count { it.startsWith("pair:") }
        val tuning=CalculatorTuning(fixture.transponder.uuid,CalculatorFrequencyAnchor.GROUND_RX,435150000)
        fixture.service.tuneFromCalculator(fixture.transponder,tuning);runCurrent()
        advanceTimeBy(1100);runCurrent()
        assertEquals(RadioTransmitOwner.CW,fixture.service.state.value.transmitOwner)
        assertEquals(writes,tx.operations.count { it.startsWith("pair:") })
        fixture.service.cancelRemoteTransmit();runCurrent()
        assertEquals(originalRx,tx.frequencyHz)
        fixture.close()
    }

    @Test fun physicalPttStateHoldsTrackingAndReleaseWakesItWithoutDisconnect() = runTest {
        val remote=remoteFixtureState()
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        remote.value=remote.value.copy(txSource=3);runCurrent()
        val writes=tx.operations.count { it.startsWith("pair:") }
        advanceTimeBy(1100);runCurrent()
        assertEquals(writes,tx.operations.count { it.startsWith("pair:") })
        assertTrue(fixture.service.state.value.txConnected)
        remote.value=remote.value.copy(txSource=0);runCurrent()
        assertTrue(tx.operations.count { it.startsWith("pair:") }>writes)
        assertTrue(fixture.service.state.value.txConnected)
        fixture.close()
    }


    private fun remoteFixtureState() = MutableStateFlow(RemoteRadioState(
        connected = true, rxFrequencyHz = 435100000L, txFrequencyHz = 145900000L,
        capabilities = RemoteRadioCapabilities(10,92,10,45,1500,18000000,1300000000,15,9,true,true,true,true,false,false)
    ))

    @Test fun l4sVoiceHoldsBothLegsAndImmediatelyResumesWithFreshTime() = runTest {
        val remote = remoteFixtureState()
        val tx = FakeRadioController(remote = remote)
        val baseTime = System.currentTimeMillis()
        val fixture = Fixture(backgroundScope,tx,FakeRadioController(),
            nowProvider = { baseTime + testScheduler.currentTime },
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true,
            transport = RadioControlSettings.TRANSPORT_USB)
        fixture.transponder = fixture.transponder.copy(uplinkMode = "FM", downlinkMode = "FM")
        fixture.satelliteRepo.positionAt = { time -> fixture.position.copy(distanceRate = if(time>baseTime+500) 3.0 else 1.0) }
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        fixture.service.setVoicePtt(true);runCurrent()
        assertEquals(RadioTransmitOwner.VOICE,fixture.service.state.value.transmitOwner)
        assertEquals(PttState.ON,fixture.service.state.value.pttState)
        val writes = tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") }
        assertTrue(runCatching { fixture.service.beginTransmit(fixture.request(501)) }.isFailure)
        advanceTimeBy(1100);runCurrent()
        assertEquals(writes,tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") })
        fixture.service.setVoicePtt(false);runCurrent()
        assertEquals(PttState.OFF,fixture.service.state.value.pttState)
        assertTrue(tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") } > writes)
        assertEquals(baseTime+testScheduler.currentTime,fixture.satelliteRepo.requestedTimes.last())
        fixture.close()
    }

    @Test fun l4sCwGapsStillHoldBothFrequenciesUntilCancelled() = runTest {
        val remote = remoteFixtureState()
        val tx = FakeRadioController(remote = remote)
        val fixture = Fixture(backgroundScope,tx,FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true,
            transport = RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertEquals("CW",fixture.service.state.value.txMode)
        fixture.service.sendCw("CQ DE TEST",20);runCurrent()
        assertEquals(RadioTransmitOwner.CW,fixture.service.state.value.transmitOwner)
        val writes = tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") }
        remote.value = remote.value.copy(txSource = 0) // key-up gap, message still sending
        advanceTimeBy(1100);runCurrent()
        assertEquals(writes,tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") })
        fixture.service.cancelRemoteTransmit();runCurrent()
        assertEquals(RadioTransmitOwner.NONE,fixture.service.state.value.transmitOwner)
        assertEquals(PttState.OFF,fixture.service.state.value.pttState)
        assertTrue(tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") } > writes)
        fixture.close()
    }

    @Test fun l4sTouchReleaseBeforeQueuedPressNeverKeysRadio() = runTest {
        val tx = FakeRadioController(remote = remoteFixtureState())
        val fixture = Fixture(backgroundScope,tx,FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true,
            transport = RadioControlSettings.TRANSPORT_USB)
        fixture.transponder = fixture.transponder.copy(uplinkMode = "FM", downlinkMode = "FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        fixture.service.setVoicePtt(true);fixture.service.setVoicePtt(false);runCurrent()
        assertFalse(tx.operations.contains("ptt:on"))
        fixture.close()
    }

    @Test
    fun ic820RejectsTwoConnectionsToTheSameRadio() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, rx, radioModel = RadioControlSettings.MODEL_ICOM_IC820)
        fixture.settings.radioControlSettings.value = fixture.settings.radioControlSettings.value.copy(rxRadioAddress = "TX")
        fixture.service.connectRadios()
        assertTrue(fixture.service.state.value.errorMessage?.contains("separate TX/RX radios") == true)
        assertFalse(tx.isConnected)
        assertFalse(rx.isConnected)
        fixture.close()
    }

    @Test
    fun ic820SinglePresetModeStillCannotAcquireCatTransmitLease() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC820, splitMode = true)
        fixture.service.connectRadios()
        assertTrue(fixture.service.state.value.splitMode)
        fixture.service.emergencyPttOff()
        assertFalse(tx.operations.contains("ptt:off"))
        val error = runCatching { fixture.service.beginTransmit(fixture.request(820L)) }.exceptionOrNull()
        assertTrue(error?.message?.contains("does not support CAT PTT") == true)
        assertFalse(tx.operations.contains("ptt:on"))
        fixture.close()
    }

    @Test fun ic820SinglePresetUsesOneConnectionAndBandAccessWithoutChangingRadioMode() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, rx, radioModel = RadioControlSettings.MODEL_ICOM_IC820, splitMode = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertEquals(1, fixture.service.state.value.physicalConnectionCount)
        assertTrue(fixture.service.state.value.isActive)
        assertFalse(rx.isConnected)
        assertTrue(tx.operations.any { it.startsWith("band:145") })
        assertTrue(tx.operations.any { it.startsWith("band:435") })
        assertTrue(tx.operations.none { it.startsWith("split:") || it.startsWith("vfo:") || it.startsWith("mode:") })
        fixture.close()
    }

    @Test fun legacySinglePresetTracksSelectedVfoAndFreezesWhilePhysicalPttIsOn() = runTest {
        val tx = FakeRadioController(pttReadbackSupported = true).also { it.frequencyHz = 435_100_000 }
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(), splitMode = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(tx.operations.any { it.startsWith("frequency:435") })
        assertTrue(tx.operations.none { it.startsWith("frequency:145") })
        tx.frequencyHz = 145_900_000
        advanceTimeBy(1_001); runCurrent()
        assertTrue(tx.operations.any { it.startsWith("frequency:145") })
        assertTrue(tx.operations.none { it.startsWith("split:") || it.startsWith("vfo:") || it.startsWith("mode:") })
        tx.physicalPtt = true
        val writes = tx.operations.count { it.startsWith("frequency:") }
        advanceTimeBy(1_001); runCurrent()
        assertEquals(writes, tx.operations.count { it.startsWith("frequency:") })
        fixture.close()
    }

    @Test fun ambiguousOrUnmatchedPresetVfoIsNeverRetuned() {
        assertEquals(true, presetHalfDuplexLeg(435_101_000, 435_100_000, 145_900_000))
        assertEquals(false, presetHalfDuplexLeg(145_901_000, 435_100_000, 145_900_000))
        assertNull(presetHalfDuplexLeg(7_100_000, 435_100_000, 145_900_000))
        assertNull(presetHalfDuplexLeg(145_900_000, 145_900_000, 145_900_000))
        assertEquals(true, presetHalfDuplexLeg(435_101_000, 435_100_000, null))
    }

    @Test fun failedIc820PresetWriteRestoresRxBandAccessWithoutSwitchingTransmitRoles() = runTest {
        val tx = FakeRadioController().also { it.frequencySucceeds = false }
        val result = tunePresetHalfDuplex(tx, true, 435_100_000, 145_900_000)
        assertFalse(result.success)
        assertEquals("band:435100000", tx.operations.last())
        assertTrue(tx.operations.none { it.startsWith("ptt:") || it.startsWith("vfo:") || it.startsWith("split:") })
    }

    @Test fun canceledIc820PresetWriteStillRestoresRxBandAccess() = runTest {
        val tx = FakeRadioController().also { it.beforeSetFrequency = { throw kotlinx.coroutines.CancellationException("canceled") } }
        val result = runCatching { tunePresetHalfDuplex(tx, true, 435_100_000, 145_900_000) }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("band:435100000", tx.operations.last())
    }

    @Test
    fun diagnosticsCaptureConnectionTrackingAndControlledFailureStages() = runTest {
        val diagnostics = ControlDiagnosticsBuffer(nowMillis = { testScheduler.currentTime })
        val fixture = Fixture(
            backgroundScope,
            FakeRadioController(setModeSucceeds = false),
            FakeRadioController(),
            diagnostics = diagnostics
        )

        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        val stages = diagnostics.events.value.map { it.stage }
        assertTrue("connect/start" in stages)
        assertTrue("connect/result" in stages)
        assertTrue("tracking/start" in stages)
        assertTrue("tracking/initialization" in stages)
        fixture.close()
    }
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

    @Test fun voxCanBeSavedAndUsedWithoutValidHiddenRadioConfiguration() = runTest {
        for (model in RadioControlSettings.SUPPORTED_RADIOS + "old-unknown-model") {
            val tx = FakeRadioController()
            val rx = FakeRadioController()
            val fixture = Fixture(backgroundScope, tx, rx, radioModel = model)
            val before = fixture.settings.radioControlSettings.value.copy(
                txRadioAddress = "invalid endpoint", rxRadioAddress = "invalid endpoint",
                civAddress = 999, baudRate = -1, l4sDopplerStepHz = -1, l4sCorrectionEditStepHz = 0)
            fixture.settings.radioControlSettings.value = before
            assertTrue(model, fixture.service.applyRadioControlSettings(before.copy(catTransport = RadioControlSettings.TRANSPORT_VOX)))
            assertEquals(before.copy(catTransport = RadioControlSettings.TRANSPORT_VOX), fixture.settings.radioControlSettings.value)
            fixture.service.connectRadios()
            assertNull(model, fixture.service.state.value.errorMessage)
            val lease = fixture.service.beginTransmit(fixture.request(120))
            assertTrue(lease.usesVox)
            fixture.service.confirmTransmitReady(lease)
            fixture.service.endTransmit(lease)
            assertTrue(model, tx.operations.isEmpty())
            assertTrue(model, rx.operations.isEmpty())
            fixture.close()
        }
    }

    @Test fun savingVoxDoesNotQueryDisconnectedL4sOrRequireItsMenuToClose() = runTest {
        val remote = remoteFixtureState()
        val tx = FakeRadioController(remote = remote)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true)
        fixture.service.connectRadios()
        runCurrent()
        remote.value = remote.value.copy(connected = false, transmitStateKnown = false, nativeMenuOpen = true)
        runCurrent()
        val commandCount = tx.operations.size
        val settings = fixture.settings.radioControlSettings.value.copy(catTransport = RadioControlSettings.TRANSPORT_VOX)
        assertTrue(fixture.service.applyRadioControlSettings(settings))
        assertEquals(settings, fixture.settings.radioControlSettings.value)
        assertTrue(tx.operations.drop(commandCount).all { it == "disconnect" })
        assertFalse(fixture.service.state.value.isActive)
        fixture.close()
    }

    @Test fun savingCatDoesNotRequireProfileStorageAckOrAStillConnectedRadio() = runTest {
        val remote = remoteFixtureState()
        val tx = FakeRadioController(remote = remote)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true)
        fixture.service.connectRadios()
        runCurrent()
        val correction = fixture.settings.radioControlSettings.value.copy(l4sCorrectionEditStepHz = 20)
        assertTrue(fixture.service.applyRadioControlSettings(correction))
        remote.value = remote.value.copy(connected = false, transmitStateKnown = false, nativeMenuOpen = true)
        runCurrent()
        val invalid = correction.copy(txRadioAddress = "missing USB device")
        assertTrue(fixture.service.applyRadioControlSettings(invalid))
        assertEquals(invalid, fixture.settings.radioControlSettings.value)
        fixture.close()
    }

    @Test fun savingCatQueriesOnlyAttachedControllersAndStillRejectsPhysicalTransmission() = runTest {
        val tx = FakeRadioController(pttReadbackSupported = true)
        val rx = FakeRadioController(pttReadbackSupported = true)
        val fixture = Fixture(backgroundScope, tx, rx)
        fixture.service.connectRadios()
        rx.isConnected = false
        val changed = fixture.settings.radioControlSettings.value.copy(baudRate = 9600)
        tx.physicalPtt = true
        assertFalse(fixture.service.applyRadioControlSettings(changed))
        tx.physicalPtt = false
        assertTrue(fixture.service.applyRadioControlSettings(changed))
        fixture.close()
    }

    @Test fun changingToVoxDoesNotInterruptAnActualTransmission() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val lease = fixture.service.beginTransmit(fixture.request(121))
        fixture.service.confirmTransmitReady(lease)
        val before = fixture.settings.radioControlSettings.value
        val commandCount = tx.operations.size
        assertFalse(fixture.service.applyRadioControlSettings(before.copy(catTransport = RadioControlSettings.TRANSPORT_VOX)))
        assertEquals(before, fixture.settings.radioControlSettings.value)
        assertEquals(commandCount, tx.operations.size)
        fixture.service.endTransmit(lease)
        fixture.close()
    }

    @Test
    fun voxLeaseDoesNotRequireRadioOrTrackingAndNeverSendsPtt() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(
            backgroundScope,
            tx,
            rx,
            transport = RadioControlSettings.TRANSPORT_VOX
        )

        val lease = fixture.service.beginTransmit(fixture.request(generation = 100L, automatic = true))
        assertTrue(lease.usesVox)
        fixture.service.confirmTransmitReady(lease)
        fixture.service.endTransmit(lease)

        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        assertTrue(tx.operations.isEmpty())
        assertTrue(rx.operations.isEmpty())
        fixture.close()
    }

    @Test
    fun leaseSetsMidpointFrequencyHoldsCatDuringWaveformAndAlwaysReleasesPtt() = runTest {
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
        assertTrue(tx.operations.indexOf("data:true:USB") < tx.operations.indexOf("ptt:on"))
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(txFrequencyCommands, tx.operations.count { it.startsWith("frequency:") })
        assertEquals(rxFrequencyCommands, rx.operations.count { it.startsWith("frequency:") })

        fixture.service.endTransmit(lease)
        assertEquals(PttState.OFF, fixture.service.state.value.pttState)
        assertTrue("ptt:on" in tx.operations)
        assertTrue("ptt:off" in tx.operations)
        assertTrue(tx.operations.lastIndexOf("ptt:off") < tx.operations.lastIndexOf("data:false:USB"))
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(tx.operations.count { it.startsWith("frequency:") } > txFrequencyCommands)
        assertTrue(rx.operations.count { it.startsWith("frequency:") } > rxFrequencyCommands)
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
    fun ft857HoldsBothLegsWhileKeyedAndAppliesLatestDopplerAfterPttOff() = runTest {
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
        assertEquals(rxWritesBeforeCycle, rx.operations.count { it.startsWith("frequency:") })
        assertTrue(fixture.satelliteRepo.requestedTimes.size > positionSamplesBeforeCycle)
        assertEquals(PttState.ON, fixture.service.state.value.pttState)

        fixture.service.endTransmit(lease)
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(tx.operations.count { it.startsWith("frequency:") } > txWritesWhileKeyed)
        assertTrue(rx.operations.count { it.startsWith("frequency:") } > rxWritesBeforeCycle)
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
        assertTrue("tx-data:true:USB" in radio.operations)
        val txUpdatesAfterPrepare = radio.operations.count { it.startsWith("tx-frequency:") }
        val rxUpdatesBeforePtt = radio.operations.count { it.startsWith("rx-frequency:") }
        fixture.service.confirmTransmitReady(lease)

        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(txUpdatesAfterPrepare, radio.operations.count { it.startsWith("tx-frequency:") })
        assertEquals(rxUpdatesBeforePtt, radio.operations.count { it.startsWith("rx-frequency:") })

        fixture.service.endTransmit(lease)
        assertTrue(radio.operations.indexOf("ptt:on") < radio.operations.lastIndexOf("ptt:off"))
        assertTrue(radio.operations.lastIndexOf("ptt:off") < radio.operations.lastIndexOf("tx-data:false:USB"))
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(radio.operations.count { it.startsWith("tx-frequency:") } > txUpdatesAfterPrepare)
        assertTrue(radio.operations.count { it.startsWith("rx-frequency:") } > rxUpdatesBeforePtt)
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
        assertTrue("data:false:USB" in tx.operations)
        fixture.close()
    }

    @Test
    fun jttyLongWaveUsesOwnWatchdogWithoutRelaxingFt4Limit() = runTest {
        val tx=FakeRadioController()
        val fixture=Fixture(backgroundScope,tx,FakeRadioController())
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val long=fixture.request(generation=91L).copy(waveformDurationMillis=30_208,maximumPttMillis=35_000)
        assertTrue(runCatching { fixture.service.beginTransmit(long) }.isFailure)
        val lease=fixture.service.beginTransmit(long.copy(mode=com.rtbishop.look4sat.core.domain.ft4.DigitalTransmitMode.JTTY))
        fixture.service.confirmTransmitReady(lease)
        advanceTimeBy(13_000);runCurrent()
        assertEquals(PttState.ON,fixture.service.state.value.pttState)
        advanceTimeBy(22_001);runCurrent()
        assertEquals(PttState.OFF,fixture.service.state.value.pttState)
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
        assertTrue("data:false:USB" in tx.operations)
        fixture.close()
    }

    @Test
    fun modeChangeDuringActiveLeaseIsRejectedWithoutStoppingTransmission() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val lease = fixture.service.beginTransmit(fixture.request(generation = 10L))
        fixture.service.confirmTransmitReady(lease)

        val offCount=tx.operations.count { it=="ptt:off" }
        val modeCount=tx.operations.count { it=="mode:USB" }

        fixture.service.setMode("USB", "USB")
        runCurrent()

        assertEquals(offCount,tx.operations.count { it=="ptt:off" })
        assertEquals(modeCount,tx.operations.count { it=="mode:USB" })
        assertEquals(PttState.ON, fixture.service.state.value.pttState)
        fixture.service.endTransmit(lease)
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
    fun rxDialTuningKeepsTxIndependentForBothInversionDirections() = runTest {
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
            fixture.settings.radioControlSettings.value =
                fixture.settings.radioControlSettings.value.copy(dialSettleMillis = 0L)
            fixture.service.connectRadios()
            fixture.startTracking()
            runCurrent()
            val initialRx = (if (inverted) 435_110_000L else 435_100_000L) + 1_250L
            assertEquals(initialRx, fixture.service.state.value.rxFrequencyHz)
            val receiver = if (split) tx else rx
            receiver.frequencyHz += 1_000L
            advanceTimeBy(3_100L)
            runCurrent()
            assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)
            assertEquals(initialRx + 1_000L, fixture.service.state.value.rxFrequencyHz)
            fixture.settings.setSatelliteOffset(12_345, "-2")
            advanceTimeBy(1_100L)
            runCurrent()
            assertEquals(initialRx + 1_000L - 3_250L, fixture.service.state.value.rxFrequencyHz)
            fixture.close()
        }
    }

    @Test
    fun dialFollowWaitsForSettleAndRejectsCrossBandReadback() = runTest {
        var now = 1_000_000L
        val tx = FakeRadioController()
        val fixture = Fixture(
            backgroundScope,
            tx,
            FakeRadioController(),
            nowProvider = { now },
            radioModel = RadioControlSettings.MODEL_ICOM_IC705,
            splitMode = true
        )
        fixture.settings.radioControlSettings.value = fixture.settings.radioControlSettings.value.copy(
            dialSettleMillis = 1_500L,
            linearDialDeadbandHz = 20L
        )
        fixture.satelliteRepo.positionAt = { fixture.position.copy(distanceRate = 0.0) }
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        tx.txFrequencyHz = 435_100_000L
        now += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)

        tx.txFrequencyHz = fixture.nominalTxHz + 1_000L
        now += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        now += 1_499L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)

        now += 1L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(fixture.nominalTxHz + 1_000L, fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test
    fun suspendedSplitReadCannotOverlapPreparationAndTrackingResumesAfterPtt() = runTest {
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
        assertEquals(operationCount, tx.operations.size)
        fixture.service.endTransmit(lease)
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(tx.operations.size > operationCount)
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
        duplexMode: String = RadioControlSettings.DUPLEX_MODE_SPLIT,
        transport: String = RadioControlSettings.TRANSPORT_USB,
        diagnostics: IControlDiagnostics = NoOpControlDiagnostics
    ) {
        val nominalTxHz = 145_900_000L
        val position = OrbitalPos(elevation = 0.5, distanceRate = 1.2, aboveHorizon = true)
        val settings = FakeSettingsRepo(radioModel, splitMode, duplexMode, transport)
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
            controllerFactory = { _, address -> if (address == "TX") tx else rx },
            diagnostics = diagnostics
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
    private val ctcssStepDelayMillis: Long = 0L,
    private val remote: MutableStateFlow<RemoteRadioState>? = null,
    private val pttReadbackSupported:Boolean=false
) : IRadioController {
    override val supportsPttReadback get()=pttReadbackSupported
    var physicalPtt:Boolean?=false
    override suspend fun readPttState()=physicalPtt
    override val remoteState get() = remote
    override val supportsAtomicPair get() = remote != null
    override suspend fun updateTrackingBases(bases: com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies):Boolean {
        if(remote?.value?.capabilities?.satelliteProfiles==true) {
            operations+="bases:${bases.rxHz}:${bases.txHz}"
            remote.value=remote.value.copy(nativeBaseFrequencies=bases)
        }
        return true
    }
    override suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?): FrequencyPairResult {
        operations += "pair:$rxHz:$txHz"
        if (remote?.value?.txSource != 0) return FrequencyPairResult.FAILED
        frequencyHz = rxHz; txFrequencyHz = txHz ?: 0
        remote.value = remote.value.copy(rxFrequencyHz = rxHz, txFrequencyHz = txHz, txEnabled = txHz != null)
        return FrequencyPairResult.APPLIED
    }
    override suspend fun startCw(text: String, wpm: Int): Boolean {
        operations += "cw:$text:$wpm"
        remote?.value = remote.value.copy(txSource = 2, cwState = CwTransmitState.SENDING)
        return true
    }
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
    var beforeConnect: (suspend () -> Unit)? = null

    override suspend fun connect(): Boolean {
        operations += "connect"
        beforeConnect?.invoke()
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

    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean {
        operations += "data:$enabled:$baseMode"
        return true
    }

    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean {
        operations += "tx-data:$enabled:$baseMode"
        return true
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
        remote?.value = remote.value.copy(rxMode = rxMode ?: "FM", txMode = txMode ?: "FM")
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
        if (pttOnSucceeds) remote?.value = remote.value.copy(txSource = 1)
        return pttOnSucceeds
    }

    override suspend fun pttOff(): Boolean {
        operations += "ptt:off"
        remote?.value = remote.value.copy(txSource = 0, cwState = CwTransmitState.CANCELLED)
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

    override suspend fun getSchedulePasses(
        catNums: Set<Int>,
        startTime: Long,
        hoursAhead: Int,
        minElevation: Double
    ): List<OrbitalPass> = emptyList()

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
    override suspend fun getSatelliteIdsWithModes(modes: List<String>) = emptyList<Int>()
    override suspend fun getSatelliteIdsWithModesAndUplink(modes: List<String>) = emptyList<Int>()
    override suspend fun getSatelliteIdsWithModesAndAmateur(modes: List<String>) = emptyList<Int>()
}

private class FakeSettingsRepo(
    radioModel: String = RadioControlSettings.MODEL_YAESU_FT817,
    splitMode: Boolean = false,
    duplexMode: String = RadioControlSettings.DUPLEX_MODE_SPLIT,
    transport: String = RadioControlSettings.TRANSPORT_USB
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
    override val rotatorSettings = MutableStateFlow(
        com.rtbishop.look4sat.core.domain.rotator.RotatorSettings()
    )
    override val otherSettings = MutableStateFlow(
        OtherSettings(
            stateOfAutoUpdate = false,
            stateOfSensors = false,
            stateOfSweep = false,
            stateOfUtc = false,
            stateOfLightTheme = false,
            stateOfNightMode = false,
            stateOfMapGrid = false,
            shouldSeeWarning = false,
            shouldSeeWhatsNew = false
        )
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
            duplexMode = duplexMode,
            catTransport = transport
        )
    )

    override fun setSelectedIds(ids: List<Int>) = Unit
    override fun setSelectedTypes(types: List<String>) = Unit
    override fun setPassesSettings(settings: PassesSettings) = Unit
    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double) = true
    override fun setStationPosition() = true
    override fun setStationPosition(locator: String) = true
    override fun getCurrentGrid(): String? = stationPosition.value.qthLocator
    override fun getSatelliteTypesIds(types: List<String>) = emptyList<Int>()
    override fun setSatelliteTypeIds(type: String, ids: List<Int>) = Unit
    override fun updateDatabaseState(state: DatabaseState) { databaseState.value = state }
    override fun updateRCSettings(settings: RCSettings) { rcSettings.value = settings }
    override fun updateRotatorSettings(
        settings: com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
    ) { rotatorSettings.value = settings }
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
    val savedBases = mutableMapOf<String,com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies>()
    var saveBasesSucceeds=true
    override fun getRadioBaseFrequencies(transponderUuid:String)=savedBases[transponderUuid]
    override fun setRadioBaseFrequencies(transponderUuid:String,bases:com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies):Boolean {
        if(!saveBasesSucceeds)return false
        savedBases[transponderUuid]=bases
        return true
    }
    override fun getSatelliteOffset(catnum: Int) = offsets[catnum].orEmpty()
    override fun setSatelliteOffset(catnum: Int, offset: String) { offsets[catnum] = offset }
    override fun getSatelliteMode(catnum: Int) = ""
    override fun setSatelliteMode(catnum: Int, mode: String) = Unit
    override fun getAmSatCallsign() = ""
    override fun setAmSatCallsign(callsign: String) = Unit
    override val wavelogUploadSettings = MutableStateFlow(WavelogUploadSettings())
    override fun updateWavelogUploadSettings(settings: WavelogUploadSettings) = Unit
    override fun getWavelogStations(): List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = emptyList()
    override fun setWavelogStations(stations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo>) = Unit
    override fun getWavelogSyncCursors(): Map<String, Long> = emptyMap()
    override fun setWavelogSyncCursors(cursors: Map<String, Long>) = Unit
    override fun getWavelogSyncUrl(): String = ""
    override fun setWavelogSyncUrl(url: String) = Unit
    override fun getLastWavelogSyncEpochMs(): Long = 0L
    override fun setLastWavelogSyncEpochMs(value: Long) = Unit
    override fun getLastWavelogFullSyncEpochMs(): Long = 0L
    override fun setLastWavelogFullSyncEpochMs(value: Long) = Unit
    override fun getWorkedGrids() = emptySet<String>()
    override fun setWorkedGrids(grids: Set<String>) = Unit
    override fun getWorkedGridQsos() = emptyMap<String, List<GridQso>>()
    override fun setWorkedGridQsos(qsos: Map<String, List<GridQso>>) = Unit
    override fun getRoamedGrids() = emptySet<String>()
    override fun setRoamedGrids(grids: Set<String>) = Unit
    private var vuccStationIds = emptySet<String>()
    override fun getVuccSelectedStationIds() = vuccStationIds
    override fun setVuccSelectedStationIds(ids: Set<String>) { vuccStationIds = ids }
    override val lotwSettings = MutableStateFlow(LoTWSettings())
    override fun updateLoTWSettings(settings: LoTWSettings) = Unit
    override fun getLastLotwSyncDate() = ""
    override fun setLastLotwSyncDate(date: String) = Unit
    override fun getLastLotwSyncCallsign() = ""
    override fun setLastLotwSyncCallsign(callsign: String) = Unit
    override fun getMarkedGridStations() =
        emptyMap<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>()
    override fun setMarkedGridStations(
        stations: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>
    ) = Unit
}
