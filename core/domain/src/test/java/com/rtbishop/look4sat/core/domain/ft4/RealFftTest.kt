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
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RealFftTest {
    @Test
    fun detectsKnownToneAtExpectedBin() {
        val size = 2_048
        val sampleRate = 12_000
        val bin = 171
        val samples = FloatArray(size) { index ->
            sin(2.0 * PI * bin * index / size).toFloat()
        }
        val spectrum = RealFft(size).magnitudeDb(samples)
        val peak = spectrum.indices.maxBy { spectrum[it] }
        assertEquals(bin, peak)
        assertTrue(spectrum[peak] > -1f)
        assertEquals(bin * sampleRate.toFloat() / size, peak * sampleRate.toFloat() / size, 0f)
    }

    @Test
    fun reusesOutputBuffer() {
        val fft = RealFft(1_024)
        val samples = FloatArray(1_024)
        assertSame(fft.magnitudeDb(samples), fft.magnitudeDb(samples))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwoSize() {
        RealFft(1_000)
    }
}
