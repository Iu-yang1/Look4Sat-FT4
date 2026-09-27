package com.rtbishop.look4sat.core.data.framework

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RotatorCommandActorTest {

    @Test
    fun keepsOnlyTheNewestPendingPointingTarget() = runTest {
        val events = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val thirdFinished = CompletableDeferred<Unit>()
        val actor = RotatorCommandActor(backgroundScope)

        actor.submitTarget(1) {
            events += "target-1"
            firstStarted.complete(Unit)
            releaseFirst.await()
            true
        }
        runCurrent()
        firstStarted.await()
        actor.submitTarget(2) { events += "target-2"; true }
        actor.submitTarget(3) {
            events += "target-3"
            thirdFinished.complete(Unit)
            true
        }
        releaseFirst.complete(Unit)
        runCurrent()
        thirdFinished.await()

        assertEquals(listOf("target-1", "target-3"), events)
    }

    @Test
    fun stopPreemptsOldPendingTargetButKeepsNewerGeneration() = runTest {
        val events = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val thirdFinished = CompletableDeferred<Unit>()
        val actor = RotatorCommandActor(backgroundScope)

        actor.submitTarget(1) {
            events += "target-1"
            firstStarted.complete(Unit)
            releaseFirst.await()
            true
        }
        runCurrent()
        firstStarted.await()
        actor.submitTarget(2) { events += "target-2"; true }
        val stop = async {
            actor.executeStop(99) {
                events += "stop"
                true
            }
        }
        runCurrent()
        actor.submitTarget(3) {
            events += "target-3"
            thirdFinished.complete(Unit)
            true
        }
        releaseFirst.complete(Unit)
        runCurrent()

        assertTrue(stop.await())
        thirdFinished.await()
        assertEquals(listOf("target-1", "stop", "target-3"), events)
    }

    @Test
    fun queryAndImmediateCommandsAreSerialized() = runTest {
        val events = mutableListOf<String>()
        val actor = RotatorCommandActor(backgroundScope)

        val immediate = async {
            actor.executeImmediate(10) {
                events += "immediate"
                "ok"
            }
        }
        val query = async {
            actor.executeQuery(11) {
                events += "query"
                byteArrayOf(1, 2, 3)
            }
        }
        runCurrent()

        assertEquals("ok", immediate.await())
        assertTrue(query.await().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("immediate", "query"), events)
    }

    @Test
    fun falseOperationIsReportedWithoutChangingItsReturnValue() = runTest {
        val outcomes = mutableListOf<RotatorCommandOutcome>()
        val actor = RotatorCommandActor(backgroundScope, outcomes::add)

        val result = async { actor.executeImmediate(7) { false } }
        runCurrent()

        assertEquals(false, result.await())
        assertEquals(1, outcomes.size)
        assertEquals(false, outcomes.single().success)
        assertEquals("Rotator command was rejected", outcomes.single().errorMessage)
    }
}
