/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.ft4

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.ft4.Ft4AutomaticTxIntent
import com.rtbishop.look4sat.core.domain.ft4.Ft4AutomationController
import com.rtbishop.look4sat.core.domain.ft4.Ft4AutomationPhase
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecoderOptions
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionRequest
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecodeResult
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.time.DisciplinedFt4SlotScheduler
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Ft4ViewModel(
    private val container: IMainContainer,
    private val cleanupScope: CoroutineScope
) : ViewModel() {
    private val ft4Service = container.ft4Service
    private val transmitter = container.ft4AudioTransmitter
    private val clock = container.disciplinedClock
    private val radioService = container.radioTrackingService
    private val sensorsRepo = container.provideSensorsRepo()
    private val scheduler = DisciplinedFt4SlotScheduler()
    private val automationController = Ft4AutomationController()
    private var automationJob: Job? = null
    private var manualTransmitJob: Job? = null
    private var generation = 0L
    private var screenActive = false
    private var activeQsoId: Long? = null
    private val loggedDecodeKeys = LinkedHashSet<String>()
    private val qsoMutex = Mutex()

    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
        Ft4State(
            settings = container.settingsRepo.ft4Settings.value,
            capability = ft4Service.capability.value,
            engineState = ft4Service.engineState.value,
            decodeResults = ft4Service.decodeResults.value,
            clock = clock.snapshot(),
            radio = radioService.state.value,
            audioHub = container.audioHub.state.value,
            transmitState = transmitter.state.value,
            stationGrid = container.settingsRepo.stationPosition.value.qthLocator
        )
    )
    private val mutableTimingState = kotlinx.coroutines.flow.MutableStateFlow(
        Ft4TimingState(clock.nowMillis(), scheduler.progress(clock.nowMillis()), clock.snapshot())
    )
    val uiState = mutableState
    val timingState = mutableTimingState
    val spectrumFrames = ft4Service.spectrumFrames

    init {
        collectState()
        collectPhoneOrientation()
        startTicker()
        startRadarTicker()
    }

    fun onAction(action: Ft4Action) {
        when (action) {
            is Ft4Action.MicrophonePermissionChanged -> {
                screenActive = true
                mutableState.update { it.copy(hasMicrophonePermission = action.granted) }
                if (container.settingsRepo.otherSettings.value.stateOfSensors) sensorsRepo.enableSensor()
                if (action.granted) {
                    startReceivingIfAvailable()
                } else {
                    cleanupScope.launch {
                        ft4Service.stopReceiving()
                        container.audioHub.stopAll()
                    }
                }
            }
            Ft4Action.ToggleReceiving -> toggleReceiving()
            Ft4Action.ClearDecodes -> ft4Service.clearDecodeResults()
            is Ft4Action.SelectAudioFrequency -> mutableState.update {
                it.copy(selectedAudioFrequencyHz = action.frequencyHz.coerceIn(0f, 3_000f))
            }
            is Ft4Action.SetTargetCall -> {
                if (automationController.snapshot.phase.isRunning()) stopAutomation()
                if (!action.callsign.equals(mutableState.value.targetCall, ignoreCase = true)) activeQsoId = null
                mutableState.update { it.copy(targetCall = action.callsign.uppercase(Locale.US)) }
            }
            is Ft4Action.SelectDecode -> {
                val sourceCall = action.result.sourceCall.trim().uppercase(Locale.US)
                if (sourceCall.isBlank()) return
                if (automationController.snapshot.phase.isRunning()) stopAutomation()
                val decodedParity = Math.floorMod(
                    Math.floorDiv(action.result.slotUtcMillis, FT4_SLOT_MILLIS),
                    2L
                ).toInt()
                mutableState.update {
                    it.copy(
                        targetCall = sourceCall,
                        txSlotParity = 1 - decodedParity
                    )
                }
                viewModelScope.launch { updateQsoFromDecode(action.result, automatic = false) }
            }
            is Ft4Action.SetTxSlotParity -> mutableState.update {
                it.copy(txSlotParity = action.parity.coerceIn(0, 1))
            }
            Ft4Action.ManualTransmit -> startManualTransmit()
            Ft4Action.StopTransmit -> viewModelScope.launch {
                manualTransmitJob?.cancel()
                transmitter.stop()
            }
            Ft4Action.ArmAutomation -> armAutomation()
            Ft4Action.StopAutomation -> stopAutomation()
            Ft4Action.ConnectRadios -> viewModelScope.launch { radioService.connectRadios() }
            Ft4Action.DisconnectRadios -> viewModelScope.launch {
                stopAutomationNow("Radio disconnected")
                radioService.disconnectRadios()
            }
            Ft4Action.ToggleTracking -> toggleTracking()
            Ft4Action.EmergencyStop -> viewModelScope.launch {
                stopAutomationNow("Emergency stop")
                manualTransmitJob?.cancel()
                transmitter.emergencyStop()
            }
            Ft4Action.ClearError -> mutableState.update { it.copy(error = "") }
            Ft4Action.Leave -> closeOperations()
        }
    }

    private fun collectState() {
        viewModelScope.launch {
            container.settingsRepo.ft4Settings.collect { settings ->
                mutableState.update { it.copy(settings = settings) }
                if (!settings.decodeEnabled) {
                    stopAutomationNow("FT4 disabled")
                    ft4Service.stopReceiving()
                    transmitter.emergencyStop()
                } else if (screenActive) {
                    startReceivingIfAvailable()
                }
            }
        }
        viewModelScope.launch {
            container.settingsRepo.stationPosition.collect { position ->
                mutableState.update { it.copy(stationGrid = position.qthLocator) }
            }
        }
        viewModelScope.launch {
            container.settingsRepo.otherSettings.collect { settings ->
                mutableState.update {
                    it.copy(
                        shouldUseCompass = settings.stateOfSensors,
                        shouldShowSweep = settings.stateOfSweep
                    )
                }
                if (settings.stateOfSensors) sensorsRepo.enableSensor() else sensorsRepo.disableSensor()
            }
        }
        viewModelScope.launch {
            combine(
                container.satelliteRepo.passes,
                container.satelliteRepo.selectedPass
            ) { passes, selection ->
                val (catalogNumber, aosTime) = selection
                passes.firstOrNull { it.catNum == catalogNumber && it.aosTime == aosTime }
                    ?: passes.firstOrNull { it.catNum == catalogNumber }
            }.collectLatest { pass ->
                mutableState.update {
                    it.copy(selectedPass = pass, orbitalPosition = null, satelliteTrack = emptyList())
                }
                if (pass != null && !pass.isDeepSpace) {
                    val track = runCatching {
                        container.satelliteRepo.getTrack(
                            pass.orbitalObject,
                            container.settingsRepo.stationPosition.value,
                            pass.aosTime,
                            pass.losTime
                        )
                    }.getOrDefault(emptyList())
                    mutableState.update { current ->
                        if (current.trackingPass?.catNum == pass.catNum) {
                            current.copy(satelliteTrack = track)
                        } else current
                    }
                }
            }
        }
        viewModelScope.launch {
            ft4Service.capability.collect { value ->
                mutableState.update { it.copy(capability = value) }
                if (screenActive) startReceivingIfAvailable()
            }
        }
        viewModelScope.launch {
            ft4Service.engineState.collect { value -> mutableState.update { it.copy(engineState = value) } }
        }
        viewModelScope.launch {
            ft4Service.decodeResults.collect { results ->
                mutableState.update { it.copy(decodeResults = results) }
                val session = automationController.snapshot
                if (!session.phase.isRunning()) return@collect
                val slot = scheduler.boundaryAt(clock.nowMillis()).index
                results.forEach { result ->
                    val before = automationController.snapshot
                    val after = automationController.onDecode(session.generation, result, slot)
                    if (after != before) updateQsoFromDecode(result, automatic = true)
                }
                val updated = automationController.snapshot
                mutableState.update {
                    it.copy(
                        automation = updated,
                        targetCall = updated.targetCall.ifBlank { it.targetCall },
                        txSlotParity = updated.txSlotParity ?: it.txSlotParity
                    )
                }
            }
        }
        viewModelScope.launch {
            radioService.state.collect { radio ->
                mutableState.update { it.copy(radio = radio) }
                val automation = automationController.snapshot
                if (automation.phase.isRunning()) {
                    val mode = radio.txMode.orEmpty()
                    val band = bandKey(radio.nominalTxFrequencyHz)
                    if (!radio.isActive || !radio.txConnected || radio.currentPass == null ||
                        radio.selectedTransponder == null
                    ) {
                        stopAutomationNow("Satellite or radio context is unavailable")
                    } else if (mode != automation.mode || band != automation.band) {
                        stopAutomationNow("FT4 operating context changed")
                    }
                }
            }
        }
        viewModelScope.launch {
            container.audioHub.state.collect { value -> mutableState.update { it.copy(audioHub = value) } }
        }
        viewModelScope.launch {
            transmitter.state.collect { value -> mutableState.update { it.copy(transmitState = value) } }
        }
    }

    private fun collectPhoneOrientation() = viewModelScope.launch {
        sensorsRepo.sensorData.collect { orientation ->
            val declination = sensorsRepo.getMagDeclination(container.settingsRepo.stationPosition.value)
            mutableState.update {
                it.copy(orientationValues = (orientation.first + declination) to orientation.second)
            }
        }
    }

    private fun startTicker() = viewModelScope.launch {
        var tick = 0
        while (isActive) {
            val now = clock.nowMillis()
            val snapshot = clock.refresh()
            mutableTimingState.value = Ft4TimingState(now, scheduler.progress(now), snapshot)
            if (tick++ % 10 == 0) mutableState.update { it.copy(clock = snapshot) }
            delay(100L)
        }
    }

    private fun startRadarTicker() = viewModelScope.launch {
        while (isActive) {
            val pass = mutableState.value.trackingPass
            if (pass != null) {
                runCatching {
                    container.satelliteRepo.getPosition(
                        pass.orbitalObject,
                        container.settingsRepo.stationPosition.value,
                        clock.nowMillis()
                    )
                }.onSuccess { position ->
                    mutableState.update { current ->
                        if (current.trackingPass?.catNum == pass.catNum) {
                            current.copy(orbitalPosition = position)
                        } else current
                    }
                }
            }
            delay(1_000L)
        }
    }

    private fun toggleReceiving() {
        val state = mutableState.value
        if (state.isReceiving) {
            viewModelScope.launch { ft4Service.stopReceiving() }
            return
        }
        when {
            !state.settings.decodeEnabled -> setError("FT4 decoding is disabled in Settings")
            !state.capability.receiveAvailable -> setError(state.capability.unavailableReason)
            !state.hasMicrophonePermission -> setError("Microphone permission is required")
            else -> ft4Service.startReceiving(
                state.decoderOptions(),
                state.settings.operatorCallsign
            )
        }
    }

    private fun startReceivingIfAvailable() {
        val state = mutableState.value
        if (!screenActive || state.isReceiving || !state.settings.decodeEnabled ||
            !state.capability.receiveAvailable || !state.hasMicrophonePermission
        ) {
            return
        }
        ft4Service.startReceiving(
            state.decoderOptions(),
            state.settings.operatorCallsign
        )
    }

    private fun startManualTransmit() {
        if (manualTransmitJob?.isActive == true || automationJob?.isActive == true) return
        val state = mutableState.value
        val message = runCatching { buildInitialMessage(state) }
            .getOrElse { return setError(it.message.orEmpty()) }
        val nextSlot = nextSlot(state.txSlotParity)
        mutableState.update {
            it.copy(
                manualTimeWarning = !clock.automaticFt4TransmitAllowed(),
                error = ""
            )
        }
        manualTransmitJob = viewModelScope.launch {
            try {
                runTransmission(message, nextSlot.startUtcMillis, ++generation, automatic = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                setError(error.message.orEmpty())
            }
        }
    }

    private fun armAutomation() {
        if (automationJob?.isActive == true) return
        val state = mutableState.value
        val radio = state.radio
        when {
            !state.settings.decodeEnabled -> return setError("FT4 decoding is disabled in Settings")
            !state.capability.receiveAvailable -> return setError(state.capability.unavailableReason)
            !state.capability.transmitAvailable -> return setError(state.capability.unavailableReason)
            !state.hasMicrophonePermission -> return setError("Microphone permission is required")
            !clock.automaticFt4TransmitAllowed() -> return setError(clock.automaticFt4TransmitBlockReason())
            !radio.isActive || !radio.txConnected -> return setError("Satellite tracking and TX radio are required")
            radio.currentPass == null || radio.selectedTransponder == null -> return setError("Select a pass and transponder first")
            state.settings.operatorCallsign.isBlank() -> return setError("Operator callsign is required")
            state.grid4.isBlank() -> return setError("Station Maidenhead grid is required")
            radio.txMode.isNullOrBlank() -> return setError("TX radio mode is unavailable")
        }
        val next = scheduler.nextBoundaryAfter(clock.nowMillis())
        val sessionGeneration = ++generation
        val automation = runCatching {
            automationController.arm(
                generation = sessionGeneration,
                mode = radio.txMode.orEmpty(),
                band = bandKey(radio.nominalTxFrequencyHz),
                myCall = state.settings.operatorCallsign,
                targetCall = state.targetCall,
                grid = state.grid4,
                nextSlotIndex = next.index
            )
        }.getOrElse { return setError(it.message.orEmpty()) }
        if (!state.isReceiving) {
            ft4Service.startReceiving(
                state.decoderOptions(),
                state.settings.operatorCallsign
            )
        }
        mutableState.update { it.copy(automation = automation, txSlotParity = automation.txSlotParity ?: 0, error = "") }
        automationJob = viewModelScope.launch { automaticLoop(sessionGeneration) }
    }

    private suspend fun automaticLoop(sessionGeneration: Long) {
        try {
            while (kotlin.coroutines.coroutineContext.isActive && automationController.snapshot.phase.isRunning()) {
                val state = mutableState.value
                val radio = state.radio
                val pass = radio.currentPass
                if (!state.settings.decodeEnabled || !clock.automaticFt4TransmitAllowed() ||
                    !radio.isActive || !radio.txConnected || pass == null ||
                    radio.selectedTransponder == null
                ) {
                    stopAutomationNow(clock.automaticFt4TransmitBlockReason().ifBlank { "FT4 automatic TX gate closed" })
                    break
                }
                val next = scheduler.nextBoundaryAfter(clock.nowMillis())
                val remaining = next.startUtcMillis - clock.nowMillis()
                if (remaining <= CLAIM_AHEAD_MILLIS) {
                    val intent = automationController.claimTransmit(sessionGeneration, next.index)
                    if (intent != null) {
                        mutableState.update { it.copy(automation = automationController.snapshot) }
                        val succeeded = try {
                            runTransmission(intent.message, next.startUtcMillis, sessionGeneration, automatic = true)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            setError(error.message.orEmpty())
                            false
                        }
                        automationController.transmissionFinished(sessionGeneration, intent, succeeded)
                        val completed = automationController.snapshot.phase == Ft4AutomationPhase.COMPLETE
                        mutableState.update { it.copy(automation = automationController.snapshot) }
                        if (completed) finishActiveQso(QsoStatus.COMPLETE)
                        if (!succeeded) break
                    }
                }
                delay(AUTOMATION_POLL_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            automationController.abort(sessionGeneration, error.message ?: error.javaClass.simpleName)
            mutableState.update { it.copy(automation = automationController.snapshot, error = error.message.orEmpty()) }
            transmitter.emergencyStop()
        }
    }

    private suspend fun runTransmission(
        message: String,
        slotStartUtcMillis: Long,
        sessionGeneration: Long,
        automatic: Boolean
    ) {
        val state = mutableState.value
        val radio = state.radio
        check(state.settings.decodeEnabled) { "FT4 is disabled" }
        check(state.capability.transmitAvailable) { state.capability.unavailableReason }
        val pass = checkNotNull(radio.currentPass) { "No satellite pass is selected" }
        val transponder = checkNotNull(radio.selectedTransponder) { "No transponder is selected" }
        val validation = ft4Service.validateMessage(message)
        check(validation.valid) { validation.error }
        appendTransmittedMessage(validation.normalizedMessage, automatic)
        transmitter.transmit(
            Ft4TransmissionRequest(
                message = validation.normalizedMessage,
                audioFrequencyHz = state.selectedAudioFrequencyHz,
                slotStartUtcMillis = slotStartUtcMillis,
                sessionGeneration = sessionGeneration,
                satelliteCatalogNumber = pass.catNum,
                transponderUuid = transponder.uuid,
                automatic = automatic
            )
        )
    }

    private fun stopAutomation() {
        val sessionGeneration = automationController.snapshot.generation
        automationJob?.cancel()
        automationJob = null
        automationController.stop(sessionGeneration)
        mutableState.update { it.copy(automation = automationController.snapshot) }
        viewModelScope.launch { transmitter.stop() }
    }

    private suspend fun stopAutomationNow(reason: String) {
        val current = automationController.snapshot
        if (current.phase.isRunning()) {
            automationController.abort(current.generation, reason)
            mutableState.update { it.copy(automation = automationController.snapshot) }
            transmitter.emergencyStop()
            finishActiveQso(QsoStatus.ABORTED)
        }
    }

    private suspend fun updateQsoFromDecode(result: Ft4DecodeResult, automatic: Boolean) = qsoMutex.withLock {
        if (!loggedDecodeKeys.add(result.stableId)) return@withLock
        trimLoggedDecodeKeys()
        val state = mutableState.value
        val theirCall = result.sourceCall.trim().uppercase(Locale.US)
        if (theirCall.isBlank()) return@withLock
        val existing = activeQsoId?.let { container.qsoRepository.find(it) }
            ?.takeIf { it.theirCallsign == theirCall }
        val detail = result.gridOrReport.trim().uppercase(Locale.US)
        val base = existing ?: newQsoRecord(state, theirCall, automatic, result.slotUtcMillis)
        val updated = base.copy(
            theirGrid = if (GRID_PATTERN.matches(detail)) detail else base.theirGrid,
            receivedReport = if (REPORT_PATTERN.matches(detail.removePrefix("R"))) detail.removePrefix("R") else base.receivedReport,
            automatic = base.automatic || automatic,
            rawMessages = (base.rawMessages + result.text).distinct(),
            status = if (detail == "73" && automatic) QsoStatus.COMPLETE else base.status,
            endUtcMillis = if (detail == "73" && automatic) result.slotUtcMillis else base.endUtcMillis
        )
        activeQsoId = container.qsoRepository.save(updated)
    }

    private suspend fun appendTransmittedMessage(message: String, automatic: Boolean) = qsoMutex.withLock {
        val state = mutableState.value
        val target = state.targetCall.trim().uppercase(Locale.US)
        if (target.isBlank()) return@withLock
        val base = activeQsoId?.let { container.qsoRepository.find(it) }
            ?.takeIf { it.theirCallsign == target }
            ?: newQsoRecord(state, target, automatic, state.clock.utcMillis)
        val detail = message.trim().split(Regex("\\s+")).lastOrNull().orEmpty().uppercase(Locale.US)
        val sentReport = detail.removePrefix("R").takeIf { REPORT_PATTERN.matches(it) } ?: base.sentReport
        activeQsoId = container.qsoRepository.save(
            base.copy(
                sentReport = sentReport,
                automatic = base.automatic || automatic,
                rawMessages = (base.rawMessages + message).distinct()
            )
        )
    }

    private suspend fun finishActiveQso(status: QsoStatus) = qsoMutex.withLock {
        val id = activeQsoId ?: return@withLock
        val record = container.qsoRepository.find(id) ?: return@withLock
        container.qsoRepository.save(record.copy(status = status, endUtcMillis = clock.nowMillis()))
        activeQsoId = null
    }

    private fun newQsoRecord(state: Ft4State, target: String, automatic: Boolean, start: Long): QsoRecord {
        val radio = state.radio
        val pass = state.trackingPass
        val transponder = radio.selectedTransponder
        return QsoRecord(
            startUtcMillis = start,
            theirCallsign = target,
            myCallsign = state.settings.operatorCallsign,
            myGrid = state.stationGrid,
            txFrequencyHz = radio.txFrequencyHz,
            rxFrequencyHz = radio.rxFrequencyHz,
            band = adifBand(radio.txFrequencyHz),
            rxBand = adifBand(radio.rxFrequencyHz),
            satelliteName = pass?.name.orEmpty(),
            transponderName = transponder?.info.orEmpty(),
            satelliteMode = listOfNotNull(transponder?.uplinkMode, transponder?.downlinkMode)
                .filter(String::isNotBlank).joinToString("/"),
            passAosUtcMillis = pass?.aosTime,
            ft4AudioFrequencyHz = state.selectedAudioFrequencyHz.toInt(),
            automatic = automatic
        )
    }

    private fun trimLoggedDecodeKeys() {
        while (loggedDecodeKeys.size > 256) loggedDecodeKeys.remove(loggedDecodeKeys.first())
    }

    private fun toggleTracking() {
        val state = mutableState.value
        val radio = state.radio
        if (radio.isActive) {
            radioService.stopTracking()
        } else {
            val pass = state.trackingPass ?: return setError("Select a pass in Radar first")
            val transponder = radio.selectedTransponder ?: return setError("Select a transponder in Radar first")
            radioService.startTracking(pass, transponder, radio.txBaseFrequencyHz)
        }
    }

    private fun nextSlot(parity: Int): com.rtbishop.look4sat.core.domain.time.Ft4SlotBoundary {
        var next = scheduler.nextBoundaryAfter(clock.nowMillis())
        if (next.sequence != parity) next = scheduler.boundaryAt(next.endUtcMillis)
        return next
    }

    private fun buildInitialMessage(state: Ft4State): String {
        val myCall = state.settings.operatorCallsign.trim().uppercase(Locale.US)
        check(myCall.isNotBlank()) { "Operator callsign is required" }
        val grid = state.grid4
        check(grid.isNotBlank()) { "Station Maidenhead grid is required" }
        val target = state.targetCall.trim().uppercase(Locale.US)
        return if (target.isBlank()) "CQ $myCall $grid" else "$target $myCall $grid"
    }

    private fun setError(message: String) {
        mutableState.update { it.copy(error = message.ifBlank { "FT4 operation failed" }) }
    }

    private fun closeOperations() {
        screenActive = false
        automationJob?.cancel()
        manualTransmitJob?.cancel()
        sensorsRepo.disableSensor()
        cleanupScope.launch {
            transmitter.emergencyStop()
            ft4Service.stopReceiving()
        }
    }

    override fun onCleared() {
        closeOperations()
    }

    companion object {
        private const val CLAIM_AHEAD_MILLIS = 1_500L
        private const val AUTOMATION_POLL_MILLIS = 100L

        fun factory(container: IMainContainer) = viewModelFactory {
            initializer { Ft4ViewModel(container, container.appScope) }
        }
    }
}

private fun Ft4AutomationPhase.isRunning(): Boolean = this !in setOf(
    Ft4AutomationPhase.IDLE,
    Ft4AutomationPhase.COMPLETE,
    Ft4AutomationPhase.ABORTED
)

private const val FT4_SLOT_MILLIS = 7_500L
private val GRID_PATTERN = Regex("[A-R]{2}\\d{2}(?:[A-X]{2})?", RegexOption.IGNORE_CASE)
private val REPORT_PATTERN = Regex("[+-]\\d{2}")

private fun Ft4State.decoderOptions() = Ft4DecoderOptions(
    decodePassCount = settings.decodeDepth,
    multiDecodeRoundCount = settings.decodeDepth,
    qsoFrequencyHz = selectedAudioFrequencyHz.toInt()
)

private fun bandKey(frequencyHz: Long?): String = frequencyHz?.let { (it / 1_000_000L).toString() }.orEmpty()

private fun adifBand(frequencyHz: Long?): String = when (frequencyHz ?: return "") {
    in 50_000_000L..54_000_000L -> "6m"
    in 144_000_000L..148_000_000L -> "2m"
    in 219_000_000L..225_000_000L -> "1.25m"
    in 420_000_000L..450_000_000L -> "70cm"
    in 902_000_000L..928_000_000L -> "33cm"
    in 1_240_000_000L..1_300_000_000L -> "23cm"
    in 2_300_000_000L..2_450_000_000L -> "13cm"
    else -> ""
}
