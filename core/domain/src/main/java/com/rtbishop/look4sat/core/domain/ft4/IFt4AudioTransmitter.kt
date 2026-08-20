/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.ft4

import kotlinx.coroutines.flow.StateFlow

data class Ft4TransmissionRequest(
    val message: String,
    val audioFrequencyHz: Float,
    val slotStartUtcMillis: Long,
    val sessionGeneration: Long,
    val satelliteCatalogNumber: Int,
    val transponderUuid: String,
    val automatic: Boolean,
    val volume: Float = 0.8f
)

sealed interface Ft4TransmitState {
    data object Idle : Ft4TransmitState
    data class Preparing(val message: String, val slotStartUtcMillis: Long) : Ft4TransmitState
    data class Waiting(val message: String, val remainingMillis: Long) : Ft4TransmitState
    data class Transmitting(val message: String, val lease: TxLease) : Ft4TransmitState
    data class Failed(val reason: String) : Ft4TransmitState
}

interface IFt4AudioTransmitter {
    val state: StateFlow<Ft4TransmitState>

    suspend fun transmit(request: Ft4TransmissionRequest)
    suspend fun stop()
    suspend fun emergencyStop()
}
