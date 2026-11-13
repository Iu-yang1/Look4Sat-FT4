package com.rtbishop.look4sat.core.data.astro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OrbitWorkersTest {
    @Test fun boundedWorkersRetainOrderAndRunEveryItemOnce() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = withContext(Dispatchers.Default) {
            (0 until 20).toList().orbitParallelMap(3) { value ->
                val running = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, running) }
                try { delay((20 - value).toLong()); value * 2 }
                finally { active.decrementAndGet() }
            }
        }
        assertEquals((0 until 20).map { it * 2 }, result)
        assertEquals(3, peak.get())
        assertEquals(0, active.get())
        assertTrue(emptyList<Int>().orbitParallelMap { it }.isEmpty())
        assertEquals(1, orbitWorkerCount(1))
        assertEquals(8, orbitWorkerCount(24))
    }

    @Test fun cancellationJoinsWorkersAndCannotPublishPartialResults() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val result = async(Dispatchers.Default) {
            (0..20).toList().orbitParallelMap(4) {
                active.incrementAndGet()
                try { entered.complete(Unit); awaitCancellation() }
                finally { active.decrementAndGet() }
            }
        }
        entered.await()
        result.cancelAndJoin()
        assertEquals(0, active.get())
        assertTrue(result.isCancelled)
    }

    @Test(expected = CancellationException::class)
    fun cancellationThrownByWorkerPropagates() = runBlocking {
        (0..5).toList().orbitParallelMap { throw CancellationException("stop") }
        Unit
    }
}
