package com.rtbishop.look4sat.core.data.framework

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpControlTransportTest {

    @Test
    fun sendsFromConfiguredLocalPortAndReceivesReply() = runBlocking {
        val (server, clientPort) = serverWithAdjacentClientPort()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val exchange = executor.submit<Pair<Int, ByteArray>> {
                val buffer = ByteArray(128)
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val payload = buffer.copyOf(request.length)
                val reply = "AZ:123.4\rEL:45.6\r".encodeToByteArray()
                server.send(DatagramPacket(reply, reply.size, request.address, request.port))
                request.port to payload
            }
            val transport = UdpControlTransport(
                host = InetAddress.getLoopbackAddress().hostAddress ?: "127.0.0.1",
                port = server.localPort,
                localPort = clientPort,
                readTimeoutMillis = 500
            )
            assertTrue(transport.connect())
            assertTrue(transport.isConnected)
            assertTrue(transport.write("POINT".encodeToByteArray()))
            val response = transport.readAvailable(128)
            val (sourcePort, request) = exchange.get(2, TimeUnit.SECONDS)

            assertEquals(clientPort, sourcePort)
            assertArrayEquals("POINT".encodeToByteArray(), request)
            assertArrayEquals("AZ:123.4\rEL:45.6\r".encodeToByteArray(), response)
            transport.disconnect()
            assertFalse(transport.isConnected)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectsInvalidEndpointWithoutOpeningSocket() = runBlocking {
        val transport = UdpControlTransport(host = "", port = 0)

        assertFalse(transport.connect())
        assertFalse(transport.isConnected)
        assertFalse(transport.write(byteArrayOf(1)))
        assertArrayEquals(ByteArray(0), transport.readAvailable(32))
    }

    private fun serverWithAdjacentClientPort(): Pair<DatagramSocket, Int> {
        repeat(50) {
            val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
            val clientPort = server.localPort + 1
            if (clientPort <= 65_535 && portIsAvailable(clientPort)) return server to clientPort
            server.close()
        }
        error("Could not reserve adjacent UDP loopback ports")
    }

    private fun portIsAvailable(port: Int): Boolean = runCatching {
        DatagramSocket(null).use { probe ->
            probe.reuseAddress = false
            probe.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        }
    }.isSuccess
}
