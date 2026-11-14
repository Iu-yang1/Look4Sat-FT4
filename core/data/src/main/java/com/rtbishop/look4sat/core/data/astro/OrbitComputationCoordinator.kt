package com.rtbishop.look4sat.core.data.astro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** One application-wide batch budget. A worker lives only while it has waiters. */
internal class OrbitComputationCoordinator(
    private val scope: CoroutineScope,
    parallelism: Int = orbitWorkerCount(),
    private val diagnostic: (String) -> Unit = {}
) {
    private class Work(val result: Deferred<Any?>, var waiters: Int = 0)
    private val pending = mutableMapOf<Any, Work>()
    private val permits = Semaphore(parallelism)

    suspend fun <T> compute(key: Any, block: suspend () -> T): T {
        coroutineContext.ensureActive()
        val work = synchronized(pending) {
            (pending[key]?.also { diagnostic("coalesced=true") } ?: Work(
                scope.async(start = CoroutineStart.LAZY) { permits.withPermit { block() } }
            ).also { pending[key] = it }).also { it.waiters++ }
        }
        work.result.start()
        try {
            @Suppress("UNCHECKED_CAST")
            return work.result.await() as T
        } finally {
            synchronized(pending) {
                work.waiters--
                if (work.waiters == 0) {
                    if (pending[key] === work) pending.remove(key)
                    work.result.cancel()
                }
            }
        }
    }
}
