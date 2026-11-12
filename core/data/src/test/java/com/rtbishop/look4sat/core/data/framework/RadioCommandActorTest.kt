package com.rtbishop.look4sat.core.data.framework

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadioCommandActorTest {
    @Test fun commandFailureCanBeHandledWithoutCancellingCallerOrWorker() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val error = runCatching { actor.execute { error("failed configuration") } }.exceptionOrNull()
        assertEquals("failed configuration", error?.message)
        assertEquals(42, actor.execute { 42 })
    }

    @Test fun callerCancellationStopsOperationAndWorkerAcceptsNextCommand() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val command = async {
            actor.execute {
                started.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        started.await()
        command.cancel()
        runCurrent()
        assertTrue(cleaned)
        assertEquals(7, actor.execute { 7 })
    }

    @Test fun timeoutDoesNotPreventEmergencyStopFromRunning() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val error = runCatching { actor.execute(timeoutMillis = 20) { awaitCancellation() } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(actor.executePttOff { true })
    }
}
