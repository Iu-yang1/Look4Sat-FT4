// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.feature.jtty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.audio.RadioAudioBackend
import com.rtbishop.look4sat.core.domain.jtty.*
import com.rtbishop.look4sat.core.domain.logbook.*
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.radioModelDescriptor
import com.rtbishop.look4sat.core.domain.predict.*
import com.rtbishop.look4sat.core.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

data class JttyState(
    val receive: JttyReceiveState = JttyReceiveState(), val radio: RadioTrackingState = RadioTrackingState(),
    val pass: OrbitalPass? = null, val position: OrbitalPos? = null, val track: List<OrbitalPos> = emptyList(),
    val transponders: List<SatRadio> = emptyList(), val theirCall: String = "", val myCall: String = "",
    val grid: String = "", val sentReport: String = "599", val receivedReport: String = "599",
    val draft: String = "", val txHz: Float = 1500f, val frames: Int? = null,
    val presets: List<String> = emptyList(), val busy: Boolean = false,
    val records: List<QsoRecord> = emptyList(), val error: String? = null, val saved: Boolean = false,
    val audioReady: Boolean = false, val audioRoute: String = "", val networkAudio: Boolean = false,
    val usesVox: Boolean = false, val pttSupported: Boolean = false, val draftError: String? = null
) {
    val transmitReady: Boolean get() = !busy && frames!=null &&
        (usesVox || pttSupported && radio.txConnected && radio.isActive && radio.trackingPhase==TrackingPhase.READY &&
            radio.pttState==PttState.OFF && radio.txMode in listOf("USB","LSB") && radio.rxMode in listOf("USB","LSB"))
}
sealed interface JttyAction {
    data object Receive : JttyAction
    data object Clear : JttyAction
    data object Send : JttyAction
    data object Stop : JttyAction
    data object Connect : JttyAction
    data object Track : JttyAction
    data object SaveLog : JttyAction
    data object DismissError : JttyAction
    data class TheirCall(val value: String): JttyAction
    data class MyCall(val value: String): JttyAction
    data class SentReport(val value: String): JttyAction
    data class ReceivedReport(val value: String): JttyAction
    data class Draft(val value: String): JttyAction
    data class TxFrequency(val value: Float): JttyAction
    data class RxFrequency(val value: Float): JttyAction
    data class Preset(val index: Int): JttyAction
    data class SavePreset(val index: Int,val text: String): JttyAction
    data class Transponder(val value: SatRadio): JttyAction
}

