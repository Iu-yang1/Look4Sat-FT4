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

import com.rtbishop.look4sat.core.domain.model.GridQso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbookMergeTest {

    private val local = QsoRecord(
        startUtcMillis = 1_700_000_000_000L,
        theirCallsign = "bg5jvm",
        myCallsign = "ba7opf",
        txFrequencyHz = 145_850_000L,
        mode = "FM",
        submode = "",
        satelliteName = "SO-50 (SaudiOSCAR 50)",
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE
    )

    private val confirmed = QsoRecord(
        startUtcMillis = 1_700_000_000_000L,
        theirCallsign = "BG5JVM",
        myCallsign = "BA7OPF",
        mode = "FM",
        submode = "",
        satelliteName = "SO-50",
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE,
        lotwConfirmed = true,
        vuccGrids = listOf("OM60", "OM50")
    )

    @Test
    fun satMatchKey_stripsParenthetical() {
        assertEquals("SO-50", satMatchKey("SO-50 (SaudiOSCAR 50)"))
        assertEquals("SO-50", satMatchKey("SO-50"))
        assertEquals("AO-91", satMatchKey("AO-91 (FUNcube-1)"))
        assertEquals("IO-86", satMatchKey("IO-86"))
    }

    @Test
    fun sameConfirmedContact_matchesAcrossSatNOGSAndOfficialName() {
        assertTrue(sameConfirmedContact(local, confirmed))
    }

    @Test
    fun sameConfirmedContact_rejectsDifferentSatellite() {
        val other = confirmed.copy(satelliteName = "AO-91")
        assertFalse(sameConfirmedContact(local, other))
    }

    @Test
    fun sameConfirmedContact_rejectsTimeSkew() {
        val skewed = confirmed.copy(startUtcMillis = confirmed.startUtcMillis + 120_000L)
        assertFalse(sameConfirmedContact(local, skewed))
    }

    @Test
    fun confirmationLookupKey_usesNormalizedSatellite() {
        assertEquals(local.confirmationLookupKey(), confirmed.confirmationLookupKey())
    }

    @Test
    fun withConfirmation_marksConfirmedAndMergesVuccGrids() {
        val merged = local.withConfirmation(confirmed)
        assertTrue(merged.lotwConfirmed)
        assertTrue(merged.lotwReceived)
        assertEquals(listOf("OM60", "OM50"), merged.vuccGrids)
        // Local myCallsign is kept when present; only blanks are backfilled.
        assertEquals("ba7opf", merged.myCallsign)
    }

    @Test
    fun toConfirmedRecord_mapsGridQso() {
        val qso = GridQso(
            call = "BH6RJD",
            epochMs = 1_700_000_000_000L,
            satName = "SO-50",
            mode = "FM",
            bandUp = "70CM",
            bandDown = "2M",
            dxcc = 318,
            country = "China",
            cqz = 24,
            state = "GD",
            myGrid = "OM60",
            myGrids = setOf("OM60", "OM50")
        )
        val record = qso.toConfirmedRecord("ba7opf")
        assertEquals("BH6RJD", record.theirCallsign)
        assertEquals("BA7OPF", record.myCallsign)
        assertEquals("OM60", record.myGrid)
        assertEquals(listOf("OM60", "OM50"), record.vuccGrids)
        assertTrue(record.lotwConfirmed)
        assertTrue(record.isSatellite)
        assertEquals("70CM", record.band)
        assertEquals("2M", record.rxBand)
    }

    @Test
    fun frequencyBand_edges() {
        assertEquals("2M", frequencyBand(144_000_000L))
        assertEquals("70CM", frequencyBand(450_000_000L))
        assertEquals("10M", frequencyBand(29_000_000L))
        assertEquals("", frequencyBand(null))
    }

    @Test
    fun displayMode_prefersSubmode() {
        val ft4 = QsoRecord(startUtcMillis = 0, theirCallsign = "x", myCallsign = "y", mode = "MFSK", submode = "FT4")
        assertEquals("FT4", ft4.displayMode)
        assertEquals("FM", local.displayMode)
    }

    @Test
    fun displayMode_ignoresStaleSubmodeForOtherModes() {
        // Regression: records persisted with the old default submode="FT4" must not
        // show FT4 when the actual mode is FM/CW/SSB.
        val staleFm = QsoRecord(startUtcMillis = 0, theirCallsign = "x", myCallsign = "y", mode = "FM", submode = "FT4")
        val staleCw = QsoRecord(startUtcMillis = 0, theirCallsign = "x", myCallsign = "y", mode = "CW", submode = "FT4")
        assertEquals("FM", staleFm.displayMode)
        assertEquals("CW", staleCw.displayMode)
    }
}
