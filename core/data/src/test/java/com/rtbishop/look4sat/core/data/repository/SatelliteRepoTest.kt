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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun onlyContentOrStationChangesRestartRepositoryPrediction() = runTest {
        val settings = FakeSettingsRepo()
        (settings.passesSettings as MutableStateFlow).value =
            settings.passesSettings.value.copy(selectedModes = listOf("USB"))
        var lookups = 0
        val local = TestLocalSource { lookups++; emptyList() }
        val repo = SatelliteRepo(
            UnconfinedTestDispatcher(testScheduler), local, settings,
            unusedService(com.rtbishop.look4sat.core.domain.astro.IOrbitEngine::class.java),
            unusedService(com.rtbishop.look4sat.core.domain.astro.ICelestialService::class.java),
            readyData()
        )
        val job = launch { repo.initRepository() }
        try {
            runCurrent()
            val initial = lookups
            assertTrue(initial > 0)
            settings.databaseState.value = settings.databaseState.value.copy(updateTimestamp = 123L)
            runCurrent()
            assertEquals(initial, lookups)
            settings.databaseState.value = settings.databaseState.value.copy(contentVersion = 1L)
            runCurrent()
            assertTrue(lookups > initial)
            val afterContent = lookups
            (settings.stationPosition as MutableStateFlow).value =
                com.rtbishop.look4sat.core.domain.predict.GeoPos(22.0, 113.0)
            runCurrent()
            assertTrue(lookups > afterContent)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun coldLoadPublishesFilteredWindowsBeforeSlowSatellitesFinish() = runBlocking {
        val satellites = listOf(orbitalData(1).getObject(), orbitalData(2).getObject())
        val settings = FakeSettingsRepo()
        (settings.selectedIds as MutableStateFlow).value = listOf(1, 2)
        (settings.passesSettings as MutableStateFlow).value =
            settings.passesSettings.value.copy(minElevation = 10.0)
        val releaseSlow = java.util.concurrent.CountDownLatch(1)
        val engine = object : com.rtbishop.look4sat.core.domain.astro.IOrbitEngine {
            override fun bind(elements: OrbitalData) = elements.getObject()
            override fun openSession(target: OrbitalObject): com.rtbishop.look4sat.core.domain.astro.IOrbitSession {
                return java.lang.reflect.Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(com.rtbishop.look4sat.core.domain.astro.IOrbitSession::class.java)
                ) { _, method, args ->
                    when (method.name) {
                        "getTarget" -> target
                        "passes" -> {
                            if (target.data.catnum == 2) {
                                check(releaseSlow.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            val start = args!![1] as Long
                            listOf(5.0, 25.0).mapIndexed { index, elevation ->
                                com.rtbishop.look4sat.core.domain.predict.OrbitalPass(
                                    aosTime = start + 1000 + index * 1000,
                                    losTime = start + 10_000 + index * 1000,
                                    maxElevation = elevation, orbitalObject = target)
                            }
                        }
                        else -> error("Unexpected ${method.name}")
                    }
                } as com.rtbishop.look4sat.core.domain.astro.IOrbitSession
            }
        }
        val repo = SatelliteRepo(kotlinx.coroutines.Dispatchers.Default,
            TestLocalSource(satellites) { emptyList() }, settings, engine,
            unusedService(com.rtbishop.look4sat.core.domain.astro.ICelestialService::class.java), readyData())
        val job = launch { repo.initRepository() }
        try {
            val first = withTimeout(5000) { repo.passes.first { it.isNotEmpty() } }
            assertTrue(repo.isCalculating.value)
            assertEquals(listOf(1), first.map { it.catNum })
            assertTrue(first.all { it.maxElevation > 10.0 })
            releaseSlow.countDown()
            withTimeout(5000) { repo.isCalculating.first { !it } }
            assertEquals(listOf(1, 2), repo.passes.value.map { it.catNum }.sorted())
        } finally {
            releaseSlow.countDown()
            job.cancelAndJoin()
        }
    }

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
            FakeSettingsRepo(), unusedService(com.rtbishop.look4sat.core.domain.astro.IOrbitEngine::class.java),
            unusedService(com.rtbishop.look4sat.core.domain.astro.ICelestialService::class.java), readyData()
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
            FakeSettingsRepo(), unusedService(com.rtbishop.look4sat.core.domain.astro.IOrbitEngine::class.java),
            unusedService(com.rtbishop.look4sat.core.domain.astro.ICelestialService::class.java), readyData()
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> unusedService(type: Class<T>): T =
        java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            error("Unexpected ${method.name}")
        } as T

    private fun readyData() = object : com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(
            com.rtbishop.look4sat.core.domain.astro.AstroDataState(
                phase = com.rtbishop.look4sat.core.domain.astro.AstroDataPhase.READY, version = "test"))
        override fun snapshot() = object : com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot {
            override val version = "test"
            override val coverageStartMillis = Long.MIN_VALUE
            override val coverageEndMillis = Long.MAX_VALUE
            override val eopEndMillis = Long.MAX_VALUE
        }
        override fun setDownloadUrl(url: String) = Unit
        override fun download() = Unit
        override fun cancel() = Unit
    }
}

private class TestLocalSource(
    private val entries: List<OrbitalObject> = emptyList(),
    private val modes: suspend (List<String>) -> List<Int>
) : ILocalSource {
    override suspend fun getEntriesTotal() = 0
    override suspend fun getEntriesList() = emptyList<SatItem>()
    override suspend fun getEntriesWithIds(ids: List<Int>) = entries.filter { it.data.catnum in ids }
    override suspend fun getEntriesEpochs() = emptyMap<Int, Double>()
    override suspend fun getEntriesNames() = emptyMap<Int, String>()
    override suspend fun renameEntries(names: Map<Int, String>) = Unit
    override suspend fun deleteEntriesWithIds(ids: List<Int>) = Unit
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
