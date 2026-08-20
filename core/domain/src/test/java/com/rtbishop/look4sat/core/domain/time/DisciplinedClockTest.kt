/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisciplinedClockTest {
    @Test
    fun trustedTimeUsesMonotonicClockAndIgnoresWallJump() {
        val source = MutableTimeSource(wallMillis = 1_000_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(clock.submitSample(source.sample(1_000_120.0)))
        source.advance(5_000L)
        assertEquals(1_005_120L, clock.nowMillis())

        source.wallMillis += 3_600_000L
        assertEquals(1_005_120L, clock.nowMillis())
        assertTrue(clock.automaticFt4TransmitAllowed())
    }

    @Test
    fun ft4AutomaticTransmitRequiresHealthyFreshSub250msTrustedTime() {
        val source = MutableTimeSource(wallMillis = 20_000L)
        val clock = SystemDisciplinedClock(source)
        assertFalse(clock.automaticFt4TransmitAllowed())
        assertTrue(clock.submitSample(source.sample(20_000.0, uncertaintyMillis = 300.0)))
        assertFalse(clock.automaticFt4TransmitAllowed())
        assertTrue(clock.automaticFt4TransmitBlockReason().contains("250ms"))

        val precise = SystemDisciplinedClock(source)
        assertTrue(precise.submitSample(source.sample(20_000.0, uncertaintyMillis = 20.0)))
        assertTrue(precise.automaticFt4TransmitAllowed())
    }

    @Test
    fun holdoverAndExpiredSamplesBlockAutomaticTransmit() {
        val source = MutableTimeSource(wallMillis = 10_000L)
        val policy = ClockHealthPolicy(holdoverAfterMillis = 1_000L, maximumSampleAgeMillis = 5_000L)
        val clock = SystemDisciplinedClock(source, policy)
        assertTrue(clock.submitSample(source.sample(10_000.0)))
        source.advance(2_000L)
        assertEquals(ClockSource.HOLDOVER, clock.snapshot().source)
        assertTrue(clock.automaticFt4TransmitAllowed())
        source.advance(4_000L)
        assertFalse(clock.snapshot().healthy)
        assertFalse(clock.automaticFt4TransmitAllowed())
    }

    @Test
    fun backwardReacquisitionNeverMovesPublishedUtcBackward() {
        val source = MutableTimeSource(wallMillis = 100_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(clock.submitSample(source.sample(100_000.0, consensus = 2)))
        source.advance(1_000L)
        assertEquals(101_000L, clock.nowMillis())
        val backward = source.sample(96_000.0, consensus = 2)
        assertFalse(clock.submitSample(backward))
        assertFalse(clock.submitSample(backward))
        assertTrue(clock.submitSample(backward))
        assertEquals(101_000L, clock.nowMillis())
        assertFalse(clock.automaticFt4TransmitAllowed())
    }

    @Test
    fun gnssRemainsPrimaryWhileNtpOnlyCrossChecks() {
        val source = MutableTimeSource(wallMillis = 50_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(clock.submitSample(source.sample(50_000.0, clockSource = ClockSource.GNSS)))
        source.advance(1_000L)
        assertTrue(clock.submitSample(source.sample(51_010.0, consensus = 3)))
        assertEquals(ClockSource.GNSS, clock.snapshot().source)
        assertTrue(clock.snapshot().detail.contains("交叉校验"))

        assertFalse(clock.submitSample(source.sample(55_000.0, consensus = 3)))
        assertEquals(ClockSource.GNSS, clock.snapshot().source)
        assertTrue(clock.snapshot().lastRejectedReason.contains("NTP 与 GNSS"))
    }

    @Test
    fun untrustedSystemClockCanReanchorButStaysUnhealthy() {
        val source = MutableTimeSource(wallMillis = 1_000L)
        val clock = SystemDisciplinedClock(source)
        source.advance(100L)
        source.wallMillis += 10_000L
        val snapshot = clock.refresh()
        assertEquals(source.wallMillis, snapshot.utcMillis)
        assertFalse(snapshot.healthy)
        assertEquals(ClockSource.SYSTEM, snapshot.source)
    }

    @Test
    fun gnssConversionAppliesFullBiasFractionalBiasAndLeapSeconds() {
        val utc = GnssTimeConverter.toUtcMillis(
            timeNanos = 86_400_000_000_000L,
            fullBiasNanos = 0L,
            biasNanos = 0.0,
            leapSeconds = 18
        )
        assertEquals(316_051_182_000.0, utc, 0.001)

        val biased = GnssTimeConverter.toUtcMillis(
            timeNanos = 1_000_000_000L,
            fullBiasNanos = -2_000_000_000L,
            biasNanos = 500_000.0,
            leapSeconds = 0
        )
        assertEquals(315_964_802_999.5, biased, 0.001)
    }

    private class MutableTimeSource(
        var wallMillis: Long,
        var monoNanos: Long = 0L
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = monoNanos
        override fun wallClockMillis(): Long = wallMillis

        fun advance(millis: Long) {
            wallMillis += millis
            monoNanos += millis * 1_000_000L
        }

        fun sample(
            utcMillis: Double,
            uncertaintyMillis: Double = 5.0,
            consensus: Int = 1,
            clockSource: ClockSource = ClockSource.NTP
        ) = ClockSample(
            utcMillis = utcMillis,
            monotonicNanos = monoNanos,
            uncertaintyMillis = uncertaintyMillis,
            source = clockSource,
            consensusMembers = consensus
        )
    }
}
