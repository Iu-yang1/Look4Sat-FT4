/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.ft4

import com.rtbishop.look4sat.core.domain.audio.AudioConsumer
import com.rtbishop.look4sat.core.domain.audio.AudioDelivery
import com.rtbishop.look4sat.core.domain.audio.Ft4SlotAssembler
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.audio.StreamingAudioResampler
import com.rtbishop.look4sat.core.domain.audio.TimestampedAudioChunk
import com.rtbishop.look4sat.core.domain.ft4.Ft4Capability
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecodeResult
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecoderOptions
import com.rtbishop.look4sat.core.domain.ft4.Ft4EngineState
import com.rtbishop.look4sat.core.domain.ft4.Ft4MessageValidation
import com.rtbishop.look4sat.core.domain.ft4.Ft4SpectrumFrame
import com.rtbishop.look4sat.core.domain.ft4.IFt4Service
import com.rtbishop.look4sat.core.domain.ft4.RealFft
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.ft4.Ft4Native
import com.rtbishop.look4sat.core.ft4.Ft4NativeDecoder
import com.rtbishop.look4sat.core.ft4.Ft4NativeDecoderOptions
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Ft4Service(
    private val scope: CoroutineScope,
    private val audioHub: IAudioHub,
    private val clock: IDisciplinedClock,
    private val decodeDispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            // 官方 WSJT-X Fortran 解码器包含较大的局部工作区，Android 默认线程栈不足。
            Thread(null, runnable, "Look4Sat-FT4-decode", DECODE_THREAD_STACK_BYTES)
        }
        .asCoroutineDispatcher()
) : IFt4Service {
    private val lifecycleLock = Any()
    private val mutableCapability = MutableStateFlow(Ft4Capability())
    private val mutableEngineState = MutableStateFlow<Ft4EngineState>(Ft4EngineState.Idle)
    private val mutableDecodeResults = MutableStateFlow<List<Ft4DecodeResult>>(emptyList())
    private var receiveJob: Job? = null
    private var sessionGeneration = 0L

    override val capability: StateFlow<Ft4Capability> = mutableCapability.asStateFlow()
    override val engineState: StateFlow<Ft4EngineState> = mutableEngineState.asStateFlow()
    override val decodeResults: StateFlow<List<Ft4DecodeResult>> = mutableDecodeResults.asStateFlow()

    override val spectrumFrames: Flow<Ft4SpectrumFrame> = flow {
        val fft = RealFft(SPECTRUM_FFT_SIZE)
        val rolling = FloatArray(SPECTRUM_FFT_SIZE)
        var buffered = 0
        var sequence = 0L
        var resampler: StreamingAudioResampler? = null
        var sourceRate = 0
        audioHub.audioFlow(AudioConsumer.FT4_SPECTRUM, AudioDelivery.LATEST).collect { chunk ->
            if (sourceRate != chunk.sampleRate) {
                sourceRate = chunk.sampleRate
                resampler = StreamingAudioResampler(sourceRate, FT4_SAMPLE_RATE)
                rolling.fill(0f)
                buffered = 0
            }
            val samples12k = requireNotNull(resampler).process(chunk.samples)
            if (samples12k.isEmpty()) return@collect
            if (samples12k.size >= rolling.size) {
                samples12k.copyInto(rolling, 0, samples12k.size - rolling.size)
                buffered = rolling.size
            } else {
                val shift = (buffered + samples12k.size - rolling.size).coerceAtLeast(0)
                if (shift > 0) {
                    rolling.copyInto(rolling, 0, shift, buffered)
                    buffered -= shift
                }
                samples12k.copyInto(rolling, buffered)
                buffered += samples12k.size
            }
            if (buffered == rolling.size) {
                val allBins = fft.magnitudeDb(rolling)
                emit(
                    Ft4SpectrumFrame(
                        sequence = sequence++,
                        sampleRate = FT4_SAMPLE_RATE,
                        frequencyStepHz = FT4_SAMPLE_RATE.toFloat() / SPECTRUM_FFT_SIZE,
                        magnitudesDb = allBins.copyOf(SPECTRUM_VISIBLE_BINS),
                        inputLevelDb = inputLevelDb(samples12k)
                    )
                )
            }
        }
    }

    init {
        scope.launch { refreshCapability() }
    }

    override suspend fun refreshCapability(): Ft4Capability = withContext(decodeDispatcher) {
        val resolved = runCatching {
            val native = Ft4Native.capabilities
            Ft4Capability(
                abi = native.abi,
                officialCoreAvailable = native.officialCoreAvailable,
                receiveAvailable = native.rxAvailable,
                transmitAvailable = native.txAvailable,
                nativeSelfTestPassed = mutableCapability.value.nativeSelfTestPassed,
                unavailableReason = native.unavailableReason,
                upstreamCommit = native.upstreamCommit
            )
        }.getOrElse { error ->
            Ft4Capability(unavailableReason = error.message ?: error.javaClass.simpleName)
        }
        mutableCapability.value = resolved
        if (!resolved.receiveAvailable && mutableEngineState.value !is Ft4EngineState.Failed) {
            mutableEngineState.value = Ft4EngineState.Unavailable(resolved)
        }
        resolved
    }

    override suspend fun runNativeSelfTest(): Result<Unit> = withContext(decodeDispatcher) {
        val result = runCatching { Ft4Native.runFt4SelfTest().getOrThrow() }
        mutableCapability.value = mutableCapability.value.copy(nativeSelfTestPassed = result.isSuccess)
        result
    }

    override fun startReceiving(options: Ft4DecoderOptions, myCall: String) {
        synchronized(lifecycleLock) {
            if (receiveJob?.isActive == true) return
            val generation = ++sessionGeneration
            receiveJob = scope.launch(decodeDispatcher) {
                receiveLoop(generation, options, myCall.trim().uppercase(Locale.US))
            }
        }
    }

    override suspend fun stopReceiving(clearResults: Boolean) {
        val job = synchronized(lifecycleLock) {
            sessionGeneration++
            receiveJob.also { receiveJob = null }
        }
        job?.cancelAndJoin()
        mutableEngineState.value = if (capability.value.receiveAvailable) {
            Ft4EngineState.Idle
        } else {
            Ft4EngineState.Unavailable(capability.value)
        }
        if (clearResults) clearDecodeResults()
    }

    override fun clearDecodeResults() {
        mutableDecodeResults.value = emptyList()
    }

    override suspend fun validateMessage(message: String): Ft4MessageValidation = withContext(decodeDispatcher) {
        val normalized = message.trim().uppercase(Locale.US).replace(WHITESPACE_REGEX, " ")
        if (!capability.value.transmitAvailable) {
            return@withContext Ft4MessageValidation(false, normalized, capability.value.unavailableReason)
        }
        val canonical = Ft4Native.validateFt4Message(normalized)
        if (canonical == null) {
            Ft4MessageValidation(false, normalized, "FT4 message cannot be packed")
        } else {
            Ft4MessageValidation(true, canonical)
        }
    }

    override suspend fun generateWaveform(
        message: String,
        audioFrequencyHz: Float,
        outputSampleRate: Int
    ): FloatArray = withContext(decodeDispatcher) {
        val validation = validateMessage(message)
        require(validation.valid) { validation.error }
        Ft4Native.generateFt4Waveform(validation.normalizedMessage, audioFrequencyHz, outputSampleRate)
    }

    private suspend fun receiveLoop(generation: Long, options: Ft4DecoderOptions, myCall: String) {
        var decoder: Ft4NativeDecoder? = null
        val assembler = Ft4SlotAssembler()
        val timeline = ResampledTimeline(clock)
        var decodedSlots = 0L
        try {
            val currentCapability = refreshCapability()
            if (!currentCapability.receiveAvailable || generation != sessionGeneration) return
            decoder = Ft4Native.createDecoder(clock.nowMillis())
            mutableEngineState.value = Ft4EngineState.Receiving(null, 0f, 0L, null)
            audioHub.audioFlow(AudioConsumer.FT4_DECODE, AudioDelivery.RELIABLE).collect { chunk ->
                if (generation != sessionGeneration || !currentCoroutineContext().isActive) return@collect
                val output = timeline.process(chunk)
                if (output.reset) assembler.reset()
                for (slot in assembler.append(output.samples, output.firstSampleUtcNanos)) {
                    val results = Ft4Native.decodeFt4Slot(
                        decoder = requireNotNull(decoder),
                        samples12k = slot.samples,
                        slotUtcMillis = slot.utcStartMillis,
                        options = options.toNative(),
                        myCall = myCall
                    ).map { native ->
                        Ft4DecodeResult(
                            slotUtcMillis = native.utcMillis,
                            snr = native.snr,
                            dtSeconds = native.dtSeconds,
                            frequencyHz = native.frequencyHz,
                            text = native.text,
                            sourceCall = native.sourceCall,
                            targetCall = native.targetCall,
                            gridOrReport = native.gridOrReport,
                            messageHash = native.messageHash
                        )
                    }
                    decodedSlots++
                    addResults(results)
                }
                val now = clock.nowMillis()
                mutableEngineState.value = Ft4EngineState.Receiving(
                    activeSlotUtcMillis = Math.floorDiv(now, SLOT_MILLIS) * SLOT_MILLIS,
                    slotProgress = Math.floorMod(now, SLOT_MILLIS).toFloat() / SLOT_MILLIS,
                    decodedSlots = decodedSlots,
                    audioSampleRate = chunk.sampleRate
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (generation == sessionGeneration) {
                mutableEngineState.value = Ft4EngineState.Failed(error.message ?: error.javaClass.simpleName)
            }
        } finally {
            decoder?.close()
            assembler.reset()
        }
    }

    private fun addResults(incoming: List<Ft4DecodeResult>) {
        if (incoming.isEmpty()) return
        val merged = LinkedHashMap<String, Ft4DecodeResult>(MAX_RESULT_HISTORY)
        incoming.forEach { merged[it.stableId] = it }
        mutableDecodeResults.value.forEach { merged.putIfAbsent(it.stableId, it) }
        mutableDecodeResults.value = merged.values.take(MAX_RESULT_HISTORY)
    }

    private class ResampledTimeline(private val clock: IDisciplinedClock) {
        private var resampler: StreamingAudioResampler? = null
        private var inputRate = 0
        private var baseMonotonicNanos = 0L
        private var emittedSamples = 0L
        private var expectedFramePosition: Long? = null

        fun process(chunk: TimestampedAudioChunk): ResampledOutput {
            val discontinuity = inputRate != chunk.sampleRate || expectedFramePosition != chunk.framePosition
            if (discontinuity) {
                inputRate = chunk.sampleRate
                resampler = StreamingAudioResampler(inputRate, FT4_SAMPLE_RATE)
                baseMonotonicNanos = chunk.elapsedRealtimeNanos
                emittedSamples = 0L
            }
            expectedFramePosition = chunk.framePosition + chunk.samples.size
            val output = requireNotNull(resampler).process(chunk.samples)
            val outputStartMonotonic = baseMonotonicNanos + emittedSamples * NANOS_PER_SECOND / FT4_SAMPLE_RATE
            emittedSamples += output.size
            return ResampledOutput(
                samples = output,
                firstSampleUtcNanos = clock.utcMillisAt(outputStartMonotonic) * NANOS_PER_MILLISECOND,
                reset = discontinuity
            )
        }
    }

    private data class ResampledOutput(
        val samples: FloatArray,
        val firstSampleUtcNanos: Long,
        val reset: Boolean
    )

    private companion object {
        const val DECODE_THREAD_STACK_BYTES = 8L * 1024L * 1024L
        const val FT4_SAMPLE_RATE = 12_000
        const val SLOT_MILLIS = 7_500L
        const val SPECTRUM_FFT_SIZE = 2_048
        const val SPECTRUM_VISIBLE_BINS = 513
        const val MAX_RESULT_HISTORY = 200
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val WHITESPACE_REGEX = Regex("\\s+")

        fun inputLevelDb(samples: FloatArray): Float {
            if (samples.isEmpty()) return -120f
            var power = 0.0
            for (sample in samples) power += sample * sample
            val rms = sqrt(power / samples.size).coerceAtLeast(1e-6)
            return (20.0 * log10(rms)).toFloat()
        }
    }
}

private fun Ft4DecoderOptions.toNative() = Ft4NativeDecoderOptions(
    decodePassCount = decodePassCount,
    multiDecodeRoundCount = multiDecodeRoundCount,
    earlyDecodeEnabled = earlyDecodeEnabled,
    widebandSearchEnabled = widebandSearchEnabled,
    qsoFrequencyHz = qsoFrequencyHz
)
