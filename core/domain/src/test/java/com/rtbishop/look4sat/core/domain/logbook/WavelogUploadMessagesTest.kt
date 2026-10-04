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
import org.junit.Assert.assertEquals
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
}
