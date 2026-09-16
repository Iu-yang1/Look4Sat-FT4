package com.rtbishop.look4sat.core.data.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class HamlibRigctldControllerTest {

    @Test
    fun connectRequiresValidFrequencyAndMode() = runTest {
        val transport = FakeRigctldTransport()
        val controller = HamlibRigctldController("127.0.0.1:4532", transport)

        assertTrue(controller.connect())
        assertEquals(listOf("\\get_freq", "\\get_mode"), transport.commands.take(2))
        assertEquals(145_900_000L to "USB", controller.readFrequencyAndMode())
    }

    @Test
    fun connectRejectsMalformedHandshake() = runTest {
        val transport = FakeRigctldTransport(faults = mutableMapOf("\\get_mode" to "garbage\n"))
        val controller = HamlibRigctldController("127.0.0.1:4532", transport)

        assertFalse(controller.connect())
        assertFalse(controller.isConnected)
    }

    @Test
    fun frequencyModeAndSplitWritesUseRigctldCommands() = runTest {
        val transport = FakeRigctldTransport()
        val controller = HamlibRigctldController("127.0.0.1:4532", transport)
        assertTrue(controller.connect())

        assertTrue(controller.setFrequency(145_925_000L))
        assertTrue(controller.setMode("CW-R"))
        assertEquals(145_925_000L to "CW-R", controller.readFrequencyAndMode())
        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setVfo(false))
        assertTrue(controller.setSplitModes("USB", "LSB"))
        val rxModeCommand = transport.commands.indexOf("\\set_mode USB 0")
        assertEquals("\\set_vfo VFOA", transport.commands[rxModeCommand - 1])
        assertTrue(controller.setTxVfoFrequency(435_100_000L))

        assertEquals(145_925_000L to "USB", controller.readFrequencyAndMode())
        assertEquals(435_100_000L, controller.readTxVfoFrequency())
        assertTrue(transport.commands.contains("\\set_split_vfo 1 VFOB"))
        assertTrue(transport.commands.contains("\\set_split_mode LSB 0"))
    }

    @Test
    fun pttAndCtcssAreConfirmedThroughRigctld() = runTest {
        val transport = FakeRigctldTransport()
        val controller = HamlibRigctldController("127.0.0.1:4532", transport)
        assertTrue(controller.connect())

        assertTrue(controller.pttOn())
        assertTrue(controller.configureTxCtcss(67.0))
        assertTrue(controller.pttOff())

        assertTrue(transport.commands.contains("\\set_ctcss_tone 670"))
        assertTrue(transport.commands.contains("\\set_func TONE 1"))
        assertEquals("\\set_vfo VFOA", transport.commands.filter { it.startsWith("\\set_vfo") }.last())
    }

    @Test
    fun splitFrequencyReadRejectsHamlibError() = runTest {
        val transport = FakeRigctldTransport()
        val controller = HamlibRigctldController("127.0.0.1:4532", transport)
        assertTrue(controller.connect())
        transport.faults["\\get_split_freq"] = "RPRT -11\n"

        assertNull(controller.readTxVfoFrequency())
    }

    @Test
    fun fragmentedResponsesAreReassembled() = runTest {
        val transport = FakeRigctldTransport(fragmentSize = 2)
        val controller = HamlibRigctldController("127.0.0.1:4532", transport)

        assertTrue(controller.connect())
        assertEquals(145_900_000L to "USB", controller.readFrequencyAndMode())
    }
}

private class FakeRigctldTransport(
    val faults: MutableMap<String, String> = mutableMapOf(),
    private val fragmentSize: Int = Int.MAX_VALUE
) : RadioTransport {
    private val replies = ArrayDeque<Byte>()
    val commands = mutableListOf<String>()
    private var frequency = 145_900_000L
    private var txFrequency = 435_100_000L
    private var mode = "USB"
    private var txMode = "LSB"
    private var split = false
    private var ptt = false
    override var isConnected = false
        private set

    override suspend fun connect(): Boolean {
        isConnected = true
        return true
    }

    override suspend fun disconnect() {
        isConnected = false
        replies.clear()
    }

    override suspend fun write(bytes: ByteArray): Boolean {
        if (!isConnected) return false
        val command = bytes.toString(Charsets.US_ASCII).trimEnd('\n', '\r')
        commands += command
        val parts = command.split(' ')
        val response = faults.remove(command) ?: when (parts[0]) {
            "\\get_freq" -> "$frequency\n"
            "\\set_freq" -> {
                frequency = parts[1].toLong()
                "RPRT 0\n"
            }
            "\\get_mode" -> "$mode\n0\n"
            "\\set_mode" -> {
                mode = parts[1]
                "RPRT 0\n"
            }
            "\\get_ptt" -> "${if (ptt) 1 else 0}\n"
            "\\set_ptt" -> {
                ptt = parts[1] != "0"
                "RPRT 0\n"
            }
            "\\get_split_vfo" -> "${if (split) 1 else 0}\nVFOB\n"
            "\\set_split_vfo" -> {
                split = parts[1] != "0"
                "RPRT 0\n"
            }
            "\\get_split_freq" -> "$txFrequency\n"
            "\\set_split_freq" -> {
                txFrequency = parts[1].toLong()
                "RPRT 0\n"
            }
            "\\get_split_mode" -> "$txMode\n0\n"
            "\\set_split_mode" -> {
                txMode = parts[1]
                "RPRT 0\n"
            }
            "\\set_vfo", "\\set_ctcss_tone", "\\set_func" -> "RPRT 0\n"
            else -> "RPRT -1\n"
        }
        replies.addAll(response.toByteArray(Charsets.US_ASCII).toList())
        return true
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray {
        val count = minOf(maxBytes, fragmentSize, replies.size)
        return ByteArray(count) { replies.removeFirst() }
    }
}
