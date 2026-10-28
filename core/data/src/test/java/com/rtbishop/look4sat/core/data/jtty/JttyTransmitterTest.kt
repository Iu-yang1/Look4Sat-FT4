package com.rtbishop.look4sat.core.data.jtty

import com.rtbishop.look4sat.core.data.ft4.*
import com.rtbishop.look4sat.core.domain.ft4.*
import com.rtbishop.look4sat.core.domain.jtty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JttyTransmitterTest {
    private class Service: IJttyService {
        override val state=MutableStateFlow(JttyReceiveState())
        override suspend fun startReceiving()=Unit
        override suspend fun stopReceiving()=Unit
        override fun clear()=Unit
        override fun selectReceiveFrequency(hz: Float)=Unit
        override suspend fun encode(message: String)=JttyEncoding(message,16)
        override suspend fun generate(message: String,frequencyHz: Float,rate: Int)=FloatArray(30_208*48)
    }
    private class Coordinator(val events: MutableList<String>): IFt4TransmitCoordinator {
        var failConfirm=false
        var failEnd=false
        override suspend fun beginTransmit(request: TxRequest): TxLease {
            events+="begin"
            assertEquals(DigitalTransmitMode.JTTY,request.mode);assertEquals(30_208L,request.waveformDurationMillis)
            assertTrue(request.maximumPttMillis<=35_000)
            return TxLease(1,request.sessionGeneration,145900000,0,request.waveformStartUtcMillis,0,
                request.waveformDurationMillis,request.maximumPttMillis,1,"radio",0,false)
        }
        override suspend fun confirmTransmitReady(lease: TxLease) { events+="confirm";check(!failConfirm) }
        override suspend fun endTransmit(lease: TxLease) { events+="end";check(!failEnd) }
        override suspend fun emergencyPttOff() { events+="off" }
    }
    private fun request(current: ()->Boolean = {true})=JttyTransmitRequest("CQ K1ABC",1500f,1,"radio",1,current)
    @Test fun preparesAudioConfirmsPttPlaysAndReleases()=runTest {
        val events=mutableListOf<String>();val coordinator=Coordinator(events)
        val tx=JttyTransmitter(Service(),coordinator,Ft4AudioOutputFactory { _,_ -> object:Ft4AudioOutput {
            override suspend fun prepareTransmit(automatic: Boolean) { events+="route";assertFalse(automatic) }
            override suspend fun prepare(samples: FloatArray) { events+="prepare" }
            override suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult { events+="play";return Ft4AudioPlaybackResult(null,0) }
            override fun close() {events+="close"}
        } },{testScheduler.currentTime})
        tx.transmit(request())
        assertEquals(listOf("route","prepare","begin","confirm","play","close","end"),events)
        assertFalse(tx.busy.value)
    }
    @Test fun routingFailureNeverKeysPtt()=runTest {
        val events=mutableListOf<String>();val coordinator=Coordinator(events)
        val tx=JttyTransmitter(Service(),coordinator,Ft4AudioOutputFactory { _,_ -> object:Ft4AudioOutput {
            override suspend fun prepareTransmit(automatic: Boolean) {error("wrong route")}
            override suspend fun play(samples: FloatArray):Ft4AudioPlaybackResult=error("must not play")
            override fun close() {events+="close"}
        } })
        assertTrue(runCatching {tx.transmit(request())}.isFailure)
        assertEquals(listOf("close"),events);assertFalse(tx.busy.value)
    }
    @Test fun unconfirmedStopDoesNotLeaveTheMutexOrBusyStateLocked()=runTest {
        val events=mutableListOf<String>();val coordinator=Coordinator(events).also {it.failEnd=true}
        val tx=JttyTransmitter(Service(),coordinator,Ft4AudioOutputFactory { _,_ -> object:Ft4AudioOutput {
            override suspend fun play(samples:FloatArray)=Ft4AudioPlaybackResult(null,0)
            override fun close()=Unit
        } })
        assertTrue(runCatching {tx.transmit(request())}.isFailure);assertFalse(tx.busy.value)
        coordinator.failEnd=false;tx.transmit(request());assertFalse(tx.busy.value)
    }
    @Test fun stopCancelsWaveAndDoesNotQueueAnotherMessage()=runTest {
        val events=mutableListOf<String>();val coordinator=Coordinator(events)
        val tx=JttyTransmitter(Service(),coordinator,Ft4AudioOutputFactory { _,_ -> object:Ft4AudioOutput {
            override suspend fun play(samples:FloatArray):Ft4AudioPlaybackResult {events+="play";delay(30_208);events+="done";return Ft4AudioPlaybackResult(null,0)}
            override fun close() {events+="close"}
        } })
        val task=launch {tx.transmit(request())};runCurrent()
        assertTrue(tx.busy.value);assertTrue(runCatching {tx.transmit(request())}.isFailure)
        tx.stop();task.join();assertFalse(tx.busy.value);assertFalse("done" in events);assertTrue("end" in events)
    }
    @Test fun refusedPttNeverPlaysAndStillReleases()=runTest {
        val events=mutableListOf<String>();val coordinator=Coordinator(events).also {it.failConfirm=true}
        val tx=JttyTransmitter(Service(),coordinator,Ft4AudioOutputFactory { _,_ -> object:Ft4AudioOutput {
            override suspend fun play(samples:FloatArray):Ft4AudioPlaybackResult=error("must not play")
            override fun close() {events+="close"}
        } })
        assertTrue(runCatching {tx.transmit(request())}.isFailure);assertTrue("end" in events)
    }
}
