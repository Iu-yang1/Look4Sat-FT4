package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.domain.audio.AudioSampleFormat
import com.rtbishop.look4sat.core.domain.audio.TimestampedAudioChunk
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

class ReceiveMeterTest {
    private fun chunk(samples: FloatArray, frame: Long, rate: Int = 12000, gap: Boolean = false) =
        TimestampedAudioChunk(samples, rate, frame, 0, AudioSampleFormat.PCM_FLOAT, gap)

    @Test fun levelsAndClippingUseActualSamples() {
        val meter = ReceiveMeter()
        meter.add(chunk(floatArrayOf(.5f, -.5f, 1f, -1f), 0))
        val result = meter.measurement("USB")
        assertEquals(4L, result.samples)
        assertEquals(2L, result.clippedSamples)
        assertEquals(0.0, result.peakDbfs, .00001)
        assertEquals(-2.0412, result.rmsDbfs, .0001)
        assertEquals("USB", result.source)
    }

    @Test fun timelineDetectsMissingSamplesRateChangeAndExplicitGapOnceEach() {
        val meter = ReceiveMeter()
        meter.add(chunk(FloatArray(120), 0))
        meter.add(chunk(FloatArray(120), 120))
        meter.add(chunk(FloatArray(120), 360))
        meter.add(chunk(FloatArray(120), 480, 48000))
        meter.add(chunk(FloatArray(120), 600, 48000, true))
        assertEquals(3L, meter.measurement("").gaps)
        assertEquals(-120.0, meter.measurement("").rmsDbfs, 0.0)
    }

    @Test fun invalidSamplesDoNotBecomeSuccessfulSilence() {
        for (samples in listOf(floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY))) {
            assertTrue(runCatching { ReceiveMeter().add(chunk(samples, 0)) }.isFailure)
        }
        assertTrue(runCatching { ReceiveMeter().add(chunk(floatArrayOf(0f), 0, 0)) }.isFailure)
    }

    @Test fun transmitToneIsOneSecondAtOneKilohertzWithRampsAndLimitedLevel() {
        val samples = testTone()
        assertEquals(12000, samples.size)
        assertEquals(0.0, samples.first().toDouble(), 0.0)
        assertEquals(0.0, samples.last().toDouble(), 0.0)
        assertTrue(samples.all { abs(it) <= .100001 })
        var real = 0.0
        var imaginary = 0.0
        samples.forEachIndexed { index, sample ->
            real += sample * cos(2 * PI * index / 12)
            imaginary += sample * sin(2 * PI * index / 12)
        }
        assertTrue(abs(imaginary) > 580)
        assertEquals(0.0, real, 1.0)
        assertTrue(abs(samples[3]) < abs(samples[123]))
    }
}
