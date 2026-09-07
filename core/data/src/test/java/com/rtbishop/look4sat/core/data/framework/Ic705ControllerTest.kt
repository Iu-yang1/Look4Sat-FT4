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
        assertEquals(listOf(0x08, 0x03, 0x04, 0x03, 0x04), transport.commandBytes)
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
}

private class ScriptedCivTransport(
    private val acknowledgeConnect: Boolean = true
) : RadioTransport {
    private val replies = ArrayDeque<ByteArray>()
    val commandBytes = mutableListOf<Int>()
    val payloads = mutableListOf<String>()
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
            0x08 -> if (acknowledgeConnect) replies += ack()
            0x03 -> replies += response(0x03, 0x00, 0x00, 0x59, 0x45, 0x01)
            0x04 -> replies += response(0x04, 0x01, 0x02)
            0x07 -> replies += ack()
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
        0xA4.toByte(),
        command.toByte(),
        *payload.map(Int::toByte).toByteArray(),
        0xFD.toByte()
    )
}
