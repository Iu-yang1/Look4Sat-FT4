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
import org.junit.Test

class LogbookResubmitTest {

    private fun record(
        id: Long,
        status: QsoStatus = QsoStatus.COMPLETE,
        uploaded: Boolean = false,
        confirmed: Boolean = false
    ) = QsoRecord(
        id = id,
        startUtcMillis = 1_760_000_000_000L + id,
        theirCallsign = "BG5JSB",
        myCallsign = "BA7OPF",
        mode = "FM",
        satelliteName = "SO-50",
        status = status,
        lotwUploaded = uploaded,
        lotwConfirmed = confirmed
    )

    @Test
    fun keepsUploadedAndConfirmedRecords() {
        // The whole point of a resubmit: rows already on LoTW must not be filtered out.
        val records = listOf(record(1, uploaded = true), record(2, confirmed = true), record(3))
        assertEquals(listOf(1L, 2L, 3L), resubmitCandidates(records, setOf(1, 2, 3)).map { it.id })
    }

    @Test
    fun dropsRecordsOutsideTheSelection() {
        val records = listOf(record(1), record(2), record(3))
        assertEquals(listOf(2L), resubmitCandidates(records, setOf(2)).map { it.id })
    }

    @Test
    fun dropsIncompleteRecords() {
        val records = listOf(
            record(1, status = QsoStatus.DRAFT),
            record(2, status = QsoStatus.ABORTED),
            record(3, status = QsoStatus.COMPLETE)
        )
        assertEquals(listOf(3L), resubmitCandidates(records, setOf(1, 2, 3)).map { it.id })
    }

    @Test
    fun emptySelectionYieldsNothing() {
        assertEquals(emptyList<Long>(), resubmitCandidates(listOf(record(1)), emptySet()).map { it.id })
    }
}
