/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdifCodecTest {
    @Test
    fun `FT4 satellite record round trips through ADIF 3 1 7`() {
        val original = QsoRecord(
            startUtcMillis = 1_725_189_306_000L,
            endUtcMillis = 1_725_189_336_000L,
            theirCallsign = "K1ABC",
            myCallsign = "BA7OPF",
            theirGrid = "FN42",
            myGrid = "OL63",
            sentReport = "-08",
            receivedReport = "-12",
            txFrequencyHz = 145_990_000L,
            rxFrequencyHz = 435_810_000L,
            band = "2m",
            rxBand = "70cm",
            satelliteName = "AO-123",
            transponderName = "U/V",
            satelliteMode = "U/V",
            passAosUtcMillis = 1_725_189_000_000L,
            ft4AudioFrequencyHz = 1_500,
            automatic = true,
            status = QsoStatus.COMPLETE,
            rawMessages = listOf("CQ K1ABC FN42", "K1ABC BA7OPF -08")
        )

        val text = AdifCodec.encode(listOf(original))
        assertTrue(text.contains("<MODE:4>MFSK<SUBMODE:3>FT4"))
        assertTrue(text.contains("<PROP_MODE:3>SAT"))
        val decoded = AdifCodec.decode(text).single()

        assertEquals(original.copy(id = 0L), decoded)
    }

    @Test
    fun `invalid records are skipped without losing valid records`() {
        val content = "<EOH><CALL:0><EOR><QSO_DATE:8>20260904<TIME_ON:6>120000<CALL:5>K1ABC<EOR>"
        val decoded = AdifCodec.decode(content)
        assertEquals(1, decoded.size)
        assertEquals("K1ABC", decoded.single().theirCallsign)
    }

    @Test
    fun `mixed modes and structured events survive import and export`() {
        val events = listOf(
            QsoMessageEvent(
                direction = QsoEventDirection.TX,
                utcMillis = 1_725_189_306_100L,
                result = QsoEventResult.COMPLETED,
                sessionId = "session-1",
                message = "K1ABC BA7OPF -08"
            )
        )
        val records = listOf(
            QsoRecord(
                startUtcMillis = 1_725_189_306_000L,
                theirCallsign = "K1ABC",
                myCallsign = "BA7OPF",
                mode = "FM",
                submode = "",
                status = QsoStatus.COMPLETE
            ),
            QsoRecord(
                startUtcMillis = 1_725_189_313_500L,
                theirCallsign = "K2XYZ",
                myCallsign = "BA7OPF",
                mode = "MFSK",
                submode = "FT8",
                sessionId = "session-1",
                messageEvents = events,
                status = QsoStatus.COMPLETE
            )
        )

        val decoded = AdifCodec.decode(AdifCodec.encode(records))

        assertEquals(listOf("FM", "MFSK"), decoded.map { it.mode })
        assertEquals(listOf("", "FT8"), decoded.map { it.submode })
        assertEquals(events, decoded.last().messageEvents)
        assertEquals("session-1", decoded.last().sessionId)
    }
}
