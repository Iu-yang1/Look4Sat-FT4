// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.core.data.jtty

import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.ft4.Ft4SpectrumFrame
import com.rtbishop.look4sat.core.domain.jtty.*
import com.rtbishop.look4sat.core.ft4.JttyNative
import com.rtbishop.look4sat.core.ft4.JttyNativeDecoder
import com.rtbishop.look4sat.core.ft4.JttyNativeMessage
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface JttyStreamDecoder : AutoCloseable {
    fun push(samples: ShortArray, centerHz: Float, widthHz: Float): List<JttyNativeMessage>
    fun cancel()
}

class JttyService(
    private val scope: CoroutineScope, private val hub: IAudioHub,
    private val stopOtherModes: suspend () -> Unit = {},
    private val worker: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(null,task,"Look4Sat-JTTY-decode",2L*1024*1024)
    }.asCoroutineDispatcher(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val decoderFactory: () -> JttyStreamDecoder = { object : JttyStreamDecoder {
        private val native=JttyNativeDecoder()
        override fun push(samples: ShortArray,centerHz: Float,widthHz: Float)=native.push(samples,centerHz,widthHz)
        override fun cancel()=native.cancel()
        override fun close()=native.close()
    } }
) : IJttyService {
    private val mutable=MutableStateFlow(JttyReceiveState())
    override val state=mutable.asStateFlow()
    private val lifecycle=Mutex()
    private var job: Job?=null
    @Volatile private var decoders=emptyList<JttyStreamDecoder>()
    override suspend fun startReceiving()=lifecycle.withLock {
        if(job?.isActive==true) return@withLock
        stopOtherModes()
        mutable.update { it.copy(receiving=true,error=null) }
        job=scope.launch { receive() }
    }
    override suspend fun stopReceiving()=lifecycle.withLock {
        decoders.forEach { it.cancel() };job?.cancelAndJoin();job=null
        mutable.update { s -> s.copy(receiving=false,messages=s.messages.map { if(it.ended) it else it.copy(ended=true) }) }
    }
    override fun clear() { mutable.update { it.copy(messages=emptyList()) } }
    override fun selectReceiveFrequency(hz: Float) {
        require(hz.isFinite() && hz in 100f..2800f)
        mutable.update { it.copy(selectedRxHz=hz) }
    }
    override suspend fun encode(message: String)=withContext(Dispatchers.Default) {
        val text=JttyText.normalize(message);val frames=JttyNative.frameCount(text)
        require(frames in 1..16) { "JTTY message cannot be encoded" };JttyEncoding(text,frames)
    }
    override suspend fun generate(message: String, frequencyHz: Float, rate: Int)=withContext(Dispatchers.Default) {
        val encoding=encode(message);JttyNative.generate(encoding.normalized,frequencyHz,rate)
    }
    private suspend fun receive()=coroutineScope {
        val queue=Channel<TimestampedAudioChunk>(8)
        val overflow=java.util.concurrent.atomic.AtomicBoolean()
        val capture=launch {
            try { hub.audioFlow(AudioConsumer.JTTY,AudioDelivery.RELIABLE).collect { chunk ->
                if(!queue.trySend(chunk.copy(samples=chunk.samples.copyOf())).isSuccess) overflow.set(true)
            } } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { queue.close(error) }
            finally { queue.close() }
        }
        try { withContext(worker) {
            val search=JttyCarrierSearch()
            var resampler: StreamingAudioResampler?=null
            var sourceRate=0;var expectedFrame: Long?=null;var epoch=0L;var anchor=0L;var sequence=0L
            val anchors=LongArray(4)
            val receiverEpochs=LongArray(4)
            val discovery=ShortArray(11_328)
            var discoveryCount=0
            var activeTargets=1
            var centers=listOf(1500f,1500f,1500f,2050f)
            fun reset() {
                decoders.forEach { it.close() };decoders=List(4) { decoderFactory() }
                epoch++;anchor=nowMillis();anchors.fill(anchor);search.reset();activeTargets=1;discoveryCount=0
                mutable.update { s -> s.copy(discontinuities=s.discontinuities+1,messages=s.messages.map { if(it.ended) it else it.copy(ended=true) }) }
            }
            try {
                reset()
                for(chunk in queue) {
                    ensureActive()
                    if(overflow.getAndSet(false)) {
                        while(queue.tryReceive().isSuccess) { /* discard stale samples after an overflow */ }
                        reset();expectedFrame=null;sourceRate=0;resampler=null
                        continue
                    }
                    val gap=chunk.discontinuity ||
                        (expectedFrame!=null && expectedFrame!=chunk.framePosition) || sourceRate!=chunk.sampleRate
                    if(gap) {
                        reset();sourceRate=chunk.sampleRate;resampler=StreamingAudioResampler(sourceRate,12000)
                    }
                    expectedFrame=chunk.framePosition+chunk.samples.size
                    val pcm=checkNotNull(resampler).process(chunk.samples)
                    if(pcm.isEmpty()) continue
                    val now=nowMillis()
                    val bins=search.push(pcm,now)
                    val candidates=search.snapshot(now)
                    val wanted=listOf(state.value.selectedRxHz)+candidates.map { it.frequencyHz }.filter { it in 100f..2800f && kotlin.math.abs(it-state.value.selectedRxHz)>40 }.take(2)
                    val newCenters=List(3) { wanted.getOrElse(it) { state.value.selectedRxHz } }+2050f
                    for(index in 0..2) if(kotlin.math.abs(centers[index]-newCenters[index])>30 || (index>=activeTargets && index<wanted.size)) {
                        decoders[index].close();decoders=decoders.toMutableList().also { it[index]=decoderFactory() }
                        anchors[index]=now;receiverEpochs[index]++
                    }
                    centers=newCenters;activeTargets=wanted.size
                    val rows=mutableListOf<JttyMessage>()
                    for(offset in pcm.indices step 512) {
                        ensureActive()
                        val block=ShortArray(minOf(512,pcm.size-offset)) { (pcm[offset+it].coerceIn(-1f,1f)*32767).toInt().toShort() }
                        decoders.forEachIndexed { index,decoder ->
                            if(index in 1..2 && wanted.size<=index) return@forEachIndexed
                            val input=if(index==3) {
                                var at=0
                                val found=mutableListOf<JttyNativeMessage>()
                                while(at<block.size) {
                                    val n=minOf(block.size-at,discovery.size-discoveryCount)
                                    block.copyInto(discovery,discoveryCount,at,at+n);discoveryCount+=n;at+=n
                                    if(discoveryCount==discovery.size) { found+=decoder.push(discovery,1450f,1350f);discoveryCount=0 }
                                }
                                found
                            } else decoder.push(block,centers[index],50f)
                            input.forEach { row ->
                                search.confirm(row.frequencyHz,row.snrDb,now)
                                rows+=JttyMessage("$epoch:$index:${receiverEpochs[index]}:${row.id}",anchors[index]+(row.startSeconds*1000).toLong(),row.frequencyHz,row.snrDb,row.text,row.kind==1,row.kind!=0,row.calls.split(' ').filter(String::isNotBlank))
                            }
                        }
                    }
                    mutable.update { current ->
                        val merged=current.messages.toMutableList()
                        rows.forEach { row ->
                            val index=merged.indexOfFirst { it.id==row.id || (it.id.substringBefore(':')==row.id.substringBefore(':') &&
                                kotlin.math.abs(it.utcMillis-row.utcMillis)<150 && kotlin.math.abs(it.frequencyHz-row.frequencyHz)<20) }
                            if(index>=0) { if(row.text.length>=merged[index].text.length) merged[index]=row.copy(id=merged[index].id) } else merged+=row
                        }
                        current.copy(messages=merged.takeLast(300),carriers=search.snapshot(now),
                            spectrum=bins?.let { Ft4SpectrumFrame(sequence++,12000,12000f/4096,it,0f) }?:current.spectrum)
                    }
                }
            } finally { decoders.forEach { it.close() };decoders=emptyList() }
        } } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: LinkageError) { mutable.update { it.copy(error=error.message ?: "JTTY native library is unavailable",receiving=false) } }
        catch(error: Exception) { mutable.update { it.copy(error=error.message,receiving=false) } }
        finally { capture.cancel();queue.cancel();mutable.update { it.copy(receiving=false) } }
    }
}
