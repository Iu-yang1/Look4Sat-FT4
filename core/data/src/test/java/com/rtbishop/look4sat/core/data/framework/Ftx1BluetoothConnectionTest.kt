package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ftx1BluetoothConnectionTest {
    @Test fun sppTransportHandlesEchoAndFragmentationForMainAndSubFrequencies() = runBlocking {
        val socket = Ftx1SppSocket(Ftx1SimulatedTransport(chunkSize = 3).apply { echo = true })
        val connector = Ftx1SppConnector(socket)
        val diagnostics = ControlDiagnosticsBuffer()
        val radio = Ftx1Controller(BluetoothSppRadioTransport(connector, diagnostics), diagnostics, 300L)
        socket.device.onWrite = { command ->
            if (command in HANDSHAKE_COMMANDS) assertFalse(radio.isConnected)
        }
        try {
            assertTrue(radio.connect())
            assertEquals(HANDSHAKE_COMMANDS, socket.device.commands)
            socket.device.onWrite = null
            assertEquals(listOf(false), connector.modes)
            assertTrue(diagnostics.events.value.any {
                it.stage == "ftx1/connect/ready" && it.message.contains("bluetooth/insecure")
            })
            assertTrue(radio.setSplitMode(true))
            repeat(3) { index ->
                assertTrue(radio.setWorkingFrequency(435_100_000L + index * 250L))
                assertTrue(radio.setTxVfoFrequency(145_900_000L - index * 250L))
            }
            assertEquals(435_100_500L, radio.readWorkingFrequency())
            assertEquals(145_899_500L, radio.readTxVfoFrequency())
            assertEquals(0, socket.device.selectedSide)
            assertFalse("ST1;" in socket.device.commands)
            assertFalse("TX1;" in socket.device.commands)
        } finally {
            radio.disconnect()
        }
        assertTrue(socket.closed)
    }

    @Test fun silentSppHandshakeTriesTheRemainingRfcommModeAndIdentifiesItAgain() = runBlocking {
        val first = Ftx1SppSocket(Ftx1SimulatedTransport().apply { silent = true; echo = true })
        val second = Ftx1SppSocket()
        val connector = Ftx1SppConnector(first, second)
        val radio = Ftx1Controller(BluetoothSppRadioTransport(connector), replyTimeoutMillis = 30L)
        try {
            assertTrue(radio.connect())
            assertEquals(listOf(false, true), connector.modes)
            assertTrue(first.closed)
            assertEquals(listOf("ID;", "ID;", "ID;"), first.device.commands)
            assertEquals(HANDSHAKE_COMMANDS, second.device.commands)
            assertTrue(radio.setWorkingFrequency(145_950_000L))
            assertEquals(145_950_000L, radio.readWorkingFrequency())
        } finally {
            radio.disconnect()
        }
    }

    @Test fun wrongRadioAndSilentSocketsFailAfterBothModesWithoutApplyingSettings() = runBlocking {
        val first = Ftx1SppSocket(Ftx1SimulatedTransport().apply { id = "ID0670;" })
        val second = Ftx1SppSocket(Ftx1SimulatedTransport().apply { silent = true })
        val connector = Ftx1SppConnector(first, second)
        val radio = Ftx1Controller(BluetoothSppRadioTransport(connector), replyTimeoutMillis = 30L)
        assertFalse(radio.connect())
        assertFalse(radio.isConnected)
        assertEquals(listOf(false, true), connector.modes)
        for (socket in listOf(first, second)) {
            assertTrue(socket.closed)
            assertEquals(listOf("ID;", "ID;", "ID;"), socket.device.commands)
        }
        radio.disconnect()
    }

    @Test fun unsupportedFirmwareDoesNotTryAnotherSocketToBypassTheVersionCheck() = runBlocking {
        val socket = Ftx1SppSocket(Ftx1SimulatedTransport().apply { firmware = "VE00107;" })
        val connector = Ftx1SppConnector(socket)
        val radio = Ftx1Controller(BluetoothSppRadioTransport(connector), replyTimeoutMillis = 100L)
        assertFalse(radio.connect())
        assertFalse(radio.isConnected)
        assertEquals(listOf(false), connector.modes)
        assertEquals(HANDSHAKE_COMMANDS, socket.device.commands)
        assertTrue(socket.closed)
        radio.disconnect()
    }

    @Test fun permissionDeniedOrDisabledBluetoothDoesNotReachTheCatHandshake() = runBlocking {
        for (error in listOf(SecurityException("Permission denied"), IOException("Bluetooth disabled"))) {
            val connector = Ftx1SppConnector(prepareError = error)
            val radio = Ftx1Controller(BluetoothSppRadioTransport(connector), replyTimeoutMillis = 30L)
            assertFalse(radio.connect())
            assertFalse(radio.isConnected)
            assertTrue(connector.modes.isEmpty())
            radio.disconnect()
        }
    }

    @Test fun cancellingSppIdentificationClosesTheSocketWithoutTryingAnotherMode() = runBlocking {
        val socket = Ftx1SppSocket(Ftx1SimulatedTransport().apply { silent = true })
        val connector = Ftx1SppConnector(socket)
        val radio = Ftx1Controller(BluetoothSppRadioTransport(connector))
        val queried = CompletableDeferred<Unit>()
        socket.device.onWrite = { queried.complete(Unit) }
        val connecting = launch { radio.connect() }
        try {
            withTimeout(2_000L) { queried.await() }
            assertFalse(radio.isConnected)
        } finally {
            connecting.cancelAndJoin()
        }
        assertTrue(socket.closed)
        assertFalse(radio.isConnected)
        assertEquals(listOf(false), connector.modes)
        assertEquals(listOf("ID;"), socket.device.commands)
    }

    @Test fun reconnectUsesAFreshHandshakeAndDoesNotReplayThePreviousFrequency() = runBlocking {
        val first = Ftx1SppSocket()
        val second = Ftx1SppSocket()
        val connector = Ftx1SppConnector(first, second)
        val radio = Ftx1Controller(BluetoothSppRadioTransport(connector), replyTimeoutMillis = 100L)
        try {
            assertTrue(radio.connect())
            assertTrue(radio.setVfo(false))
            assertTrue(radio.setFrequency(145_950_000L))
            radio.disconnect()
            assertTrue(first.closed)
            assertTrue(radio.connect())
            assertEquals(HANDSHAKE_COMMANDS, second.device.commands)
            assertEquals(145_500_000L, radio.readWorkingFrequency())
            assertTrue(radio.setFrequency(435_050_000L))
            assertEquals(435_050_000L, radio.readWorkingFrequency())
            assertEquals(435_100_000L, radio.readTxVfoFrequency())
            assertFalse("TX1;" in second.device.commands)
        } finally {
            radio.disconnect()
        }
        assertTrue(second.closed)
    }

    private companion object {
        val HANDSHAKE_COMMANDS = listOf("ID;", "FA;", "VE0;")
    }
}

