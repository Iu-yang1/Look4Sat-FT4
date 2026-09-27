/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class RotatorCommandKind {
    TARGET,
    IMMEDIATE,
    QUERY,
    STOP
}

data class RotatorCommandOutcome(
    val sequence: Long,
    val kind: RotatorCommandKind,
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * Serializes rotator I/O while keeping only the newest unsent pointing target.
 *
 * STOP uses a priority queue and invalidates every target submitted before it. A target
 * already inside a transport write cannot be interrupted, but no stale queued movement is
 * allowed to run before STOP. Targets submitted after STOP remain valid and run afterwards.
 */
class RotatorCommandActor(
    scope: CoroutineScope,
    private val onOutcome: (RotatorCommandOutcome) -> Unit = {}
) {
    private class Command<T>(
        val sequence: Long,
        val kind: RotatorCommandKind,
        val generation: Long,
        val deadlineNanos: Long,
        val timeoutMillis: Long,
        val operation: suspend () -> T,
        val result: CompletableDeferred<Result<T>>?
    )

    private val targets = Channel<Command<*>>(Channel.CONFLATED)
    private val priority = Channel<Command<*>>(Channel.UNLIMITED)
    private val targetGeneration = AtomicLong(0L)
    private val generationLock = Any()

    init {
        scope.launch {
            while (isActive) executeUnchecked(nextCommand())
        }
    }

    fun submitTarget(
        sequence: Long,
        timeoutMillis: Long = DEFAULT_TARGET_TIMEOUT_MILLIS,
        operation: suspend () -> Boolean
    ): Boolean = synchronized(generationLock) {
        targets.trySend(
            Command(
                sequence = sequence,
                kind = RotatorCommandKind.TARGET,
                generation = targetGeneration.get(),
                deadlineNanos = deadlineAfter(timeoutMillis),
                timeoutMillis = timeoutMillis,
                operation = operation,
                result = null
            )
        ).isSuccess
    }

    suspend fun <T> executeImmediate(
        sequence: Long,
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        invalidateTargets: Boolean = false,
        operation: suspend () -> T
    ): T {
        if (invalidateTargets) cancelPendingTargets()
        return enqueue(sequence, RotatorCommandKind.IMMEDIATE, timeoutMillis, operation)
    }

    suspend fun <T> executeQuery(
        sequence: Long,
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        operation: suspend () -> T
    ): T = enqueue(sequence, RotatorCommandKind.QUERY, timeoutMillis, operation)

    suspend fun executeStop(
        sequence: Long,
        timeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MILLIS,
        operation: suspend () -> Boolean
    ): Boolean {
        val generation = synchronized(generationLock) {
            val next = targetGeneration.incrementAndGet()
            drainTargets()
            next
        }
        return enqueue(
            sequence = sequence,
            kind = RotatorCommandKind.STOP,
            timeoutMillis = timeoutMillis,
            operation = operation,
            generation = generation,
            urgent = true
        )
    }

    fun cancelPendingTargets() {
        synchronized(generationLock) {
            targetGeneration.incrementAndGet()
            drainTargets()
        }
    }

    private suspend fun <T> enqueue(
        sequence: Long,
        kind: RotatorCommandKind,
        timeoutMillis: Long,
        operation: suspend () -> T,
        generation: Long = targetGeneration.get(),
        urgent: Boolean = false
    ): T {
        require(timeoutMillis > 0)
        val result = CompletableDeferred<Result<T>>()
        val command = Command(
            sequence = sequence,
            kind = kind,
            generation = generation,
            deadlineNanos = deadlineAfter(timeoutMillis),
            timeoutMillis = timeoutMillis,
            operation = operation,
            result = result
        )
        if (urgent) {
            withContext(NonCancellable) { priority.send(command) }
        } else {
            priority.send(command)
        }
        return result.await().getOrThrow()
    }

    private suspend fun nextCommand(): Command<*> =
        priority.tryReceive().getOrNull()
            ?: targets.tryReceive().getOrNull()
            ?: select {
                priority.onReceive { it }
                targets.onReceive { it }
            }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeUnchecked(command: Command<*>) {
        command as Command<Any?>
        val skipReason = when {
            command.kind == RotatorCommandKind.TARGET && command.generation != targetGeneration.get() ->
                "Pointing target was superseded by a stop"
            System.nanoTime() >= command.deadlineNanos -> "Rotator command expired in queue"
            else -> null
        }
        if (skipReason != null) {
            finish(command, Result.failure(IllegalStateException(skipReason)))
            return
        }

        val result = try {
            Result.success(withTimeout(command.timeoutMillis) { command.operation() })
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Rotator command timed out"))
        } catch (error: Throwable) {
            Result.failure(error)
        }
        finish(command, result)
    }

    private fun finish(command: Command<Any?>, result: Result<Any?>) {
        val value = result.getOrNull()
        val successful = result.isSuccess && value != false
        val error = result.exceptionOrNull()?.message
            ?: if (value == false) "Rotator command was rejected" else null
        runCatching {
            onOutcome(
                RotatorCommandOutcome(
                    sequence = command.sequence,
                    kind = command.kind,
                    success = successful,
                    errorMessage = error
                )
            )
        }
        command.result?.complete(result)
    }

    private fun drainTargets() {
        while (targets.tryReceive().isSuccess) {
            // Drain every target from the invalidated generation.
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        require(timeoutMillis > 0)
        val timeoutNanos = timeoutMillis.coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLI) * NANOS_PER_MILLI
        val now = System.nanoTime()
        return if (now > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE else now + timeoutNanos
    }

    private companion object {
        const val DEFAULT_TARGET_TIMEOUT_MILLIS = 2_000L
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 2_500L
        const val DEFAULT_STOP_TIMEOUT_MILLIS = 2_500L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
