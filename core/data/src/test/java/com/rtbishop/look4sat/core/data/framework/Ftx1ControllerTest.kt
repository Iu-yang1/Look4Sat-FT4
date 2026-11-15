package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test

class Ftx1ControllerTest {
    @Test fun cancellingHandshakeClosesTheTransportWithoutStartingTransmission() = runBlocking {
        val link=Ftx1SimulatedTransport().apply { silent=true }
        val radio=Ftx1Controller(link)
        val connecting=launch { radio.connect() }
        delay(30); connecting.cancelAndJoin()
        assertFalse(link.isConnected);assertFalse(radio.isConnected)
        assertFalse(link.commands.any { it=="TX1;" })
    }
    @Test fun productionTransportRoundTripsFtx1CommandsThroughLocalTcpSimulator() = runBlocking {
        ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { server ->
            val device=Ftx1SimulatedTransport()
            val failure=AtomicReference<Throwable?>()
            val worker=thread(isDaemon=true,name="FTX1-loopback-simulator") {
                try { runBlocking {
                    device.connect()
                    server.accept().use { socket ->
                        socket.soTimeout=5_000
                        val command=StringBuilder()
                        while(true) {
                            val value=socket.getInputStream().read(); if(value<0) break
                            command.append(value.toChar())
                            check(command.length<=64)
                            if(value==';'.code) {
                                device.write(command.toString().encodeToByteArray());command.setLength(0)
                                val reply=device.readAvailable(512)
                                if(reply.isNotEmpty()) {
                                    val split=reply.size/2
                                    socket.getOutputStream().apply { write(reply,0,split);flush() }
                                    Thread.sleep(8)
                                    socket.getOutputStream().apply { write(reply,split,reply.size-split);flush() }
                                }
                            }
                        }
                    }
                } } catch(error:Throwable) { failure.set(error) }
            }
            val radio=Ftx1Controller(TcpRadioTransport("127.0.0.1",server.localPort),replyTimeoutMillis=500)
            try {
                assertTrue(radio.connect());assertTrue(radio.setSplitMode(true))
                assertTrue(radio.setSplitModes("FM","FM"));assertTrue(radio.configureTxCtcss(67.0))
                assertTrue(radio.setWorkingFrequency(435_100_250));assertTrue(radio.setTxVfoFrequency(145_899_750))
                assertEquals(435_100_250L,radio.readWorkingFrequency())
                assertEquals(145_899_750L,radio.readTxVfoFrequency())
                assertFalse(device.commands.contains("ST1;"))
            } finally { radio.disconnect() }
            worker.join(6_000)
            assertFalse(worker.isAlive);assertNull(failure.get()?.stackTraceToString(),failure.get())
        }
    }
    @Test fun readOnlyHandshakeAndMainSubTrackingDoNotSwapTheReceiver() = runBlocking {
        val link=Ftx1SimulatedTransport(chunkSize=3)
        val radio=Ftx1Controller(link,replyTimeoutMillis=300)
        assertTrue(radio.connect())
        assertEquals(listOf("ID;","FA;","VE0;"),link.commands)
        assertTrue(radio.setVfo(true)); assertTrue(radio.setSplitMode(true))
        assertTrue(radio.setSplitModes("USB","FM"))
        assertTrue(radio.configureTxCtcss(88.5))
        link.commands.clear()
        repeat(3) {
            assertTrue(radio.setWorkingFrequency(435_100_000L+it*250))
            assertTrue(radio.setTxVfoFrequency(145_900_000L-it*250))
        }
        assertEquals(435_100_500L,radio.readWorkingFrequency())
        assertEquals(145_899_500L,radio.readTxVfoFrequency())
        assertFalse(link.commands.any { it.startsWith("VS") || it=="SV;" || it=="ST1;" })
        assertEquals(0,link.selectedSide)
        assertEquals("CN10008;",link.tones[1])
        assertEquals("CT11;",link.toneModes[1])
        radio.disconnect()
    }
    @Test fun modeDataAndBothPttSourcesAreVerifiedByReadback() = runBlocking {
        val link=Ftx1SimulatedTransport()
        val radio=Ftx1Controller(link,replyTimeoutMillis=100)
        assertTrue(radio.connect()); assertTrue(radio.setSplitMode(true))
        assertTrue(radio.setSplitModes("LSB","USB"))
        assertTrue(radio.setTxDataMode(true,"USB")); assertEquals('C',link.modes[1])
        assertEquals('1',link.modes[0])
        assertTrue(radio.pttOn()); assertEquals(true,radio.readPttState())
        assertTrue(radio.pttOff()); assertEquals(false,radio.readPttState())
        link.physicalPtt=true
        assertEquals(true,radio.readPttState()); assertFalse(radio.pttOff())
        link.physicalPtt=false
        assertTrue(radio.setTxDataMode(false,"USB")); assertEquals('2',link.modes[1])
        radio.disconnect()
    }
    @Test fun memoryExitIsExplicitAndOnlyHappensWhenApplyingControl() = runBlocking {
        val link=Ftx1SimulatedTransport().apply { memory[0]="11" }
        val radio=Ftx1Controller(link,replyTimeoutMillis=100)
        assertTrue(radio.connect()); assertEquals("11",link.memory[0])
        assertTrue(radio.setWorkingFrequency(145_950_000))
        assertEquals("00",link.memory[0])
        assertTrue(link.commands.indexOf("VM000;")<link.commands.indexOf("FA145950000;"))
        radio.disconnect()
    }
    @Test fun rejectionIgnoredWritesAndTransportFailureAreNotReportedAsSuccess() = runBlocking {
        val link=Ftx1SimulatedTransport()
        val diagnostics=ControlDiagnosticsBuffer()
        val radio=Ftx1Controller(link,diagnostics,60)
        assertTrue(radio.connect())
        link.reject="FA145950000;"; assertFalse(radio.setWorkingFrequency(145_950_000))
        assertTrue(diagnostics.events.value.any { it.stage=="ftx1/reply/rejected" })
        link.reject=null; link.ignore="FA145950000;"; assertFalse(radio.setWorkingFrequency(145_950_000))
        assertTrue(diagnostics.events.value.any { it.stage=="ftx1/reply/mismatch" })
        link.failWrites=true; assertFalse(radio.setWorkingFrequency(145_950_000)); assertFalse(radio.isConnected)
        radio.disconnect()
    }
    @Test fun wrongRadioOldFirmwareAndMissingRepliesFailBoundedIdentification() = runBlocking {
        for(kind in listOf("wrong-id","old-firmware","silent")) {
            val link=Ftx1SimulatedTransport().apply {
                if(kind=="wrong-id") id="ID0670;"
                if(kind=="old-firmware") firmware="VE00107;"
                if(kind=="silent") silent=true
            }
            val radio=Ftx1Controller(link,replyTimeoutMillis=20)
            assertFalse(kind,radio.connect()); assertFalse(radio.isConnected); assertFalse(link.isConnected)
            assertTrue(link.commands.count { it=="ID;" } in 1..3)
            assertFalse(link.commands.any { it=="TX1;" || it.startsWith("PC") })
        }
    }
    @Test fun malformedAndIncompleteFrequencyRepliesNeverBecomeValidReadback() = runBlocking {
        val link=Ftx1SimulatedTransport()
        val radio=Ftx1Controller(link,replyTimeoutMillis=25)
        assertTrue(radio.connect()); link.frequencyReply="FA14550000;"
        assertNull(radio.readWorkingFrequency())
        link.frequencyReply="FA145500000"; assertNull(radio.readWorkingFrequency())
        link.frequencyReply=null; assertEquals(145_500_000L,radio.readWorkingFrequency())
        radio.disconnect()
    }
}