private class Ftx1SppConnector(
    vararg sockets: Ftx1SppSocket,
    private val prepareError: Exception? = null
) : SppConnector {
    private val sockets = ArrayDeque(sockets.toList())
    val modes = mutableListOf<Boolean>()
    override fun prepare() { prepareError?.let { throw it } }
    override fun create(secure: Boolean): SppSocket {
        modes += secure
        return sockets.removeFirst()
    }
}

private class Ftx1SppSocket(
    val device: Ftx1SimulatedTransport = Ftx1SimulatedTransport()
) : SppSocket {
    override val isConnected get() = device.isConnected
    var closed = false
        private set
    private val pending = ConcurrentLinkedQueue<Byte>()
    override val input = object : InputStream() {
        override fun available(): Int {
            pending.addAll(runBlocking { device.readAvailable(512) }.toList())
            return pending.size
        }
        override fun read(): Int = pending.poll()?.let { it.toInt() and 0xFF } ?: -1
    }
    override val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (!runBlocking { device.write(bytes.copyOfRange(offset, offset + length)) }) {
                throw IOException("Simulated SPP write failed")
            }
        }
    }
    override fun connect() { runBlocking { device.connect() } }
    override fun close() {
        closed = true
        pending.clear()
        runBlocking { device.disconnect() }
    }
}
