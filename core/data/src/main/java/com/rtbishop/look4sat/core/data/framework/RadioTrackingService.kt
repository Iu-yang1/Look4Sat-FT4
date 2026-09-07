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
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    private val transportFactory: ((String, String, Int) -> RadioTransport)? = null
) : IRadioTrackingService, IFt4TransmitCoordinator {

    private val tag = "RadioTracking"
    private val _state = MutableStateFlow(RadioTrackingState())
    override val state: StateFlow<RadioTrackingState> = _state

    private var txController: IRadioController? = null
    private var rxController: IRadioController? = null
    private var trackingJob: Job? = null
    private val transmitMutex = Mutex()
    @Volatile private var activeTxLease: TxLease? = null
    @Volatile private var transmitPreparing = false
    private var pttWatchdogJob: Job? = null
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

    override suspend fun connectRadios() {
        emergencyPttOff()
        txController?.disconnect()
        rxController?.disconnect()

        val rcSettings = settingsRepo.radioControlSettings.value
        val txAddr     = rcSettings.txRadioAddress
        val rxAddr     = rcSettings.rxRadioAddress
        val profile    = radioProfile(rcSettings.radioModel)
        val isIcom     = profile.isIcom
        val isSplit    = isIcom && rcSettings.splitMode

        Log.i(tag, "connectRadios model=${rcSettings.radioModel} split=$isSplit TX=$txAddr RX=$rxAddr")
        _state.update { it.copy(splitMode = isSplit, lastCommandError = null) }

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
            Log.i(tag, "IC-705 split mode connected: txOk=$txOk")
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
    }

    private fun makeController(profile: RadioProfile, address: String): IRadioController {
        val settings = settingsRepo.radioControlSettings.value
        val injected = controllerFactory?.invoke(profile.isIcom, address)
        val transport = if (injected == null) {
            transportFactory?.invoke(settings.catTransport, address, settings.baudRate)
        } else null
        val controller = injected ?: if (profile.isIcom) {
            if (transport == null) {
                Ic705Controller(checkNotNull(bluetoothManager), address, checkNotNull(profile.civAddress))
            } else {
                Ic705Controller(null, address, checkNotNull(profile.civAddress), transport)
            }
        } else {
            if (transport == null) {
                Ft817Controller(checkNotNull(bluetoothManager), address)
            } else {
                Ft817Controller(null, address, transport)
            }
        }
        return SerialRadioController(controller, commandActor)
    }

    override suspend fun disconnectRadios() {
        stopTracking()
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
                physicalConnectionCount = 0
            )
        }
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
        transmitPreparing = true
        updateCommandState(busy = true)
        try {
            val current = _state.value
            val pass = checkNotNull(current.currentPass) { "No satellite pass is selected" }
            val transponder = checkNotNull(current.selectedTransponder) { "No transponder is selected" }
            check(current.isActive) { "Satellite tracking is not active" }
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
            val effectiveFrequency = position.getUplinkFreq(nominal)
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            check(controller.isConnected) { "TX radio is not connected" }

            val radioSettings = settingsRepo.radioControlSettings.value
            val split = radioSettings.splitMode && radioProfile(radioSettings.radioModel).isIcom
            val frequencySet = if (split) {
                controller.setTxVfoFrequency(effectiveFrequency)
            } else {
                controller.setFrequency(effectiveFrequency)
            }
            check(frequencySet) { "TX frequency command was not acknowledged" }
            if (!split) {
                current.txMode?.let { mode ->
                    check(controller.setMode(mode)) { "TX mode command was not acknowledged" }
                }
            }

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
                pttSafetyGeneration = controller.pttSafetyGeneration(),
                automatic = request.automatic
            )
            activeTxLease = lease
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
            updateCommandFailure(error)
            throw error
        } finally {
            commandLatencyEstimator.record((System.nanoTime() - startedNanos) / 1_000_000L)
            transmitPreparing = false
            updateCommandState(busy = false)
        }
    }

    override suspend fun confirmTransmitReady(lease: TxLease) = transmitMutex.withLock {
        val startedNanos = System.nanoTime()
        check(activeTxLease == lease) { "Transmit lease is stale" }
        updateCommandState(busy = true, pttState = PttState.ARMING)
        try {
            if (lease.automatic) validateAutomaticLease(lease)
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            val confirmed = withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) {
                controller.pttOnIfGeneration(lease.pttSafetyGeneration)
            } == true
            check(confirmed) { "PTT ON was not confirmed" }
            _state.update { it.copy(pttState = PttState.ON, lastCommandError = null) }
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

    override fun recommendedPrepareLeadMillis(): Long = commandLatencyEstimator.p95WithMargin()

    override suspend fun endTransmit(lease: TxLease) {
        txController?.invalidatePendingPttOn()
        transmitMutex.withLock {
            if (activeTxLease != lease) return@withLock
            updateCommandState(busy = true)
            try {
                forcePttOffLocked(null)
            } finally {
                updateCommandState(busy = false)
            }
        }
    }

    override suspend fun emergencyPttOff() {
        txController?.invalidatePendingPttOn()
        transmitMutex.withLock {
            updateCommandState(busy = true)
            try {
                forcePttOffLocked(null)
            } finally {
                updateCommandState(busy = false)
            }
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

    private suspend fun forcePttOffLocked(failure: String?) {
        pttWatchdogJob?.cancel()
        pttWatchdogJob = null
        val hadActiveLease = activeTxLease != null
        val controller = txController
        val acknowledged = withContext(NonCancellable) {
            if (controller?.isConnected == true) {
                withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) { controller.pttOff() } == true
            } else {
                !hadActiveLease
            }
        }
        activeTxLease = null
        val error = failure ?: if (!acknowledged) "PTT OFF was not confirmed" else null
        _state.update {
            it.copy(
                pttState = if (error == null) PttState.OFF else PttState.ERROR,
                txLeaseId = null,
                lastCommandError = error,
                errorMessage = error ?: it.errorMessage
            )
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
        _state.update { it.copy(lastCommandError = message, errorMessage = message) }
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    override fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?) {
        txController?.invalidatePendingPttOn()
        trackingJob?.cancel()
        _state.update {
            it.copy(
                isActive = false,
                trackingPhase = TrackingPhase.INITIALIZING,
                currentPass = pass,
                selectedTransponder = transponder,
                txBaseFrequencyHz = txBaseFreqHz,
                lastCommandError = null
            )
        }
        trackingJob = appScope.launch {
            try {
                if (hasTransmitClaim) emergencyPttOff()
                val rcSettings = settingsRepo.radioControlSettings.value
                val isSplit = radioProfile(rcSettings.radioModel).isIcom && rcSettings.splitMode
                if (isSplit) runSplitTracking(transponder, txBaseFreqHz)
                else runDualRadioTracking(transponder, txBaseFreqHz)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failTrackingInitialization(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    // ── Dual-radio tracking (Yaesu or two IC-705s) ──────────────────────────

    private suspend fun runDualRadioTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val tx = txController
        val rx = rxController

        // Initial setup: set band/mode/CTCSS on both radios
        val txMode = transponder.uplinkMode
        val rxMode = transponder.downlinkMode
            ?: transponder.uplinkMode?.let {
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
        if (txMode?.uppercase() == "FM") {
            _state.value.ctcssTone?.let { tone ->
                Log.d(tag, "Setting CTCSS: ${tone}Hz")
                if (tx?.setCtcssTone(tone) != true || tx.setCtcssMode(true) != true) {
                    return failTrackingInitialization("CTCSS command was not acknowledged")
                }
            }
        }
        _state.update {
            it.copy(
                isActive = true,
                trackingPhase = TrackingPhase.READY,
                txMode = txMode,
                rxMode = rxMode
            )
        }

        var lastSetTxFreq = 0.0
        var lastSetRxFreq = 0.0
        var tuningRadio   = ""
        var lastReadFreq  = 0L
        var stableCount   = 0

        while (currentCoroutineContext().isActive) {
            val currentState = _state.value
            if (!currentState.isActive) break

            val satPass = currentState.currentPass ?: break
            val xpdr    = currentState.selectedTransponder ?: break
            var txBaseFreq = currentState.txBaseFrequencyHz
            val stationPos = settingsRepo.stationPosition.value
            val now = clock.nowMillis()
            if (now >= satPass.losTime) {
                if (hasTransmitClaim) emergencyPttOff()
                _state.update {
                    it.copy(
                        isActive = false,
                        trackingPhase = TrackingPhase.IDLE,
                        lastCommandError = "Satellite pass reached LOS"
                    )
                }
                break
            }
            val pos = satelliteRepo.getPosition(satPass.orbitalObject, stationPos, now)
            val txNow = txController
            val rxNow = rxController
            val v = pos.distanceRate * 1000.0
            if (hasTransmitClaim && tuningRadio == "tx") {
                tuningRadio = ""
                stableCount = 0
            }

            if (tuningRadio.isNotEmpty()) {
                val radio = if (tuningRadio == "tx") txNow else rxNow
                if (radio != null && radio.isConnected) {
                    val read = radio.readFrequencyAndMode()
                    if (read != null) {
                        val (freq, _) = read
                        if (kotlin.math.abs(freq - lastReadFreq) <= 20) stableCount++
                        else { stableCount = 0; lastReadFreq = freq }
                        if (stableCount >= 2) {
                            if (tuningRadio == "tx" && txBaseFreq != null) {
                                val newBase = (freq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT + v)).toLong()
                                if (newBase > 0) {
                                    txBaseFreq = newBase
                                    _state.update { it.copy(txBaseFrequencyHz = newBase) }
                                    Log.i(tag, "TX tuning done → base=$newBase")
                                }
                            } else if (tuningRadio == "rx") {
                                val rxNominal = (freq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT - v)).toLong()
                                val newTxBase = TransponderMapper.mapDownlinkToUplink(rxNominal, xpdr)
                                if (newTxBase != null && newTxBase > 0) {
                                    txBaseFreq = newTxBase
                                    _state.update { it.copy(txBaseFrequencyHz = newTxBase) }
                                    Log.i(tag, "RX tuning done → txBase=$newTxBase")
                                }
                            }
                            tuningRadio   = ""
                            stableCount   = 0
                            lastSetTxFreq = 0.0
                            lastSetRxFreq = 0.0
                        }
                    }
                }
            } else {
                // Detect manual dial changes
                if (
                    !transmitPreparing && activeTxLease == null && txBaseFreq != null && txNow != null &&
                    txNow.isConnected && lastSetTxFreq > 0.0
                ) {
                    val read = txNow.readFrequencyAndMode()
                    if (read != null && kotlin.math.abs(read.first - lastSetTxFreq) >= 20.0) {
                        tuningRadio  = "tx"
                        lastReadFreq = read.first
                        stableCount  = 0
                        Log.i(tag, "TX tuning detected (read=${read.first}, lastSet=$lastSetTxFreq)")
                    }
                }
                if (tuningRadio.isEmpty() && rxNow != null && rxNow.isConnected && lastSetRxFreq > 0.0) {
                    val read = rxNow.readFrequencyAndMode()
                    if (read != null && kotlin.math.abs(read.first - lastSetRxFreq) >= 20.0) {
                        tuningRadio  = "rx"
                        lastReadFreq = read.first
                        stableCount  = 0
                        Log.i(tag, "RX tuning detected (read=${read.first}, lastSet=$lastSetRxFreq)")
                    }
                }
            }

            val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
            val rxBaseFreq  = if (txBaseFreq != null) {
                TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
            } else xpdr.downlinkLow
            val rxRadioFreq = rxBaseFreq?.let { pos.getDownlinkFreq(it) }

            if (tuningRadio.isEmpty()) {
                if (!transmitPreparing && activeTxLease == null && txNow != null && txNow.isConnected && txRadioFreq != null) {
                    if (txNow.setFrequency(txRadioFreq)) lastSetTxFreq = txRadioFreq.toDouble()
                    else failTrackingCommand("TX frequency was not acknowledged")
                }
                if (rxNow != null && rxNow.isConnected && rxRadioFreq != null) {
                    if (rxNow.setFrequency(rxRadioFreq)) lastSetRxFreq = rxRadioFreq.toDouble()
                    else failTrackingCommand("RX frequency was not acknowledged")
                }
            }

            _state.update {
                val frozenLease = activeTxLease
                it.copy(
                    txConnected  = txNow?.isConnected ?: false,
                    rxConnected  = rxNow?.isConnected ?: false,
                    txFrequencyHz = frozenLease?.effectiveTxFrequencyHz ?: txRadioFreq,
                    rxFrequencyHz = rxRadioFreq,
                    nominalTxFrequencyHz = txBaseFreq,
                    nominalRxFrequencyHz = rxBaseFreq,
                    txDopplerCorrectionHz = frozenLease?.txDopplerCorrectionHz
                        ?: dopplerCorrection(txRadioFreq, txBaseFreq),
                    rxDopplerCorrectionHz = dopplerCorrection(rxRadioFreq, rxBaseFreq),
                    azimuth      = Math.toDegrees(pos.azimuth),
                    elevation    = Math.toDegrees(pos.elevation),
                    distance     = pos.distance
                )
            }
            delay(1000)
        }
    }

    // ── IC-705 split-radio tracking ─────────────────────────────────────────

    private suspend fun runSplitTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val radio = txController
            ?: return failTrackingInitialization("TX radio is not connected")
        if (!radio.isConnected) return failTrackingInitialization("TX radio is not connected")

        val txMode = transponder.uplinkMode
        val rxMode = transponder.downlinkMode
            ?: transponder.uplinkMode?.let {
                TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
            }

        // Compute nominal base frequencies
        val txCenter = when {
            transponder.uplinkLow != null && transponder.uplinkHigh != null ->
                (transponder.uplinkLow!! + transponder.uplinkHigh!!) / 2
            transponder.uplinkLow != null -> transponder.uplinkLow!!
            else -> null
        }
        val rxNominal = if (txCenter != null) {
            TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        } else transponder.downlinkLow

        val txBase = initialTxBaseFreqHz ?: txCenter
        Log.i(tag, "IC-705 split setup: txBase=${txBase}Hz rxNominal=${rxNominal}Hz txMode=$txMode rxMode=$rxMode")

        // ── Initial setup sequence ──────────────────────────────────────────
        // Sequence per IC-705: explicitly select VFO, then band → freq → mode.
        // ACK from each command gates the next — no fixed delays needed.

        // VFO-A = RX (downlink)
        Log.d(tag, "Split init: selecting VFO-A for RX (downlink)")
        if (!radio.setVfo(vfoA = true)) return failTrackingInitialization("VFO-A selection was not acknowledged")
        if (rxNominal != null) {
            Log.d(tag, "Split init: VFO-A band for ${rxNominal}Hz")
            if (!radio.setBand(rxNominal)) return failTrackingInitialization("RX band was not acknowledged")
            Log.d(tag, "Split init: VFO-A freq=${rxNominal}Hz")
            if (!radio.setFrequency(rxNominal)) return failTrackingInitialization("RX frequency was not acknowledged")
        }

        // VFO-B = TX (uplink)
        Log.d(tag, "Split init: selecting VFO-B for TX (uplink)")
        if (!radio.setVfo(vfoA = false)) return failTrackingInitialization("VFO-B selection was not acknowledged")
        if (txBase != null) {
            Log.d(tag, "Split init: VFO-B band for ${txBase}Hz")
            if (!radio.setBand(txBase)) return failTrackingInitialization("TX band was not acknowledged")
            Log.d(tag, "Split init: VFO-B freq=${txBase}Hz")
            if (!radio.setFrequency(txBase)) return failTrackingInitialization("TX frequency was not acknowledged")
        }
        if (!radio.setSplitModes(rxMode = rxMode, txMode = txMode)) {
            return failTrackingInitialization("Split VFO modes were not acknowledged or verified")
        }
        if (txMode?.uppercase() == "FM") {
            val tone = _state.value.ctcssTone
            if (tone != null) {
                Log.d(tag, "Split init: CTCSS=${tone}Hz")
                if (!radio.setCtcssTone(tone) || !radio.setCtcssMode(true)) {
                    return failTrackingInitialization("CTCSS command was not acknowledged")
                }
            } else {
                if (!radio.setCtcssMode(false)) return failTrackingInitialization("CTCSS OFF was not acknowledged")
            }
        }

        // Enable SPLIT on VFO-A (return display to RX VFO first)
        Log.d(tag, "Split init: returning to VFO-A, then enabling SPLIT mode")
        if (!radio.setVfo(vfoA = true) || !radio.setSplitMode(enabled = true)) {
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
        Log.i(tag, "IC-705 split init done — entering tracking loop")

        // ── Tracking loop with tuning detection ─────────────────────────────
        var lastSetTxFreq = 0.0
        var lastSetRxFreq = 0.0
        var tuningRadio   = ""  // "tx" or "rx" when manual tuning detected
        var lastReadFreq  = 0L
        var stableCount   = 0

        while (currentCoroutineContext().isActive) {
            val currentState = _state.value
            if (!currentState.isActive) break

            val satPass = currentState.currentPass ?: break
            val xpdr    = currentState.selectedTransponder ?: break
            var txBaseFreq = currentState.txBaseFrequencyHz
            val stationPos = settingsRepo.stationPosition.value
            val now = clock.nowMillis()
            if (now >= satPass.losTime) {
                if (hasTransmitClaim) emergencyPttOff()
                _state.update {
                    it.copy(
                        isActive = false,
                        trackingPhase = TrackingPhase.IDLE,
                        lastCommandError = "Satellite pass reached LOS"
                    )
                }
                break
            }
            val pos = satelliteRepo.getPosition(satPass.orbitalObject, stationPos, now)
            val v = pos.distanceRate * 1000.0

            val transmitFrozen = hasTransmitClaim
            if (!transmitFrozen && tuningRadio.isNotEmpty()) {
                // User is tuning — wait for frequency to stabilize
                val readFreq = if (tuningRadio == "tx") radio.readTxVfoFrequency() else radio.readWorkingFrequency()
                if (readFreq != null) {
                    if (kotlin.math.abs(readFreq - lastReadFreq) <= 20) stableCount++
                    else { stableCount = 0; lastReadFreq = readFreq }

                    if (stableCount >= 2) {
                        // Frequency stable — reverse-calculate base frequency
                        if (tuningRadio == "tx" && txBaseFreq != null) {
                            val newBase = (readFreq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT + v)).toLong()
                            if (newBase > 0) {
                                txBaseFreq = newBase
                                _state.update { it.copy(txBaseFrequencyHz = newBase) }
                                Log.i(tag, "Split TX tuning done → base=$newBase")
                            }
                        } else if (tuningRadio == "rx") {
                            val rxNominal = (readFreq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT - v)).toLong()
                            val newTxBase = TransponderMapper.mapDownlinkToUplink(rxNominal, xpdr)
                            if (newTxBase != null && newTxBase > 0) {
                                txBaseFreq = newTxBase
                                _state.update { it.copy(txBaseFrequencyHz = newTxBase) }
                                Log.i(tag, "Split RX tuning done → txBase=$newTxBase")
                            }
                        }
                        tuningRadio   = ""
                        stableCount   = 0
                        lastSetTxFreq = 0.0
                        lastSetRxFreq = 0.0
                    }
                }
            } else if (!transmitFrozen) {
                // Detect manual dial changes
                if (txBaseFreq != null && lastSetTxFreq > 0.0) {
                    val readTx = radio.readTxVfoFrequency()
                    if (readTx != null && kotlin.math.abs(readTx - lastSetTxFreq) >= 20.0) {
                        tuningRadio  = "tx"
                        lastReadFreq = readTx
                        stableCount  = 0
                        Log.i(tag, "Split TX tuning detected (read=${readTx}, lastSet=$lastSetTxFreq)")
                    }
                }
                if (tuningRadio.isEmpty() && lastSetRxFreq > 0.0) {
                    val readRx = radio.readWorkingFrequency()
                    if (readRx != null && kotlin.math.abs(readRx - lastSetRxFreq) >= 20.0) {
                        tuningRadio  = "rx"
                        lastReadFreq = readRx
                        stableCount  = 0
                        Log.i(tag, "Split RX tuning detected (read=${readRx}, lastSet=$lastSetRxFreq)")
                    }
                }
            }

            // Determine Doppler-corrected frequencies
            val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
            val rxBaseCalc  = if (txBaseFreq != null) {
                TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
            } else xpdr.downlinkLow
            val rxRadioFreq = rxBaseCalc?.let { pos.getDownlinkFreq(it) }

            if (radio.isConnected && tuningRadio.isEmpty() && !transmitFrozen) {
                // Update both VFOs every cycle — no PTT polling needed.
                // 0x25/00 = active (RX) VFO, 0x25/01 = inactive (TX) VFO.
                if (rxRadioFreq != null) {
                    Log.d(tag, "Split loop RX (0x25/00): ${rxRadioFreq}Hz")
                    if (radio.setWorkingFrequency(rxRadioFreq)) lastSetRxFreq = rxRadioFreq.toDouble()
                    else failTrackingCommand("RX frequency was not acknowledged")
                }
                if (activeTxLease == null && txRadioFreq != null) {
                    Log.d(tag, "Split loop TX (0x25/01): ${txRadioFreq}Hz")
                    if (radio.setTxVfoFrequency(txRadioFreq)) lastSetTxFreq = txRadioFreq.toDouble()
                    else failTrackingCommand("TX frequency was not acknowledged")
                }
            }

            _state.update {
                val frozenLease = activeTxLease
                it.copy(
                    txConnected   = radio.isConnected,
                    rxConnected   = radio.isConnected,
                    txFrequencyHz = frozenLease?.effectiveTxFrequencyHz ?: txRadioFreq,
                    rxFrequencyHz = rxRadioFreq,
                    nominalTxFrequencyHz = txBaseFreq,
                    nominalRxFrequencyHz = rxBaseCalc,
                    txDopplerCorrectionHz = frozenLease?.txDopplerCorrectionHz
                        ?: dopplerCorrection(txRadioFreq, txBaseFreq),
                    rxDopplerCorrectionHz = dopplerCorrection(rxRadioFreq, rxBaseCalc),
                    azimuth       = Math.toDegrees(pos.azimuth),
                    elevation     = Math.toDegrees(pos.elevation),
                    distance      = pos.distance
                )
            }
            delay(1000)
        }
    }

    // ── Other IRadioTrackingService methods ─────────────────────────────────

    override fun stopTracking() {
        txController?.invalidatePendingPttOn()
        trackingJob?.cancel()
        trackingJob = null
        _state.update { it.copy(isActive = false, trackingPhase = TrackingPhase.IDLE) }
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
        }
    }

    override fun setTransponder(transponder: SatRadio) {
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
            val tx = txController
            val rx = rxController
            val rxMode = transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }
            val split = _state.value.splitMode
            val txModeOk: Boolean
            val rxModeOk: Boolean
            if (split) {
                val modesOk = tx?.setSplitModes(rxMode, transponder.uplinkMode) ?: false
                txModeOk = modesOk
                rxModeOk = modesOk
            } else {
                txModeOk = transponder.uplinkMode?.let { tx?.setMode(it) } ?: true
                rxModeOk = rxMode?.let { mode -> rx?.setMode(mode) } ?: true
            }
            if (transponder.uplinkMode?.uppercase() == "FM") {
                _state.value.ctcssTone?.let { tone ->
                    if (tx?.setCtcssTone(tone) != true || tx.setCtcssMode(true) != true) {
                        return@launch failTrackingCommand("CTCSS command was not acknowledged")
                    }
                }
            }
            if (txModeOk != false && rxModeOk != false) updateSelectedTransponder(transponder, rxMode)
            else failTrackingCommand("Radio mode command was not acknowledged")
        }
    }

    override fun setTxBaseFrequency(frequencyHz: Long) {
        if (hasTransmitClaim) {
            appScope.launch {
                emergencyPttOff()
                _state.update { it.copy(txBaseFrequencyHz = frequencyHz) }
            }
            return
        }
        _state.update { it.copy(txBaseFrequencyHz = frequencyHz) }
    }

    override fun adjustTxBaseFrequency(deltaHz: Long) {
        val current = _state.value.txBaseFrequencyHz ?: return
        setTxBaseFrequency(current + deltaHz)
    }

    override fun setCtcssTone(toneHz: Double?) {
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
            val tx = txController
            val acknowledged = if (toneHz != null) {
                tx?.setCtcssTone(toneHz) == true && tx.setCtcssMode(true)
            } else {
                tx?.setCtcssMode(false) == true
            }
            if (acknowledged) _state.update { it.copy(ctcssTone = toneHz) }
            else failTrackingCommand("CTCSS command was not acknowledged")
        }
    }

    override fun setMode(txMode: String, rxMode: String) {
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
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
                txFrequencyHz = txCenter,
                rxFrequencyHz = rxNominal,
                nominalTxFrequencyHz = txCenter,
                nominalRxFrequencyHz = rxNominal,
                txMode = transponder.uplinkMode,
                rxMode = rxMode
            )
        }
    }

    private fun failTrackingCommand(message: String) {
        _state.update { it.copy(lastCommandError = message, errorMessage = message) }
    }

    private fun failTrackingInitialization(message: String) {
        _state.update {
            it.copy(
                isActive = false,
                trackingPhase = TrackingPhase.ERROR,
                lastCommandError = message,
                errorMessage = message
            )
        }
    }

    private companion object {
        const val PTT_COMMAND_TIMEOUT_MILLIS = 2_500L
        const val MAX_WAVEFORM_MILLIS = 6_000L
        const val MAX_PTT_MILLIS = 12_000L
        const val MAX_AUTOMATIC_START_LATENESS_MILLIS = 150L
    }
}

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
