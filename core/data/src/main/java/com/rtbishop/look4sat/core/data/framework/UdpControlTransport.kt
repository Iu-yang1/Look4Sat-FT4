/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class UdpControlTransport(
    private val host: String,
    private val port: Int,
    private val localPort: Int? = null,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS
) : ControlTransport {
    @Volatile
    private var socket: DatagramSocket? = null

    override val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (
            host.isBlank() ||
            port !in 1..65_535 ||
            localPort != null && localPort !in 1..65_535 ||
            readTimeoutMillis <= 0 ||
            connectTimeoutMillis <= 0
        ) return@withContext false

        disconnect()
        val candidate = DatagramSocket(null)
        socket = candidate
        try {
            val connected = withTimeoutOrNull(connectTimeoutMillis) {
                runInterruptible {
                    candidate.reuseAddress = true
                    candidate.soTimeout = readTimeoutMillis
                    candidate.bind(InetSocketAddress(localPort ?: 0))
                    candidate.connect(InetAddress.getByName(host), port)
                }
                true
            } == true
            if (!connected) closeCandidate(candidate)
            connected
        } catch (cancelled: CancellationException) {
            closeCandidate(candidate)
            throw cancelled
        } catch (_: Exception) {
            closeCandidate(candidate)
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        socket?.close()
        socket = null
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext false
        if (bytes.isEmpty()) return@withContext true
        try {
            runInterruptible {
                current.send(DatagramPacket(bytes, bytes.size))
            }
            true
        } catch (cancelled: CancellationException) {
            closeCandidate(current)
            throw cancelled
        } catch (_: Exception) {
            closeCandidate(current)
            false
        }
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext ByteArray(0)
        if (maxBytes <= 0) return@withContext ByteArray(0)
        val buffer = ByteArray(maxBytes.coerceAtMost(MAX_DATAGRAM_BYTES))
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            runInterruptible { current.receive(packet) }
            buffer.copyOf(packet.length)
        } catch (_: SocketTimeoutException) {
            ByteArray(0)
        } catch (cancelled: CancellationException) {
            closeCandidate(current)
            throw cancelled
        } catch (_: Exception) {
            closeCandidate(current)
            ByteArray(0)
        }
    }

    private fun closeCandidate(candidate: DatagramSocket) {
        candidate.close()
        if (socket === candidate) socket = null
    }

    private companion object {
        const val MAX_DATAGRAM_BYTES = 65_507
        const val DEFAULT_READ_TIMEOUT_MILLIS = 50
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 1_500L
    }
}
