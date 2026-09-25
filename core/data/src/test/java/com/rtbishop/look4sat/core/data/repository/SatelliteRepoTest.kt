/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SatelliteRepoTest {
    @Test
    fun reloadedEmptyModeFilterKeepsSelectedSatellitesWithoutRadioMetadata() {
        val satellites = listOf(orbitalData(1).getObject(), orbitalData(2).getObject())
        val savedModes = emptyList<String>().joinToString(",")

        val filtered = filterSatellitesByModes(satellites, parseSelectedModes(savedModes), emptyList())

        assertEquals(satellites, filtered)
    }

    @Test
    fun selectedModeWithNoMatchesProducesNoSatellites() {
        val satellite = orbitalData(1).getObject()

        assertTrue(filterSatellitesByModes(listOf(satellite), emptyList(), emptyList()).isNotEmpty())
        assertTrue(filterSatellitesByModes(listOf(satellite), listOf("FT4"), emptyList()).isEmpty())
    }

    @Test
    fun newerCalculationCancelsOlderRequestAndAlwaysClearsBusyState() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var lookupCount = 0
        var firstCancelled = false
        val local = TestLocalSource {
            lookupCount++
            if (lookupCount == 1) {
                firstStarted.complete(Unit)
                try {
                    releaseFirst.await()
                } finally {
                    firstCancelled = true
                }
            }
            emptyList()
        }
        val repo = SatelliteRepo(
            UnconfinedTestDispatcher(testScheduler),
            local,
            FakeSettingsRepo()
        )
        val first = async { repo.calculatePasses(0L, 1, 0.0, 0, 1439, false, listOf("USB")) }
        firstStarted.await()
        val second = async { repo.calculatePasses(60_000L, 1, 0.0, 0, 1439, false, listOf("FM")) }
        runCurrent()
        second.await()
        first.await()

        assertTrue(firstCancelled)
        assertFalse(repo.isCalculating.value)
    }

    @Test
    fun cancellingNewestCalculationClearsBusyState() = runTest {
        val started = CompletableDeferred<Unit>()
        val local = TestLocalSource {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            emptyList()
        }
        val repo = SatelliteRepo(
            UnconfinedTestDispatcher(testScheduler),
            local,
            FakeSettingsRepo()
        )
        val calculation = async {
            repo.calculatePasses(0L, 1, 0.0, 0, 1439, false, listOf("USB"))
        }
        started.await()

        calculation.cancelAndJoin()

        assertFalse(repo.isCalculating.value)
    }

    private fun orbitalData(catnum: Int) = OrbitalData(
        name = "TEST",
        epoch = 24_100.0,
        meanmo = 15.0,
        eccn = 0.001,
        incl = 51.6,
        raan = 0.0,
        argper = 0.0,
        meanan = 0.0,
        catnum = catnum,
        bstar = 0.0
    )
}

private class TestLocalSource(
    private val modes: suspend (List<String>) -> List<Int>
) : ILocalSource {
    override suspend fun getEntriesTotal() = 0
    override suspend fun getEntriesList() = emptyList<SatItem>()
    override suspend fun getEntriesWithIds(ids: List<Int>) = emptyList<OrbitalObject>()
    override suspend fun insertEntries(entries: List<OrbitalData>) = Unit
    override suspend fun deleteEntries() = Unit
    override suspend fun getIdsWithModes(modes: List<String>) = this.modes(modes)
    override suspend fun getIdsWithModesAndUplink(modes: List<String>) = this.modes(modes)
    override suspend fun getIdsWithModesAndAmateur(modes: List<String>) = this.modes(modes)
    override suspend fun getRadiosTotal() = 0
    override suspend fun getRadiosWithId(id: Int) = emptyList<SatRadio>()
    override suspend fun insertRadios(radios: List<SatRadio>, isCustom: Boolean) = Unit
    override suspend fun deleteManagedRadios() = Unit
    override suspend fun deleteRadios() = Unit
}
