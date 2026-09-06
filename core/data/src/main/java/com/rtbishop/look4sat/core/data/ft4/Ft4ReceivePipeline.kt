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

import com.rtbishop.look4sat.core.domain.audio.Ft4AudioSlot
import com.rtbishop.look4sat.core.domain.audio.Ft4SlotAssembler
import com.rtbishop.look4sat.core.domain.audio.StreamingAudioResampler
import com.rtbishop.look4sat.core.domain.audio.TimestampedAudioChunk
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal data class Ft4ReceivePipelineSnapshot(
    val decodedSlots: Long = 0,
    val lastDecodeDurationMillis: Long? = null,
    val lastDecodeResultCount: Int = 0,
    val lastDecodedSlotUtcMillis: Long? = null,
    val assemblingSlotUtcMillis: Long? = null,
    val assembledSampleCount: Int = 0,
    val droppedAudioBlocks: Long = 0,
    val captureQueueDepth: Int = 0,
    val decodeQueueDepth: Int = 0,
    val audioSampleRate: Int? = null,
    val timestampResidualMillis: Double? = null
)

internal data class Ft4ResampledOutput(
    val samples: FloatArray,
    val firstSampleUtcNanos: Long,
    val reset: Boolean,
    val droppedBlock: Boolean,
    val timestampResidualNanos: Long?
)

/**
 * 把 AudioRecord 的 BOOTTIME 帧时间轴映射到纪律 UTC。帧号或时间戳发生跳跃时立即重置，
 * 使休眠前的残缺时隙不会和唤醒后的音频拼接。
 */
internal class Ft4ResampledTimeline(private val clock: IDisciplinedClock) {
    private var resampler: StreamingAudioResampler? = null
    private var inputRate = 0
    private var baseBootTimeNanos = 0L
    private var emittedSamples = 0L
    private var expectedFramePosition: Long? = null
    private var expectedChunkBootTimeNanos: Long? = null
    private var initialized = false

    fun process(chunk: TimestampedAudioChunk): Ft4ResampledOutput {
        val expectedBootTime = expectedChunkBootTimeNanos
        val timestampResidual = expectedBootTime?.let { chunk.elapsedRealtimeNanos - it }
        val frameGap = expectedFramePosition?.let { it != chunk.framePosition } ?: false
        val timeGap = timestampResidual?.let { abs(it) > TIMESTAMP_DISCONTINUITY_NANOS } ?: false
        val rateChange = initialized && inputRate != chunk.sampleRate
        val discontinuity = !initialized || rateChange || frameGap || timeGap
        val droppedBlock = initialized && (rateChange || frameGap || timeGap)
        if (discontinuity) {
            inputRate = chunk.sampleRate
            resampler = StreamingAudioResampler(inputRate, FT4_SAMPLE_RATE)
            baseBootTimeNanos = chunk.elapsedRealtimeNanos
            emittedSamples = 0L
            initialized = true
        }
        expectedFramePosition = chunk.framePosition + chunk.samples.size
        expectedChunkBootTimeNanos = chunk.elapsedRealtimeNanos +
            chunk.samples.size.toLong() * NANOS_PER_SECOND / chunk.sampleRate

        val output = requireNotNull(resampler).process(chunk.samples)
        val outputStartBootTime = baseBootTimeNanos + emittedSamples * NANOS_PER_SECOND / FT4_SAMPLE_RATE
        emittedSamples += output.size
        return Ft4ResampledOutput(
            samples = output,
            firstSampleUtcNanos = clock.utcMillisAt(outputStartBootTime) * NANOS_PER_MILLISECOND,
            reset = discontinuity,
            droppedBlock = droppedBlock,
            timestampResidualNanos = timestampResidual
        )
    }

    private companion object {
        const val FT4_SAMPLE_RATE = 12_000
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val TIMESTAMP_DISCONTINUITY_NANOS = 20_000_000L
    }
}

/** 录音摄取、时隙装配和 native 解码之间使用独立有界队列。 */
internal class Ft4ReceivePipeline(
    private val clock: IDisciplinedClock,
    private val assemblyDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val captureQueueCapacity: Int = DEFAULT_CAPTURE_QUEUE_CAPACITY,
    private val decodeQueueCapacity: Int = DEFAULT_DECODE_QUEUE_CAPACITY
) {
    suspend fun run(
        audio: Flow<TimestampedAudioChunk>,
        decodeSlot: suspend (Ft4AudioSlot) -> Int,
        onSnapshot: (Ft4ReceivePipelineSnapshot) -> Unit
    ) = coroutineScope {
        val chunks = Channel<TimestampedAudioChunk>(captureQueueCapacity)
        val slots = Channel<Ft4AudioSlot>(decodeQueueCapacity)
        val captureDepth = AtomicInteger(0)
        val decodeDepth = AtomicInteger(0)
        val droppedBlocks = AtomicLong(0L)
        val assembler = Ft4SlotAssembler()
        val timeline = Ft4ResampledTimeline(clock)
        val snapshotLock = Any()
        var snapshot = Ft4ReceivePipelineSnapshot()

        fun publish(transform: (Ft4ReceivePipelineSnapshot) -> Ft4ReceivePipelineSnapshot) {
            synchronized(snapshotLock) {
                snapshot = transform(snapshot).copy(
                    droppedAudioBlocks = droppedBlocks.get(),
                    captureQueueDepth = captureDepth.get(),
                    decodeQueueDepth = decodeDepth.get()
                )
                onSnapshot(snapshot)
            }
        }

        val decodeJob = launch {
            for (slot in slots) {
                decodeDepth.decrementAndGet()
                val started = System.nanoTime()
                val resultCount = decodeSlot(slot)
                val duration = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
                publish { current ->
                    current.copy(
                        decodedSlots = current.decodedSlots + 1,
                        lastDecodeDurationMillis = duration,
                        lastDecodeResultCount = resultCount,
                        lastDecodedSlotUtcMillis = slot.utcStartMillis
                    )
                }
            }
        }
        val assemblyJob = launch(assemblyDispatcher) {
            try {
                for (chunk in chunks) {
                    captureDepth.decrementAndGet()
                    val output = timeline.process(chunk)
                    if (output.reset) assembler.reset()
                    if (output.droppedBlock) droppedBlocks.incrementAndGet()
                    val completed = assembler.append(output.samples, output.firstSampleUtcNanos)
                    publish { current ->
                        current.copy(
                            audioSampleRate = chunk.sampleRate,
                            assemblingSlotUtcMillis = assembler.activeSlotStartMillis,
                            assembledSampleCount = assembler.bufferedSampleCount,
                            timestampResidualMillis = output.timestampResidualNanos
                                ?.div(NANOS_PER_MILLISECOND.toDouble())
                        )
                    }
                    for (slot in completed) {
                        decodeDepth.incrementAndGet()
                        slots.send(slot)
                    }
                }
            } finally {
                slots.close()
            }
        }
        val captureJob = launch {
            try {
                audio.collect { chunk ->
                    val result = chunks.trySend(chunk)
                    if (result.isSuccess) {
                        captureDepth.incrementAndGet()
                    } else {
                        droppedBlocks.incrementAndGet()
                        publish { it }
                    }
                }
            } finally {
                chunks.close()
            }
        }
        joinAll(captureJob, assemblyJob, decodeJob)
    }

    private companion object {
        const val DEFAULT_CAPTURE_QUEUE_CAPACITY = Channel.UNLIMITED
        const val DEFAULT_DECODE_QUEUE_CAPACITY = 24
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
