/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.time

import com.rtbishop.look4sat.core.domain.time.ClockSample
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.time.MonotonicTimeSource
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SntpClientTest {
    @Test
    fun parsesValidatedFourTimestampResponseAndRtt() {
        LocalNtpServer(ResponseMode.VALID, serverOffsetMillis = 120.0).use { server ->
            val result = SntpClient(1_000, JvmTimeSource).query("127.0.0.1", server.port)
            assertTrue(abs(result.offsetMillis - 120.0) < 35.0)
            assertTrue(result.roundTripDelayMillis >= 0.0)
            assertTrue(result.sample.uncertaintyMillis >= 1.0)
            assertEquals(2, result.stratum)
        }
    }

    @Test
    fun rejectsOriginateMismatchKissOfDeathAndInvalidMode() {
        for (mode in listOf(ResponseMode.BAD_ORIGINATE, ResponseMode.KISS_OF_DEATH, ResponseMode.BAD_MODE)) {
            LocalNtpServer(mode).use { server ->
                assertThrows(NtpProtocolException::class.java) {
                    SntpClient(1_000, JvmTimeSource).query("127.0.0.1", server.port)
                }
            }
        }
    }

    @Test
    fun multiSourceConsensusRejectsOffsetOutlier() {
        val discipline = MultiSourceNtpDiscipline { host ->
            val offset = when (host) {
                "time.google.com" -> 10.0
                "time.cloudflare.com" -> 12.0
                else -> 1_000.0
            }
            measurement(host, offset)
        }
        val result = discipline.synchronize()
        assertEquals(11.0, result.offsetMillis, 0.001)
        assertEquals(2, result.sample.consensusMembers)
        assertTrue(result.sample.uncertaintyMillis >= 1.0)
    }

    @Test
    fun multiSourceRequiresAtLeastTwoSuccessfulServers() {
        val discipline = MultiSourceNtpDiscipline { host ->
            if (host == "time.google.com") measurement(host, 10.0) else throw IOException("offline")
        }
        assertThrows(IOException::class.java) { discipline.synchronize() }
    }

    @Test
    fun multiSourceRechecksConsensusAfterOutlierRemoval() {
        val discipline = MultiSourceNtpDiscipline { host ->
            val offset = when (host) {
                "time.google.com" -> -1_000.0
                "time.cloudflare.com" -> 0.0
                else -> 1_000.0
            }
            measurement(host, offset)
        }

        val failure = assertThrows(IOException::class.java) { discipline.synchronize() }

        assertTrue(failure.message?.contains("单源降级") == true)
    }

    private fun measurement(host: String, offset: Double): NtpMeasurement = NtpMeasurement(
        server = host,
        offsetMillis = offset,
        roundTripDelayMillis = 10.0,
        rootDispersionMillis = 1.0,
        stratum = 2,
        sample = ClockSample(
            utcMillis = 1_000_000.0 + offset,
            monotonicNanos = 1_000L,
            uncertaintyMillis = 5.0,
            source = ClockSource.NTP
        )
    )

    private enum class ResponseMode { VALID, BAD_ORIGINATE, KISS_OF_DEATH, BAD_MODE }

    private class LocalNtpServer(
        private val mode: ResponseMode,
        private val serverOffsetMillis: Double = 0.0
    ) : AutoCloseable {
        private val socket = DatagramSocket(0)
        private val completed = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        val port: Int = socket.localPort

        init {
            thread(name = "local-ntp-test", isDaemon = true) {
                try {
                    val request = ByteArray(SntpClient.PACKET_SIZE)
                    val packet = DatagramPacket(request, request.size)
                    socket.receive(packet)
                    val response = ByteArray(SntpClient.PACKET_SIZE)
                    response[0] = if (mode == ResponseMode.BAD_MODE) 0x23 else 0x24
                    response[1] = if (mode == ResponseMode.KISS_OF_DEATH) 0 else 2
                    if (mode == ResponseMode.KISS_OF_DEATH) {
                        "RATE".toByteArray(Charsets.US_ASCII).copyInto(response, SntpClient.REFERENCE_ID_OFFSET)
                    }
                    request.copyInto(
                        response,
                        destinationOffset = SntpClient.ORIGINATE_OFFSET,
                        startIndex = SntpClient.TRANSMIT_OFFSET,
                        endIndex = SntpClient.PACKET_SIZE
                    )
                    if (mode == ResponseMode.BAD_ORIGINATE) response[SntpClient.ORIGINATE_OFFSET]++
                    val now = System.currentTimeMillis() + serverOffsetMillis
                    SntpClient.writeNtpTimestamp(response, SntpClient.RECEIVE_OFFSET, now)
                    SntpClient.writeNtpTimestamp(response, SntpClient.TRANSMIT_OFFSET, now + 1.0)
                    socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                } catch (error: Throwable) {
                    if (!socket.isClosed) failure.set(error)
                } finally {
                    completed.countDown()
                }
            }
        }

        override fun close() {
            completed.await(2, TimeUnit.SECONDS)
            socket.close()
            failure.get()?.let { throw AssertionError("local NTP server failed", it) }
        }
    }

    private object JvmTimeSource : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = System.nanoTime()
        override fun wallClockMillis(): Long = System.currentTimeMillis()
    }
}
