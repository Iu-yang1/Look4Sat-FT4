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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class Ft4Capability(
    val abi: String = "unknown",
    val officialCoreAvailable: Boolean = false,
    val receiveAvailable: Boolean = false,
    val transmitAvailable: Boolean = false,
    val nativeSelfTestPassed: Boolean? = null,
    val unavailableReason: String = "",
    val upstreamCommit: String = ""
)

data class Ft4DecoderOptions(
    val decodePassCount: Int = 3,
    val multiDecodeRoundCount: Int = 3,
    val earlyDecodeEnabled: Boolean = true,
    val widebandSearchEnabled: Boolean = true,
    val qsoFrequencyHz: Int = 1_500
)

data class Ft4DecodeResult(
    val slotUtcMillis: Long,
    val snr: Int,
    val dtSeconds: Float,
    val frequencyHz: Float,
    val text: String,
    val sourceCall: String,
    val targetCall: String,
    val gridOrReport: String,
    val messageHash: Long
) {
    val stableId: String = "$slotUtcMillis:$messageHash"
}

data class Ft4SpectrumFrame(
    val sequence: Long,
    val sampleRate: Int,
    val frequencyStepHz: Float,
    val magnitudesDb: FloatArray,
    val inputLevelDb: Float
)

sealed interface Ft4EngineState {
    data class Unavailable(val capability: Ft4Capability) : Ft4EngineState
    data object Idle : Ft4EngineState
    data class Receiving(
        val activeSlotUtcMillis: Long?,
        val slotProgress: Float,
        val decodedSlots: Long,
        val audioSampleRate: Int?,
        val lastDecodeDurationMillis: Long? = null,
        val lastDecodeResultCount: Int = 0,
        val lastDecodedSlotUtcMillis: Long? = null,
        val assemblingSlotUtcMillis: Long? = null,
        val assembledSampleCount: Int = 0,
        val droppedAudioBlocks: Long = 0,
        val droppedDecodeSlots: Long = 0,
        val captureQueueDepth: Int = 0,
        val decodeQueueDepth: Int = 0,
        val timestampResidualMillis: Double? = null,
        val clockCorrectionMillis: Double? = null,
        val lastResetReason: String? = null,
        val lastDecodeWasEarly: Boolean = false
    ) : Ft4EngineState

    data class Failed(val reason: String) : Ft4EngineState
}

data class Ft4MessageValidation(
    val valid: Boolean,
    val normalizedMessage: String,
    val error: String = ""
)

interface IFt4Service {
    val capability: StateFlow<Ft4Capability>
    val engineState: StateFlow<Ft4EngineState>
    val decodeResults: StateFlow<List<Ft4DecodeResult>>
    val spectrumFrames: Flow<Ft4SpectrumFrame>

    suspend fun refreshCapability(): Ft4Capability
    suspend fun runNativeSelfTest(): Result<Unit>
    fun startReceiving(options: Ft4DecoderOptions = Ft4DecoderOptions(), myCall: String = "")
    suspend fun stopReceiving(clearResults: Boolean = false)
    fun clearDecodeResults()
    suspend fun validateMessage(message: String): Ft4MessageValidation
    suspend fun generateWaveform(message: String, audioFrequencyHz: Float, outputSampleRate: Int): FloatArray
}
