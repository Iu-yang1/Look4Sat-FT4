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
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

data class NtpMeasurement(
    val server: String,
    val offsetMillis: Double,
    val roundTripDelayMillis: Double,
    val rootDispersionMillis: Double,
    val stratum: Int,
    val sample: ClockSample
)

class NtpProtocolException(message: String) : IOException(message)

fun interface NtpQueryClient {
    @Throws(IOException::class)
    fun query(host: String): NtpMeasurement
}

/** 经完整响应身份、服务器状态和四时间戳校验的最小 SNTPv4 客户端。 */
class SntpClient(
    private val timeoutMillis: Int = 3_000,
    private val timeSource: MonotonicTimeSource = AndroidMonotonicTimeSource
) : NtpQueryClient {
    override fun query(host: String): NtpMeasurement = query(host, NTP_PORT)

    @Throws(IOException::class)
    fun query(host: String, port: Int): NtpMeasurement {
        require(host.isNotBlank()) { "NTP 服务器不能为空" }
        val address = InetAddress.getByName(host)
        val request = ByteArray(PACKET_SIZE)
        request[0] = ((NTP_VERSION shl 3) or MODE_CLIENT).toByte()

        val t1Wall = timeSource.wallClockMillis().toDouble()
        val t1Mono = timeSource.elapsedRealtimeNanos()
        writeNtpTimestamp(request, TRANSMIT_OFFSET, t1Wall)
        val requestTransmit = request.copyOfRange(TRANSMIT_OFFSET, TRANSMIT_OFFSET + 8)

        val response = ByteArray(PACKET_SIZE)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            socket.connect(address, port)
            socket.send(DatagramPacket(request, request.size, address, port))
            val packet = DatagramPacket(response, response.size)
            try {
                socket.receive(packet)
            } catch (error: SocketTimeoutException) {
                throw IOException("NTP $host 在 ${timeoutMillis}ms 后超时", error)
            }
            if (packet.length < PACKET_SIZE) {
                throw NtpProtocolException("NTP $host 仅返回 ${packet.length} 字节")
            }
        }

        val t4Mono = timeSource.elapsedRealtimeNanos()
        val t4Wall = timeSource.wallClockMillis().toDouble()
        val monotonicElapsedMs = (t4Mono - t1Mono) / 1_000_000.0
        if (abs((t4Wall - t1Wall) - monotonicElapsedMs) > MAX_WALL_JUMP_DURING_QUERY_MS) {
            throw NtpProtocolException("NTP 查询期间本地系统时间发生跳变")
        }

        validateResponse(host, response, requestTransmit)
        val stratum = response[1].toInt() and 0xff
        val t2 = readNtpTimestamp(response, RECEIVE_OFFSET, t4Wall)
        val t3 = readNtpTimestamp(response, TRANSMIT_OFFSET, t4Wall)
        if (t2 == 0.0 || t3 == 0.0 || t3 < t2) {
            throw NtpProtocolException("NTP $host 返回无效的接收/发送时间戳")
        }
        val offset = ((t2 - t1Wall) + (t3 - t4Wall)) / 2.0
        val delay = (t4Wall - t1Wall) - (t3 - t2)
        if (delay < -MAX_NEGATIVE_DELAY_MS || delay > MAX_ACCEPTED_DELAY_MS) {
            throw NtpProtocolException("NTP $host 往返延迟无效: ${delay.toInt()}ms")
        }
        val rootDispersion = readUnsignedFixed16_16(response, ROOT_DISPERSION_OFFSET) * 1_000.0
        val boundedDelay = max(0.0, delay)
        val uncertainty = max(MIN_UNCERTAINTY_MS, boundedDelay / 2.0 + rootDispersion)
        return NtpMeasurement(
            server = host,
            offsetMillis = offset,
            roundTripDelayMillis = boundedDelay,
            rootDispersionMillis = rootDispersion,
            stratum = stratum,
            sample = ClockSample(
                utcMillis = t4Wall + offset,
                monotonicNanos = t4Mono,
                uncertaintyMillis = uncertainty,
                source = ClockSource.NTP,
                detail = "NTP $host stratum=$stratum rtt=${boundedDelay.toInt()}ms",
                roundTripDelayMillis = boundedDelay
            )
        )
    }

    private fun validateResponse(host: String, response: ByteArray, requestTransmit: ByteArray) {
        val leap = (response[0].toInt() ushr 6) and 0x3
        val version = (response[0].toInt() ushr 3) and 0x7
        val mode = response[0].toInt() and 0x7
        val stratum = response[1].toInt() and 0xff
        if (leap == LEAP_UNSYNCHRONIZED) {
            throw NtpProtocolException("NTP $host 尚未同步")
        }
        if (version !in 3..4 || mode != MODE_SERVER) {
            throw NtpProtocolException("NTP $host version/mode 无效: $version/$mode")
        }
        if (stratum == 0) {
            val code = String(response, REFERENCE_ID_OFFSET, 4, StandardCharsets.US_ASCII)
            throw NtpProtocolException("NTP $host Kiss-o'-Death: $code")
        }
        if (stratum > MAX_STRATUM) {
            throw NtpProtocolException("NTP $host stratum 无效: $stratum")
        }
        val originate = response.copyOfRange(ORIGINATE_OFFSET, ORIGINATE_OFFSET + 8)
        if (!originate.contentEquals(requestTransmit)) {
            throw NtpProtocolException("NTP $host originate timestamp 不匹配")
        }
    }

    companion object {
        private const val NTP_PORT = 123
        internal const val PACKET_SIZE = 48
        private const val NTP_VERSION = 4
        private const val MODE_CLIENT = 3
        internal const val MODE_SERVER = 4
        private const val LEAP_UNSYNCHRONIZED = 3
        private const val MAX_STRATUM = 15
        internal const val ROOT_DISPERSION_OFFSET = 8
        internal const val REFERENCE_ID_OFFSET = 12
        internal const val ORIGINATE_OFFSET = 24
        internal const val RECEIVE_OFFSET = 32
        internal const val TRANSMIT_OFFSET = 40
        private const val UNIX_TO_NTP_SECONDS = 2_208_988_800L
        private const val NTP_ERA_SECONDS = 4_294_967_296L
        private const val MAX_WALL_JUMP_DURING_QUERY_MS = 100.0
        private const val MAX_NEGATIVE_DELAY_MS = 5.0
        private const val MAX_ACCEPTED_DELAY_MS = 5_000.0
        private const val MIN_UNCERTAINTY_MS = 1.0

        internal fun writeNtpTimestamp(buffer: ByteArray, offset: Int, unixMillis: Double) {
            val ntpSeconds = unixMillis / 1_000.0 + UNIX_TO_NTP_SECONDS
            val seconds = ntpSeconds.toLong()
            val fraction = ((ntpSeconds - seconds) * 4_294_967_296.0).toLong()
            writeUnsigned32(buffer, offset, seconds)
            writeUnsigned32(buffer, offset + 4, fraction)
        }

        internal fun readNtpTimestamp(
            buffer: ByteArray,
            offset: Int,
            referenceUnixMillis: Double
        ): Double {
            val seconds32 = readUnsigned32(buffer, offset)
            val fraction = readUnsigned32(buffer, offset + 4) / 4_294_967_296.0
            if (seconds32 == 0L && fraction == 0.0) return 0.0
            var unixSeconds = seconds32 - UNIX_TO_NTP_SECONDS
            val referenceSeconds = (referenceUnixMillis / 1_000.0).toLong()
            while (unixSeconds - referenceSeconds > NTP_ERA_SECONDS / 2) unixSeconds -= NTP_ERA_SECONDS
            while (referenceSeconds - unixSeconds > NTP_ERA_SECONDS / 2) unixSeconds += NTP_ERA_SECONDS
            return (unixSeconds + fraction) * 1_000.0
        }

        private fun readUnsignedFixed16_16(buffer: ByteArray, offset: Int): Double =
            readUnsigned32(buffer, offset) / 65_536.0

        private fun readUnsigned32(buffer: ByteArray, offset: Int): Long =
            ((buffer[offset].toLong() and 0xffL) shl 24) or
                ((buffer[offset + 1].toLong() and 0xffL) shl 16) or
                ((buffer[offset + 2].toLong() and 0xffL) shl 8) or
                (buffer[offset + 3].toLong() and 0xffL)

        private fun writeUnsigned32(buffer: ByteArray, offset: Int, value: Long) {
            buffer[offset] = (value ushr 24).toByte()
            buffer[offset + 1] = (value ushr 16).toByte()
            buffer[offset + 2] = (value ushr 8).toByte()
            buffer[offset + 3] = value.toByte()
        }
    }
}

