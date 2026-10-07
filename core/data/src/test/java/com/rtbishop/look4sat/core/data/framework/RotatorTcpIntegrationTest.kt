package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.rotator.RotatorCodec
import com.rtbishop.look4sat.core.domain.rotator.RotatorPosition
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatorTcpIntegrationTest {

    @Test
    fun rotctldLoopbackQueriesFragmentedPositionAndSendsPoint() = runBlocking {
        verifyTcpProtocol(
            protocol = RotatorProtocol.ROTCTLD,
            responseFragments = listOf("123.4\n".encodeToByteArray(), "45.6\n".encodeToByteArray()),
            expectedPosition = RotatorPosition(123.4, 45.6),
            expectedQuery = "p\n".encodeToByteArray(),
            expectedPoint = "P 100.0 30.0\n".encodeToByteArray()
        )
    }

    @Test
    fun oz9aarLoopbackQueriesFragmentedJsonAndSendsGoto() = runBlocking {
        verifyTcpProtocol(
            protocol = RotatorProtocol.OZ9AAR_URC,
            responseFragments = listOf(
                "{\"AZ\":210.5,".encodeToByteArray(),
                "\"EL\":33.2}".encodeToByteArray()
            ),
            expectedPosition = RotatorPosition(210.5, 33.2),
            expectedQuery = "{\"POLL\"}".encodeToByteArray(),
            expectedPoint = "{\"GOTO\":[100.0,30.0]}".encodeToByteArray()
        )
    }

    private suspend fun verifyTcpProtocol(
        protocol: RotatorProtocol,
        responseFragments: List<ByteArray>,
        expectedPosition: RotatorPosition,
        expectedQuery: ByteArray,
        expectedPoint: ByteArray
    ) {
        val failure = AtomicReference<Throwable?>(null)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = thread(name = "fake-${protocol.name.lowercase()}", isDaemon = true) {
                try {
                    server.accept().use { client ->
                        client.soTimeout = 2_000
                        assertArrayEquals(expectedQuery, client.getInputStream().readExact(expectedQuery.size))
                        responseFragments.forEach { fragment ->
                            client.getOutputStream().apply { write(fragment); flush() }
                            Thread.sleep(25L)
                        }
                        assertArrayEquals(expectedPoint, client.getInputStream().readExact(expectedPoint.size))
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

            val transport = TcpRadioTransport(
                host = checkNotNull(InetAddress.getLoopbackAddress().hostAddress),
                port = server.localPort,
                timeoutMillis = 1_000
            )
            assertTrue(transport.connect())
            assertTrue(transport.write(requireNotNull(RotatorCodec.positionQuery(protocol))))
            val response = ControlResponseAccumulator().readUntilQuiet(
                transport = transport,
                maxBytes = 1_024,
                timeoutMillis = 1_500L,
                quietMillis = 100L,
                pollMillis = 10L
            )
            assertEquals(expectedPosition, RotatorCodec.parsePosition(protocol, response))
            assertTrue(transport.write(RotatorCodec.point(protocol, RotatorPosition(100.0, 30.0))))
            transport.disconnect()

            worker.join(2_000L)
            assertTrue("fake server did not finish", !worker.isAlive)
            assertNull(failure.get()?.stackTraceToString(), failure.get())
        }
    }
}

private fun InputStream.readExact(size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(result, offset, size - offset)
        check(count >= 0) { "socket closed after $offset of $size bytes" }
        offset += count
    }
    return result
}
