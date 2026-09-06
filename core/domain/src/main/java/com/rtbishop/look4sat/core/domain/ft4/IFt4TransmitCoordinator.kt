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

data class TxRequest(
    val sessionGeneration: Long,
    val waveformStartUtcMillis: Long,
    val waveformDurationMillis: Long = 5_040L,
    val expectedSatelliteCatalogNumber: Int,
    val expectedTransponderUuid: String,
    val maximumPttMillis: Long = 8_500L
)

data class TxLease(
    val id: Long,
    val sessionGeneration: Long,
    val effectiveTxFrequencyHz: Long,
    val txDopplerCorrectionHz: Long,
    val waveformMidpointUtcMillis: Long,
    val maximumPttMillis: Long
)

interface IFt4TransmitCoordinator {
    fun recommendedPrepareLeadMillis(): Long = 350L
    suspend fun beginTransmit(request: TxRequest): TxLease
    suspend fun confirmTransmitReady(lease: TxLease)
    suspend fun endTransmit(lease: TxLease)
    suspend fun emergencyPttOff()
}
