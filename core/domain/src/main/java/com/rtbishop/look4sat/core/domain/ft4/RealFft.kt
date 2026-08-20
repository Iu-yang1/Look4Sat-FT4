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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** 可复用缓冲的基数 2 实数 FFT，输出 0 Hz 到 Nyquist 的 dB 幅度。 */
class RealFft(val size: Int = 2_048) {
    private val real = FloatArray(size)
    private val imaginary = FloatArray(size)
    private val window = FloatArray(size) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (size - 1))).toFloat()
    }
    private val bitReversed = IntArray(size) { reverseBits(it, Integer.numberOfTrailingZeros(size)) }
    private val output = FloatArray(size / 2 + 1)

    init {
        require(size >= 256 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    /** 返回的数组由本实例复用；调用方需要长期保存时应自行复制。 */
    fun magnitudeDb(samples: FloatArray, offset: Int = 0): FloatArray {
        require(offset >= 0 && offset + size <= samples.size)
        for (index in 0 until size) {
            real[index] = samples[offset + index] * window[index]
            imaginary[index] = 0f
        }
        transformInPlace()
        val scale = 2f / window.sum()
        for (index in output.indices) {
            val amplitude = sqrt(real[index] * real[index] + imaginary[index] * imaginary[index]) * scale
            output[index] = (20.0 * log10(amplitude.coerceAtLeast(MIN_AMPLITUDE).toDouble())).toFloat()
        }
        return output
    }

    private fun transformInPlace() {
        for (index in 0 until size) {
            val target = bitReversed[index]
            if (target > index) {
                val tempReal = real[index]
                real[index] = real[target]
                real[target] = tempReal
                val tempImaginary = imaginary[index]
                imaginary[index] = imaginary[target]
                imaginary[target] = tempImaginary
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()
            val half = length / 2
            var start = 0
            while (start < size) {
                var twiddleReal = 1f
                var twiddleImaginary = 0f
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                start += length
            }
            length *= 2
        }
    }

    private companion object {
        const val MIN_AMPLITUDE = 1e-7f

        fun reverseBits(value: Int, bits: Int): Int {
            var source = value
            var result = 0
            repeat(bits) {
                result = result shl 1 or (source and 1)
                source = source ushr 1
            }
            return result
        }
    }
}
