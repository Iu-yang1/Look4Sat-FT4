// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.core.data.jtty

import com.rtbishop.look4sat.core.data.ft4.Ft4AudioOutput
import com.rtbishop.look4sat.core.data.ft4.Ft4AudioOutputFactory
import com.rtbishop.look4sat.core.domain.ft4.*
import com.rtbishop.look4sat.core.domain.jtty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class JttyTransmitter(
    private val service: IJttyService, private val coordinator: IDigitalTransmitCoordinator,
    private val outputFactory: Ft4AudioOutputFactory, private val nowMillis: () -> Long = System::currentTimeMillis
) : IJttyTransmitter {
    private val mutex=Mutex()
    private val mutableBusy=MutableStateFlow(false)
    override val busy=mutableBusy.asStateFlow()
    @Volatile private var active: Job?=null
    override suspend fun transmit(request: JttyTransmitRequest) {
        check(mutex.tryLock()) { "Another JTTY transmission is active" }
        var lease: TxLease?=null;var output: Ft4AudioOutput?=null
        active=currentCoroutineContext()[Job];mutableBusy.value=true
        try {
            check(request.isCurrent()) { "Radio session changed" }
            val info=service.encode(request.message)
            val wave=service.generate(info.normalized,request.frequencyHz,48000)
            require(wave.size.toLong()*1000/48000==info.durationMillis && wave.all(Float::isFinite))
            output=outputFactory.create(48000,request.volume)
            output.prepareTransmit(false);output.prepare(wave)
            currentCoroutineContext().ensureActive();check(request.isCurrent()) { "Radio session changed" }
            lease=coordinator.beginTransmit(TxRequest(request.sessionGeneration,nowMillis(),info.durationMillis,
                request.satelliteCatalogNumber,request.transponderUuid,false,(info.durationMillis+4_000).coerceAtMost(35_000),DigitalTransmitMode.JTTY))
            coordinator.confirmTransmitReady(lease)
            val activeLease=lease
            coroutineScope {
                val guard=launch {
                    while(isActive) {
                        check(request.isCurrent() && coordinator.isTransmitLeaseValid(activeLease)) { "Radio disconnected or session changed" }
                        delay(20)
                    }
                }
                try { withTimeout(info.durationMillis+2_000) { output.play(wave) } } finally { guard.cancel() }
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    runCatching { output?.close() }
                    if(lease!=null) coordinator.endTransmit(lease) // Preserve an unconfirmed stop as an error.
                }
            } finally { active=null;mutableBusy.value=false;mutex.unlock() }
        }
    }
    override suspend fun stop() {
        val task=active
        if(task!=null && task!=currentCoroutineContext()[Job]) task.cancelAndJoin()
        if(task!=null) coordinator.emergencyPttOff()
    }
}
