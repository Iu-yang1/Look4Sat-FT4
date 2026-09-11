package com.rtbishop.look4sat.core.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.logbook.QuickLogError
import com.rtbishop.look4sat.core.domain.logbook.QuickQsoDetails
import com.rtbishop.look4sat.core.domain.logbook.quickLogValidation
import com.rtbishop.look4sat.core.domain.logbook.quickQsoRecord
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

val quickLogModes = listOf("FM", "SSB", "CW")

data class QuickLogState(
    val pass: OrbitalPass? = null,
    val transponders: List<SatRadio> = emptyList(),
    val transponder: SatRadio? = null,
    val callsign: String = "",
    val theirGrid: String = "",
    val sent: String = "59",
    val received: String = "59",
    val mode: String = "FM",
    val saving: Boolean = false,
    val error: QuickLogError? = null,
    val savedCallsign: String = ""
)

class QuickLogViewModel(private val container: IMainContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(QuickLogState())
    val state = mutableState.asStateFlow()
    private var contextPass: OrbitalPass? = null
    private var contextTransponders: List<SatRadio> = emptyList()
    private var contextSelectedUuid: String? = null
    private var loadTranspondersJob: Job? = null
    private var modeChosen = false

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshContext()
                delay(1_000)
            }
        }
    }

    fun setContext(pass: OrbitalPass?, transponders: List<SatRadio>, selectedUuid: String?) {
        contextPass = pass
        contextTransponders = transponders
        contextSelectedUuid = selectedUuid
        refreshContext()
    }

    fun callsign(value: String) = mutableState.update {
        it.copy(callsign = value, error = null, savedCallsign = "")
    }

    fun theirGrid(value: String) = mutableState.update { it.copy(theirGrid = value, error = null) }
    fun sent(value: String) = mutableState.update { it.copy(sent = value, error = null) }
    fun received(value: String) = mutableState.update { it.copy(received = value, error = null) }

    fun mode(value: String) {
        if (value !in quickLogModes) return
        modeChosen = true
        mutableState.update { previous ->
            previous.copy(
                mode = value,
                sent = updateDefaultReport(previous.sent, previous.mode, value),
                received = updateDefaultReport(previous.received, previous.mode, value),
                error = null
            )
        }
    }

    private fun refreshContext() {
        val now = container.disciplinedClock.nowMillis()
        val tracking = container.radioTrackingService.state.value
        val passes = container.satelliteRepo.passes.value
        val current = passes.firstOrNull { it.aosTime <= now && (it.isDeepSpace || it.losTime >= now) }
        val previous = mutableState.value
        val next = if (previous.callsign.isNotBlank() || previous.saving) previous.pass else {
            contextPass ?: tracking.currentPass?.takeIf { tracking.isActive } ?: current
        }
        val passChanged = passKey(previous.pass) != passKey(next)
        mutableState.update {
            it.copy(
                pass = next,
                error = it.error.takeUnless { error -> error == QuickLogError.SATELLITE && next != null }
            )
        }
        if (passChanged) {
            modeChosen = false
            loadPassDefaults(next)
        } else {
            refreshAutomaticValues()
        }
    }

    private fun loadPassDefaults(pass: OrbitalPass?) {
        loadTranspondersJob?.cancel()
        if (pass == null) {
            mutableState.update { it.copy(transponders = emptyList(), transponder = null) }
            return
        }
        loadTranspondersJob = viewModelScope.launch {
            val channels = if (passKey(pass) == passKey(contextPass) && contextTransponders.isNotEmpty()) {
                contextTransponders
            } else {
                container.satelliteRepo.getRadiosWithId(pass.catNum)
            }
            if (passKey(mutableState.value.pass) != passKey(pass)) return@launch
            applyTransponders(channels)
        }
    }

    private fun refreshAutomaticValues() {
        val state = mutableState.value
        val usesContext = passKey(state.pass) == passKey(contextPass)
        val channels = if (usesContext) contextTransponders else state.transponders
        if (usesContext || channels.isNotEmpty()) applyTransponders(channels)
    }

    private fun applyTransponders(channels: List<SatRadio>) {
        val previous = mutableState.value
        val tracking = container.radioTrackingService.state.value
        val trackingChannel = tracking.selectedTransponder
            ?.takeIf { tracking.isActive && tracking.currentPass?.catNum == previous.pass?.catNum }
        val selected = when {
            passKey(previous.pass) == passKey(contextPass) -> {
                channels.firstOrNull { it.uuid == contextSelectedUuid }
                    ?: channels.firstOrNull { it.uuid == trackingChannel?.uuid }
                    ?: previous.transponder?.let { old -> channels.firstOrNull { it.uuid == old.uuid } }
                    ?: channels.firstOrNull { it.isAlive }
                    ?: channels.firstOrNull()
            }
            else -> channels.firstOrNull { it.uuid == trackingChannel?.uuid }
                ?: previous.transponder?.let { old -> channels.firstOrNull { it.uuid == old.uuid } }
                ?: channels.firstOrNull { it.isAlive }
                ?: channels.firstOrNull()
        }
        val nextMode = if (modeChosen) previous.mode else modeFor(selected, previous.mode)
        mutableState.update {
            it.copy(
                transponders = channels,
                transponder = selected,
                mode = nextMode,
                sent = updateDefaultReport(it.sent, it.mode, nextMode),
                received = updateDefaultReport(it.received, it.mode, nextMode)
            )
        }
    }

    fun save() {
        val snapshot = mutableState.value
        if (snapshot.saving) return
        val validationError = validate(snapshot)
        if (validationError != null) {
            mutableState.update { it.copy(error = validationError) }
            return
        }
        val pass = snapshot.pass ?: return
        val now = container.disciplinedClock.nowMillis()
        val radio = container.radioTrackingService.state.value
        val tracking = radio.takeIf { it.isActive && it.currentPass?.catNum == pass.catNum }
        val channel = snapshot.transponder ?: tracking?.selectedTransponder
        val trackingMatchesChannel = channel != null && tracking?.selectedTransponder?.uuid == channel.uuid
        val tx = tracking?.txFrequencyHz?.takeIf { trackingMatchesChannel }
            ?: centerFrequency(channel?.uplinkLow, channel?.uplinkHigh)
        val rx = tracking?.rxFrequencyHz?.takeIf { trackingMatchesChannel }
            ?: centerFrequency(channel?.downlinkLow, channel?.downlinkHigh)
        val configuredGrid = container.settingsRepo.stationPosition.value.qthLocator
        val myGrid = configuredGrid.takeIf { qthToPosition(it) != null }.orEmpty()
        mutableState.update { it.copy(saving = true, error = null, savedCallsign = "") }
        viewModelScope.launch {
            try {
                val record = quickQsoRecord(
                    now = now,
                    pass = pass,
                    callsign = snapshot.callsign,
                    sent = snapshot.sent,
                    received = snapshot.received,
                    mode = snapshot.mode,
                    myCallsign = container.settingsRepo.ft4Settings.value.operatorCallsign,
                    myGrid = myGrid,
                    radio = radio,
                    transponder = channel,
                    details = QuickQsoDetails(
                        theirGrid = snapshot.theirGrid,
                        txFrequencyHz = tx,
                        rxFrequencyHz = rx,
                        satelliteMode = listOfNotNull(channel?.uplinkMode, channel?.downlinkMode)
                            .filter(String::isNotBlank)
                            .joinToString("/")
                    )
                )
                container.qsoRepository.save(record)
                mutableState.update {
                    it.copy(
                        callsign = "",
                        theirGrid = "",
                        savedCallsign = record.theirCallsign
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = QuickLogError.STORAGE) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    private fun validate(state: QuickLogState): QuickLogError? {
        if (state.pass == null) return QuickLogError.SATELLITE
        quickLogValidation(state.callsign, state.sent, state.received, state.mode)?.let { return it }
        if (state.theirGrid.isNotBlank() && qthToPosition(state.theirGrid) == null) return QuickLogError.GRID
        return null
    }

    private fun modeFor(transponder: SatRadio?, fallback: String): String {
        val tracking = container.radioTrackingService.state.value
        val value = tracking.txMode
            ?.takeIf { tracking.isActive && tracking.currentPass?.catNum == mutableState.value.pass?.catNum }
            ?: transponder?.uplinkMode
            ?: transponder?.downlinkMode
            ?: return fallback
        return when (val normalized = value.uppercase(Locale.US)) {
            "CWR" -> "CW"
            "USB", "LSB" -> "SSB"
            "FMN" -> "FM"
            in quickLogModes -> normalized
            else -> fallback
        }
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory { initializer { QuickLogViewModel(container) } }
    }
}

private fun centerFrequency(low: Long?, high: Long?): Long? = when {
    low != null && high != null -> low + (high - low) / 2L
    else -> low ?: high
}

private fun defaultReport(mode: String): String = if (mode.equals("CW", true)) "599" else "59"

private fun updateDefaultReport(value: String, previousMode: String, nextMode: String): String =
    if (value == defaultReport(previousMode)) defaultReport(nextMode) else value

private fun passKey(pass: OrbitalPass?): Pair<Int, Long>? = pass?.let { it.catNum to it.aosTime }
