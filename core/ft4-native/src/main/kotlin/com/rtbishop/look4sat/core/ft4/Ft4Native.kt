/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.ft4

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.Locale

data class Ft4NativeCapabilities(
    val abi: String,
    val officialCoreAvailable: Boolean,
    val rxAvailable: Boolean,
    val txAvailable: Boolean,
    val unavailableReason: String,
    val upstreamCommit: String
)

data class Ft4NativeDecodeResult(
    val utcMillis: Long,
    val snr: Int,
    val dtSeconds: Float,
    val frequencyHz: Float,
    val text: String,
    val sourceCall: String,
    val targetCall: String,
    val gridOrReport: String,
    val messageHash: Long
)

data class Ft4NativeDecoderOptions(
    val decodePassCount: Int = 3,
    val multiDecodeRoundCount: Int = 3,
    val earlyDecodeEnabled: Boolean = true,
    val widebandSearchEnabled: Boolean = true,
    val qsoFrequencyHz: Int = 1500
)

class Ft4NativeDecoder internal constructor(private var handle: Long) : Closeable {
    internal fun requireHandle(): Long = checkNotNull(handle.takeIf { it != 0L }) {
        "FT4 decoder is closed"
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        Ft4Native.destroyDecoder(current)
    }
}

object Ft4Native {
    const val SLOT_SAMPLE_RATE = 12_000
    const val SLOT_SAMPLE_COUNT = 90_000
    const val TONE_COUNT = 105

    private val requestMutex = Mutex()

    val capabilities: Ft4NativeCapabilities by lazy {
        val values = Ft4NativeBindings.nativeCapabilities()
        Ft4NativeCapabilities(
            abi = values.getOrElse(0) { "unknown" },
            officialCoreAvailable = values.getOrElse(1) { "0" } == "1",
            rxAvailable = values.getOrElse(2) { "0" } == "1",
            txAvailable = values.getOrElse(3) { "0" } == "1",
            unavailableReason = values.getOrElse(4) { "unknown" },
            upstreamCommit = values.getOrElse(5) { "unknown" }
        )
    }

    suspend fun createDecoder(utcMillis: Long): Ft4NativeDecoder = requestMutex.withLock {
        check(capabilities.rxAvailable) { capabilities.unavailableReason }
        val handle = Ft4NativeBindings.nativeCreate(utcMillis)
        check(handle != 0L) { "Unable to create FT4 decoder" }
        Ft4NativeDecoder(handle)
    }

    suspend fun decodeFt4Slot(
        decoder: Ft4NativeDecoder,
        samples12k: FloatArray,
        slotUtcMillis: Long,
        options: Ft4NativeDecoderOptions = Ft4NativeDecoderOptions(),
        myCall: String = "",
        hintCalls: List<String> = emptyList()
    ): List<Ft4NativeDecodeResult> = requestMutex.withLock {
        require(samples12k.size == SLOT_SAMPLE_COUNT) {
            "FT4 slot must contain exactly $SLOT_SAMPLE_COUNT samples"
        }
        Ft4NativeBindings.nativeDecode(
            handle = decoder.requireHandle(),
            samples = samples12k,
            utcMillis = slotUtcMillis,
            passCount = options.decodePassCount.coerceIn(1, 3),
            roundCount = options.multiDecodeRoundCount.coerceIn(1, 3),
            earlyDecode = options.earlyDecodeEnabled,
            wideband = options.widebandSearchEnabled,
            qsoFrequencyHz = options.qsoFrequencyHz.coerceIn(0, 3000),
            myCall = normalizeCall(myCall),
            hintCalls = hintCalls.take(4).map(::normalizeCall).toTypedArray()
        ).toList()
    }

    suspend fun validateFt4Message(message: String): String? = requestMutex.withLock {
        if (!capabilities.txAvailable) return@withLock null
        Ft4NativeBindings.nativeValidate(normalizeMessage(message))
    }

    suspend fun generateFt4Waveform(
        message: String,
        audioFrequencyHz: Float,
        outputSampleRate: Int
    ): FloatArray = requestMutex.withLock {
        require(capabilities.txAvailable) { capabilities.unavailableReason }
        require(outputSampleRate == 12_000 || outputSampleRate == 24_000 || outputSampleRate == 48_000)
        require(audioFrequencyHz in 0f..3000f)
        Ft4NativeBindings.nativeGenerate(normalizeMessage(message), audioFrequencyHz, outputSampleRate)
            ?: error("FT4 message cannot be encoded")
    }

    suspend fun runFt4SelfTest(): Result<Unit> = requestMutex.withLock {
        val error = Ft4NativeBindings.nativeSelfTest()
        if (error.isEmpty()) Result.success(Unit) else Result.failure(IllegalStateException(error))
    }

    internal fun destroyDecoder(handle: Long) {
        Ft4NativeBindings.nativeDestroy(handle)
    }

    private fun normalizeMessage(message: String) = message.trim()
        .uppercase(Locale.US)
        .replace(Regex("\\s+"), " ")

    private fun normalizeCall(call: String) = call.trim().uppercase(Locale.US)
}

internal object Ft4NativeBindings {
    init {
        System.loadLibrary("look4sat_ft4")
    }

    external fun nativeCapabilities(): Array<String>
    external fun nativeCreate(utcMillis: Long): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeDecode(
        handle: Long,
        samples: FloatArray,
        utcMillis: Long,
        passCount: Int,
        roundCount: Int,
        earlyDecode: Boolean,
        wideband: Boolean,
        qsoFrequencyHz: Int,
        myCall: String,
        hintCalls: Array<String>
    ): Array<Ft4NativeDecodeResult>
    external fun nativeValidate(message: String): String?
    external fun nativeGenerate(message: String, frequencyHz: Float, sampleRate: Int): FloatArray?
    external fun nativeSelfTest(): String
}
