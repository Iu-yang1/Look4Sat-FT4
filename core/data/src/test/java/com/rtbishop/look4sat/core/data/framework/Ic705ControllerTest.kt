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

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ic705ControllerTest {
    @Test
    fun readFrequencyAndModeCombinesSeparateCivReplies() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())

        assertEquals(145_590_000L to "USB", controller.readFrequencyAndMode())
        assertEquals(listOf(0x07, 0x03, 0x04, 0x03, 0x04), transport.commandBytes)
    }

    @Test
    fun connectionIsRejectedWhenCatDoesNotAcknowledge() = runTest {
        val transport = ScriptedCivTransport(acknowledgeConnect = false)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )

        assertFalse(controller.connect())
        assertFalse(controller.isConnected)
        assertFalse(transport.isConnected)
    }

    @Test
    fun splitModesTargetBothVfosAndRequireReadback() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))

        assertEquals(
            listOf(
                "07:00",
                "26:00:01",
                "26:01:05",
                "26:00",
                "26:01"
            ),
            transport.payloads.drop(3)
        )
    }

    @Test
    fun ic705ReenablesCtcssAfterFrequencyChange() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        assertTrue(controller.setCtcssMode(true))
        transport.payloads.clear()

        assertTrue(controller.setFrequency(145_900_000L))

        assertEquals(0x05, transport.payloads.first().substringBefore(':').toInt(16))
        assertEquals("16:42:01", transport.payloads.last())
    }

    @Test
    fun bandPreparationDoesNotWriteBandStackingMemory() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        transport.payloads.clear()

        assertTrue(controller.setBand(435_100_000L))

        assertTrue(transport.payloads.isEmpty())
    }

    @Test
    fun ic705RestoresTxVfoToneAndReturnsToRxVfo() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        assertTrue(controller.setVfo(vfoA = false))
        assertTrue(controller.setCtcssMode(true))
        assertTrue(controller.setVfo(vfoA = true))
        transport.payloads.clear()

        assertTrue(controller.setTxVfoFrequency(435_100_000L))

        assertEquals("25:01", transport.payloads.first().take(5))
        assertEquals(
            listOf("07:01", "16:42:01", "07:00"),
            transport.payloads.takeLast(3)
        )
    }

    @Test
    fun txCtcssConfigurationTargetsTxAndReturnsToRxVfo() = runTest {
        val transport = ScriptedCivTransport()
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            transport = transport
        )
        assertTrue(controller.connect())
        transport.payloads.clear()

        assertTrue(controller.configureTxCtcss(88.5))

        assertEquals(
            listOf("07:01", "1B:00:00:08:85", "16:42:01", "07:00"),
            transport.payloads
        )
    }

    @Test
    fun ic9700UsesSatelliteMainSubInsteadOfSelectedVfoCommands() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))
        assertTrue(controller.setWorkingFrequency(145_900_000L))
        assertTrue(controller.setTxVfoFrequency(435_100_000L))

        val satelliteCommands = transport.payloads.drop(3)
        assertEquals("0F:00", satelliteCommands.first())
        assertEquals("16:5A:01", satelliteCommands[1])
        assertTrue(satelliteCommands.contains("07:D0"))
        assertTrue(satelliteCommands.contains("07:D1"))
        assertFalse(satelliteCommands.any { it.startsWith("25:") || it.startsWith("26:") })
    }

    @Test
    fun ic9700RestoresMainWhenSubFrequencyCommandFails() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2, acknowledgeSetFrequency = false)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())

        assertFalse(controller.setTxVfoFrequency(435_100_000L))
        assertEquals("07:D0", transport.payloads.last())
    }

    @Test
    fun ic9700RestoresMainWhenSubSelectionFails() = runTest {
        val transport = ScriptedCivTransport(
            civAddress = 0xA2,
            acknowledgeTxSelection = false
        )
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())
        transport.payloads.clear()

        assertFalse(controller.setTxVfoFrequency(435_100_000L))
        assertEquals(listOf("07:D1", "07:D0"), transport.payloads)
        transport.payloads.clear()

        assertNull(controller.readTxVfoFrequency())
        assertEquals(listOf("07:D1", "07:D0"), transport.payloads)
    }

    @Test
    fun ic9700CanUseOrdinarySplitFallback() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700,
            dedicatedSatelliteMode = false
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))

        val commands = transport.payloads.drop(3)
        assertEquals(listOf("16:5A:00", "16:5A", "0F:01"), commands.take(3))
        assertFalse(commands.any { it == "07:D0" || it == "07:D1" })
        assertTrue(commands.any { it.startsWith("26:00") })
        assertTrue(commands.any { it.startsWith("26:01") })
    }

    @Test
    fun ic910UsesDocumentedSatelliteCommandAndMainSubControl() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x60)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC910,
            transport = transport,
            variant = IcomCivVariant.IC910
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))
        assertTrue(controller.setWorkingFrequency(435_100_000L))
        assertTrue(controller.setTxVfoFrequency(145_900_000L))

        val commands = transport.payloads.drop(3)
        assertEquals(listOf("0F:00", "1A:07:01", "1A:07"), commands.take(3))
        assertTrue(commands.contains("07:D0"))
        assertTrue(commands.contains("07:D1"))
        assertFalse(commands.any { it.startsWith("25:") || it.startsWith("26:") })
    }

    @Test
    fun satelliteModeRequiresMatchingReadback() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0xA2, satelliteReadbackOverride = false)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC9700,
            transport = transport,
            variant = IcomCivVariant.IC9700
        )
        assertTrue(controller.connect())

        assertFalse(controller.setSplitMode(true))
        assertEquals(listOf("16:5A:01", "16:5A"), transport.payloads.takeLast(2))
    }

    @Test
    fun ic910OrdinarySplitFallbackAvoidsUnsupportedSelectedVfoCommands() = runTest {
        val transport = ScriptedCivTransport(civAddress = 0x60)
        val controller = Ic705Controller(
            bluetoothManager = null,
            deviceAddress = "USB",
            civAddress = IcomCivProtocol.ADDR_IC910,
            transport = transport,
            variant = IcomCivVariant.IC910,
            dedicatedSatelliteMode = false
        )
        assertTrue(controller.connect())

        assertTrue(controller.setSplitMode(true))
        assertTrue(controller.setSplitModes(rxMode = "USB", txMode = "FM"))
        assertTrue(controller.setWorkingFrequency(435_100_000L))
        assertTrue(controller.setTxVfoFrequency(145_900_000L))

        val commands = transport.payloads.drop(3)
        assertEquals(listOf("1A:07:00", "1A:07", "0F:01"), commands.take(3))
        assertTrue(commands.contains("07:00"))
        assertTrue(commands.contains("07:01"))
        assertFalse(commands.any { it.startsWith("25:") || it.startsWith("26:") })
    }
}

