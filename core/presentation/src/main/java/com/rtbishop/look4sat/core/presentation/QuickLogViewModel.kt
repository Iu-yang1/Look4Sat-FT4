package com.rtbishop.look4sat.core.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

val quickLogModes = listOf("FM", "SSB", "CW", "AM", "FT4", "FT8", "RTTY")

data class QuickLogState(
    val pass: OrbitalPass? = null,
    val candidates: List<OrbitalPass> = emptyList(),
    val transponders: List<SatRadio> = emptyList(),
    val transponder: SatRadio? = null,
    val utcText: String = "",
    val callsign: String = "",
    val theirGrid: String = "",
    val sent: String = "59",
    val received: String = "59",
    val mode: String = "FM",
    val txFrequencyHz: String = "",
    val rxFrequencyHz: String = "",
    val myCallsign: String = "",
    val myGrid: String = "",
    val status: QsoStatus = QsoStatus.COMPLETE,
    val comment: String = "",
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
    private var chosenPass: OrbitalPass? = null
    private var loadTranspondersJob: Job? = null
    private var modeChosen = false
    private var transponderChosen = false
    private var utcEdited = false
    private var txFrequencyEdited = false
    private var rxFrequencyEdited = false
    private var myCallsignEdited = false
    private var myGridEdited = false

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

    fun selectPass(pass: OrbitalPass) {
        chosenPass = pass
        setPass(pass)
    }

    fun selectTransponder(transponder: SatRadio?) {
        transponderChosen = true
        mutableState.update { previous ->
            val nextMode = modeFor(transponder, previous.mode)
            val selectedMode = if (modeChosen) previous.mode else nextMode
            previous.copy(
                transponder = transponder,
                mode = selectedMode,
                sent = updateDefaultReport(previous.sent, previous.mode, selectedMode),
                received = updateDefaultReport(previous.received, previous.mode, selectedMode),
                txFrequencyHz = if (txFrequencyEdited) previous.txFrequencyHz else defaultTxFrequency(transponder),
                rxFrequencyHz = if (rxFrequencyEdited) previous.rxFrequencyHz else defaultRxFrequency(transponder),
                error = null
            )
        }
    }

    fun utc(value: String) {
        utcEdited = true
        mutableState.update { it.copy(utcText = value, error = null) }
    }

    fun callsign(value: String) = mutableState.update {
        it.copy(callsign = value, error = null, savedCallsign = "")
    }

    fun theirGrid(value: String) = mutableState.update { it.copy(theirGrid = value, error = null) }
    fun sent(value: String) = mutableState.update { it.copy(sent = value, error = null) }
    fun received(value: String) = mutableState.update { it.copy(received = value, error = null) }

    fun mode(value: String) {
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

    fun txFrequency(value: String) {
        txFrequencyEdited = true
        mutableState.update { it.copy(txFrequencyHz = value.filter(Char::isDigit), error = null) }
    }

    fun rxFrequency(value: String) {
        rxFrequencyEdited = true
        mutableState.update { it.copy(rxFrequencyHz = value.filter(Char::isDigit), error = null) }
    }

    fun myCallsign(value: String) {
        myCallsignEdited = true
        mutableState.update { it.copy(myCallsign = value, error = null) }
    }

    fun myGrid(value: String) {
        myGridEdited = true
        mutableState.update { it.copy(myGrid = value, error = null) }
    }

    fun status(value: QsoStatus) = mutableState.update { it.copy(status = value, error = null) }
    fun comment(value: String) = mutableState.update { it.copy(comment = value, error = null) }

    private fun refreshContext() {
        val now = container.disciplinedClock.nowMillis()
        val tracking = container.radioTrackingService.state.value
        val passes = container.satelliteRepo.passes.value
        val current = passes.filter { it.aosTime <= now && (it.isDeepSpace || it.losTime >= now) }
        val available = (listOfNotNull(contextPass, chosenPass) + current + passes.filter { it.losTime >= now })
            .distinctBy { it.catNum }
        val configuredGrid = container.settingsRepo.stationPosition.value.qthLocator
        val defaultGrid = configuredGrid.takeIf { qthToPosition(it) != null }.orEmpty()
        val previous = mutableState.value
        val next = if (previous.callsign.isNotBlank() || previous.saving) previous.pass else {
            chosenPass?.takeIf { it.isDeepSpace || it.losTime >= now }
                ?: contextPass ?: tracking.currentPass?.takeIf { tracking.isActive } ?: current.firstOrNull()
        }
        val passChanged = passKey(previous.pass) != passKey(next)
        mutableState.update {
            it.copy(
                pass = next,
                candidates = available,
                utcText = if (utcEdited) it.utcText else formatUtc(now),
                myCallsign = if (myCallsignEdited) it.myCallsign
                else container.settingsRepo.ft4Settings.value.operatorCallsign,
                myGrid = if (myGridEdited) it.myGrid
                else defaultGrid
            )
        }
        if (passChanged) {
            resetAutomaticChoices()
            loadPassDefaults(next)
        } else {
            refreshAutomaticValues()
        }
    }

    private fun setPass(pass: OrbitalPass) {
        if (passKey(mutableState.value.pass) == passKey(pass)) return
        mutableState.update { it.copy(pass = pass, error = null) }
        resetAutomaticChoices()
        loadPassDefaults(pass)
    }

    private fun resetAutomaticChoices() {
        transponderChosen = false
        modeChosen = false
        txFrequencyEdited = false
        rxFrequencyEdited = false
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
        val channels = if (passKey(state.pass) == passKey(contextPass) && contextTransponders.isNotEmpty()) {
            contextTransponders
        } else {
            state.transponders
        }
        if (channels.isNotEmpty()) applyTransponders(channels)
    }

    private fun applyTransponders(channels: List<SatRadio>) {
        val previous = mutableState.value
        val tracking = container.radioTrackingService.state.value
        val trackingChannel = tracking.selectedTransponder
            ?.takeIf { tracking.isActive && tracking.currentPass?.catNum == previous.pass?.catNum }
        val selected = when {
            transponderChosen -> channels.firstOrNull { it.uuid == previous.transponder?.uuid }
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
        val trackingMatchesSelection = trackingChannel?.uuid == selected?.uuid
        val trackedTx = tracking.txFrequencyHz.takeIf {
            trackingMatchesSelection &&
                tracking.isActive && tracking.currentPass?.catNum == previous.pass?.catNum
        }
        val trackedRx = tracking.rxFrequencyHz.takeIf {
            trackingMatchesSelection &&
                tracking.isActive && tracking.currentPass?.catNum == previous.pass?.catNum
        }
        mutableState.update {
            it.copy(
                transponders = channels,
                transponder = selected,
                mode = nextMode,
                sent = updateDefaultReport(it.sent, it.mode, nextMode),
                received = updateDefaultReport(it.received, it.mode, nextMode),
                txFrequencyHz = if (txFrequencyEdited) it.txFrequencyHz
                else (trackedTx?.toString() ?: defaultTxFrequency(selected)),
                rxFrequencyHz = if (rxFrequencyEdited) it.rxFrequencyHz
                else (trackedRx?.toString() ?: defaultRxFrequency(selected))
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
        val started = parseUtc(snapshot.utcText) ?: return
        val tx = snapshot.txFrequencyHz.toLongOrNull()
        val rx = snapshot.rxFrequencyHz.toLongOrNull()
        val now = container.disciplinedClock.nowMillis()
        val radio = container.radioTrackingService.state.value
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
                    myCallsign = snapshot.myCallsign,
                    myGrid = snapshot.myGrid,
                    radio = radio,
                    transponder = snapshot.transponder,
                    details = QuickQsoDetails(
                        startUtcMillis = started,
                        theirGrid = snapshot.theirGrid,
                        txFrequencyHz = tx,
                        rxFrequencyHz = rx,
                        satelliteMode = listOfNotNull(
                            snapshot.transponder?.uplinkMode,
                            snapshot.transponder?.downlinkMode
                        ).filter(String::isNotBlank).joinToString("/"),
                        comment = snapshot.comment,
                        status = snapshot.status
                    )
                )
                container.qsoRepository.save(record)
                utcEdited = false
                mutableState.update {
                    it.copy(
                        utcText = formatUtc(container.disciplinedClock.nowMillis()),
                        callsign = "",
                        theirGrid = "",
                        comment = "",
                        status = QsoStatus.COMPLETE,
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
        if (state.myGrid.isNotBlank() && qthToPosition(state.myGrid) == null) return QuickLogError.GRID
        if (state.txFrequencyHz.isNotBlank() && state.txFrequencyHz.toLongOrNull()?.takeIf { it > 0L } == null) {
            return QuickLogError.FREQUENCY
        }
        if (state.rxFrequencyHz.isNotBlank() && state.rxFrequencyHz.toLongOrNull()?.takeIf { it > 0L } == null) {
            return QuickLogError.FREQUENCY
        }
        if (parseUtc(state.utcText) == null) return QuickLogError.UTC
        return null
    }

    private fun modeFor(transponder: SatRadio?, fallback: String): String {
        val tracking = container.radioTrackingService.state.value
        val value = tracking.txMode
            ?.takeIf {
                !transponderChosen &&
                    tracking.isActive && tracking.currentPass?.catNum == mutableState.value.pass?.catNum
            }
            ?: transponder?.uplinkMode
            ?: transponder?.downlinkMode
            ?: return fallback
        return when (value.uppercase(Locale.US)) {
            "CWR" -> "CW"
            "USB", "LSB" -> "SSB"
            "FMN" -> "FM"
            in quickLogModes -> value.uppercase(Locale.US)
            else -> fallback
        }
    }

    private fun defaultTxFrequency(transponder: SatRadio?): String =
        centerFrequency(transponder?.uplinkLow, transponder?.uplinkHigh)?.toString().orEmpty()

    private fun defaultRxFrequency(transponder: SatRadio?): String =
        centerFrequency(transponder?.downlinkLow, transponder?.downlinkHigh)?.toString().orEmpty()

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory { initializer { QuickLogViewModel(container) } }
    }
}

private fun centerFrequency(low: Long?, high: Long?): Long? = when {
    low != null && high != null -> low + (high - low) / 2L
    else -> low ?: high
}

private fun defaultReport(mode: String): String = when (mode.uppercase(Locale.US)) {
    "CW", "RTTY" -> "599"
    "FT4", "FT8" -> "-10"
    else -> "59"
}

private fun updateDefaultReport(value: String, previousMode: String, nextMode: String): String =
    if (value == defaultReport(previousMode)) defaultReport(nextMode) else value

private fun passKey(pass: OrbitalPass?): Pair<Int, Long>? = pass?.let { it.catNum to it.aosTime }

private fun formatUtc(value: Long): String = utcFormatter().format(Date(value))

private fun parseUtc(value: String): Long? {
    if (!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))) return null
    return runCatching { utcFormatter().parse(value)?.time }.getOrNull()
}

private fun utcFormatter() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
    isLenient = false
}