internal class Ftx1SimulatedTransport(private val chunkSize: Int = 512): RadioTransport {
    override var isConnected=false
    val commands=mutableListOf<String>()
    val frequencies=longArrayOf(145_500_000,435_100_000)
    val modes=charArrayOf('2','4')
    val memory=arrayOf("00","00")
    val tones=arrayOf("CN00000;","CN10000;")
    val toneModes=arrayOf("CT00;","CT10;")
    var selectedSide=0
    private var txSide=0
    private var catPtt=false
    var physicalPtt=false
    var id="ID0840;"
    var firmware="VE00108;"
    var reject:String?=null
    var ignore:String?=null
    var frequencyReply:String?=null
    var failWrites=false
    var silent=false
    var echo=false
    var onWrite: ((String) -> Unit)?=null
    private val pending=ConcurrentLinkedQueue<Byte>()
    override suspend fun connect():Boolean {isConnected=true;return true}
    override suspend fun disconnect(){isConnected=false;pending.clear()}
    override suspend fun write(bytes:ByteArray):Boolean {
        if(!isConnected || failWrites)return false
        val command=bytes.toString(Charsets.US_ASCII);commands+=command
        onWrite?.invoke(command)
        fun reply(text:String){pending.addAll(text.encodeToByteArray().toList())}
        if(echo)reply(command)
        if(silent || command==ignore)return true
        if(command==reject){reply("?;");return true}
        when {
            command=="ID;"->reply(id)
            command=="VE0;"->reply(firmware)
            command=="FA;" || command=="FB;" -> {
                val side=if(command[1]=='A')0 else 1
                reply(frequencyReply ?: command.take(2)+frequencies[side].toString().padStart(9,'0')+";")
            }
            command.matches(Regex("F[AB][0-9]{9};"))->frequencies[if(command[1]=='A')0 else 1]=command.substring(2,11).toLong()
            command.matches(Regex("VM[01];"))->reply("VM${command[2]}${memory[command[2]-'0']};")
            command.matches(Regex("VM[01]00;"))->memory[command[2]-'0']="00"
            command.matches(Regex("MD[01];"))->reply("MD${command[2]}${modes[command[2]-'0']};")
            command.matches(Regex("MD[01][1-9A-FHI];"))->modes[command[2]-'0']=command[3]
            command=="VS;"->reply("VS$selectedSide;")
            command.matches(Regex("VS[01];"))->selectedSide=command[2]-'0'
            command=="FT;"->reply("FT$txSide;")
            command.matches(Regex("FT[01];"))->txSide=command[2]-'0'
            command=="ST;"->reply("ST0;")
            command=="ST0;"->Unit
            command=="TX;"->reply("TX${if(physicalPtt)2 else if(catPtt)1 else 0};")
            command=="TX1;"->catPtt=true
            command=="TX0;"->catPtt=false
            command.matches(Regex("CT[01];"))->reply(toneModes[command[2]-'0'])
            command.matches(Regex("CT[01][0-5];"))->toneModes[command[2]-'0']=command
            command.matches(Regex("CN[01]0;"))->reply(tones[command[2]-'0'])
            command.matches(Regex("CN[01]0[0-9]{3};"))->tones[command[2]-'0']=command
            else->reply("?;")
        }
        return true
    }
    override suspend fun readAvailable(maxBytes:Int):ByteArray {
        val bytes=ArrayList<Byte>()
        repeat(minOf(maxBytes,chunkSize)) { pending.poll()?.let(bytes::add) ?: return bytes.toByteArray() }
        return bytes.toByteArray()
    }
}
