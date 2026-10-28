package com.rtbishop.look4sat.core.data.jtty

import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.ft4.JttyNativeMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JttyReceiveTest {
    private class Hub : IAudioHub {
        override val state=MutableStateFlow<AudioHubState>(AudioHubState.Idle)
        override val inputDevices=MutableStateFlow(emptyList<AudioInputDevice>())
        val source=MutableSharedFlow<TimestampedAudioChunk>(extraBufferCapacity=20)
        var subscriptions=0
        override fun audioFlow(consumer:AudioConsumer,delivery:AudioDelivery)=flow {
            subscriptions++;try { emitAll(source) } finally { subscriptions-- }
        }
        override suspend fun selectInputDevice(deviceKey:String?)=Unit
        override suspend fun stopAll()=Unit
    }
    private class Decoder: JttyStreamDecoder {
        var cancelled=false;var closed=false
        override fun push(samples:ShortArray,centerHz:Float,widthHz:Float)=listOf(JttyNativeMessage(1,0.0,centerHz,-8f,0,"HELLO","K1ABC"))
        override fun cancel() {cancelled=true}
        override fun close() {closed=true}
    }
    @Test fun singleSubscriptionAndStopWaitsBeforeReleasingAllDecoders()=runTest {
        val hub=Hub();val decoders=mutableListOf<Decoder>()
        val service=JttyService(backgroundScope,hub,worker=StandardTestDispatcher(testScheduler),decoderFactory={Decoder().also {decoders+=it}})
        service.startReceiving();runCurrent();service.startReceiving();runCurrent()
        assertEquals(1,hub.subscriptions)
        hub.source.emit(TimestampedAudioChunk(FloatArray(512),12000,0,0,AudioSampleFormat.PCM_FLOAT));runCurrent()
        assertTrue(service.state.value.messages.isNotEmpty())
        service.stopReceiving();runCurrent()
        assertEquals(0,hub.subscriptions);assertTrue(decoders.all {it.closed});assertFalse(service.state.value.receiving)
    }
    @Test fun discontinuityEndsOldMessagesInsteadOfJoiningAcrossGap()=runTest {
        val hub=Hub();val decoders=mutableListOf<Decoder>()
        val service=JttyService(backgroundScope,hub,worker=StandardTestDispatcher(testScheduler),nowMillis={testScheduler.currentTime},decoderFactory={Decoder().also {decoders+=it}})
        service.startReceiving();runCurrent()
        hub.source.emit(TimestampedAudioChunk(FloatArray(512),12000,0,0,AudioSampleFormat.PCM_FLOAT));runCurrent()
        val ids=service.state.value.messages.map {it.id}.toSet()
        hub.source.emit(TimestampedAudioChunk(FloatArray(512),12000,2000,100_000_000,AudioSampleFormat.PCM_FLOAT,true));runCurrent()
        assertTrue(service.state.value.messages.filter {it.id in ids}.all {it.ended})
        assertTrue(service.state.value.discontinuities>=2)
        service.selectReceiveFrequency(1300f);assertEquals(1300f,service.state.value.selectedRxHz)
        service.stopReceiving()
    }
}