class JttyViewModel(private val container: IMainContainer): ViewModel() {
    private val service=container.jttyService
    private val transmitter=container.jttyTransmitter
    private val mutable=MutableStateFlow(JttyState(presets=container.settingsRepo.jttyPresets(),
        myCall=container.settingsRepo.ft4Settings.value.operatorCallsign.ifBlank {
            container.settingsRepo.wavelogUploadSettings.value.stationCallsign.ifBlank { container.settingsRepo.lotwSettings.value.callsign }
        },grid=container.settingsRepo.stationPosition.value.qthLocator))
    val state=mutable.asStateFlow()
    private var generation=0L
    private var transmitJob: Job?=null
    private var validationJob: Job?=null
    private var entered=false
    init {
        viewModelScope.launch { service.state.collect { receive -> mutable.update { it.copy(receive=receive) } } }
        viewModelScope.launch { transmitter.busy.collect { busy -> mutable.update { it.copy(busy=busy) } } }
        viewModelScope.launch { container.radioTrackingService.state.collect { radio -> mutable.update { it.copy(radio=radio) } } }
        viewModelScope.launch { container.radioAudioSession.state.collect { audio -> mutable.update { it.copy(audioReady=audio.transmittingReady,audioRoute=audio.route) } } }
        viewModelScope.launch { container.settingsRepo.stationPosition.collect { position -> mutable.update { it.copy(grid=position.qthLocator) } } }
        viewModelScope.launch { container.settingsRepo.radioControlSettings.collect { config ->
            mutable.update { it.copy(networkAudio=config.radioAudio.backend==RadioAudioBackend.ICOM_WLAN,
                usesVox=config.catTransport==RadioControlSettings.TRANSPORT_VOX,
                pttSupported=radioModelDescriptor(config.radioModel).capabilities.ptt) }
        } }
        viewModelScope.launch { container.qsoRepository.records.collect { records -> mutable.update { it.copy(records=records.filter { r -> r.mode=="JTTY" }.take(100)) } } }
        launch {
            combine(container.satelliteRepo.passes,container.satelliteRepo.selectedPass) { passes,selection ->
                passes.firstOrNull { it.catNum==selection.first && it.aosTime==selection.second }
                    ?: passes.firstOrNull { it.catNum==selection.first }
            }.distinctUntilChangedBy { it?.let { p -> p.catNum to p.aosTime } }.collectLatest { selected ->
                val pass=selected ?: container.radioTrackingService.state.value.currentPass
                generation++;transmitJob?.cancel();transmitter.stop();service.stopReceiving();service.clear()
                mutable.update { it.copy(pass=pass,theirCall="",draft="",frames=null,draftError=null,sentReport="599",receivedReport="599",position=null,track=emptyList()) }
                if(pass!=null) {
                    val radios=container.satelliteRepo.getRadiosWithId(pass.catNum)
                    val track=if(pass.isDeepSpace) emptyList() else container.satelliteRepo.getTrack(pass.orbitalObject,container.settingsRepo.stationPosition.value,pass.aosTime,pass.losTime)
                    mutable.update { it.copy(transponders=radios,track=track) }
                    while(currentCoroutineContext().isActive) {
                        val position=container.satelliteRepo.getPosition(pass.orbitalObject,container.settingsRepo.stationPosition.value,container.disciplinedClock.nowMillis())
                        mutable.update { it.copy(position=position) };delay(1000)
                    }
                }
            }
        }
        launch {
            if(state.value.myCall.isBlank()) {
                val callsign=container.lotwUploadRepository.certificate()?.callsign.orEmpty()
                if(state.value.myCall.isBlank()) mutable.update { it.copy(myCall=callsign) }
            }
        }
    }
    suspend fun enter() {
        if(entered) return
        try {
            container.ft4AudioTransmitter.stop();container.ft4Service.stopReceiving()
            entered=true
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { mutable.update { it.copy(error=error.message) } }
    }
    fun leave() {
        entered=false;generation++;transmitJob?.cancel()
        container.appScope.launch {
            try { transmitter.stop() }
            catch(error: Exception) { mutable.update { it.copy(error=error.message) } }
            finally { service.stopReceiving() }
        }
    }
    fun onAction(action: JttyAction) {
        when(action) {
            is JttyAction.TheirCall -> mutable.update { it.copy(theirCall=action.value.uppercase(Locale.US),saved=false) }
            is JttyAction.MyCall -> mutable.update { it.copy(myCall=action.value.uppercase(Locale.US)) }
            is JttyAction.SentReport -> mutable.update { it.copy(sentReport=action.value) }
            is JttyAction.ReceivedReport -> mutable.update { it.copy(receivedReport=action.value) }
            is JttyAction.Draft -> if(!state.value.busy) {
                mutable.update { it.copy(draft=action.value,frames=null,draftError=null) };validateDraft()
            }
            is JttyAction.TxFrequency -> if(!state.value.busy && action.value.isFinite() && action.value in 100f..2800f) mutable.update { it.copy(txHz=action.value) }
            is JttyAction.RxFrequency -> if(action.value.isFinite()) service.selectReceiveFrequency(action.value.coerceIn(100f,2800f))
            is JttyAction.Transponder -> if(!transmitter.busy.value && transmitJob?.isActive!=true) launch {
                val resume=state.value.receive.receiving
                generation++
                service.stopReceiving()
                container.radioTrackingService.setTransponder(action.value)
                if(resume && entered) service.startReceiving()
            }
            is JttyAction.Preset -> launch {
                val current=state.value
                val text=JttyText.expand(current.presets[action.index],mapOf("CALL" to current.theirCall,"MYCALL" to current.myCall,"RST" to current.sentReport,"GRID" to current.grid))
                onAction(JttyAction.Draft(text))
            }
            is JttyAction.SavePreset -> launch {
                container.settingsRepo.saveJttyPreset(action.index,action.text)
                mutable.update { it.copy(presets=container.settingsRepo.jttyPresets()) }
            }
            JttyAction.Receive -> launch {
                enter();check(entered) { state.value.error ?: "Could not release the previous radio session" }
                if(state.value.receive.receiving) service.stopReceiving() else service.startReceiving()
            }
            JttyAction.Clear -> service.clear()
            JttyAction.Connect -> launch { container.radioTrackingService.connectRadios() }
            JttyAction.Track -> launch {
                val current=state.value
                if(current.radio.isActive) container.radioTrackingService.stopTracking()
                else {
                    val pass=checkNotNull(current.pass) { "Select a satellite pass" }
                    val transponder=current.radio.selectedTransponder ?: current.transponders.firstOrNull { it.downlinkMode in listOf("USB","LSB") }
                    container.radioTrackingService.startTracking(pass,checkNotNull(transponder) { "Select a linear transponder" },current.radio.txBaseFrequencyHz)
                }
            }
            JttyAction.Send -> send()
            JttyAction.Stop -> launch { transmitJob?.cancel();transmitter.stop() }
            JttyAction.SaveLog -> launch { saveLog() }
            JttyAction.DismissError -> mutable.update { it.copy(error=null,saved=false) }
        }
    }
    private fun validateDraft() {
        validationJob?.cancel()
        validationJob=viewModelScope.launch {
            delay(200);val draft=state.value.draft
            val result=runCatching { service.encode(draft) }
            if(state.value.draft==draft) mutable.update { it.copy(frames=result.getOrNull()?.frames,
                draftError=if(draft.isBlank()) null else result.exceptionOrNull()?.message) }
        }
    }
    private fun send() {
        if(transmitJob?.isActive==true || state.value.busy) return
        val current=state.value;val expected=generation
        transmitJob=launch {
            enter()
            check(entered) { state.value.error ?: "Could not release the previous radio session" }
            val pass=checkNotNull(current.pass) { "Select a satellite pass" }
            val transponder=checkNotNull(current.radio.selectedTransponder) { "Select a transponder" }
            transmitter.transmit(JttyTransmitRequest(current.draft,current.txHz,pass.catNum,transponder.uuid,expected,
                { entered && generation==expected && state.value.pass?.catNum==pass.catNum && state.value.radio.selectedTransponder?.uuid==transponder.uuid }))
        }
    }
    private suspend fun saveLog() {
        val current=state.value
        val pass=checkNotNull(current.pass) { "Select a satellite pass" }
        val record=quickQsoRecord(container.disciplinedClock.nowMillis(),pass,current.theirCall,current.sentReport,current.receivedReport,
            "JTTY",current.myCall,current.grid,current.radio,current.radio.selectedTransponder)
        container.qsoRepository.save(record)
        mutable.update { it.copy(theirCall="",saved=true) }
    }
    private fun launch(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { mutable.update { it.copy(error=error.message ?: "JTTY failed") } }
    }
    override fun onCleared() { leave() }
    companion object {
        fun factory(container: IMainContainer)=viewModelFactory { initializer { JttyViewModel(container) } }
    }
}
