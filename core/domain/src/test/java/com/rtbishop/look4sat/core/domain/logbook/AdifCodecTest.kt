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
import org.junit.Assert.assertFalse
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
            theirGrid = "EN52",
            theirVuccGrids = listOf("EN52", "EN53"),
            myGrid = "OL62TI",
            vuccGrids = listOf("OL62", "OL61"),
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
            lotwQslDate = "2026-09-20"
        )
        val decoded = AdifCodec.decode(AdifCodec.encode(listOf(record))).single()
        assertEquals(record.startUtcMillis, decoded.startUtcMillis)
        assertEquals(record.theirCallsign, decoded.theirCallsign)
        assertEquals(record.myCallsign, decoded.myCallsign)
        assertEquals(record.satelliteName, decoded.satelliteName)
        assertEquals("2M", decoded.band)
        assertEquals("70CM", decoded.rxBand)
        assertTrue(decoded.lotwConfirmed)
        // Grid directions survive a round trip: the opposite's set stays theirs,
        // the operator's own set stays theirs.
        assertEquals("EN52", decoded.theirGrid)
        assertEquals(listOf("EN52", "EN53"), decoded.theirVuccGrids)
        assertEquals("OL62TI", decoded.myGrid)
        assertEquals(listOf("OL62", "OL61"), decoded.vuccGrids)
    }

    @Test
    fun decode_lotwStyleReportFields() {
        // LoTW report subset: lowercase eor, alphabetical fields. The un-prefixed
        // grid fields belong to the OPPOSITE station, MY_* fields to the operator.
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
            <VUCC_GRIDS:9>OM60,OM50
            <MY_GRIDSQUARE:6>OL62TI
            <MY_VUCC_GRIDS:9>OL62,OL61
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
        assertEquals("OM60", record.theirGrid)
        assertEquals(listOf("OM60", "OM50"), record.theirVuccGrids)
        assertEquals("OL62TI", record.myGrid)
        assertEquals(listOf("OL62", "OL61"), record.vuccGrids)
        assertTrue(record.lotwConfirmed)
        assertTrue(record.isSatellite)
    }

    @Test
    fun decodeWavelogPullStyleRecordKeepsGridDirections() {
        // Wavelog's export: the OPPOSITE station's grids un-prefixed, the station
        // profile's grid under MY_*. A pull must not flip them into each other.
        val adi = "<EOH><CALL:6>BG5JVM<QSO_DATE:8>20260916<TIME_ON:4>0745" +
            "<VUCC_GRIDS:9>EN52,EN53<MY_GRIDSQUARE:6>OL62TI<MY_VUCC_GRIDS:9>OL62,OL61" +
            "<MODE:2>FM<PROP_MODE:3>SAT<SAT_NAME:5>SO-50<EOR>"
        val record = AdifCodec.decode(adi).single()
        assertEquals(listOf("EN52", "EN53"), record.theirVuccGrids)
        assertEquals("OL62TI", record.myGrid)
        assertEquals(listOf("OL62", "OL61"), record.vuccGrids)
    }

    @Test
    fun encodeOmitsStationCallsignForWavelog() {
        val record = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            theirCallsign = "N0CALL",
            myCallsign = "BA7OPF",
            txFrequencyHz = 145_850_000L,
            band = "2M",
            satelliteName = "SO-50"
        )
        val adif = AdifCodec.encode(listOf(record), includeStationCallsign = false)
        assertTrue(!adif.contains("<STATION_CALLSIGN"))
        assertTrue(adif.contains("<CALL:6>N0CALL"))
        assertTrue(adif.contains("<SAT_NAME:5>SO-50"))
    }

    @Test
    fun encodeKeepsOwnGridsOutOfTheOppositeVuccField() {
        // The reported defect: an operator's stamped own grid set must never land in
        // VUCC_GRIDS — Wavelog displays that field as the OPPOSITE station's grid.
        val record = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            theirCallsign = "N0CALL",
            myCallsign = "BA7OPF",
            myGrid = "OL62TI",
            vuccGrids = listOf("OL62"),
            satelliteName = "SO-50"
        )
        val adif = AdifCodec.encode(listOf(record), includeStationCallsign = false)
        assertTrue(adif.contains("<MY_VUCC_GRIDS:4>OL62"))
        assertFalse(adif.contains("<VUCC_GRIDS:"))
        assertFalse(adif.contains("<GRIDSQUARE:"))
    }

    @Test
    fun encodeWritesTheOppositeMultiGridSetToVuccGrids() {
        val multi = QsoRecord(
            startUtcMillis = 1_700_000_000_000L,
            theirCallsign = "N0CALL",
            myCallsign = "BA7OPF",
            theirGrid = "EN52",
            theirVuccGrids = listOf("EN52", "EN53"),
            satelliteName = "SO-50"
        )
        val multiAdif = AdifCodec.encode(listOf(multi), false)
        assertTrue(multiAdif.contains("<GRIDSQUARE:4>EN52"))
        assertTrue(multiAdif.contains("<VUCC_GRIDS:9>EN52,EN53"))

        // A single opposite grid rides in GRIDSQUARE only; one value inside the VUCC
        // field would trigger Wavelog's single-grid warning in its QSO view.
        val single = multi.copy(theirVuccGrids = listOf("EN52"))
        val singleAdif = AdifCodec.encode(listOf(single), false)
        assertTrue(singleAdif.contains("<GRIDSQUARE:4>EN52"))
        assertFalse(singleAdif.contains("<VUCC_GRIDS:"))
    }
}
