/* Protocol adapted from wfview, GPL-3.0; see IcomLanProtocol.kt for pinned source. */
package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.data.framework.RadioTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

internal class IcomLanSession(
    private val settings: RadioAudioSettings,
    private val credentials: Pair<String,String>,
    private val scope: CoroutineScope,
    private val allowLoopback: Boolean = false,
    private val nowNanos: ()->Long = System::nanoTime,
    private val tokenRenewalMillis: Long = 60_000
) {
    private val status=MutableStateFlow(RadioAudioState(route="IC-705 WLAN"))
    val state: StateFlow<RadioAudioState> = status
    private val pcm=MutableSharedFlow<TimestampedAudioChunk>(extraBufferCapacity=32)
    val audio: Flow<TimestampedAudioChunk> = pcm
    private val civ=Channel<ByteArray>(64)
    private var control: Link?=null
    private var serial: Link?=null
    private var sound: Link?=null
    private var ready=CompletableDeferred<Unit>()
    private var token=0
    private val tokenRequest=SecureRandom().nextInt(65536)
    private var inner=0
    private var capability: ByteArray?=null
    private var authenticated=false
    private var streamRequested=false
    private var lastRenew=0L
    private var timeline=0L
    private var firstNanos=0L
    private var audioSeq=0
    private var civSeq=0
    @Volatile private var sending=false
    private var gapCount=0L
    @Volatile private var lastAudio=0L
    private val reorder=IcomAudioReorder()
    private val sessionJob=SupervisorJob(scope.coroutineContext[Job])
    private val workers=CoroutineScope(scope.coroutineContext+sessionJob)
    @Volatile private var closed=false

    suspend fun open() = withContext(Dispatchers.IO) {
        val address=InetAddress.getByName(settings.host)
        require((address.isSiteLocalAddress || address.isLinkLocalAddress || allowLoopback && address.isLoopbackAddress) &&
            !address.isMulticastAddress && !address.isAnyLocalAddress) { "Select a local-network radio address" }
        require(settings.controlPort in 1..65535)
        control=Link(address,settings.controlPort) { b -> controlPacket(b) }
        checkNotNull(control).start()
        try { withTimeout(12_000) { ready.await() } } catch (e: Exception) { close(); throw e }
    }

    private inner class Link(val address: InetAddress, port: Int, var consumePacket: suspend (ByteArray)->Unit) {
        val socket=DatagramSocket().apply { connect(address,port); soTimeout=100 }
        val id=SecureRandom().nextInt().let { if(it==0) 1 else it }
        @Volatile var remote=0
        private val seq=AtomicInteger(1)
        private var received=System.nanoTime()
        private var lastPing=0L
        private var lastHello=0L
        private var lastIdle=0L
        private var pingSequence=0
        private var connected=false
        private val history=linkedMapOf<Int,ByteArray>()
        fun packet(size: Int,type: Int=0) = IcomLanProtocol.header(size,id,remote,seq.getAndIncrement(),type)
        private fun untracked(size: Int,type: Int,sequence: Int=0)=IcomLanProtocol.header(size,id,remote,sequence,type)
        @Synchronized fun send(b: ByteArray, remember: Boolean=false) {
            check(!closed && !socket.isClosed) { "WLAN session closed" }
            socket.send(DatagramPacket(b,b.size))
            if(remember) { history[IcomLanProtocol.u16(b,6)]=b.copyOf(); while(history.size>32) history.remove(history.keys.first()) }
        }
        fun start() { workers.launch(Dispatchers.IO) {
            try {
                val buffer=ByteArray(65507)
                while(isActive && !closed) {
                    val now=System.nanoTime()
                    if(!connected && now-lastHello>500_000_000L) { send(untracked(16,3)); lastHello=now }
                    if(connected && now-lastPing>500_000_000L) {
                        send(untracked(21,7,pingSequence++).also { IcomLanProtocol.le(it).putInt(17,(now/1_000_000).toInt()) })
                        lastPing=now
                    }
                    if(connected && now-lastIdle>100_000_000L) { send(packet(16),true);lastIdle=now }
                    if(connected && now-received>5_000_000_000L) error("WLAN connection timed out")
                    if(this@Link===sound && lastAudio!=0L && !sending && now-lastAudio>3_000_000_000L) error("WLAN audio stream stopped")
                    try {
                        val datagram=DatagramPacket(buffer,buffer.size); socket.receive(datagram)
                        val b=buffer.copyOf(datagram.length)
                        if(!IcomLanProtocol.valid(b)) continue
                        val p=IcomLanProtocol.le(b)
                        if(p.getInt(12)!=id && p.getInt(12)!=0) continue
                        if(remote!=0 && p.getInt(8)!=remote) continue
                        received=System.nanoTime()
                        when(IcomLanProtocol.u16(b,4)) {
                            4 -> { remote=p.getInt(8); connected=true; send(untracked(16,6,1)) }
                            5 -> error("Radio closed WLAN connection")
                            1 -> { // Only retransmit idempotent login/control packets, never CAT or audio.
                                if(b.size==16) synchronized(this@Link) { history[IcomLanProtocol.u16(b,6)]?.let { send(it) } }
                            }
                            7 -> if(b.size==21 && b[16]==0.toByte()) send(b.copyOf().also { reply ->
                                IcomLanProtocol.le(reply).putInt(8,id).putInt(12,remote);reply[16]=1
                            })
                        }
                        if(IcomLanProtocol.u16(b,4)!=1) consumePacket(b)
                    } catch (_: SocketTimeoutException) { }
                    if(this@Link===control && authenticated && now-lastRenew>tokenRenewalMillis*1_000_000L) {
                        sendToken(5);lastRenew=now
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if(!closed) {
                    status.update { it.copy(receivingReady=false,transmittingReady=false,error=e.message ?: "WLAN failure") }
                    ready.completeExceptionally(e)
                    close()
                }
            }
        } }
        fun dispose() {
            runCatching { val b=untracked(16,5);socket.send(DatagramPacket(b,b.size)) }
            socket.close()
            synchronized(this) { history.clear() }
        }
    }
    private fun auth(size: Int, request: Int): ByteArray {
        val link=checkNotNull(control)
        val b=link.packet(size)
        IcomLanProtocol.be(b).putInt(16,size-16).putShort(22,(inner++).toShort())
        b[20]=1;b[21]=request.toByte()
        IcomLanProtocol.le(b).putShort(26,tokenRequest.toShort()).putInt(28,token)
        return b
    }
    private fun sendToken(request: Int) {
        checkNotNull(control).send(auth(64,request).also { IcomLanProtocol.be(it).putShort(36,0x0798.toShort()) },true)
    }
    private suspend fun controlPacket(b: ByteArray) {
        if(b.size==16 && IcomLanProtocol.u16(b,4)==6 && !authenticated) {
            checkNotNull(control).send(auth(128,0).also {
                IcomLanProtocol.credential(credentials.first).copyInto(it,64)
                IcomLanProtocol.credential(credentials.second).copyInto(it,80)
                "Look4Sat".toByteArray().copyInto(it,96)
            },true)
        } else if(b.size==96) {
            check(IcomLanProtocol.le(b).getInt(48)==0) { "Icom network login rejected" }
            check(IcomLanProtocol.u16(b,26)==tokenRequest) { "Stale Icom login reply" }
            token=IcomLanProtocol.le(b).getInt(28);authenticated=true
            lastRenew=System.nanoTime();sendToken(2)
        } else if(b.size>=0x42+0x66 && (b.size-0x42)%0x66==0) {
            check(IcomLanProtocol.u16(b,64)==1) { "Select a single IC-705 network radio" }
            capability=b.copyOfRange(0x42,0x42+0x66)
        } else if(b.size==80) {
            check(IcomLanProtocol.le(b).getInt(48)==0 && b[64]==0.toByte()) { "Radio rejected or closed the audio stream" }
            val serialPort=IcomLanProtocol.be(b).getShort(66).toInt() and 65535
            val audioPort=IcomLanProtocol.be(b).getShort(70).toInt() and 65535
            check(serialPort>0 && audioPort>0) { "Missing negotiated Icom ports" }
            openStreams(serialPort,audioPort)
        } else if(b.size==64 && b[21]==5.toByte() && b[20]==2.toByte()) {
            check(IcomLanProtocol.le(b).getInt(48)==0) { "Icom token renewal rejected" }
        }
        if(authenticated && capability!=null && !streamRequested) requestStreams()
    }
    private fun requestStreams() {
        val cap=checkNotNull(capability)
        check(IcomLanProtocol.u16(cap,0x53)>1 && IcomLanProtocol.u16(cap,0x55)>1) {
            "Radio does not advertise 48 kHz audio"
        }
        // Reserve the actual local ports before announcing them to the radio.
        val address=checkNotNull(control).address
        serial=Link(address,settings.controlPort) { }
        sound=Link(address,settings.controlPort) { }
        val request=auth(144,3)
        cap.copyInto(request,32,0,16);cap.copyInto(request,64,16,48)
        IcomLanProtocol.credential(credentials.first).copyInto(request,96)
        request[112]=1;request[113]=1;request[114]=4;request[115]=4
        IcomLanProtocol.be(request).putInt(116,48000).putInt(120,48000)
            .putInt(124,checkNotNull(serial).socket.localPort).putInt(128,checkNotNull(sound).socket.localPort).putInt(132,100)
        request[136]=1;streamRequested=true;checkNotNull(control).send(request,true)
    }
    private fun openStreams(civPort: Int, audioPort: Int) {
        if(ready.isCompleted) return
        val address=checkNotNull(control).address
        // Preserve the ports advertised during negotiation.
        serial?.socket?.apply { disconnect();connect(address,civPort) }
        sound?.socket?.apply { disconnect();connect(address,audioPort) }
        val serialLink=checkNotNull(serial)
        val soundLink=checkNotNull(sound)
        serialLink.consumePacket = { b ->
            if(b.size==16 && IcomLanProtocol.u16(b,4)==6) serialLink.send(serialLink.packet(22).also {
                IcomLanProtocol.le(it).putShort(16,0x01c0);it[21]=4
                IcomLanProtocol.be(it).putShort(19,(civSeq++).toShort())
            })
            if(b.size>21 && b[16]==0xc1.toByte() && IcomLanProtocol.u16(b,17)==b.size-21) {
                check(civ.trySend(b.copyOfRange(21,b.size)).isSuccess) { "Network CAT receive overflow" }
            }
        }
        soundLink.consumePacket = { b ->
            if(b.size>24 && IcomLanProtocol.u16(b,4)==0) {
                lastAudio=System.nanoTime()
                val samples=IcomLanProtocol.pcm(b)
                for((data,gap) in reorder.offer(IcomLanProtocol.be(b).getShort(18).toInt() and 65535,samples)) {
                    if(firstNanos==0L) firstNanos=nowNanos()
                    if(gap) {
                        gapCount++
                        // The missing packet's sample count is unknown. Re-anchor an invalidated timeline;
                        // never invent PCM to fill an FT4 cycle or claim a radio-side sample timestamp.
                        firstNanos=nowNanos()-timeline*1_000_000_000L/48000
                    }
                    val chunk=TimestampedAudioChunk(data,48000,timeline,firstNanos+timeline*1_000_000_000L/48000,AudioSampleFormat.PCM_16,gap)
                    check(pcm.tryEmit(chunk)) { "Network audio consumer overflow" }
                    timeline+=data.size
                    status.update { it.copy(receivingReady=true,transmittingReady=true,discontinuities=gapCount) }
                }
            }
        }
        serialLink.start();soundLink.start()
        status.value=RadioAudioState(route="IC-705 WLAN",generation=System.nanoTime())
        ready.complete(Unit)
    }
    suspend fun writeCiv(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        check(settings.networkCat) { "WLAN CAT is disabled for this session" }
        val link=checkNotNull(serial)
        link.send(link.packet(21+bytes.size).also {
            it[16]=0xc1.toByte(); IcomLanProtocol.le(it).putShort(17,bytes.size.toShort());
            IcomLanProtocol.be(it).putShort(19,(civSeq++).toShort());bytes.copyInto(it,21)
        });true
    }
    fun readCiv()=civ.tryReceive().getOrNull() ?: byteArrayOf()
    suspend fun transmit(samples: FloatArray, rate: Int) = withContext(Dispatchers.IO) {
        require(rate==48000 && samples.all(Float::isFinite))
        check(state.value.transmittingReady && !closed)
        sending=true
        val start=System.nanoTime();var at=0
        try {
            while(at<samples.size) {
                currentCoroutineContext().ensureActive();check(sending && state.value.transmittingReady)
                val waitNanos=start+at*1_000_000_000L/rate-System.nanoTime()
                check(waitNanos > -100_000_000L) { "WLAN audio sender missed its deadline" }
                if(waitNanos>0) delay((waitNanos+999999)/1000000)
                val count=minOf(480,samples.size-at)
                val pcm=ByteArray(count*2);val buffer=IcomLanProtocol.le(pcm)
                repeat(count) { buffer.putShort((samples[at+it].coerceIn(-1f,1f)*32767).toInt().toShort()) }
                val link=checkNotNull(sound)
                val sequence=IcomLanProtocol.u16(link.packet(24+pcm.size),6)
                link.send(IcomLanProtocol.audio(link.id,link.remote,sequence,audioSeq++,pcm));at+=count
            }
            val remaining=start+samples.size*1_000_000_000L/rate-System.nanoTime()
            if(remaining>0) delay((remaining+999999)/1000000)
        } finally { sending=false;lastAudio=System.nanoTime() }
    }
    fun stopTransmit() { sending=false }
    @Synchronized fun close() {
        if(closed) return
        closed=true;sending=false
        ready.completeExceptionally(CancellationException("WLAN session closed"))
        control?.dispose();serial?.dispose();sound?.dispose()
        sessionJob.cancel();civ.close()
        status.update { it.copy(receivingReady=false,transmittingReady=false) }
    }
}