private class ScriptedCivTransport(
    private val acknowledgeConnect: Boolean = true,
    private val civAddress: Int = 0xA4,
    private val acknowledgeSetFrequency: Boolean = true,
    private val satelliteReadbackOverride: Boolean? = null,
    private val acknowledgeTxSelection: Boolean = true
) : RadioTransport {
    private val replies = ArrayDeque<ByteArray>()
    val commandBytes = mutableListOf<Int>()
    val payloads = mutableListOf<String>()
    private var mainSelected = true
    private var mainMode = 0x01
    private var subMode = 0x05
    private var satelliteMode = false
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
        val payload = bytes.copyOfRange(4, bytes.lastIndex)
        val command = payload.first().toInt() and 0xFF
        commandBytes += command
        payloads += payload.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        when (command) {
            0x07 -> {
                val selectsTx = payload.getOrNull(1)?.let { it == 0x01.toByte() || it == 0xD1.toByte() } == true
                val acknowledged = (payload.size < 2 && acknowledgeConnect) ||
                    (payload.size >= 2 && (!selectsTx || acknowledgeTxSelection))
                if (acknowledged && payload.size >= 2) {
                    mainSelected = payload[1] == 0x00.toByte() || payload[1] == 0xD0.toByte()
                }
                if (acknowledged) replies += ack()
            }
            0x03 -> replies += response(0x03, 0x00, 0x00, 0x59, 0x45, 0x01)
            0x04 -> replies += response(0x04, if (mainSelected) mainMode else subMode, 0x02)
            0x05 -> if (acknowledgeSetFrequency) replies += ack()
            0x0F -> replies += ack()
            0x16 -> when {
                payload.size >= 3 && payload[1] == 0x5A.toByte() -> {
                    satelliteMode = payload[2] == 0x01.toByte()
                    replies += ack()
                }
                payload.size == 2 && payload[1] == 0x5A.toByte() -> {
                    val enabled = satelliteReadbackOverride ?: satelliteMode
                    replies += response(0x16, 0x5A, if (enabled) 0x01 else 0x00)
                }
                else -> replies += ack()
            }
            0x1A -> when {
                payload.size >= 3 && payload[1] == 0x07.toByte() -> {
                    satelliteMode = payload[2] == 0x01.toByte()
                    replies += ack()
                }
                payload.size == 2 && payload[1] == 0x07.toByte() -> {
                    val enabled = satelliteReadbackOverride ?: satelliteMode
                    replies += response(0x1A, 0x07, if (enabled) 0x01 else 0x00)
                }
                else -> replies += ack()
            }
            0x1B -> replies += ack()
            0x25 -> replies += ack()
            0x06 -> {
                if (payload.size >= 2) {
                    if (mainSelected) mainMode = payload[1].toInt() and 0xFF
                    else subMode = payload[1].toInt() and 0xFF
                }
                replies += ack()
            }
            0x26 -> {
                if (payload.size >= 3) {
                    replies += ack()
                } else {
                    val mode = if (payload[1].toInt() == 0) 0x01 else 0x05
                    val otherSelector = if (payload[1].toInt() == 0) 0x01 else 0x00
                    replies += response(0x26, otherSelector, mode, 0x01)
                    replies += response(0x26, payload[1].toInt(), mode, 0x01)
                }
            }
        }
        return true
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray =
        if (replies.isEmpty()) ByteArray(0) else replies.removeFirst().let { it.copyOf(minOf(it.size, maxBytes)) }

    private fun ack(): ByteArray = response(0xFB)

    private fun response(command: Int, vararg payload: Int): ByteArray = byteArrayOf(
        0xFE.toByte(),
        0xFE.toByte(),
        0xE0.toByte(),
        civAddress.toByte(),
        command.toByte(),
        *payload.map(Int::toByte).toByteArray(),
        0xFD.toByte()
    )
}
