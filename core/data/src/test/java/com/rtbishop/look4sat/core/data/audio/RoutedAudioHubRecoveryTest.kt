package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedAudioHubRecoveryTest {
    @Test fun failedSharedSourceCanBeStartedAgainWithoutOpeningTheMicrophone()=runTest {
        val configuration=MutableStateFlow(RadioControlSettings(false,RadioControlSettings.MODEL_ICOM_IC705,"","","","",19200,
            radioAudio=RadioAudioSettings(RadioAudioBackend.ICOM_WLAN)))
        val settings=Proxy.newProxyInstance(ISettingsRepo::class.java.classLoader,arrayOf(ISettingsRepo::class.java)) { _,method,_ ->
            if(method.name=="getRadioControlSettings") configuration else error("Unexpected setting ${method.name}")
        } as ISettingsRepo
        val local=object:IAudioHub {
            override val state=MutableStateFlow<AudioHubState>(AudioHubState.Idle)
            override val inputDevices=MutableStateFlow(emptyList<AudioInputDevice>())
            override fun audioFlow(consumer:AudioConsumer,delivery:AudioDelivery):Flow<TimestampedAudioChunk> = error("Must not open phone microphone")
            override suspend fun selectInputDevice(deviceKey:String?)=Unit
            override suspend fun stopAll()=Unit
        }
        var attempts=0
        val session=object:IRadioAudioSession {
            override val state=MutableStateFlow(RadioAudioState())
            override fun receive():Flow<TimestampedAudioChunk> = flow {
                attempts++
                if(attempts==1) { state.value=RadioAudioState(error="offline");throw IllegalStateException("offline") }
                state.value=RadioAudioState(receivingReady=true)
                emit(TimestampedAudioChunk(floatArrayOf(0.25f),48000,0,0,AudioSampleFormat.PCM_16))
                awaitCancellation()
            }
            override fun devices()=emptyList<RadioAudioDevice>()
            override suspend fun prepare(receive:Boolean)=Unit
            override suspend fun prepareTransmit(automatic:Boolean)=Unit
            override suspend fun transmit(samples:FloatArray,sampleRate:Int)=Unit
            override suspend fun stopTransmit()=Unit
            override suspend fun close()=Unit
            override fun credentialsConfigured()=false
            override fun saveCredentials(username:String,password:String,model:String?,config:RadioAudioSettings?)=Unit
            override suspend fun discover()=emptyList<String>()
        }
        val hub=RoutedAudioHub(local,session,settings,backgroundScope)
        val first=backgroundScope.launch { hub.audioFlow(AudioConsumer.FT4_DECODE).collect() }
        runCurrent();assertEquals(1,attempts);assertTrue(hub.state.value is AudioHubState.Failed)
        first.cancelAndJoin();runCurrent()
        val second=backgroundScope.async { hub.audioFlow(AudioConsumer.CW).first() }
        runCurrent()
        assertEquals(0.25f,second.await().samples.single())
        assertEquals(2,attempts)
    }
}
