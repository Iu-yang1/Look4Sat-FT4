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
    fun encodeDecode_roundTrip() {
        val record = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            endUtcMillis = 1_700_000_000_100L,
            theirCallsign = "BG5JVM",
            myCallsign = "BA7OPF",
            theirGrid = "OM60",
            myGrid = "OL62TI",
            sentReport = "59",
            receivedReport = "59",
            txFrequencyHz = 145_850_000L,
            rxFrequencyHz = 436_805_000L,
            band = "2M",
            rxBand = "70CM",
            mode = "FM",
            satelliteName = "SO-50",
            satelliteMode = "FM",
            propagationMode = "SAT",
            status = QsoStatus.COMPLETE,
            lotwConfirmed = true,
            lotwQslDate = "2026-09-20",
            vuccGrids = listOf("OM60", "OM50")
        )
        val decoded = AdifCodec.decode(AdifCodec.encode(listOf(record))).single()
        assertEquals(record.startUtcMillis, decoded.startUtcMillis)
        assertEquals(record.theirCallsign, decoded.theirCallsign)
        assertEquals(record.myCallsign, decoded.myCallsign)
        assertEquals(record.satelliteName, decoded.satelliteName)
        assertEquals("2M", decoded.band)
        assertEquals("70CM", decoded.rxBand)
        assertTrue(decoded.lotwConfirmed)
        assertEquals(listOf("OM60", "OM50"), decoded.vuccGrids)
    }

    @Test
    fun decode_lotwStyleReportFields() {
        // LoTW report subset: lowercase eor, alphabetical fields, VUCC_GRIDS list.
        val adi = """
            <EOH>
            <CALL:6>BH6RJD
            <QSO_DATE:8>20260916
            <TIME_ON:6>051300
            <MODE:2>FM
            <SAT_NAME:5>SO-50
            <PROP_MODE:3>SAT
            <BAND:3>2M
            <BAND_RX:4>70CM
            <GRIDSQUARE:4>OM60
            <MY_GRIDSQUARE:8>OM60IL70
            <VUCC_GRIDS:9>OM60,OM50
            <STATION_CALLSIGN:6>BH6RJD
            <DXCC:3>318
            <COUNTRY:5>China
            <LOTW_QSL_RCVD:1>Y
            <eor>
        """.trimIndent()
        val records = AdifCodec.decode(adi)
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("BH6RJD", record.theirCallsign)
        assertEquals("SO-50", record.satelliteName)
        assertEquals("FM", record.displayMode)
        assertEquals("OM60IL70", record.myGrid)
        assertEquals(listOf("OM60", "OM50"), record.vuccGrids)
        assertTrue(record.lotwConfirmed)
        assertTrue(record.isSatellite)
    }
}
