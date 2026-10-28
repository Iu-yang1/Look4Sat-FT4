package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.domain.audio.RadioAudioBackend
import com.rtbishop.look4sat.core.domain.audio.RadioAudioSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class IcomLanSessionTest {
    @Test fun staleCatTransportCannotReadOrCloseAReplacementSession()=runBlocking {
        FakeRadio().use { radio ->
            val settings=RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port,networkCat=true)
            val first=IcomLanSession(settings,"user" to "pass",this,allowLoopback=true)
            val second=IcomLanSession(settings,"user" to "pass",this,allowLoopback=true)
            try {
                first.open();var current:IcomLanSession?=first
                val transport=IcomLanTransport({ checkNotNull(current) },{ current },{ it.close() })
                assertTrue(transport.connect())
                second.open();current=second
                assertFalse(transport.isConnected)
                assertFalse(transport.write(byteArrayOf(1)))
                assertTrue(transport.readAvailable(8).isEmpty())
                transport.disconnect()
                assertTrue(second.isConnected)
            } finally { first.close();second.close() }
        }
    }
    @Test fun selfCheckNeedsStableAudioAndMeasuredRoundTrip()=runBlocking {
        FakeRadio().use { radio ->
            val client=IcomLanSession(RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port),
                "user" to "pass",this,allowLoopback=true)
            try {
                client.open()
                assertFalse(client.state.value.linkSelfCheckPassed)
                val ready=withTimeout(6000) { client.state.first { it.linkSelfCheckPassed } }
                assertTrue(ready.latencyMillis!!<=500)
                assertFalse(ready.endToEndTimingVerified)
            } finally { client.close() }
        }
    }
    @Test fun rejectedTokenRenewalClosesAudioInsteadOfReconnectingIntoTransmission()=runBlocking {
        FakeRadio(rejectRenewal=true).use { radio ->
            val client=IcomLanSession(RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port),
                "user" to "pass",this,allowLoopback=true,tokenRenewalMillis=800)
            try {
                client.open()
                withTimeout(4000) { client.state.first { it.error!=null } }
                assertFalse(client.state.value.receivingReady)
                assertFalse(client.state.value.transmittingReady)
                assertTrue(radio.audioPackets.isEmpty())
            } finally { client.close() }
        }
    }
    @Test fun completeFt4WaveformIsPacedForFivePointZeroFourSeconds()=runBlocking {
        FakeRadio().use { radio ->
            val client=IcomLanSession(RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port),
                "user" to "pass",this,allowLoopback=true)
            try {
                client.open();withTimeout(4000) { client.state.first { it.transmittingReady } }
                val started=System.nanoTime()
                client.transmit(FloatArray(241920),48000)
                val elapsed=(System.nanoTime()-started)/1_000_000
                assertTrue("duration=$elapsed",elapsed in 5000..6500)
                withTimeout(2000) { while(radio.audioPackets.size<504) delay(10) }
                assertEquals(504,radio.audioPackets.size)
                assertEquals(0,radio.duplicateAudioEnvelopeSequences)
            } finally { client.close() }
        }
    }
    @Test fun cancellationClearsTransmissionAndCannotLeakQueuedAudio()=runBlocking {
        FakeRadio().use { radio ->
            val client=IcomLanSession(RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port),
                "user" to "pass",this,allowLoopback=true)
            try {
                client.open();withTimeout(4000) { client.state.first { it.transmittingReady } }
                val sending=launch { runCatching { client.transmit(FloatArray(241920),48000) } }
                withTimeout(2000) { while(radio.audioPackets.size<5) delay(5) }
                client.stopTransmit();sending.cancelAndJoin();delay(50)
                val count=radio.audioPackets.size;delay(100)
                assertEquals(count,radio.audioPackets.size)
                assertTrue(count<504)
            } finally { client.close() }
        }
    }
    @Test fun localRadioNegotiatesPortsReceivesPcmAndNeverUsesCatInMixedMode() = runBlocking {
        FakeRadio().use { radio ->
            val client=IcomLanSession(RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port),
                "user" to "pass",this,allowLoopback=true)
            try {
                client.open()
                withTimeout(4000) { client.state.first { it.transmittingReady } }
                assertTrue(client.state.value.receivingReady)
                assertTrue(runCatching { client.writeCiv(byteArrayOf(1)) }.isFailure)
                client.transmit(FloatArray(1440) { 0.25f },48000)
                withTimeout(2000) { while(radio.audioPackets.size<3) delay(10) }
                assertEquals(3,radio.audioPackets.size)
                assertTrue(radio.civWrites.isEmpty())
                val times=radio.audioPackets.map { it.first }
                assertTrue(times.last()-times.first()>10_000_000)
                assertTrue(radio.validLogin.get())
            } finally { client.close() }
            assertFalse(client.state.value.transmittingReady)
        }
    }
    @Test fun badLoginDoesNotOpenStreamsOrTransmit()=runBlocking {
        FakeRadio(reject=true).use { radio ->
            val client=IcomLanSession(RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port),
                "user" to "pass",this,allowLoopback=true)
            try { assertTrue(runCatching { client.open() }.isFailure);assertTrue(radio.audioPackets.isEmpty()) }
            finally { client.close() }
        }
    }
    @Test fun networkCatUsesOnlyTheNegotiatedSerialStream()=runBlocking {
        FakeRadio().use { radio ->
            val client=IcomLanSession(RadioAudioSettings(RadioAudioBackend.ICOM_WLAN,host="127.0.0.1",controlPort=radio.port,networkCat=true),
                "user" to "pass",this,allowLoopback=true)
            try {
                client.open();withTimeout(4000) { client.state.first { it.transmittingReady } }
                val command=byteArrayOf(-2,-2,-92,-32,0x1c,0,0,-3)
                assertTrue(client.writeCiv(command))
                withTimeout(2000) { while(radio.civWrites.isEmpty()) delay(10) }
                assertArrayEquals(command,radio.civWrites.single())
            } finally { client.close() }
        }
    }
    private class FakeRadio(private val reject: Boolean=false,private val rejectRenewal:Boolean=false): AutoCloseable {
        private val sockets=List(3) { DatagramSocket(InetSocketAddress("127.0.0.1",0)).apply { soTimeout=100 } }
        val port=sockets[0].localPort
        val validLogin=AtomicBoolean(false)
        val audioPackets=CopyOnWriteArrayList<Pair<Long,ByteArray>>()
        val civWrites=CopyOnWriteArrayList<ByteArray>()
        @Volatile var duplicateAudioEnvelopeSequences=0
        private val running=AtomicBoolean(true)
        private val threads=sockets.mapIndexed { index,socket -> Thread {
            var target: SocketAddress?=null;var peer=0;var seq=1;var lastAudio=0L;var audioSequence=0
            val receivedSequences=mutableSetOf<Int>()
            while(running.get()) try {
                val packet=DatagramPacket(ByteArray(8192),8192);socket.receive(packet)
                target=packet.socketAddress
                val b=packet.data.copyOf(packet.length);val p=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                peer=p.getInt(8)
                if(index==2 && p.getShort(4).toInt()==0 && !receivedSequences.add(p.getShort(6).toInt() and 65535)) duplicateAudioEnvelopeSequences++
                fun response(size:Int,type:Int=0):ByteArray=ByteArray(size).also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0,size).putShort(4,type.toShort())
                        .putShort(6,(seq++).toShort()).putInt(8,0x1020304+index).putInt(12,peer)
                }
                fun send(data:ByteArray) { socket.send(DatagramPacket(data,data.size,target)) }
                when(p.getShort(4).toInt()) {
                    3 -> send(response(16,4))
                    6 -> send(response(16,6))
                    7 -> if(b[16]==0.toByte()) send(response(21,7).also { it[16]=1;b.copyInto(it,17,17,21) })
                    0 -> when {
                        index==0 && b.size==128 -> {
                            validLogin.set(b.copyOfRange(64,68).contentEquals(byteArrayOf(0x5c,0x22,0x55,0x5c)))
                            send(response(96).also {
                                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(26,p.getShort(26)).putInt(28,0x76543210)
                                    .putInt(48,if(reject) -1 else 0)
                            })
                            if(!reject) send(response(0xa8).also {
                                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(64,1).putShort(0x42+0x53,0x1f).putShort(0x42+0x55,0x1f)
                                "IC-705".toByteArray().copyInto(it,0x42+16)
                            })
                        }
                        index==0 && b.size==64 && b[21]==5.toByte() -> send(response(64).also {
                            it[20]=2;it[21]=5
                            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(48,if(rejectRenewal) -1 else 0)
                        })
                        index==0 && b.size==144 -> send(response(80).also {
                            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putShort(66,sockets[1].localPort.toShort()).putShort(70,sockets[2].localPort.toShort())
                        })
                        index==1 && b.size>21 && b[16]==0xc1.toByte() -> civWrites+=b.copyOfRange(21,b.size)
                        index==2 && b.size>24 -> audioPackets+=System.nanoTime() to b
                    }
                }
                if(index==2 && System.nanoTime()-lastAudio>10_000_000) {
                    send(response(24+960).also { audio ->
                        ByteBuffer.wrap(audio).order(ByteOrder.BIG_ENDIAN).putShort(18,(audioSequence++).toShort()).putShort(22,960)
                    });lastAudio=System.nanoTime()
                }
            } catch(_:SocketTimeoutException) { } catch(_:SocketException) { break }
        }.apply { isDaemon=true;start() } }
        override fun close() { running.set(false);sockets.forEach { it.close() };threads.forEach { it.join(1000) } }
    }
}