/** 并发查询多个服务器，以 offset 中位数剔除离群值。 */
class MultiSourceNtpDiscipline(private val client: NtpQueryClient = SntpClient()) {
    @Throws(IOException::class)
    fun synchronize(): NtpMeasurement {
        val executor = Executors.newFixedThreadPool(DEFAULT_SERVERS.size) { runnable ->
            Thread(runnable, "look4sat-ntp-query").apply { isDaemon = true }
        }
        val failures = mutableListOf<String>()
        val measurements = try {
            val futures = executor.invokeAll(
                DEFAULT_SERVERS.map { server -> Callable { client.query(server) } },
                QUERY_BATCH_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            futures.mapIndexedNotNull { index, future ->
                if (future.isCancelled) {
                    failures += "${DEFAULT_SERVERS[index]} timeout"
                    null
                } else {
                    try {
                        future.get()
                    } catch (error: Exception) {
                        failures += "${DEFAULT_SERVERS[index]} ${error.cause?.message ?: error.message}"
                        null
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
        if (measurements.isEmpty()) {
            throw IOException("所有 NTP 源失败: ${failures.joinToString("; ")}")
        }
        if (measurements.size < MIN_CONSENSUS_SOURCES) {
            throw IOException("NTP 一致性样本不足: ${measurements.size}/$MIN_CONSENSUS_SOURCES")
        }

        val medianOffset = measurements.map { it.offsetMillis }.sorted().median()
        val inliers = measurements.filter {
            abs(it.offsetMillis - medianOffset) <=
                max(MIN_OUTLIER_WINDOW_MS, it.sample.uncertaintyMillis * 4.0)
        }
        if (inliers.size < MIN_CONSENSUS_SOURCES) {
            throw IOException(
                "NTP 最终一致性集合不足: ${inliers.size}/$MIN_CONSENSUS_SOURCES；" +
                    "单源降级，禁止自动发射"
            )
        }
        val best = inliers.minBy { it.sample.uncertaintyMillis }
        val fusedOffset = inliers.map { it.offsetMillis }.sorted().median()
        val correction = fusedOffset - best.offsetMillis
        return best.copy(
            offsetMillis = fusedOffset,
            sample = best.sample.copy(
                utcMillis = best.sample.utcMillis + correction,
                uncertaintyMillis = max(
                    best.sample.uncertaintyMillis,
                    inliers.maxOf { abs(it.offsetMillis - fusedOffset) }
                ),
                detail = "NTP ${inliers.size}/${DEFAULT_SERVERS.size} sources rtt=${best.roundTripDelayMillis.toInt()}ms",
                consensusMembers = inliers.size
            )
        )
    }

    private fun List<Double>.median(): Double {
        val middle = size / 2
        return if (size % 2 == 0) (this[middle - 1] + this[middle]) / 2.0 else this[middle]
    }

    companion object {
        val DEFAULT_SERVERS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
        private const val QUERY_BATCH_TIMEOUT_MS = 4_500L
        private const val MIN_OUTLIER_WINDOW_MS = 250.0
        private const val MIN_CONSENSUS_SOURCES = 2
    }
}
