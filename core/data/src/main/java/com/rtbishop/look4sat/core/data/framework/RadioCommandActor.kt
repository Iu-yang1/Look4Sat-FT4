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

import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 所有 CAT、频率、模式、tone 与 PTT 操作共用的串行命令通道。 */
class RadioCommandActor(
    scope: CoroutineScope,
    private val stateChanged: (busy: Boolean, failure: String?) -> Unit
) {
    private class Command<T>(
        val operation: suspend () -> T,
        val result: CompletableDeferred<Result<T>>
    )

    private val queue = Channel<Command<*>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in queue) executeUnchecked(command)
        }
    }

    suspend fun <T> execute(operation: suspend () -> T): T {
        val result = CompletableDeferred<Result<T>>()
        queue.send(Command(operation, result))
        return result.await().getOrThrow()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeUnchecked(command: Command<*>) {
        command as Command<Any?>
        stateChanged(true, null)
        val result = runCatching { command.operation() }
        stateChanged(false, result.exceptionOrNull()?.message)
        command.result.complete(result)
    }
}

class SerialRadioController(
    private val delegate: IRadioController,
    private val actor: RadioCommandActor
) : IRadioController {
    override val isConnected: Boolean get() = delegate.isConnected
    override suspend fun connect() = actor.execute(delegate::connect)
    override suspend fun disconnect() = actor.execute(delegate::disconnect)
    override suspend fun setFrequency(frequencyHz: Long) = actor.execute { delegate.setFrequency(frequencyHz) }
    override suspend fun setMode(mode: String) = actor.execute { delegate.setMode(mode) }
    override suspend fun setCtcssMode(enabled: Boolean) = actor.execute { delegate.setCtcssMode(enabled) }
    override suspend fun setCtcssTone(toneHz: Double) = actor.execute { delegate.setCtcssTone(toneHz) }
    override suspend fun readFrequencyAndMode() = actor.execute(delegate::readFrequencyAndMode)
    override suspend fun pttOn() = actor.execute(delegate::pttOn)
    override suspend fun pttOff() = actor.execute(delegate::pttOff)
    override suspend fun setBand(frequencyHz: Long) = actor.execute { delegate.setBand(frequencyHz) }
    override suspend fun setVfo(vfoA: Boolean) = actor.execute { delegate.setVfo(vfoA) }
    override suspend fun setSplitMode(enabled: Boolean) = actor.execute { delegate.setSplitMode(enabled) }
    override suspend fun setWorkingFrequency(frequencyHz: Long) = actor.execute { delegate.setWorkingFrequency(frequencyHz) }
    override suspend fun setTxVfoFrequency(frequencyHz: Long) = actor.execute { delegate.setTxVfoFrequency(frequencyHz) }
    override suspend fun readWorkingFrequency() = actor.execute(delegate::readWorkingFrequency)
    override suspend fun readTxVfoFrequency() = actor.execute(delegate::readTxVfoFrequency)
}
