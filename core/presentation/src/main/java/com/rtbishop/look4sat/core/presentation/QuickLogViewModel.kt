package com.rtbishop.look4sat.core.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.logbook.QuickLogError
import com.rtbishop.look4sat.core.domain.logbook.quickLogValidation
import com.rtbishop.look4sat.core.domain.logbook.quickQsoRecord
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QuickLogState(
    val pass: OrbitalPass? = null,
    val candidates: List<OrbitalPass> = emptyList(),
    val callsign: String = "",
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
    private var chosenPass: OrbitalPass? = null
    private var modeChosen = false

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshContext()
                delay(1_000)
            }
        }
    }

    fun setContext(pass: OrbitalPass?) { contextPass = pass; refreshContext() }
    fun selectPass(pass: OrbitalPass) { chosenPass = pass; mutableState.update { it.copy(pass = pass, error = null) } }
    fun callsign(value: String) = mutableState.update { it.copy(callsign = value, error = null, savedCallsign = "") }
    fun sent(value: String) = mutableState.update { it.copy(sent = value, error = null) }
    fun received(value: String) = mutableState.update { it.copy(received = value, error = null) }
    fun mode(value: String) {
        modeChosen = true
        mutableState.update {
            val previousDefault = if (it.mode == "CW") "599" else "59"
            val nextDefault = if (value == "CW") "599" else "59"
            it.copy(
                mode = value,
                sent = if (it.sent == previousDefault) nextDefault else it.sent,
                received = if (it.received == previousDefault) nextDefault else it.received,
                error = null
            )
        }
    }

    private fun refreshContext() {
        val now = container.disciplinedClock.nowMillis()
        val tracking = container.radioTrackingService.state.value
        val passes = container.satelliteRepo.passes.value
        val current = passes.filter { it.aosTime <= now && (it.isDeepSpace || it.losTime >= now) }
        val available = (listOfNotNull(contextPass, chosenPass) + current + passes.filter { it.losTime >= now })
            .distinctBy { it.catNum }
        mutableState.update { previous ->
            val next = if (previous.callsign.isNotBlank() || previous.saving) previous.pass else {
                chosenPass?.takeIf { it.isDeepSpace || it.losTime >= now }
                    ?: contextPass ?: tracking.currentPass?.takeIf { tracking.isActive } ?: current.firstOrNull()
            }
            val radioMode = tracking.txMode?.takeIf { tracking.isActive && tracking.currentPass?.catNum == next?.catNum }
                ?.uppercase(java.util.Locale.US)
            val mode = if (modeChosen || previous.callsign.isNotBlank()) previous.mode else when (radioMode) {
                "CW", "CWR" -> "CW"
                "USB", "LSB", "SSB" -> "SSB"
                "FM", "FMN" -> "FM"
                else -> previous.mode
            }
            val default = if (mode == "CW") "599" else "59"
            previous.copy(
                pass = next,
                candidates = available,
                mode = mode,
                sent = if (mode != previous.mode) default else previous.sent,
                received = if (mode != previous.mode) default else previous.received
            )
        }
    }

    fun save() {
        val snapshot = mutableState.value
        if (snapshot.saving) return
        val error = if (snapshot.pass == null) QuickLogError.SATELLITE else {
            quickLogValidation(snapshot.callsign, snapshot.sent, snapshot.received, snapshot.mode)
        }
        if (error != null) { mutableState.update { it.copy(error = error) }; return }
        val pass = snapshot.pass ?: return
        val now = container.disciplinedClock.nowMillis()
        val radio = container.radioTrackingService.state.value
        mutableState.update { it.copy(saving = true, error = null, savedCallsign = "") }
        viewModelScope.launch {
            try {
                val matching = container.satelliteRepo.getRadiosWithId(pass.catNum).filter { channel ->
                    val mode = channel.uplinkMode ?: channel.downlinkMode
                    mode.equals(snapshot.mode, true) || (snapshot.mode == "SSB" && mode in listOf("USB", "LSB"))
                }
                val record = quickQsoRecord(
                    now, pass, snapshot.callsign, snapshot.sent, snapshot.received, snapshot.mode,
                    container.settingsRepo.ft4Settings.value.operatorCallsign,
                    container.settingsRepo.stationPosition.value.qthLocator,
                    radio, matching.singleOrNull()
                )
                container.qsoRepository.save(record)
                mutableState.update { it.copy(callsign = "", savedCallsign = record.theirCallsign) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = QuickLogError.STORAGE) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory { initializer { QuickLogViewModel(container) } }
    }
}
