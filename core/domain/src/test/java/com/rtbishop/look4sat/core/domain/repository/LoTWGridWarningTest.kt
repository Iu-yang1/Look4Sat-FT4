/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoTWGridWarningTest {

    private fun record(myGrid: String, vuccGrids: List<String> = emptyList()) = QsoRecord(
        startUtcMillis = 1_700_000_000_000L,
        theirCallsign = "BG5JSB",
        myCallsign = "BA7OPF",
        myGrid = myGrid,
        vuccGrids = vuccGrids,
        mode = "FM",
        satelliteName = "SO-50",
        propagationMode = "SAT"
    )

    @Test
    fun matchingGridsProduceNoWarning() {
        assertNull(uploadGridWarning(listOf(record("OM91")), listOf("OM91")))
    }

    @Test
    fun recordsLoggedOutsideTheStationAreCounted() {
        val warning = uploadGridWarning(
            listOf(record("OM91"), record("OM91"), record("OL62")),
            listOf("OL62")
        )
        assertEquals(2, warning?.count)
        assertEquals(listOf("OM91"), warning?.recordGrids)
        assertEquals(listOf("OL62"), warning?.stationGrids)
    }

    @Test
    fun boundaryStationCoversItsRecords() {
        val warning = uploadGridWarning(
            listOf(record("OL62", listOf("OL61", "OL62"))),
            listOf("OL61", "OL62", "OM60", "OM61")
        )
        assertNull(warning)
    }

    @Test
    fun partialOverlapIsNotFlagged() {
        // The record's set touches the station set — a boundary-bookkeeping difference,
        // not the roaming signature.
        assertNull(uploadGridWarning(listOf(record("OL62", listOf("OL62", "OL63"))), listOf("OL62")))
    }

    @Test
    fun recordsWithoutGridsOrStationAreNotFlagged() {
        assertNull(uploadGridWarning(listOf(record("")), listOf("OL62")))
        assertNull(uploadGridWarning(listOf(record("OM91")), emptyList()))
    }

    @Test
    fun stampedGridSetIsComparedInsteadOfTheLoggedGrid() {
        val warning = uploadGridWarning(
            listOf(record("OL62", listOf("OM91", "OM92", "PM01", "PM02"))),
            listOf("OL62")
        )
        assertEquals(1, warning?.count)
        assertEquals(listOf("OM91", "OM92", "PM01", "PM02"), warning?.recordGrids)
    }
}
