package com.rtbishop.look4sat.core.domain.ft4

import org.junit.Assert.*
import org.junit.Test

class Ft4WaveformSpecTest {
    @Test fun physicalFrameHasBothRampSymbolsAndStartsHalfASecondIntoTheSlot() {
        for((rate,samples) in listOf(12000 to 60480,24000 to 120960,48000 to 241920)) {
            val spec=Ft4WaveformSpec.ft4(rate)
            assertEquals(samples,spec.sampleCount)
            assertEquals(5040,spec.durationMillis)
            assertEquals(500,spec.startOffsetMillis)
            assertEquals(7500,spec.slotMillis)
        }
    }
    @Test fun unsupportedSampleRateIsRejectedBeforeSynthesis() {
        assertTrue(runCatching { Ft4WaveformSpec.ft4(44100) }.isFailure)
    }
}
