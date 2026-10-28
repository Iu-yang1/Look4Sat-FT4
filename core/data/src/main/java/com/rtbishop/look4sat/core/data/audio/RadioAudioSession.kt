package com.rtbishop.look4sat.core.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.data.framework.RadioTransport
import com.rtbishop.look4sat.core.data.ft4.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.*

@OptIn(kotlinx.coroutines.FlowPreview::class)
class RadioAudioSession(
    private val context: Context,
    private val settingsRepo: ISettingsRepo,
    private val localHub: IAudioHub,
    private val scope: CoroutineScope,
    private val diagnostics: com.rtbishop.look4sat.core.domain.control.IControlDiagnostics =
        com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics,
    private val abortTransmit: suspend ()->Unit
) : IRadioAudioSession {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val secrets=RadioAudioCredentials(context)
    private val mutex=Mutex()
    private val status=MutableStateFlow(RadioAudioState())
    override val state: StateFlow<RadioAudioState> = status
    private var lan: IcomLanSession?=null
    private var lanObserver: Job?=null
    private var configured: RadioAudioSettings?=null
    private var output: Ft4AudioOutput?=null
    private var transmitGeneration=0L
    private var previousAudioMode: Int?=null
    private var previousCommunicationDevice: AudioDeviceInfo?=null
    private fun settings()=settingsRepo.radioControlSettings.value.radioAudio
    private fun identity()=settingsRepo.radioControlSettings.value.radioModel+":"+settings().host+":"+settings().controlPort
    override fun credentialsConfigured()=runCatching { secrets.read(identity())!=null }.getOrDefault(false)
    override fun saveCredentials(username: String,password: String,model: String?,config: RadioAudioSettings?) {
        val target=config ?: settings()
        secrets.save((model ?: settingsRepo.radioControlSettings.value.radioModel)+":"+target.host+":"+target.controlPort,username,password)
    }
    init {
        scope.launch {
            state.sample(1000).map { "phase=${it.phase} rttMs=${it.latencyMillis} jitterMs=${it.jitterMillis} gaps=${it.discontinuities} checked=${it.linkSelfCheckPassed}" }
                .distinctUntilChanged().collect { message -> diagnostics.record(
                    com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource.RADIO,"audio/link",message) }
        }
        scope.launch {
            state.map { Triple(it.receivingReady,it.transmittingReady,it.error) }.distinctUntilChanged().collect {
                diagnostics.record(com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource.RADIO,
                    "audio/state","rx=${it.first} tx=${it.second} route=${state.value.route} error=${it.third.orEmpty()}",
                    if(it.third==null) com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity.INFO
                    else com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity.ERROR)
            }
        }
        scope.launch {
            localHub.state.collect { capture ->
                if(settings().backend==RadioAudioBackend.ANDROID_DEVICE && capture is AudioHubState.Failed) {
                    status.update { it.copy(receivingReady=false,transmittingReady=false,phase=RadioAudioConnectionPhase.FAILED,error=capture.reason) }
                    abortTransmit()
                }
            }
        }
        scope.launch {
            settingsRepo.radioControlSettings.map { it.radioModel to it.radioAudio }.distinctUntilChanged().drop(1).collect {
                abortTransmit();close()
            }
        }
    }
    override fun devices()=manager.getDevices(AudioManager.GET_DEVICES_ALL).filter { it.type in TYPES }.map {
        RadioAudioDevice(deviceKey(it),it.productName.toString(),it.isSource)
    }
    override suspend fun prepare(receive: Boolean) {
        try { prepareConnection(receive) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            status.update { it.copy(receivingReady=false,transmittingReady=false,linkSelfCheckPassed=false,
                phase=RadioAudioConnectionPhase.FAILED,error=error.message ?: "Radio audio is unavailable") }
            throw error
        }
    }
    private suspend fun prepareConnection(receive: Boolean) = mutex.withLock {
        val config=settings()
        check(config.backend!=RadioAudioBackend.UNCONFIGURED) { "Select the radio audio connection in CAT settings" }
        if(configured!=config) { lan?.close();lan=null;configured=config;status.value=RadioAudioState(generation=System.nanoTime()) }
        if(config.backend==RadioAudioBackend.ICOM_WLAN) {
            check(settingsRepo.radioControlSettings.value.radioModel==RadioControlSettings.MODEL_ICOM_IC705) { "WLAN audio requires IC-705" }
            if(lan==null || lan?.state?.value?.error!=null) {
                lan?.close();lanObserver?.cancel()
                val credentials=checkNotNull(secrets.read(identity())) { "Save the radio network credentials" }
                val session=IcomLanSession(config,credentials,scope,nowNanos=SystemClock::elapsedRealtimeNanos)
                lan=session
                lanObserver=scope.launch { session.state.collect { s ->
                    if(lan===session && configured==config) {
                        status.value=s
                        if(s.error!=null) abortTransmit()
                    }
                } }
                session.open()
            }
        } else {
            require(config.txDeviceKey.isNotBlank()) { "Select a radio audio output" }
            val bluetooth=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                deviceKey(it)==config.txDeviceKey && it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if(bluetooth!=null && previousAudioMode==null) {
                previousAudioMode=manager.mode
                manager.mode=AudioManager.MODE_IN_COMMUNICATION
                if(Build.VERSION.SDK_INT>=31) {
                    previousCommunicationDevice=manager.communicationDevice
                    check(manager.setCommunicationDevice(bluetooth)) { "Bluetooth communication audio is unavailable" }
                } else {
                    @Suppress("DEPRECATION")
                    manager.startBluetoothSco()
                }
            }
            if(receive) {
                require(config.rxDeviceKey.isNotBlank()) { "Select a radio audio input" }
                localHub.selectInputDevice(config.rxDeviceKey)
            }
        }
    }
    override fun receive(): Flow<TimestampedAudioChunk> = flow {
        var retries=0
        while(currentCoroutineContext().isActive) {
            try {
                prepare(true)
                if(settings().backend==RadioAudioBackend.ICOM_WLAN) {
                    val current=checkNotNull(lan)
                    coroutineScope {
                        val monitor=launch { current.state.collect { check(it.error==null) { it.error ?: "WLAN failure" } } }
                        try { emitAll(current.audio) } finally {
                            monitor.cancel()
                            if(!settings().networkCat) withContext(NonCancellable) { close() }
                        }
                    }
                } else emitAll(localHub.audioFlow(AudioConsumer.FT4_DECODE).onEach {
                    val capture=localHub.state.value as? AudioHubState.Capturing
            status.update { old -> old.copy(receivingReady=capture!=null,error=null,route=capture?.deviceName.orEmpty(),
                phase=if(capture!=null) RadioAudioConnectionPhase.READY else RadioAudioConnectionPhase.CONNECTING) }
                })
                break
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                if(settings().backend!=RadioAudioBackend.ICOM_WLAN || retries>=3 ||
                    error.message.orEmpty().contains("login rejected",true) || error.message.orEmpty().contains("credentials",true)) {
                    status.update { it.copy(receivingReady=false,transmittingReady=false,linkSelfCheckPassed=false,
                        phase=RadioAudioConnectionPhase.FAILED,error=error.message ?: "Audio reconnect failed") }
                    throw error
                }
                abortTransmit()
                withContext(NonCancellable) { close() }
                status.value=RadioAudioState(generation=System.nanoTime(),phase=RadioAudioConnectionPhase.RECONNECTING)
                delay(500L shl retries)
                retries++
            }
        }
    }
    override suspend fun prepareTransmit(automatic: Boolean) {
        RadioAudioPolicy.requireTransmit(settings(),automatic)
        prepare(false)
        if(settings().backend==RadioAudioBackend.ICOM_WLAN) {
            withTimeout(4000) { state.first { it.transmittingReady || it.error!=null } }
            check(state.value.transmittingReady && state.value.error==null) { state.value.error ?: "Radio audio is not ready" }
            if(automatic) RadioAudioPolicy.requireAutomaticReady(state.value)
        }
    }
    fun createOutput(rate: Int, volume: Float): Ft4AudioOutput {
        val config=settings()
        val generation=++transmitGeneration
        return object: Ft4AudioOutput {
            override val latencyCompensationMillis=config.txLatencyMillis
            private var delegate: Ft4AudioOutput?=null
            override suspend fun prepareTransmit(automatic: Boolean) { this@RadioAudioSession.prepareTransmit(automatic) }
            override suspend fun prepare(samples: FloatArray) {
                check(config==settings() && generation==transmitGeneration)
                if(config.backend==RadioAudioBackend.ANDROID_DEVICE) {
                    delegate=AndroidFt4AudioOutputFactory(context,config.txDeviceKey).create(rate,volume)
                    delegate!!.prepare(samples)
                    status.update { it.copy(transmittingReady=true,route=config.txDeviceKey,error=null,phase=RadioAudioConnectionPhase.READY) }
                }
                output=this
            }
            override suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult {
                check(config==settings() && generation==transmitGeneration) { "Audio configuration changed" }
                return if(config.backend==RadioAudioBackend.ICOM_WLAN) {
                    checkNotNull(lan).transmit(samples.map { it*volume }.toFloatArray(),rate)
                    Ft4AudioPlaybackResult(null,0)
                } else checkNotNull(delegate).play(samples)
            }
            override fun close() { delegate?.close();lan?.stopTransmit();if(output===this) output=null }
        }
    }
    override suspend fun transmit(samples: FloatArray,sampleRate: Int) {
        prepareTransmit(false)
        val player=createOutput(sampleRate,1f)
        try { player.prepare(samples);player.play(samples) } finally { player.close() }
    }
    override suspend fun stopTransmit() { transmitGeneration++;lan?.stopTransmit();output?.close();output=null }
    override suspend fun close() = mutex.withLock {
        stopTransmit();lan?.close();lan=null;lanObserver?.cancel();lanObserver=null;configured=null
        status.value=RadioAudioState(generation=System.nanoTime())
        previousAudioMode?.let { mode ->
            if(Build.VERSION.SDK_INT>=31) {
                previousCommunicationDevice?.let { manager.setCommunicationDevice(it) } ?: manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
            }
            manager.mode=mode;previousAudioMode=null;previousCommunicationDevice=null
        }
        Unit
    }
    fun transport(): RadioTransport = IcomLanTransport(
        prepareSession={ prepare(false);check(settings().networkCat);checkNotNull(lan) },
        currentSession={ lan },
        releaseSession={ old -> if(old===lan) close() else old.close() })
    override suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast=true;socket.soTimeout=150
            val targets=NetworkInterface.getNetworkInterfaces().toList().flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.distinct()
            val id=java.security.SecureRandom().nextInt()
            val hello=IcomLanProtocol.header(16,id,0,0,3)
            targets.forEach { socket.send(DatagramPacket(hello,hello.size,it,settings().controlPort)) }
            val end=System.nanoTime()+1_500_000_000;val found=linkedSetOf<String>()
            while(System.nanoTime()<end) {
                currentCoroutineContext().ensureActive()
                try { val packet=DatagramPacket(ByteArray(256),256);socket.receive(packet)
                    val b=packet.data.copyOf(packet.length)
                    if(IcomLanProtocol.valid(b) && IcomLanProtocol.u16(b,4)==4 && IcomLanProtocol.le(b).getInt(12)==id && packet.address.isSiteLocalAddress)
                        found+=packet.address.hostAddress.orEmpty()
                } catch(_:SocketTimeoutException) { }
            }
            found.toList()
        }
    }
    companion object {
        private val TYPES=setOf(AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_LINE_ANALOG,AudioDeviceInfo.TYPE_LINE_DIGITAL,AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        fun deviceKey(device: AudioDeviceInfo): String {
            val address=if(Build.VERSION.SDK_INT>=28) device.address else ""
            return "${device.type}:${address.ifBlank { device.productName.toString() }}"
        }
    }
}
