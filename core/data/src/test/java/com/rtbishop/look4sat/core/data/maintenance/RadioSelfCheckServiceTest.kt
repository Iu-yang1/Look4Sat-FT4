package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.data.ft4.*
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.ft4.*
import com.rtbishop.look4sat.core.domain.maintenance.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.*
import com.rtbishop.look4sat.core.domain.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class RadioSelfCheckServiceTest {
    private inline fun <reified T> stub(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T

    private class Hub : IAudioHub {
        override val state = MutableStateFlow<AudioHubState>(AudioHubState.Idle)
        override val inputDevices = MutableStateFlow(emptyList<AudioInputDevice>())
        val samples = MutableSharedFlow<TimestampedAudioChunk>()
        var subscribers = 0
        override fun audioFlow(consumer: AudioConsumer, delivery: AudioDelivery) = flow {
            assertEquals(AudioConsumer.SELF_CHECK, consumer)
            subscribers++
            try { emitAll(samples) } finally { subscribers-- }
        }
        override suspend fun selectInputDevice(deviceKey: String?) { error("Must not change receive source") }
        override suspend fun stopAll() { error("Must not stop other consumers") }
    }

    private inner class Fixture(scope: CoroutineScope) {
        val hub = Hub()
        val settings = MutableStateFlow(RadioControlSettings(true, RadioControlSettings.MODEL_ICOM_IC705,
            "", "", "", "", 19200).copy(radioAudio = RadioAudioSettings(backend = RadioAudioBackend.ICOM_WLAN)))
        val radioState = MutableStateFlow(RadioTrackingState())
        var permission = true
        var begins = 0
        var ends = 0
        var outputs = 0
        var closed = 0
        var rejectLease = false
        var offFails = false
        var longPlayback = false
        val radio = stub<IRadioTrackingService> { method ->
            when (method) {
                "getState" -> radioState
                "probe" -> RadioProbe(true, 435_100_000, "USB", true,
                    radioState.value.pttState == PttState.ON, txFrequencyHz = 145_900_000, txMode = "USB")
                else -> error("Read-only check issued an unexpected command: $method")
            }
        }
        val coordinator = object : IDigitalTransmitCoordinator {
            override suspend fun beginTransmit(request: TxRequest): TxLease {
                begins++
                check(!rejectLease) { "Another transmit lease is active" }
                assertEquals(DigitalTransmitMode.DIAGNOSTIC, request.mode)
                return TxLease(1, 1, 145_900_000, 0, 0, 500, 1000, 5000, 0, "", 0, false, diagnostic = true)
            }
            override suspend fun confirmTransmitReady(lease: TxLease) {
                radioState.value = radioState.value.copy(pttState = PttState.ON)
            }
            override suspend fun endTransmit(lease: TxLease) {
                ends++
                radioState.value = radioState.value.copy(pttState = if (offFails) PttState.ERROR else PttState.OFF)
            }
            override suspend fun emergencyPttOff() {
                radioState.value = radioState.value.copy(pttState = if (offFails) PttState.ERROR else PttState.OFF)
            }
        }
        val service = RadioSelfCheckService(scope,
            stub<ISettingsRepo> { if (it == "getRadioControlSettings") settings else error(it) },
            radio, hub,
            stub<IRadioAudioSession> {
                when (it) {
                    "getState" -> MutableStateFlow(RadioAudioState(transmittingReady = true, route = "WLAN"))
                    "devices" -> emptyList<RadioAudioDevice>()
                    else -> error("Unexpected audio connection operation: $it")
                }
            }, coordinator, Ft4AudioOutputFactory { _, _ ->
                outputs++
                object : Ft4AudioOutput {
                    override suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult {
                        delay(if (longPlayback) 20_000 else 1000)
                        return Ft4AudioPlaybackResult(0.0, 0)
                    }
                    override fun close() { closed++ }
                }
            },
            stub<IDisciplinedClock> {
                when (it) {
                    "nowMillis" -> 1_000L
                    "snapshot" -> ClockSnapshot(1000, 1000, 0.0, 0.0, 10.0, ClockSource.NTP, 0, true)
                    else -> error(it)
                }
            }, NoOpControlDiagnostics, emptyFlow(), { permission })
    }

    @Test fun ordinaryCheckSharesReceiveWithoutCreatingTransmitOutputOrChangingCat() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.service.start(); runCurrent()
        assertEquals(1, fixture.hub.subscribers)
        fixture.hub.samples.emit(TimestampedAudioChunk(FloatArray(1200) { .1f },
            12000, 0, 0, AudioSampleFormat.PCM_FLOAT))
        advanceTimeBy(5001); runCurrent()
        val state = fixture.service.state.value
        assertEquals(CheckStatus.PASSED, state.results.single { it.item == CheckItem.RECEIVE }.status)
        assertEquals(CheckStatus.ATTENTION, state.results.single { it.item == CheckItem.OUTPUT }.status)
        assertEquals(0, fixture.outputs)
        assertEquals(0, fixture.begins)
        assertEquals(0, fixture.hub.subscribers)
    }

    @Test fun noConfiguredSourceAndDeniedPermissionNeverFallBackToMicrophone() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.settings.value = fixture.settings.value.copy(radioAudio = RadioAudioSettings())
        runCurrent(); fixture.service.start(); runCurrent()
        assertEquals(CheckStatus.NOT_CHECKED, fixture.service.state.value.results.single { it.item == CheckItem.RECEIVE }.status)
        fixture.settings.value = fixture.settings.value.copy(radioAudio = RadioAudioSettings(
            backend = RadioAudioBackend.ANDROID_DEVICE, rxDeviceKey = "USB"))
        fixture.permission = false
        runCurrent(); fixture.service.start(); runCurrent()
        val receive = fixture.service.state.value.results.single { it.item == CheckItem.RECEIVE }
        assertEquals(CheckAction.PERMISSION, receive.action)
        assertEquals(0, fixture.hub.subscribers)
    }

    @Test fun competingTransmissionIsRejectedBeforePreparingAudio() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.rejectLease = true
        runCurrent(); fixture.service.testTransmit(true); runCurrent()
        assertEquals(CheckStatus.FAILED, fixture.service.state.value.testResult)
        assertEquals(0, fixture.outputs)
        assertEquals(0, fixture.ends)
    }

    @Test fun cancellationStopsToneReleasesLeaseAndClosesOutput() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.longPlayback = true
        runCurrent(); fixture.service.testTransmit(true); runCurrent()
        assertTrue(fixture.service.state.value.transmitting)
        fixture.service.cancel(); runCurrent()
        assertEquals(1, fixture.ends)
        assertEquals(1, fixture.closed)
        assertFalse(fixture.service.state.value.stopRequired)
        assertEquals(PttState.OFF, fixture.radioState.value.pttState)
    }

    @Test fun failedOffRetainsStopActionAndCannotBeHiddenByRestartingCheck() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.offFails = true
        runCurrent(); fixture.service.testTransmit(false); runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertEquals(CheckStatus.FAILED, fixture.service.state.value.testResult)
        assertTrue(fixture.service.state.value.stopRequired)
        fixture.service.start(); fixture.service.testTransmit(false); runCurrent()
        assertTrue(fixture.service.state.value.stopRequired)
        assertEquals(1, fixture.begins)
        fixture.offFails = false
        fixture.service.stopTransmit()
        assertFalse(fixture.service.state.value.stopRequired)
    }

    @Test fun configChangeCancelsReceiveAndMarksPreviousResultsStale() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent(); fixture.service.start(); runCurrent()
        advanceTimeBy(5001); runCurrent()
        assertFalse(fixture.service.state.value.running)
        fixture.settings.value = fixture.settings.value.copy(baudRate = 9600)
        runCurrent()
        assertTrue(fixture.service.state.value.stale)
        assertEquals(0, fixture.hub.subscribers)
    }
}
