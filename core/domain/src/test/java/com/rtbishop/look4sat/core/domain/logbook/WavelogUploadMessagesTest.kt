/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.repository.WavelogProblem
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadOutcome
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavelogUploadMessagesTest {

    private fun record(
        complete: Boolean = true,
        wavelogUploaded: Boolean = false,
        lotwConfirmed: Boolean = false,
        lotwUploaded: Boolean = false
    ) = QsoRecord(
        startUtcMillis = 1_700_000_000_000L,
        theirCallsign = "BG5JVM",
        myCallsign = "BA7OPF",
        status = if (complete) QsoStatus.COMPLETE else QsoStatus.DRAFT,
        lotwConfirmed = lotwConfirmed,
        lotwUploaded = lotwUploaded,
        wavelogUploaded = wavelogUploaded
    )

    private fun preview(
        count: Int = 0,
        skipped: Int = 0,
        reasons: Map<WavelogProblem, Int> = emptyMap(),
        blockedBy: WavelogProblem? = null
    ) = WavelogUploadPreview(
        count = count,
        skipped = skipped,
        reasons = reasons,
        stationLabel = "OL62TI · BA7OPF",
        firstUtc = "",
        lastUtc = "",
        contacts = emptyList(),
        submittedIds = List(count) { it.toLong() },
        blockedBy = blockedBy
    )

    @Test
    fun freshLocalRecordIsEligible() {
        assertTrue(wavelogUploadCandidates(listOf(record())).isNotEmpty())
    }

    @Test
    fun locallyUploadedToLotwStaysEligible() {
        assertTrue(wavelogUploadCandidates(listOf(record(lotwUploaded = true))).isNotEmpty())
        assertTrue(
            wavelogUploadCandidates(listOf(record(lotwConfirmed = true, lotwUploaded = true))).isNotEmpty()
        )
    }

    @Test
    fun importedConfirmationRowIsExcluded() {
        assertTrue(wavelogUploadCandidates(listOf(record(lotwConfirmed = true))).isEmpty())
    }

    @Test
    fun draftAndAlreadyUploadedRecordsAreExcluded() {
        assertTrue(wavelogUploadCandidates(listOf(record(complete = false))).isEmpty())
        assertTrue(wavelogUploadCandidates(listOf(record(wavelogUploaded = true))).isEmpty())
    }

    @Test
    fun problemLabelsAreOperatorFacing() {
        assertEquals("Wavelog upload not configured", WavelogProblem.NOT_CONFIGURED.label())
        assertEquals("no station profile selected", WavelogProblem.NO_STATION.label())
        assertEquals("logged grid outside the Wavelog station grid", WavelogProblem.GRID_MISMATCH.label())
    }

    // --- Piggyback segments (Wavelog rides the LoTW upload flow) ---

    @Test
    fun `idle segment stays silent when nothing was held back`() {
        assertNull(wavelogIdleSegment(preview()))
        assertNull(wavelogIdleSegment(preview(count = 0, skipped = 0)))
    }

    @Test
    fun `idle segment explains a blocked profile`() {
        assertEquals(
            "Wavelog: no station profile selected",
            wavelogIdleSegment(preview(blockedBy = WavelogProblem.NO_STATION))
        )
    }

    @Test
    fun `idle segment lists held-back records with reasons`() {
        assertEquals(
            "Wavelog: 3 QSO(s) skipped — 3× logged grid outside the Wavelog station grid",
            wavelogIdleSegment(preview(skipped = 3, reasons = mapOf(WavelogProblem.GRID_MISMATCH to 3)))
        )
    }

    @Test
    fun `confirmed segment reports the outcome and pre-POST skips`() {
        val outcome = WavelogUploadOutcome.Imported(imported = 2, duplicates = 0, skipped = 0, markIds = listOf(1, 2))
        assertEquals("Uploaded 2 QSO(s) to Wavelog", wavelogConfirmedSegment(outcome, preview()))
        assertEquals(
            "Uploaded 2 QSO(s) to Wavelog\n1 QSO(s) skipped — 1× logged grid outside the Wavelog station grid",
            wavelogConfirmedSegment(
                outcome,
                preview(skipped = 1, reasons = mapOf(WavelogProblem.GRID_MISMATCH to 1))
            )
        )
    }

    @Test
    fun `confirmed segment keeps the all-duplicates wording unprefixed`() {
        val outcome = WavelogUploadOutcome.Imported(imported = 0, duplicates = 2, skipped = 0, markIds = listOf(1, 2))
        assertEquals("All 2 record(s) already in Wavelog", wavelogConfirmedSegment(outcome, preview()))
    }

    @Test
    fun `confirmed segment prefixes failures with Wavelog`() {
        assertEquals("Wavelog: boom", wavelogConfirmedSegment(WavelogUploadOutcome.Failed("boom"), preview()))
    }

    @Test
    fun `transport failure segment asks for a retry on the next upload`() {
        assertEquals(
            "Wavelog: upload failed — it will retry with your next upload",
            wavelogTransportFailureSegment()
        )
    }
}
