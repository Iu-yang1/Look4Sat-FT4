package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.data.ft4.Ft4AudioOutputFactory
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.ft4.*
import com.rtbishop.look4sat.core.domain.maintenance.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.*
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.PI
import kotlin.math.sin

internal class RadioSelfCheckService(
    private val scope: CoroutineScope,
    private val settings: ISettingsRepo,
    private val radio: IRadioTrackingService,
    private val hub: IAudioHub,
    private val session: IRadioAudioSession,
    private val coordinator: IDigitalTransmitCoordinator,
    private val outputFactory: Ft4AudioOutputFactory,
    private val clock: IDisciplinedClock,
    private val diagnostics: IControlDiagnostics,
    private val n76: Flow<TimestampedAudioChunk>,
    private val hasPermission: () -> Boolean
) : IRadioSelfCheckService {
    private val mutable = MutableStateFlow(SelfCheckState())
    override val state = mutable.asStateFlow()
    private var checkJob: Job? = null
    private var testJob: Job? = null

    init {
        scope.launch {
            combine(settings.radioControlSettings, hub.inputDevices) { config, devices -> config to devices }
                .distinctUntilChanged().drop(1).collect {
                    checkJob?.cancelAndJoin()
                    stopTransmit()
                    mutable.update { it.copy(stale = it.checkedAtMillis != 0L) }
                }
        }
        scope.launch {
            radio.state.map { it.rxConnected to it.txConnected }.distinctUntilChanged().drop(1).collect {
                mutable.update { it.copy(stale = it.checkedAtMillis != 0L) }
                if (!it.first && !it.second) stopTransmit()
            }
        }
    }

    private fun result(item: CheckItem, status: CheckStatus, detail: String = "", action: CheckAction = CheckAction.NONE) {
        mutable.update { old -> old.copy(results = old.results.map { if (it.item == item) CheckResult(item, status, detail, action) else it }) }
        diagnostics.record(ControlDiagnosticSource.RADIO, "selfcheck/${item.name.lowercase()}", "$status $detail")
    }

    @Synchronized override fun start() {
        if (checkJob?.isActive == true || testJob?.isActive == true || mutable.value.stopRequired) return
        checkJob = scope.launch {
            mutable.value = SelfCheckState(running = true)
            val config = settings.radioControlSettings.value
            try {
                result(CheckItem.CAT, CheckStatus.RUNNING)
                try {
                    val probe = withTimeout(10_000) { radio.probe() }
                    result(CheckItem.CAT, if (probe.connected && probe.frequencyHz != null) CheckStatus.PASSED else CheckStatus.ATTENTION,
                        action = if (probe.connected) CheckAction.NONE else CheckAction.CONNECT)
                    result(CheckItem.FREQUENCY, if (probe.frequencyHz != null) CheckStatus.PASSED else CheckStatus.NOT_CHECKED,
                        listOfNotNull(probe.frequencyHz?.toString(), probe.mode).joinToString(" Hz "))
                    result(CheckItem.PTT, when {
                        !probe.connected -> CheckStatus.NOT_CHECKED
                        !probe.pttSupported -> CheckStatus.UNSUPPORTED
                        probe.transmitting == null -> CheckStatus.FAILED
                        probe.transmitting == true -> CheckStatus.ATTENTION
                        else -> CheckStatus.PASSED
                    })
                } catch (timeout: TimeoutCancellationException) {
                    result(CheckItem.CAT, CheckStatus.FAILED, timeout.message.orEmpty(), CheckAction.CONNECT)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    result(CheckItem.CAT, CheckStatus.FAILED, error.message.orEmpty(), CheckAction.CONNECT)
                }
                val audio = config.radioAudio
                val outputAvailable = when (audio.backend) {
                    RadioAudioBackend.UNCONFIGURED -> false
                    RadioAudioBackend.ANDROID_DEVICE -> session.devices().any { !it.input && it.key == audio.txDeviceKey }
                    RadioAudioBackend.ICOM_WLAN -> session.state.value.transmittingReady
                }
                // Configuration inspection is deliberately not a successful playback test.
                result(CheckItem.OUTPUT, if (outputAvailable) CheckStatus.ATTENTION else CheckStatus.NOT_CHECKED,
                    audio.txDeviceKey.ifBlank { session.state.value.route }, CheckAction.AUDIO)
                val time = clock.snapshot()
                result(CheckItem.CLOCK, if (time.healthy) CheckStatus.PASSED else CheckStatus.ATTENTION,
                    "${time.source} ±${time.uncertaintyMillis.toLong()} ms", CheckAction.CLOCK)
                result(CheckItem.CALIBRATION, if (session.state.value.endToEndTimingVerified) CheckStatus.PASSED else CheckStatus.NOT_CHECKED)
                receive(config)
                check(config == settings.radioControlSettings.value) { "Configuration changed" }
                mutable.update { it.copy(checkedAtMillis = System.currentTimeMillis(), stale = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            finally {
                mutable.update { old -> old.copy(running = false, results = old.results.map {
                    if (it.status == CheckStatus.RUNNING) it.copy(status = CheckStatus.NOT_CHECKED) else it
                }) }
            }
        }
    }

    private suspend fun receive(config: RadioControlSettings) {
        if (radio.state.value.pttState != PttState.OFF || radio.state.value.catTransmitting) {
            result(CheckItem.RECEIVE, CheckStatus.NOT_CHECKED)
            return
        }
        val n76Selected = config.radioModel == RadioControlSettings.MODEL_VERO_N76 && config.n76BluetoothAudio
        if (!n76Selected && config.radioAudio.backend == RadioAudioBackend.UNCONFIGURED) {
            result(CheckItem.RECEIVE, CheckStatus.NOT_CHECKED, action = CheckAction.AUDIO)
            return
        }
        if (!n76Selected && config.radioAudio.backend != RadioAudioBackend.ICOM_WLAN && !hasPermission()) {
            result(CheckItem.RECEIVE, CheckStatus.ATTENTION, action = CheckAction.PERMISSION)
            return
        }
        val current = hub.state.value as? AudioHubState.Capturing
        if (current != null && config.radioAudio.backend == RadioAudioBackend.ANDROID_DEVICE &&
            current.deviceName.isNotBlank() && hub.inputDevices.value.none {
                it.key == config.radioAudio.rxDeviceKey && it.name == current.deviceName
            }) {
            result(CheckItem.RECEIVE, CheckStatus.ATTENTION, action = CheckAction.AUDIO)
            return
        }
        result(CheckItem.RECEIVE, CheckStatus.RUNNING)
        val meter = ReceiveMeter()
        try {
            val stream = if (n76Selected) n76 else hub.audioFlow(AudioConsumer.SELF_CHECK)
            var lastPublish = 0L
            withTimeoutOrNull(5000) {
                stream.collect { chunk ->
                    meter.add(chunk)
                    val capture = hub.state.value as? AudioHubState.Capturing
                    check(capture?.systemSilenced != true) { "Audio capture is silenced" }
                    if (config.radioAudio.backend == RadioAudioBackend.ANDROID_DEVICE && capture != null) {
                        check(hub.inputDevices.value.any {
                            it.key == config.radioAudio.rxDeviceKey && it.name == capture.deviceName
                        }) { "Receive route does not match the selected device" }
                    }
                    val now = System.nanoTime()
                    if (now - lastPublish >= 100_000_000) {
                        mutable.update { it.copy(receive = meter.measurement(if (n76Selected) "N76" else capture?.deviceName ?: session.state.value.route)) }
                        lastPublish = now
                    }
                }
            }
            val measure = meter.measurement(mutable.value.receive.source)
            mutable.update { it.copy(receive = measure) }
            result(CheckItem.RECEIVE, when {
                measure.samples == 0L -> CheckStatus.FAILED
                measure.gaps != 0L || measure.clippedSamples != 0L || measure.peakDbfs < -90 -> CheckStatus.ATTENTION
                else -> CheckStatus.PASSED
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { result(CheckItem.RECEIVE, CheckStatus.FAILED, error.message.orEmpty(), CheckAction.AUDIO) }
    }

    @Synchronized override fun testTransmit(withAudio: Boolean) {
        if (testJob?.isActive == true || checkJob?.isActive == true || mutable.value.stopRequired) return
        val config = settings.radioControlSettings.value
        testJob = scope.launch {
            var lease: TxLease? = null
            var output: com.rtbishop.look4sat.core.data.ft4.Ft4AudioOutput? = null
            mutable.update { it.copy(preparing = true, error = null, testResult = CheckStatus.RUNNING) }
            try {
                val waveform = testTone()
                check(config == settings.radioControlSettings.value) { "Configuration changed" }
                lease = coordinator.beginTransmit(TxRequest(
                    System.nanoTime(), clock.nowMillis(), 1000, radio.state.value.currentPass?.catNum ?: 0,
                    radio.state.value.selectedTransponder?.uuid.orEmpty(), false, 5000,
                    DigitalTransmitMode.DIAGNOSTIC, diagnosticAudio = withAudio
                ))
                mutable.update { it.copy(testFrequencyHz = lease.effectiveTxFrequencyHz) }
                if (withAudio) {
                    check(config.radioAudio.backend != RadioAudioBackend.UNCONFIGURED) { "Select a radio audio output" }
                    output = outputFactory.create(12000, 1f)
                    withTimeout(10_000) {
                        output.prepareTransmit(false)
                        output.prepare(waveform)
                    }
                }
                withTimeout(5000) {
                    mutable.update { it.copy(stopRequired = true) }
                    coordinator.confirmTransmitReady(lease)
                    mutable.update { it.copy(preparing = false, transmitting = true) }
                    val during = radio.probe()
                    check(!during.pttSupported || during.transmitting == true) { "PTT ON readback did not match" }
                    if (withAudio) {
                        check(checkNotNull(output).play(waveform).underrunCount == 0) { "Transmit audio underrun" }
                    } else delay(1000)
                }
                coordinator.endTransmit(lease)
                lease = null
                val after = radio.probe()
                check(radio.state.value.pttState == PttState.OFF &&
                    (!after.pttSupported || after.transmitting == false)) { "PTT OFF was not confirmed" }
                mutable.update { it.copy(stopRequired = false) }
                result(CheckItem.PTT, if (after.pttSupported) CheckStatus.PASSED else CheckStatus.UNSUPPORTED)
                if (withAudio) result(CheckItem.OUTPUT, CheckStatus.PASSED)
                mutable.update { it.copy(testResult = CheckStatus.PASSED) }
            } catch (timeout: TimeoutCancellationException) {
                mutable.update { it.copy(testResult = CheckStatus.FAILED, error = timeout.message) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(testResult = CheckStatus.NOT_CHECKED) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(testResult = CheckStatus.FAILED, error = error.message) }
            } finally {
                withContext(NonCancellable) {
                    try {
                        try { output?.close() } finally { lease?.let { coordinator.endTransmit(it) } }
                    } catch (error: Exception) {
                        mutable.update { it.copy(error = error.message, testResult = CheckStatus.FAILED) }
                    } finally {
                        mutable.update { it.copy(transmitting = false, preparing = false,
                            stopRequired = it.stopRequired && radio.state.value.pttState != PttState.OFF) }
                    }
                }
                diagnostics.record(ControlDiagnosticSource.RADIO, "selfcheck/transmit", mutable.value.testResult.name)
            }
        }
    }

    override suspend fun stopTransmit() {
        val job = testJob
        job?.cancel()
        if (mutable.value.stopRequired) coordinator.emergencyPttOff()
        job?.join()
        mutable.update { it.copy(stopRequired = it.stopRequired && radio.state.value.pttState != PttState.OFF) }
    }

    override suspend fun refreshTransmitPreview() {
        if (testJob?.isActive == true) return
        try {
            val probe = withTimeout(10_000) { radio.probe() }
            mutable.update { it.copy(testFrequencyHz = probe.txFrequencyHz, testMode = probe.txMode) }
        } catch (timeout: TimeoutCancellationException) {
            mutable.update { it.copy(error = timeout.message) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
    }

    override suspend fun cancel() {
        checkJob?.cancelAndJoin()
        stopTransmit()
    }
}

internal fun testTone(): FloatArray = FloatArray(12000) { index ->
    val ramp = minOf(index / 120.0, (11999 - index) / 120.0, 1.0).coerceAtLeast(0.0)
    (0.1 * ramp * sin(2 * PI * 1000 * index / 12000)).toFloat()
}
