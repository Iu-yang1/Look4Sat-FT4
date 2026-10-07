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
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.util.Log
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.model.parseRadioTcpEndpoint
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.ft4.IFt4TransmitCoordinator
import com.rtbishop.look4sat.core.domain.ft4.TxLease
import com.rtbishop.look4sat.core.domain.ft4.TxRequest
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.TrackingPhase
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.domain.utility.DialFollowPolicy
import com.rtbishop.look4sat.core.domain.utility.DialFollowState
import com.rtbishop.look4sat.core.domain.utility.DialLeg
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RadioTrackingService(
    private val appScope: CoroutineScope,
    private val bluetoothManager: BluetoothManager?,
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private val clock: IDisciplinedClock,
    private val controllerFactory: ((Boolean, String) -> IRadioController)? = null,
    private val transportFactory: ((String, String, Int, Int) -> RadioTransport)? = null,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : IRadioTrackingService, IFt4TransmitCoordinator {

    private val tag = "RadioTracking"
    private val _state = MutableStateFlow(RadioTrackingState())
    override val state: StateFlow<RadioTrackingState> = _state

    private var txController: IRadioController? = null
    private var rxController: IRadioController? = null
    private var trackingJob: Job? = null
    private val connectionMutex = Mutex()
    private val transmitMutex = Mutex()
    private val tuningRevision = AtomicLong()
    private val pendingControls = AtomicInteger()
    @Volatile private var activeTxLease: TxLease? = null
    @Volatile private var transmitPreparing = false
    private var pttWatchdogJob: Job? = null
    private var dataModeController: IRadioController? = null
    private var dataModeBaseMode: String? = null
    private var dataModeUsesSplit = false
    private var nextLeaseId = 1L
    private val commandLatencyEstimator = RadioCommandLatencyEstimator()
    private val commandActor = RadioCommandActor(appScope) { busy, failure ->
        _state.update { current ->
            current.copy(
                commandBusy = busy,
                lastCommandError = failure ?: current.lastCommandError,
                errorMessage = failure ?: current.errorMessage
            )
        }
    }
    private val hasTransmitClaim: Boolean
        get() = transmitPreparing || activeTxLease != null

    // ── Connection ──────────────────────────────────────────────────────────

    override suspend fun connectRadios(): Unit = connectionMutex.withLock {
        cancelTrackingAndJoin()
        emergencyPttOff()
        txController?.disconnect()
        rxController?.disconnect()
        txController = null
        rxController = null

        val rcSettings = settingsRepo.radioControlSettings.value
        val txAddr     = rcSettings.txRadioAddress
        val rxAddr     = rcSettings.rxRadioAddress
        val profile    = radioProfile(rcSettings.radioModel, rcSettings.civAddress)
        val isIcom     = profile.isIcom
        val isSplit    = isIcom && rcSettings.splitMode
        val usesHamlib = rcSettings.catTransport == RadioControlSettings.TRANSPORT_TCP &&
            rcSettings.tcpProtocol == RadioControlSettings.TCP_PROTOCOL_HAMLIB
        val isSatelliteMode = isSplit && profile.supportsSatelliteMode &&
            rcSettings.duplexMode == RadioControlSettings.DUPLEX_MODE_SATELLITE &&
            !usesHamlib
        diagnose(
            stage = "connect/start",
            message = "model=${profile.model} transport=${rcSettings.catTransport} " +
                "split=$isSplit satellite=$isSatelliteMode"
        )

        Log.i(tag, "connectRadios model=${rcSettings.radioModel} split=$isSplit TX=$txAddr RX=$rxAddr")
        _state.update {
            it.copy(
                splitMode = isSplit,
                satelliteMode = isSatelliteMode,
                txConnected = false,
                rxConnected = false,
                physicalConnectionCount = 0,
                pttState = PttState.OFF,
                lastCommandError = null
            )
        }

        if (rcSettings.catTransport == RadioControlSettings.TRANSPORT_VOX) {
            Log.i(tag, "VOX transport selected; CAT radios are not connected")
            diagnose("connect/vox", "CAT bypassed for VOX")
            return
        }

        if (rcSettings.catTransport == RadioControlSettings.TRANSPORT_TCP) {
            val invalidEndpoint = when {
                txAddr.isNotBlank() && parseRadioTcpEndpoint(txAddr) == null -> "TX" to txAddr
                !isSplit && rxAddr.isNotBlank() && parseRadioTcpEndpoint(rxAddr) == null -> "RX" to rxAddr
                else -> null
            }
            if (invalidEndpoint != null) {
                diagnose(
                    "connect/validation",
                    "invalid ${invalidEndpoint.first} TCP endpoint",
                    ControlDiagnosticSeverity.ERROR
                )
                _state.update {
                    it.copy(
                        errorMessage = "Invalid ${invalidEndpoint.first} TCP endpoint " +
                            "(${invalidEndpoint.second}). Use host:port or [IPv6]:port"
                    )
                }
                return
            }
        }

        if (isSplit) {
            // Single-radio split mode: only TX slot is used
            if (txAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio address configured in Settings") }
                return
            }
            val tx = makeController(profile, txAddr)
            txController = tx
            rxController = null
            _state.update { it.copy(errorMessage = null) }
            val txOk = tx.connect()
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = txOk,
                    physicalConnectionCount = if (txOk) 1 else 0,
                    errorMessage = if (!txOk) "Could not connect to radio ($txAddr)" else null
                )
            }
            Log.i(tag, "Icom single-radio mode connected: txOk=$txOk")
        } else {
            if (txAddr.isBlank() && rxAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio addresses configured in Settings") }
                return
            }
            val tx = makeController(profile, txAddr)
            val rx = makeController(profile, rxAddr)
            txController = tx
            rxController = rx
            _state.update { it.copy(errorMessage = null) }
            val txOk = if (txAddr.isNotBlank()) tx.connect() else false
            val rxOk = if (rxAddr.isNotBlank()) rx.connect() else false
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = rxOk,
                    physicalConnectionCount = listOf(txOk, rxOk).count { connected -> connected },
                    errorMessage = when {
                        !txOk && !rxOk -> "Could not connect to TX and RX radios"
                        !txOk          -> "Could not connect to TX radio ($txAddr)"
                        !rxOk          -> "Could not connect to RX radio ($rxAddr)"
                        else           -> null
                    }
                )
            }
            Log.i(tag, "Dual-radio connected: txOk=$txOk rxOk=$rxOk")
        }
        diagnose(
            "connect/result",
            "tx=${_state.value.txConnected} rx=${_state.value.rxConnected} " +
                "physical=${_state.value.physicalConnectionCount}",
            if (_state.value.txConnected || _state.value.rxConnected) {
                ControlDiagnosticSeverity.INFO
            } else {
                ControlDiagnosticSeverity.ERROR
            }
        )
    }

    private fun makeController(profile: RadioProfile, address: String): IRadioController {
        val settings = settingsRepo.radioControlSettings.value
        val usesHamlib = settings.catTransport == RadioControlSettings.TRANSPORT_TCP &&
            settings.tcpProtocol == RadioControlSettings.TCP_PROTOCOL_HAMLIB
        val dedicatedSatelliteMode = !usesHamlib && settings.splitMode && profile.supportsSatelliteMode &&
            settings.duplexMode == RadioControlSettings.DUPLEX_MODE_SATELLITE
        val injected = controllerFactory?.invoke(profile.isIcom, address)
        val transport = if (injected == null) {
            transportFactory?.invoke(
                settings.catTransport,
                address,
                settings.baudRate,
                profile.serialStopBits
            )
        } else null
        val controller = injected ?: if (usesHamlib) {
            HamlibRigctldController(
                endpoint = address,
                transport = checkNotNull(transport)
            )
        } else if (profile.isIcom) {
            if (transport == null) {
                Ic705Controller(
                    bluetoothManager = checkNotNull(bluetoothManager),
                    deviceAddress = address,
                    civAddress = checkNotNull(profile.civAddress),
                    variant = checkNotNull(profile.icomVariant),
                    dedicatedSatelliteMode = dedicatedSatelliteMode
                )
            } else {
                Ic705Controller(
                    bluetoothManager = null,
                    deviceAddress = address,
                    civAddress = checkNotNull(profile.civAddress),
                    transport = transport,
                    variant = checkNotNull(profile.icomVariant),
                    dedicatedSatelliteMode = dedicatedSatelliteMode
                )
            }
        } else {
            if (transport == null) {
                Ft817Controller(
                    bluetoothManager = checkNotNull(bluetoothManager),
                    deviceAddress = address,
                    variant = checkNotNull(profile.yaesuVariant)
                )
            } else {
                Ft817Controller(
                    bluetoothManager = null,
                    deviceAddress = address,
                    transport = transport,
                    variant = checkNotNull(profile.yaesuVariant)
                )
            }
        }
        return SerialRadioController(controller, commandActor)
    }

    override suspend fun disconnectRadios(): Unit = connectionMutex.withLock {
        diagnose("disconnect/start", "disconnect requested")
        cancelTrackingAndJoin()
        emergencyPttOff()
        txController?.disconnect()
        rxController?.disconnect()
        txController = null
        rxController = null
        _state.update {
            it.copy(
                txConnected = false,
                rxConnected = false,
                isActive = false,
                trackingPhase = TrackingPhase.IDLE,
                pttState = PttState.OFF,
                commandBusy = false,
                txLeaseId = null,
                satelliteMode = false,
                physicalConnectionCount = 0
            )
        }
        diagnose("disconnect/result", "radio controllers disconnected")
    }

    // ── FT4 transmit lease ──────────────────────────────────────────────────

    override suspend fun beginTransmit(request: TxRequest): TxLease = transmitMutex.withLock {
        val startedNanos = System.nanoTime()
        require(request.waveformDurationMillis in 1L..MAX_WAVEFORM_MILLIS) {
            "Invalid FT4 waveform duration"
        }
        require(request.maximumPttMillis >= request.waveformDurationMillis) {
            "PTT watchdog is shorter than the waveform"
        }
        check(activeTxLease == null) { "Another transmit lease is active" }
        if (settingsRepo.radioControlSettings.value.catTransport == RadioControlSettings.TRANSPORT_VOX) {
            return@withLock beginVoxTransmit(request)
        }
        check(pendingControls.get() == 0) { "Radio settings are changing" }
        val safetyGeneration = txController?.pttSafetyGeneration()
        transmitPreparing = true
        updateCommandState(busy = true)
        try {
            val current = _state.value
            val pass = checkNotNull(current.currentPass) { "No satellite pass is selected" }
            val transponder = checkNotNull(current.selectedTransponder) { "No transponder is selected" }
            check(current.isActive && current.trackingPhase == TrackingPhase.READY) { "Satellite tracking is not ready" }
            check(current.pttState == PttState.OFF) { "PTT OFF has not been confirmed" }
            val txMode = current.txMode
            val rxMode = current.rxMode
            check(txMode in setOf("USB", "LSB") && rxMode in setOf("USB", "LSB")) {
                "FT4 requires confirmed USB/LSB modes"
            }
            check(TransponderMapper.mapUplinkModeToDownlinkMode(checkNotNull(txMode), transponder.isInverted) == rxMode) {
                "FT4 sidebands do not match transponder inversion"
            }
            if (request.automatic) {
                check(current.trackingPhase == TrackingPhase.READY) { "Satellite tracking is not ready" }
                validateAutomaticWindow(request, pass)
            }
            check(pass.catNum == request.expectedSatelliteCatalogNumber) { "Satellite context changed" }
            check(transponder.uuid == request.expectedTransponderUuid) { "Transponder context changed" }
            val midpoint = request.waveformStartUtcMillis + request.waveformDurationMillis / 2L
            val nominal = current.txBaseFrequencyHz ?: transponder.uplinkCenterFrequency()
            checkNotNull(nominal) { "The selected transponder has no uplink frequency" }
            val position = satelliteRepo.getPosition(
                pass.orbitalObject,
                settingsRepo.stationPosition.value,
                midpoint
            )
            if (request.automatic) validateOrbitPosition(position)
            val requestedFrequency = position.getUplinkFreq(nominal)
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            check(controller.isConnected) { "TX radio is not connected" }

            val split = current.splitMode
            val frequencySet = if (split) {
                controller.setTxVfoFrequency(requestedFrequency)
            } else {
                controller.setFrequency(requestedFrequency)
            }
            check(frequencySet) { "TX frequency command was not acknowledged" }
            val readbackFrequency = if (split) {
                check(controller.setSplitModes(rxMode, txMode)) { "Duplex mode readback did not match" }
                controller.readTxVfoFrequency()
            } else {
                check(controller.setMode(checkNotNull(txMode))) { "TX mode command was not acknowledged" }
                val readback = controller.readFrequencyAndMode()
                check(readback?.second == txMode) { "TX mode readback did not match" }
                rxController?.takeIf { it.isConnected }?.let { receiver ->
                    check(receiver.setMode(checkNotNull(rxMode))) { "RX mode command was not acknowledged" }
                    check(receiver.readFrequencyAndMode()?.second == rxMode) { "RX mode readback did not match" }
                }
                readback.first
            }
            val effectiveFrequency = checkNotNull(readbackFrequency) { "TX frequency readback was unavailable" }
            // Legacy Yaesu CAT uses 10 Hz frequency resolution.
            check(kotlin.math.abs(effectiveFrequency - requestedFrequency) <= 10L) {
                "TX frequency readback did not match"
            }
            val profile = radioProfile(settingsRepo.radioControlSettings.value.radioModel)
            if (profile.capabilities.dataMode) {
                val dataModeEnabled = if (split) {
                    controller.setTxDataMode(enabled = true, baseMode = checkNotNull(txMode))
                } else {
                    controller.setDataMode(enabled = true, baseMode = checkNotNull(txMode))
                }
                check(dataModeEnabled) { "TX DATA mode was not acknowledged" }
                dataModeController = controller
                dataModeBaseMode = txMode
                dataModeUsesSplit = split
                diagnose("transmit/data", "DATA mode enabled split=$split")
            }
            check(pendingControls.get() == 0 && controller.pttSafetyGeneration() == safetyGeneration &&
                _state.value.isActive && _state.value.currentPass == pass &&
                _state.value.selectedTransponder == transponder) { "Radio context changed during transmit preparation" }

            val lease = TxLease(
                id = nextLeaseId++,
                sessionGeneration = request.sessionGeneration,
                effectiveTxFrequencyHz = effectiveFrequency,
                txDopplerCorrectionHz = effectiveFrequency - nominal,
                waveformStartUtcMillis = request.waveformStartUtcMillis,
                waveformMidpointUtcMillis = midpoint,
                waveformDurationMillis = request.waveformDurationMillis,
                maximumPttMillis = request.maximumPttMillis.coerceAtMost(MAX_PTT_MILLIS),
                expectedSatelliteCatalogNumber = request.expectedSatelliteCatalogNumber,
                expectedTransponderUuid = request.expectedTransponderUuid,
                pttSafetyGeneration = checkNotNull(safetyGeneration),
                automatic = request.automatic
            )
            activeTxLease = lease
            diagnose("transmit/prepared", "lease=${lease.id} midpoint=${lease.waveformMidpointUtcMillis}")
            _state.update {
                it.copy(
                    pttState = PttState.OFF,
                    txLeaseId = lease.id,
                    txLeaseGeneration = lease.sessionGeneration,
                    txFrequencyHz = effectiveFrequency,
                    nominalTxFrequencyHz = nominal,
                    txDopplerCorrectionHz = lease.txDopplerCorrectionHz,
                    lastCommandError = null
                )
            }
            lease
        } catch (error: Throwable) {
            withContext(NonCancellable) { restoreDataModeLocked() }
            updateCommandFailure(error)
            throw error
        } finally {
            commandLatencyEstimator.record((System.nanoTime() - startedNanos) / 1_000_000L)
            transmitPreparing = false
            tuningRevision.incrementAndGet()
            updateCommandState(busy = false)
        }
    }

    private fun beginVoxTransmit(request: TxRequest): TxLease {
        val midpoint = request.waveformStartUtcMillis + request.waveformDurationMillis / 2L
        val lease = TxLease(
            id = nextLeaseId++,
            sessionGeneration = request.sessionGeneration,
            effectiveTxFrequencyHz = 0L,
            txDopplerCorrectionHz = 0L,
            waveformStartUtcMillis = request.waveformStartUtcMillis,
            waveformMidpointUtcMillis = midpoint,
            waveformDurationMillis = request.waveformDurationMillis,
            maximumPttMillis = request.maximumPttMillis.coerceAtMost(MAX_PTT_MILLIS),
            expectedSatelliteCatalogNumber = request.expectedSatelliteCatalogNumber,
            expectedTransponderUuid = request.expectedTransponderUuid,
            pttSafetyGeneration = 0L,
            automatic = request.automatic,
            usesVox = true
        )
        activeTxLease = lease
        _state.update {
            it.copy(
                pttState = PttState.OFF,
                txLeaseId = lease.id,
                txLeaseGeneration = lease.sessionGeneration,
                lastCommandError = null
            )
        }
        return lease
    }

    override suspend fun confirmTransmitReady(lease: TxLease) = transmitMutex.withLock {
        if (lease.usesVox) {
            check(activeTxLease == lease) { "Transmit lease is stale" }
            return@withLock
        }
        val startedNanos = System.nanoTime()
        check(activeTxLease == lease) { "Transmit lease is stale" }
        updateCommandState(busy = true, pttState = PttState.ARMING)
        try {
            check(pendingControls.get() == 0 && _state.value.isActive &&
                _state.value.trackingPhase == TrackingPhase.READY) { "Radio tracking context changed" }
            if (lease.automatic) validateAutomaticLease(lease)
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            val confirmed = withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) {
                controller.pttOnIfGeneration(lease.pttSafetyGeneration)
            } == true
            check(confirmed) { "PTT ON was not confirmed" }
            _state.update { it.copy(pttState = PttState.ON, lastCommandError = null) }
            diagnose("transmit/ptt", "PTT confirmed lease=${lease.id}")
            pttWatchdogJob?.cancel()
            pttWatchdogJob = appScope.launch {
                delay(lease.maximumPttMillis)
                if (activeTxLease == lease) emergencyPttOff()
            }
        } catch (error: Throwable) {
            forcePttOffLocked(error.message ?: "PTT ON failed")
            throw error
        } finally {
            commandLatencyEstimator.record((System.nanoTime() - startedNanos) / 1_000_000L)
            updateCommandState(busy = false)
        }
    }

    override fun recommendedPrepareLeadMillis(): Long {
        if (settingsRepo.radioControlSettings.value.catTransport == RadioControlSettings.TRANSPORT_VOX) {
            return 350L
        }
        // Include a tracking cycle already in flight and duplex mode/frequency readbacks,
        // including the first transmission before measured latency samples are available.
        val minimum = if (_state.value.splitMode) 2_000L else 1_500L
        return commandLatencyEstimator.p95WithMargin().coerceAtLeast(minimum)
    }

    override suspend fun endTransmit(lease: TxLease) {
        if (lease.usesVox) {
            transmitMutex.withLock {
                if (activeTxLease == lease) releaseVoxLeaseLocked()
            }
            return
        }
        txController?.invalidatePendingPttOn()
        val urgentOffConfirmed = activeTxLease == lease && sendUrgentPttOff()
        transmitMutex.withLock {
            if (activeTxLease != lease) return@withLock
            updateCommandState(busy = true)
            try {
                forcePttOffLocked(null, urgentOffConfirmed.takeIf { it })
            } finally {
                updateCommandState(busy = false)
            }
        }
    }

    private suspend fun cancelTrackingAndJoin() {
        txController?.invalidatePendingPttOn()
        val job = trackingJob
        trackingJob = null
        job?.cancelAndJoin()
        _state.update { it.copy(isActive = false, trackingPhase = TrackingPhase.IDLE) }
    }

    override suspend fun emergencyPttOff() {
        if (activeTxLease?.usesVox == true ||
            settingsRepo.radioControlSettings.value.catTransport == RadioControlSettings.TRANSPORT_VOX
        ) {
            transmitMutex.withLock {
                if (activeTxLease?.usesVox == true) releaseVoxLeaseLocked()
            }
            return
        }
        txController?.invalidatePendingPttOn()
        val urgentOffConfirmed = sendUrgentPttOff()
        transmitMutex.withLock {
            updateCommandState(busy = true)
            try {
                forcePttOffLocked(null, urgentOffConfirmed.takeIf { it })
            } finally {
                updateCommandState(busy = false)
            }
        }
    }

    private fun releaseVoxLeaseLocked() {
        activeTxLease = null
        _state.update { it.copy(pttState = PttState.OFF, txLeaseId = null, lastCommandError = null) }
    }

    private suspend fun sendUrgentPttOff(): Boolean {
        // Reach the actor's urgent lane before waiting for a possibly suspended tracking cycle.
        return withContext(NonCancellable) {
            val controller = txController
            if (controller?.isConnected == true) {
                withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) { controller.pttOff() } == true
            } else activeTxLease == null
        }
    }

    private fun validateAutomaticWindow(request: TxRequest, pass: OrbitalPass) {
        val end = request.waveformStartUtcMillis + request.waveformDurationMillis
        check(end >= request.waveformStartUtcMillis) { "FT4 waveform window overflow" }
        check(request.waveformStartUtcMillis >= pass.aosTime && end <= pass.losTime) {
            "Automatic FT4 waveform is outside the selected pass"
        }
    }

    private suspend fun validateAutomaticLease(lease: TxLease) {
        val current = _state.value
        check(current.isActive && current.trackingPhase == TrackingPhase.READY) {
            "Satellite tracking is not ready"
        }
        val pass = checkNotNull(current.currentPass) { "No satellite pass is selected" }
        val transponder = checkNotNull(current.selectedTransponder) { "No transponder is selected" }
        check(pass.catNum == lease.expectedSatelliteCatalogNumber) { "Satellite context changed" }
        check(transponder.uuid == lease.expectedTransponderUuid) { "Transponder context changed" }
        validateAutomaticWindow(
            TxRequest(
                sessionGeneration = lease.sessionGeneration,
                waveformStartUtcMillis = lease.waveformStartUtcMillis,
                waveformDurationMillis = lease.waveformDurationMillis,
                expectedSatelliteCatalogNumber = lease.expectedSatelliteCatalogNumber,
                expectedTransponderUuid = lease.expectedTransponderUuid,
                automatic = true,
                maximumPttMillis = lease.maximumPttMillis
            ),
            pass
        )
        val now = clock.nowMillis()
        check(now <= lease.waveformStartUtcMillis + MAX_AUTOMATIC_START_LATENESS_MILLIS) {
            "Automatic FT4 transmit slot was missed"
        }
        val position = satelliteRepo.getPosition(
            pass.orbitalObject,
            settingsRepo.stationPosition.value,
            maxOf(now, lease.waveformStartUtcMillis)
        )
        validateOrbitPosition(position)
    }

    private fun validateOrbitPosition(position: com.rtbishop.look4sat.core.domain.predict.OrbitalPos) {
        check(
            position.aboveHorizon &&
                position.azimuth.isFinite() &&
                position.elevation.isFinite() &&
                position.distance.isFinite() &&
                position.distanceRate.isFinite()
        ) { "Satellite orbit position is not valid for automatic transmit" }
    }

    private suspend fun forcePttOffLocked(failure: String?, priorAcknowledged: Boolean? = null) {
        pttWatchdogJob?.cancel()
        pttWatchdogJob = null
        val hadActiveLease = activeTxLease != null
        val controller = txController
        val acknowledged = priorAcknowledged ?: withContext(NonCancellable) {
            if (controller?.isConnected == true) {
                withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) { controller.pttOff() } == true
            } else {
                !hadActiveLease
            }
        }
        val dataModeRestored = withContext(NonCancellable) { restoreDataModeLocked() }
        activeTxLease = null
        tuningRevision.incrementAndGet()
        val error = failure ?: when {
            !acknowledged -> "PTT OFF was not confirmed"
            !dataModeRestored -> "TX DATA mode could not be restored"
            else -> null
        }
        _state.update {
            it.copy(
                pttState = if (error == null) PttState.OFF else PttState.ERROR,
                txLeaseId = null,
                lastCommandError = error,
                errorMessage = error ?: it.errorMessage
            )
        }
        diagnose(
            "transmit/release",
            "pttOff=$acknowledged dataRestored=$dataModeRestored",
            if (error == null) ControlDiagnosticSeverity.INFO else ControlDiagnosticSeverity.ERROR
        )
    }

    private suspend fun restoreDataModeLocked(): Boolean {
        val controller = dataModeController ?: return true
        val baseMode = dataModeBaseMode ?: return true
        val split = dataModeUsesSplit
        dataModeController = null
        dataModeBaseMode = null
        dataModeUsesSplit = false
        if (!controller.isConnected) return false
        return if (split) {
            controller.setTxDataMode(enabled = false, baseMode = baseMode)
        } else {
            controller.setDataMode(enabled = false, baseMode = baseMode)
        }
    }

    private fun updateCommandState(busy: Boolean, pttState: PttState? = null) {
        _state.update { current ->
            current.copy(
                commandBusy = busy,
                pttState = pttState ?: current.pttState
            )
        }
    }

    private fun updateCommandFailure(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        diagnose("command/failure", message, ControlDiagnosticSeverity.ERROR)
        _state.update { it.copy(trackingPhase = TrackingPhase.ERROR, lastCommandError = message, errorMessage = message) }
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    override fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?) {
        diagnose("tracking/start", "satellite=${pass.catNum} transponderSelected=true")
        txController?.invalidatePendingPttOn()
        val previousJob = trackingJob
        previousJob?.cancel()
        _state.update {
            it.copy(
                isActive = false,
                trackingPhase = TrackingPhase.INITIALIZING,
                currentPass = pass,
                selectedTransponder = transponder,
                txBaseFrequencyHz = txBaseFreqHz,
                txFrequencyHz = null,
                rxFrequencyHz = null,
                lastCommandError = null
            )
        }
        trackingJob = appScope.launch {
            try {
                withContext(NonCancellable) { previousJob?.join() }
                currentCoroutineContext().ensureActive()
                if (hasTransmitClaim) emergencyPttOff()
                val isSplit = _state.value.splitMode
                if (isSplit) runSplitTracking(transponder, txBaseFreqHz)
                else runDualRadioTracking(transponder, txBaseFreqHz)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                failTrackingInitialization("Radio setup was interrupted")
            } catch (error: Throwable) {
                failTrackingInitialization(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    // ── Dual-radio tracking (Yaesu or two IC-705s) ──────────────────────────

    private suspend fun runDualRadioTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val tx = txController
        val rx = rxController

        transmitMutex.withLock {
            // Initial setup: set band/mode/CTCSS on both radios
            val txMode = transponder.resolvedUplinkMode()
            val rxMode = transponder.downlinkMode?.uppercase()
                ?: txMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }

            Log.i(tag, "DualRadio start: txMode=$txMode rxMode=$rxMode")

            if (tx != null && tx.isConnected && txMode != null) {
                Log.d(tag, "Setting TX mode: $txMode")
                if (!tx.setMode(txMode)) return failTrackingInitialization("TX mode was not acknowledged")
            }
            if (rx != null && rx.isConnected && rxMode != null) {
                Log.d(tag, "Setting RX mode: $rxMode")
                if (!rx.setMode(rxMode)) return failTrackingInitialization("RX mode was not acknowledged")
            }
            if (tx != null && tx.isConnected) {
                val tone = _state.value.ctcssTone.takeIf { txMode.isFmMode() }
                val ctcssOk = if (tone != null) {
                    Log.d(tag, "Setting CTCSS: ${tone}Hz")
                    tx.setCtcssTone(tone) && tx.setCtcssMode(true)
                } else {
                    tx.setCtcssMode(false)
                }
                if (!ctcssOk) return failTrackingInitialization("CTCSS command was not acknowledged")
            }
            _state.update {
                it.copy(
                    isActive = true,
                    trackingPhase = TrackingPhase.READY,
                    txMode = txMode,
                    rxMode = rxMode,
                    txBaseFrequencyHz = initialTxBaseFreqHz ?: transponder.uplinkCenterFrequency()
                )
            }
            diagnose("tracking/ready", "mode=dual-radio")
        }

        runTrackingLoop(split = false)
    }

    // ── Icom single-radio duplex tracking ───────────────────────────────────

    private suspend fun runSplitTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val radio = txController
            ?: return failTrackingInitialization("TX radio is not connected")
        if (!radio.isConnected) return failTrackingInitialization("TX radio is not connected")

        val txMode = transponder.resolvedUplinkMode()
        val isSatelliteMode = _state.value.satelliteMode
        val rxMode = transponder.downlinkMode?.uppercase()
            ?: txMode?.let {
                TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
            }

        transmitMutex.withLock {
            // Compute nominal base frequencies
            val txCenter = transponder.uplinkCenterFrequency()
            val rxNominal = if (txCenter != null) {
                TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
            } else transponder.downlinkLow

            val txBase = initialTxBaseFreqHz ?: txCenter
            Log.i(tag, "Icom duplex setup: txBase=${txBase}Hz rxNominal=${rxNominal}Hz txMode=$txMode rxMode=$rxMode")

            // ── Initial setup sequence ──────────────────────────────────────────
            // Explicitly select each VFO/band, then configure frequency and mode.
            // ACK from each command gates the next — no fixed delays needed.

            // Satellite-capable Icoms expose MAIN/SUB only after their dedicated mode is enabled.
            if (isSatelliteMode && !radio.setSplitMode(enabled = true)) {
                return failTrackingInitialization("Satellite mode was not acknowledged")
            }
            if (!isSatelliteMode && !radio.setSplitMode(enabled = false)) {
                return failTrackingInitialization("Split mode could not be reset before setup")
            }

            // VFO-A or MAIN = RX (downlink)
            Log.d(tag, "Duplex init: selecting RX target (VFO-A/MAIN)")
            if (!radio.setVfo(vfoA = true)) return failTrackingInitialization("RX target selection was not acknowledged")
            if (rxNominal != null) {
                Log.d(tag, "Duplex init: RX band for ${rxNominal}Hz")
                if (!radio.setBand(rxNominal)) return failTrackingInitialization("RX band was not acknowledged")
                Log.d(tag, "Duplex init: RX freq=${rxNominal}Hz")
                if (!radio.setFrequency(rxNominal)) return failTrackingInitialization("RX frequency was not acknowledged")
            }

            // VFO-B or SUB = TX (uplink)
            Log.d(tag, "Duplex init: selecting TX target (VFO-B/SUB)")
            if (!radio.setVfo(vfoA = false)) return failTrackingInitialization("TX target selection was not acknowledged")
            if (txBase != null) {
                Log.d(tag, "Duplex init: TX band for ${txBase}Hz")
                if (!radio.setBand(txBase)) return failTrackingInitialization("TX band was not acknowledged")
                Log.d(tag, "Duplex init: TX freq=${txBase}Hz")
                if (!radio.setFrequency(txBase)) return failTrackingInitialization("TX frequency was not acknowledged")
            }
            if (!radio.setSplitModes(rxMode = rxMode, txMode = txMode)) {
                return failTrackingInitialization("Split VFO modes were not acknowledged or verified")
            }
            val splitTone = _state.value.ctcssTone.takeIf { txMode.isFmMode() }
            Log.d(tag, "Split init: TX CTCSS=${splitTone ?: "OFF"}")
            if (!radio.configureTxCtcss(splitTone)) {
                return failTrackingInitialization("CTCSS command was not acknowledged")
            }

            // Enable ordinary split when dedicated satellite mode is not selected.
            Log.d(tag, "Split init: returning to VFO-A, then enabling SPLIT mode")
            if (!radio.setVfo(vfoA = true) || (!isSatelliteMode && !radio.setSplitMode(enabled = true))) {
                return failTrackingInitialization("Split mode was not acknowledged")
            }

            _state.update {
                it.copy(
                    isActive = true,
                    trackingPhase = TrackingPhase.READY,
                    txMode = txMode,
                    rxMode = rxMode,
                    txBaseFrequencyHz = txBase
                )
            }
            diagnose("tracking/ready", "mode=single-radio split=true satellite=$isSatelliteMode")
            Log.i(tag, "Icom duplex init done — entering tracking loop")

        }
        runTrackingLoop(split = true)
    }

    // A cycle and a settings change own the same lock as transmit preparation.
    // During PTT, suppress dial-change detection but keep Doppler writes running.
    private suspend fun runTrackingLoop(split: Boolean) {
        var lastSetTx: Long? = null
        var lastSetRx: Long? = null
        var dialFollowState = DialFollowState()
        var revision = -1L
        var previousOffset: Long? = null
        while (currentCoroutineContext().isActive && _state.value.isActive) {
            try {
                transmitMutex.withLock {
                    val current = _state.value
                    if (!current.isActive) return@withLock
                    val pass = current.currentPass ?: return@withLock
                    val transponder = current.selectedTransponder ?: return@withLock
                    val now = clock.nowMillis()
                    if (now >= pass.losTime) {
                        if (hasTransmitClaim) forcePttOffLocked(null)
                        _state.update { it.copy(isActive = false, trackingPhase = TrackingPhase.IDLE,
                            lastCommandError = "Satellite pass reached LOS") }
                        return@withLock
                    }
                    val cycleRevision = tuningRevision.get()
                    val isLinear = DopplerFrequencyCalculator.isLinearTransponder(transponder)
                    val controlSettings = settingsRepo.radioControlSettings.value
                    val dialDeadband = if (isLinear) {
                        controlSettings.linearDialDeadbandHz
                    } else {
                        controlSettings.fmDialDeadbandHz
                    }
                    val offset = if (isLinear) {
                        DopplerFrequencyCalculator.parseOffsetHz(settingsRepo.getSatelliteOffset(pass.catNum))
                    } else 0L
                    if (revision != cycleRevision || previousOffset != offset) {
                        dialFollowState = DialFollowState()
                        lastSetTx = null
                        lastSetRx = null
                        revision = cycleRevision
                        previousOffset = offset
                    }
                    val position = satelliteRepo.getPosition(pass.orbitalObject, settingsRepo.stationPosition.value, now)
                    val tx = txController
                    val rx = if (split) tx else rxController
                    val frozen = hasTransmitClaim
                    var base = current.txBaseFrequencyHz
                    var observedTx = current.txFrequencyHz
                    var observedRx = current.rxFrequencyHz
                    suspend fun readTx() = if (split) tx?.readTxVfoFrequency() else tx?.readFrequencyAndMode()?.first
                    suspend fun readRx() = if (split) rx?.readWorkingFrequency() else rx?.readFrequencyAndMode()?.first
                    // Do not change the nominal pair under a waveform, even from the RX dial.
                    if (!frozen) {
                        suspend fun sampleDial(leg: DialLeg): Boolean {
                            val controller = if (leg == DialLeg.TX) tx else rx
                            val commanded = if (leg == DialLeg.TX) lastSetTx else lastSetRx
                            if (controller?.isConnected != true || commanded == null || base == null) return false
                            val frequency = (if (leg == DialLeg.TX) readTx() else readRx())
                                ?: return dialFollowState.activeLeg != null
                            if (leg == DialLeg.TX) observedTx = frequency else observedRx = frequency
                            val result = DialFollowPolicy.update(
                                state = dialFollowState,
                                leg = leg,
                                observedFrequencyHz = frequency,
                                commandedFrequencyHz = commanded,
                                nowMillis = now,
                                deadbandHz = dialDeadband,
                                settleMillis = controlSettings.dialSettleMillis
                            )
                            dialFollowState = result.state
                            result.acceptedFrequencyHz?.let { acceptedFrequency ->
                                val velocity = position.distanceRate * 1000.0
                                val candidate = if (leg == DialLeg.TX) {
                                    (acceptedFrequency.toDouble() * SPEED_OF_LIGHT /
                                        (SPEED_OF_LIGHT + velocity)).toLong()
                                } else {
                                    val nominalRx = (acceptedFrequency.toDouble() * SPEED_OF_LIGHT /
                                        (SPEED_OF_LIGHT - velocity)).toLong() - offset
                                    TransponderMapper.mapDownlinkToUplink(nominalRx, transponder)
                                }
                                if (candidate != null && candidate > 0L) base = candidate
                                lastSetTx = null
                                lastSetRx = null
                                return true
                            }
                            return dialFollowState.activeLeg != null
                        }
                        val activeLeg = dialFollowState.activeLeg
                        if (activeLeg != null) {
                            sampleDial(activeLeg)
                        } else if (!sampleDial(DialLeg.TX)) {
                            sampleDial(DialLeg.RX)
                        }
                    } else {
                        dialFollowState = DialFollowState()
                    }
                    // A synchronous UI intent can invalidate this snapshot while a read suspends.
                    if (cycleRevision != tuningRevision.get() || !_state.value.isActive) return@withLock
                    val nominalRx = (base?.let { TransponderMapper.mapUplinkToDownlink(it, transponder) }
                        ?: transponder.downlinkLow)?.plus(offset)
                    val desiredTx = base?.let(position::getUplinkFreq)
                    val desiredRx = nominalRx?.let(position::getDownlinkFreq)
                    // The FT4 lease preloads the slot-midpoint frequency. Hold both CAT legs
                    // until the waveform ends so no synthesizer step occurs under modulation.
                    if (!frozen && dialFollowState.activeLeg == null) {
                        val txWriteAllowed = current.pttState != PttState.ON ||
                            radioProfile(controlSettings.radioModel)
                                .canSetTxFrequencyWhileTransmitting
                        var wroteTx = false
                        if (txWriteAllowed && tx?.isConnected == true && desiredTx != null) {
                            val ok = if (split) tx.setTxVfoFrequency(desiredTx) else tx.setFrequency(desiredTx)
                            if (ok) { lastSetTx = desiredTx; observedTx = desiredTx; wroteTx = true }
                            else failTrackingCommand("TX frequency was not acknowledged")
                        }
                        if (rx?.isConnected == true && desiredRx != null) {
                            if (split && wroteTx && controlSettings.sharedBusCommandDelayMillis > 0L) {
                                delay(controlSettings.sharedBusCommandDelayMillis)
                            }
                            val ok = if (split) rx.setWorkingFrequency(desiredRx) else rx.setFrequency(desiredRx)
                            if (ok) { lastSetRx = desiredRx; observedRx = desiredRx }
                            else failTrackingCommand("RX frequency was not acknowledged")
                        }
                    }
                    if (cycleRevision != tuningRevision.get()) return@withLock
                    _state.update {
                        it.copy(
                            txConnected = tx?.isConnected == true,
                            rxConnected = rx?.isConnected == true,
                            txBaseFrequencyHz = base,
                            txFrequencyHz = observedTx,
                            rxFrequencyHz = observedRx,
                            nominalTxFrequencyHz = base,
                            nominalRxFrequencyHz = nominalRx,
                            txDopplerCorrectionHz = dopplerCorrection(observedTx, base),
                            rxDopplerCorrectionHz = dopplerCorrection(observedRx, nominalRx),
                            azimuth = Math.toDegrees(position.azimuth),
                            elevation = Math.toDegrees(position.elevation),
                            distance = position.distance
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                // Preserve tracking when a transport cancels only its current transaction.
                currentCoroutineContext().ensureActive()
                tuningRevision.incrementAndGet()
            }
            delay(1000L)
        }
    }

    // ── Other IRadioTrackingService methods ─────────────────────────────────

    override fun stopTracking() {
        txController?.invalidatePendingPttOn()
        trackingJob?.cancel()
        _state.update { it.copy(isActive = false, trackingPhase = TrackingPhase.IDLE) }
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
        }
    }

    override fun setTransponder(transponder: SatRadio) {
        changeRadioSettings {
            val txMode = transponder.resolvedUplinkMode()
            val tx = txController
            val rx = rxController
            val rxMode = transponder.downlinkMode?.uppercase()
                ?: txMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }
            // Selecting a transponder is also local UI state. Allow it before a
            // radio is connected; startTracking will program the selected mode.
            if (tx?.isConnected != true && rx?.isConnected != true) {
                updateSelectedTransponder(transponder, rxMode)
                return@changeRadioSettings
            }
            val split = _state.value.splitMode
            val txModeOk: Boolean
            val rxModeOk: Boolean
            if (split) {
                val modesOk = tx?.setSplitModes(rxMode, txMode) ?: false
                txModeOk = modesOk
                rxModeOk = modesOk
            } else {
                txModeOk = txMode?.let { tx?.setMode(it) } ?: true
                rxModeOk = rxMode?.let { mode -> rx?.setMode(mode) } ?: true
            }
            val tone = _state.value.ctcssTone.takeIf { txMode.isFmMode() }
            val ctcssOk = if (split) {
                tx != null && tx.configureTxCtcss(tone)
            } else if (tx == null || !tx.isConnected) {
                true
            } else if (tone != null) {
                tx.setCtcssTone(tone) && tx.setCtcssMode(true)
            } else {
                tx.setCtcssMode(false)
            }
            if (!ctcssOk) {
                return@changeRadioSettings failTrackingCommand("CTCSS command was not acknowledged")
            }
            if (txModeOk != false && rxModeOk != false) updateSelectedTransponder(transponder, rxMode)
            else failTrackingCommand("Radio mode command was not acknowledged")
        }
    }

    private fun changeRadioSettings(block: suspend () -> Unit) {
        pendingControls.incrementAndGet()
        tuningRevision.incrementAndGet()
        txController?.invalidatePendingPttOn()
        appScope.launch {
            try {
                if (hasTransmitClaim) sendUrgentPttOff()
                transmitMutex.withLock {
                    if (hasTransmitClaim) forcePttOffLocked(null)
                    if (_state.value.pttState != PttState.OFF) {
                        failTrackingCommand("PTT OFF must be confirmed before changing radio settings")
                        return@withLock
                    }
                    block()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failTrackingCommand(error.message ?: "Radio settings update failed")
            } finally {
                tuningRevision.incrementAndGet()
                pendingControls.decrementAndGet()
            }
        }
    }

    override fun setTxBaseFrequency(frequencyHz: Long) {
        if (frequencyHz <= 0L) return
        changeRadioSettings { _state.update { it.copy(txBaseFrequencyHz = frequencyHz) } }
    }

    override fun adjustTxBaseFrequency(deltaHz: Long) {
        changeRadioSettings {
            val base = _state.value.txBaseFrequencyHz ?: return@changeRadioSettings
            val adjusted = Math.addExact(base, deltaHz)
            if (adjusted > 0L) _state.update { it.copy(txBaseFrequencyHz = adjusted) }
        }
    }

    override fun setCtcssTone(toneHz: Double?) {
        changeRadioSettings {
            val tx = txController
            val acknowledged = if (_state.value.splitMode) {
                tx != null && tx.configureTxCtcss(toneHz)
            } else if (toneHz != null) {
                tx?.setCtcssTone(toneHz) == true && tx.setCtcssMode(true)
            } else {
                tx?.setCtcssMode(false) == true
            }
            if (acknowledged) _state.update { it.copy(ctcssTone = toneHz) }
            else failTrackingCommand("CTCSS command was not acknowledged")
        }
    }

    override fun setMode(txMode: String, rxMode: String) {
        changeRadioSettings {
            val split = _state.value.splitMode
            val txOk = if (split) {
                txController?.setSplitModes(rxMode, txMode) ?: false
            } else {
                txController?.setMode(txMode) ?: true
            }
            val rxOk = if (split) true else rxController?.setMode(rxMode) ?: true
            if (txOk && rxOk) _state.update { it.copy(txMode = txMode, rxMode = rxMode) }
            else failTrackingCommand("Radio mode command was not acknowledged")
        }
    }

    private fun updateSelectedTransponder(transponder: SatRadio, rxMode: String?) {
        val txCenter = transponder.uplinkCenterFrequency()
        val rxNominal = if (txCenter != null) TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        else transponder.downlinkLow
        _state.update {
            it.copy(
                selectedTransponder = transponder,
                txBaseFrequencyHz = txCenter,
                nominalTxFrequencyHz = txCenter,
                nominalRxFrequencyHz = rxNominal,
                txMode = transponder.resolvedUplinkMode(),
                rxMode = rxMode
            )
        }
    }

    private fun failTrackingCommand(message: String) {
        diagnose("tracking/command", message, ControlDiagnosticSeverity.ERROR)
        _state.update { it.copy(lastCommandError = message, errorMessage = message) }
    }

    private fun failTrackingInitialization(message: String) {
        diagnose("tracking/initialization", message, ControlDiagnosticSeverity.ERROR)
        _state.update {
            it.copy(
                isActive = false,
                trackingPhase = TrackingPhase.ERROR,
                lastCommandError = message,
                errorMessage = message
            )
        }
    }

    private fun diagnose(
        stage: String,
        message: String,
        severity: ControlDiagnosticSeverity = ControlDiagnosticSeverity.INFO
    ) {
        diagnostics.record(ControlDiagnosticSource.RADIO, stage, message, severity)
    }

    private companion object {
        const val PTT_COMMAND_TIMEOUT_MILLIS = 2_500L
        const val MAX_WAVEFORM_MILLIS = 6_000L
        const val MAX_PTT_MILLIS = 12_000L
        const val MAX_AUTOMATIC_START_LATENESS_MILLIS = 150L
    }
}

private fun SatRadio.resolvedUplinkMode(): String? =
    uplinkMode?.uppercase() ?: downlinkMode?.let {
        TransponderMapper.mapUplinkModeToDownlinkMode(it.uppercase(), isInverted)
    }

private fun String?.isFmMode(): Boolean = this?.uppercase() in setOf("FM", "FM-N", "NFM")

private fun SatRadio.uplinkCenterFrequency(): Long? {
    val low = uplinkLow
    val high = uplinkHigh
    return when {
        low != null && high != null -> (low + high) / 2L
        low != null -> low
        else -> null
    }
}

private fun dopplerCorrection(corrected: Long?, nominal: Long?): Long? =
    if (corrected != null && nominal != null) corrected - nominal else null

internal class RadioCommandLatencyEstimator(
    private val capacity: Int = 32,
    private val fallbackMillis: Long = 350L
) {
    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun record(valueMillis: Long) {
        samples.addLast(valueMillis.coerceAtLeast(0L))
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized
    fun p95WithMargin(): Long {
        if (samples.size < 4) return fallbackMillis
        val sorted = samples.sorted()
        val index = kotlin.math.ceil(sorted.size * 0.95).toInt().coerceIn(1, sorted.size) - 1
        return (sorted[index] + 100L).coerceIn(250L, 1_500L)
    }
}
