/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.audio

import kotlin.math.abs
import kotlin.math.roundToInt

data class Ft4AudioSlot(
    val utcStartMillis: Long,
    val samples: FloatArray,
    val isFinal: Boolean = true,
    val validSampleCount: Int = samples.size
)

/** 将连续 12 kHz 音频按纪律 UTC 对齐为完整的 7.5 秒 FT4 时隙。 */
class Ft4SlotAssembler(
    private val earlyDecodeSamples: Int? = null
) {
    private val slotBuffer = FloatArray(SLOT_SAMPLES)
    private var buffered = 0
    private var targetSlotStartNanos: Long? = null
    private var expectedNextChunkNanos: Long? = null
    private var earlyDecodeEmitted = false

    var lastResetReason: String? = null
        private set

    init {
        require(earlyDecodeSamples == null || earlyDecodeSamples in 1 until SLOT_SAMPLES)
    }

    val bufferedSampleCount: Int
        get() = buffered

    val activeSlotStartMillis: Long?
        get() = targetSlotStartNanos?.div(NANOS_PER_MILLISECOND)

    fun append(samples: FloatArray, firstSampleUtcNanos: Long): List<Ft4AudioSlot> {
        if (samples.isEmpty()) return emptyList()
        val expected = expectedNextChunkNanos
        if (expected != null && abs(firstSampleUtcNanos - expected) > DISCONTINUITY_TOLERANCE_NANOS) {
            reset("timestamp_discontinuity")
        }
        expectedNextChunkNanos = firstSampleUtcNanos +
            samples.size.toLong() * NANOS_PER_SECOND / SAMPLE_RATE

        var sourceOffset = 0
        val completed = mutableListOf<Ft4AudioSlot>()
        while (sourceOffset < samples.size) {
            var target = targetSlotStartNanos
            if (target == null) {
                target = nextBoundary(firstSampleUtcNanos)
                targetSlotStartNanos = target
            }

            if (buffered == 0) {
                val relativeNanos = target - firstSampleUtcNanos
                if (relativeNanos >= 0L) {
                    val candidateOffset = (
                        relativeNanos.toDouble() * SAMPLE_RATE / NANOS_PER_SECOND
                        ).roundToInt()
                    if (candidateOffset >= samples.size) break
                    sourceOffset = maxOf(sourceOffset, candidateOffset)
                } else {
                    val lateNanos = firstSampleUtcNanos - target
                    if (lateNanos > HALF_SAMPLE_NANOS) {
                        target = nextBoundary(firstSampleUtcNanos)
                        targetSlotStartNanos = target
                        continue
                    }
                }
            }

            val copyCount = minOf(SLOT_SAMPLES - buffered, samples.size - sourceOffset)
            samples.copyInto(slotBuffer, buffered, sourceOffset, sourceOffset + copyCount)
            buffered += copyCount
            sourceOffset += copyCount
            if (!earlyDecodeEmitted && earlyDecodeSamples != null &&
                buffered >= earlyDecodeSamples && buffered < SLOT_SAMPLES
            ) {
                completed += Ft4AudioSlot(
                    utcStartMillis = target / NANOS_PER_MILLISECOND,
                    samples = slotBuffer.copyOf(),
                    isFinal = false,
                    validSampleCount = buffered
                )
                earlyDecodeEmitted = true
            }
            if (buffered == SLOT_SAMPLES) {
                completed += Ft4AudioSlot(target / NANOS_PER_MILLISECOND, slotBuffer.copyOf())
                buffered = 0
                earlyDecodeEmitted = false
                targetSlotStartNanos = target + SLOT_NANOS
            }
        }
        return completed
    }

    fun reset(reason: String = "manual") {
        slotBuffer.fill(0f)
        buffered = 0
        earlyDecodeEmitted = false
        targetSlotStartNanos = null
        expectedNextChunkNanos = null
        lastResetReason = reason
    }

    private fun nextBoundary(utcNanos: Long): Long =
        Math.floorDiv(utcNanos, SLOT_NANOS) * SLOT_NANOS + SLOT_NANOS

    companion object {
        const val SAMPLE_RATE = 12_000
        const val SLOT_SAMPLES = 90_000
        const val SLOT_MILLIS = 7_500L
        private const val SLOT_NANOS = SLOT_MILLIS * 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val HALF_SAMPLE_NANOS = NANOS_PER_SECOND / SAMPLE_RATE / 2
        private const val DISCONTINUITY_TOLERANCE_NANOS = 2_000_000L
    }
}
