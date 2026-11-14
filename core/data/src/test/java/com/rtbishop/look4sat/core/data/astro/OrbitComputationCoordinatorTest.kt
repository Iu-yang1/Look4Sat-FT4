package com.rtbishop.look4sat.core.data.astro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OrbitComputationCoordinatorTest {
    @Test fun oneCancelledWaiterDoesNotCancelSharedComputation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val joined = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = OrbitComputationCoordinator(scope, diagnostic = { joined.complete(Unit) })
            val one = async { coordinator.compute("same") { started.complete(Unit); release.await(); 42 } }
            started.await()
            val two = async { coordinator.compute<Int>("same") { error("Must join the first worker") } }
            joined.await()
            one.cancel()
            release.complete(Unit)
            assertEquals(42, two.await())
        } finally { scope.cancel() }
    }

    @Test fun lastWaiterCancellationStopsWorkerAndAllowsFreshRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val coordinator = OrbitComputationCoordinator(scope)
            val waiter = async {
                coordinator.compute("cancel") {
                    try { started.complete(Unit); CompletableDeferred<Unit>().await() }
                    finally { stopped.complete(Unit) }
                }
            }
            started.await()
            waiter.cancel()
            withTimeout(5000) { stopped.await() }
            assertEquals(7, coordinator.compute("cancel") { 7 })
        } finally { scope.cancel() }
    }

    @Test fun sharedBudgetBoundsConcurrentWorkWithoutCoalescingDifferentRanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val coordinator = OrbitComputationCoordinator(scope, parallelism = 2)
            val values = (0..12).map { key ->
                async {
                    coordinator.compute(key) {
                        val count = active.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, count) }
                        try { delay(15); key } finally { active.decrementAndGet() }
                    }
                }
            }.awaitAll()
            assertEquals((0..12).toList(), values)
            assertEquals(2, maximum.get())
        } finally { scope.cancel() }
    }
}
