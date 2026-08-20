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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcomCivProtocolTest {
    @Test
    fun pttOnAndOffCommandsUseCivTransceiverStatus() {
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(),
                0x1C, 0x00, 0x01, 0xFD.toByte()
            ),
            IcomCivProtocol.buildPttCommand(true)
        )
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(),
                0x1C, 0x00, 0x00, 0xFD.toByte()
            ),
            IcomCivProtocol.buildPttCommand(false)
        )
    }

    @Test
    fun pttReadCommandAndResponseAreParsed() {
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte(),
                0x1C, 0x00, 0xFD.toByte()
            ),
            IcomCivProtocol.buildReadPttCommand()
        )
        assertTrue(IcomCivProtocol.parsePttState(byteArrayOf(0x00, 0x01)) == true)
        assertFalse(IcomCivProtocol.parsePttState(byteArrayOf(0x00, 0x00)) == true)
        assertNull(IcomCivProtocol.parsePttState(byteArrayOf(0x01, 0x01)))
        assertNull(IcomCivProtocol.parsePttState(byteArrayOf(0x00, 0x02)))
    }

    @Test
    fun pttReadbackFrameSurvivesBroadcastNoise() {
        val noise = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0x00, 0xA4.toByte(), 0x03, 0xFD.toByte()
        )
        val response = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(),
            0x1C, 0x00, 0x01, 0xFD.toByte()
        )
        val parsed = IcomCivProtocol.parseResponse(noise + response, 0x1C)
        assertEquals(0x1C.toByte(), parsed?.cmd)
        assertArrayEquals(byteArrayOf(0x00, 0x01), parsed?.payload)
    }

    @Test
    fun ackStatusDistinguishesNakFromNoResponse() {
        val ack = byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), 0xA4.toByte(),
            0xFB.toByte(), 0xFD.toByte()
        )
        val nak = ack.copyOf().also { it[4] = 0xFA.toByte() }

        assertEquals(true, IcomCivProtocol.ackStatus(ack))
        assertEquals(false, IcomCivProtocol.ackStatus(nak))
        assertNull(IcomCivProtocol.ackStatus(byteArrayOf()))
    }
}
