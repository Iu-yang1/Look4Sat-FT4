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
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import kotlinx.coroutines.CoroutineScope
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
    private val controllerFactory: ((Boolean, String) -> IRadioController)? = null
) : IRadioTrackingService, IFt4TransmitCoordinator {

    private val tag = "RadioTracking"
    /** Delay between each step of the split-mode init sequence (ms). */
    private val INIT_STEP_DELAY_MS = 200L
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
        val isIcom     = rcSettings.radioModel == RadioControlSettings.MODEL_ICOM_IC705
        val isSplit    = isIcom && rcSettings.splitMode

        Log.i(tag, "connectRadios model=${rcSettings.radioModel} split=$isSplit TX=$txAddr RX=$rxAddr")
        _state.update { it.copy(splitMode = isSplit, lastCommandError = null) }

        if (isSplit) {
            // Single-radio split mode: only TX slot is used
            if (txAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio address configured in Settings") }
                return
            }
            val tx = makeController(isIcom, txAddr)
            txController = tx
            rxController = null
            _state.update { it.copy(errorMessage = null) }
            val txOk = tx.connect()
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = false,
                    errorMessage = if (!txOk) "Could not connect to radio ($txAddr)" else null
                )
            }
            Log.i(tag, "IC-705 split mode connected: txOk=$txOk")
        } else {
            if (txAddr.isBlank() && rxAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio addresses configured in Settings") }
                return
            }
            val tx = makeController(isIcom, txAddr)
            val rx = makeController(isIcom, rxAddr)
            txController = tx
            rxController = rx
            _state.update { it.copy(errorMessage = null) }
            val txOk = if (txAddr.isNotBlank()) tx.connect() else false
            val rxOk = if (rxAddr.isNotBlank()) rx.connect() else false
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = rxOk,
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

    private fun makeController(isIcom: Boolean, address: String): IRadioController =
        controllerFactory?.invoke(isIcom, address) ?: if (isIcom) {
            Ic705Controller(checkNotNull(bluetoothManager), address)
        } else {
            Ft817Controller(checkNotNull(bluetoothManager), address)
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
                pttState = PttState.OFF,
                commandBusy = false,
                txLeaseId = null
            )
        }
    }

    // ── FT4 transmit lease ──────────────────────────────────────────────────

    override suspend fun beginTransmit(request: TxRequest): TxLease = transmitMutex.withLock {
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
            check(pass.catNum == request.expectedSatelliteCatalogNumber) { "Satellite context changed" }
            check(transponder.uuid == request.expectedTransponderUuid) { "Transponder context changed" }
            val midpoint = request.waveformStartUtcMillis + request.waveformDurationMillis / 2L
            check(midpoint in pass.aosTime..pass.losTime) { "FT4 waveform midpoint is outside the pass" }
            val nominal = current.txBaseFrequencyHz ?: transponder.uplinkCenterFrequency()
            checkNotNull(nominal) { "The selected transponder has no uplink frequency" }
            val position = satelliteRepo.getPosition(
                pass.orbitalObject,
                settingsRepo.stationPosition.value,
                midpoint
            )
            check(position.elevation.isFinite() && position.elevation > 0.0) {
                "Satellite is below the horizon at the waveform midpoint"
            }
            val effectiveFrequency = position.getUplinkFreq(nominal)
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            check(controller.isConnected) { "TX radio is not connected" }

            val split = settingsRepo.radioControlSettings.value.splitMode &&
                settingsRepo.radioControlSettings.value.radioModel == RadioControlSettings.MODEL_ICOM_IC705
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
                waveformMidpointUtcMillis = midpoint,
                maximumPttMillis = request.maximumPttMillis.coerceAtMost(MAX_PTT_MILLIS)
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
            transmitPreparing = false
            updateCommandState(busy = false)
        }
    }

    override suspend fun confirmTransmitReady(lease: TxLease) = transmitMutex.withLock {
        check(activeTxLease == lease) { "Transmit lease is stale" }
        updateCommandState(busy = true, pttState = PttState.ARMING)
        try {
            val controller = checkNotNull(txController) { "TX radio is not connected" }
            val confirmed = withTimeoutOrNull(PTT_COMMAND_TIMEOUT_MILLIS) { controller.pttOn() } == true
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
            updateCommandState(busy = false)
        }
    }

    override suspend fun endTransmit(lease: TxLease) = transmitMutex.withLock {
        if (activeTxLease != lease) return@withLock
        updateCommandState(busy = true)
        try {
            forcePttOffLocked(null)
        } finally {
            updateCommandState(busy = false)
        }
    }

    override suspend fun emergencyPttOff() = transmitMutex.withLock {
        updateCommandState(busy = true)
        try {
            forcePttOffLocked(null)
        } finally {
            updateCommandState(busy = false)
        }
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
        if (hasTransmitClaim) appScope.launch { emergencyPttOff() }
        _state.update {
            it.copy(
                isActive             = true,
                currentPass          = pass,
                selectedTransponder  = transponder,
                txBaseFrequencyHz    = txBaseFreqHz
            )
        }
        trackingJob?.cancel()

        val rcSettings = settingsRepo.radioControlSettings.value
        val isIcom     = rcSettings.radioModel == RadioControlSettings.MODEL_ICOM_IC705
        val isSplit    = isIcom && rcSettings.splitMode

        if (isSplit) {
            trackingJob = appScope.launch { runSplitTracking(transponder, txBaseFreqHz) }
        } else {
            trackingJob = appScope.launch { runDualRadioTracking(transponder, txBaseFreqHz) }
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
            tx.setMode(txMode)
        }
        if (rx != null && rx.isConnected && rxMode != null) {
            Log.d(tag, "Setting RX mode: $rxMode")
            rx.setMode(rxMode)
        }
        if (txMode?.uppercase() == "FM") {
            _state.value.ctcssTone?.let { tone ->
                Log.d(tag, "Setting CTCSS: ${tone}Hz")
                tx?.setCtcssTone(tone)
                tx?.setCtcssMode(true)
            }
        }
        _state.update { it.copy(txMode = txMode, rxMode = rxMode) }

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
            val now = System.currentTimeMillis()
            if (now >= satPass.losTime) {
                if (hasTransmitClaim) emergencyPttOff()
                _state.update { it.copy(isActive = false, lastCommandError = "Satellite pass reached LOS") }
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
                    txNow.setFrequency(txRadioFreq)
                    lastSetTxFreq = txRadioFreq.toDouble()
                }
                if (rxNow != null && rxNow.isConnected && rxRadioFreq != null) {
                    rxNow.setFrequency(rxRadioFreq)
                    lastSetRxFreq = rxRadioFreq.toDouble()
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
        val radio = txController ?: return
        if (!radio.isConnected) return

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
        radio.setVfo(vfoA = true)
        if (rxNominal != null) {
            Log.d(tag, "Split init: VFO-A band for ${rxNominal}Hz")
            radio.setBand(rxNominal)
            Log.d(tag, "Split init: VFO-A freq=${rxNominal}Hz")
            radio.setFrequency(rxNominal)
        }
        if (rxMode != null) {
            Log.d(tag, "Split init: VFO-A mode=$rxMode")
            radio.setMode(rxMode)
        }

        // VFO-B = TX (uplink)
        Log.d(tag, "Split init: selecting VFO-B for TX (uplink)")
        radio.setVfo(vfoA = false)
        if (txBase != null) {
            Log.d(tag, "Split init: VFO-B band for ${txBase}Hz")
            radio.setBand(txBase)
            Log.d(tag, "Split init: VFO-B freq=${txBase}Hz")
            radio.setFrequency(txBase)
        }
        if (txMode != null) {
            Log.d(tag, "Split init: VFO-B mode=$txMode")
            radio.setMode(txMode)
        }
        if (txMode?.uppercase() == "FM") {
            val tone = _state.value.ctcssTone
            if (tone != null) {
                Log.d(tag, "Split init: CTCSS=${tone}Hz")
                radio.setCtcssTone(tone)
                radio.setCtcssMode(true)
            } else {
                radio.setCtcssMode(false)
            }
        }

        // Enable SPLIT on VFO-A (return display to RX VFO first)
        Log.d(tag, "Split init: returning to VFO-A, then enabling SPLIT mode")
        radio.setVfo(vfoA = true)
        radio.setSplitMode(enabled = true)

        _state.update { it.copy(txMode = txMode, rxMode = rxMode, txBaseFrequencyHz = txBase) }
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
            val now = System.currentTimeMillis()
            if (now >= satPass.losTime) {
                if (hasTransmitClaim) emergencyPttOff()
                _state.update { it.copy(isActive = false, lastCommandError = "Satellite pass reached LOS") }
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
                    radio.setWorkingFrequency(rxRadioFreq)
                    lastSetRxFreq = rxRadioFreq.toDouble()
                }
                if (activeTxLease == null && txRadioFreq != null) {
                    Log.d(tag, "Split loop TX (0x25/01): ${txRadioFreq}Hz")
                    radio.setTxVfoFrequency(txRadioFreq)
                    lastSetTxFreq = txRadioFreq.toDouble()
                }
            }

            _state.update {
                val frozenLease = activeTxLease
                it.copy(
                    txConnected   = radio.isConnected,
                    rxConnected   = false,  // single radio
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
        trackingJob?.cancel()
        trackingJob = null
        _state.update { it.copy(isActive = false) }
        if (hasTransmitClaim) appScope.launch { emergencyPttOff() }
    }

    override fun setTransponder(transponder: SatRadio) {
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
            val tx = txController
            val rx = rxController
            transponder.uplinkMode?.let { tx?.setMode(it) }
            val rxMode = transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }
            rxMode?.let { rx?.setMode(it) }
            if (transponder.uplinkMode?.uppercase() == "FM") {
                _state.value.ctcssTone?.let { tone ->
                    tx?.setCtcssTone(tone)
                    tx?.setCtcssMode(true)
                }
            }
        }
        val txCenter = when {
            transponder.uplinkLow != null && transponder.uplinkHigh != null ->
                (transponder.uplinkLow!! + transponder.uplinkHigh!!) / 2
            transponder.uplinkLow != null -> transponder.uplinkLow!!
            else -> null
        }
        val rxNominal = if (txCenter != null) {
            TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        } else transponder.downlinkLow
        _state.update {
            it.copy(
                selectedTransponder = transponder,
                txBaseFrequencyHz   = txCenter,
                txFrequencyHz       = txCenter,
                rxFrequencyHz       = rxNominal,
                nominalTxFrequencyHz = txCenter,
                nominalRxFrequencyHz = rxNominal,
                txMode              = transponder.uplinkMode,
                rxMode              = transponder.downlinkMode
                    ?: transponder.uplinkMode?.let { m ->
                        TransponderMapper.mapUplinkModeToDownlinkMode(m, transponder.isInverted)
                    }
            )
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
        if (hasTransmitClaim) appScope.launch { emergencyPttOff() }
        _state.update { it.copy(ctcssTone = toneHz) }
        appScope.launch {
            val tx = txController
            if (toneHz != null) {
                tx?.setCtcssTone(toneHz)
                tx?.setCtcssMode(true)
            } else {
                tx?.setCtcssMode(false)
            }
        }
    }

    override fun setMode(txMode: String, rxMode: String) {
        appScope.launch {
            if (hasTransmitClaim) emergencyPttOff()
            txController?.setMode(txMode)
            rxController?.setMode(rxMode)
        }
        _state.update { it.copy(txMode = txMode, rxMode = rxMode) }
    }

    private companion object {
        const val PTT_COMMAND_TIMEOUT_MILLIS = 2_500L
        const val MAX_WAVEFORM_MILLIS = 6_000L
        const val MAX_PTT_MILLIS = 12_000L
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
