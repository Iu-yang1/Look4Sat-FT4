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

import com.rtbishop.look4sat.core.data.database.QsoDao
import com.rtbishop.look4sat.core.data.database.entity.QsoEntity
import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QsoRepositoryTest {
    @Test fun unconfirmedDownloadMergesWithoutConfirmingOrDuplicatingLocalDetails() = runTest {
        val repository = QsoRepository(FakeQsoDao(), UnconfinedTestDispatcher(testScheduler))
        val local = record(QsoStatus.COMPLETE).copy(startUtcMillis = 1_750_000_000_000, rawMessages = listOf("original"), comment = "Portable")
        val id = repository.save(local)
        val remote = local.copy(id = 0, txFrequencyHz = null, rawMessages = emptyList(), comment = "", lotwReceived = true)
        val merged = repository.mergeLoTW(listOf(remote))
        assertEquals(0, merged.imported)
        assertEquals(1, merged.updated)
        assertEquals(1, repository.mergeLoTW(listOf(remote)).skipped)
        val saved = repository.find(id)!!
        assertEquals(false, saved.lotwConfirmed)
        assertEquals(true, saved.lotwReceived)
        assertEquals(listOf("original"), saved.rawMessages)
        assertEquals(local.txFrequencyHz, saved.txFrequencyHz)
        repository.save(saved.copy(txFrequencyHz = 145_991_000))
        assertEquals(false, repository.find(id)!!.lotwReceived)
    }

    @Test fun unconfirmedRefreshNeverDowngradesExistingConfirmation() = runTest {
        val repository = QsoRepository(FakeQsoDao(), UnconfinedTestDispatcher(testScheduler))
        val local = record(QsoStatus.COMPLETE).copy(lotwConfirmed = true, lotwQslDate = "20260909")
        val id = repository.save(local)
        repository.mergeLoTW(listOf(local.copy(lotwConfirmed = false, lotwReceived = true, lotwQslDate = "")))
        assertEquals(true, repository.find(id)!!.lotwConfirmed)
        assertEquals("20260909", repository.find(id)!!.lotwQslDate)
    }

    @Test
    fun repeatedImportSkipsTheSameQso() = runTest {
        val dao = FakeQsoDao()
        val repository = QsoRepository(dao, UnconfinedTestDispatcher(testScheduler))
        val content = AdifCodec.encode(listOf(record(QsoStatus.COMPLETE)))

        val first = repository.importAdi(content)
        val second = repository.importAdi(content)

        assertEquals(1, first.imported)
        assertEquals(0, first.skipped)
        assertEquals(0, second.imported)
        assertEquals(1, second.skipped)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun defaultExportExcludesDraftsAndAbortedRecords() = runTest {
        val dao = FakeQsoDao()
        val repository = QsoRepository(dao, UnconfinedTestDispatcher(testScheduler))
        repository.importAdi(
            AdifCodec.encode(
                listOf(
                    record(QsoStatus.COMPLETE),
                    record(QsoStatus.DRAFT).copy(startUtcMillis = 2_000L),
                    record(QsoStatus.ABORTED).copy(startUtcMillis = 3_000L)
                )
            )
        )

        val defaultStatuses = AdifCodec.decode(repository.exportAdi()).map { it.status }
        val explicitStatuses = AdifCodec.decode(repository.exportAdi(includeIncomplete = true)).map { it.status }.toSet()

        assertEquals(listOf(QsoStatus.COMPLETE), defaultStatuses)
        assertEquals(setOf(QsoStatus.COMPLETE, QsoStatus.DRAFT, QsoStatus.ABORTED), explicitStatuses)
    }

    private fun record(status: QsoStatus) = QsoRecord(
        startUtcMillis = 1L,
        theirCallsign = "K1ABC",
        myCallsign = "BA7OPF",
        txFrequencyHz = 145_990_000L,
        mode = "FM",
        submode = "",
        satelliteName = "AO-123",
        status = status
    )

    @Test
    fun repeatLoTWSyncUpdatesExistingQsoWithoutLosingManualDetails() = runTest {
        val dao = FakeQsoDao()
        val repository = QsoRepository(dao, UnconfinedTestDispatcher(testScheduler))
        val local = record(QsoStatus.COMPLETE).copy(startUtcMillis = 1_750_000_000_000L, rawMessages = listOf("original"), comment = "Portable")
        val id = repository.save(local)
        val confirmed = local.copy(id = 0, txFrequencyHz = null, rawMessages = emptyList(), comment = "", theirGrid = "FN31", lotwConfirmed = true)
        val first = repository.mergeConfirmed(listOf(confirmed))
        val second = repository.mergeConfirmed(listOf(confirmed))
        assertEquals(0, first.imported)
        assertEquals(1, first.updated)
        assertEquals(1, second.skipped)
        val saved = repository.find(id)!!
        assertEquals("Portable", saved.comment)
        assertEquals(listOf("original"), saved.rawMessages)
        assertEquals(local.txFrequencyHz, saved.txFrequencyHz)
        assertEquals(true, saved.lotwConfirmed)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun changingContactIdentityClearsItsOldConfirmation() = runTest {
        val repository = QsoRepository(FakeQsoDao(), UnconfinedTestDispatcher(testScheduler))
        val id = repository.save(record(QsoStatus.COMPLETE).copy(lotwConfirmed = true, dxcc = 291))
        val existing = repository.find(id)!!
        repository.save(existing.copy(theirCallsign = "JA1ABC"))
        assertEquals(false, repository.find(id)!!.lotwConfirmed)
        assertEquals(null, repository.find(id)!!.dxcc)
    }
}

private class FakeQsoDao : QsoDao {
    private val records = MutableStateFlow<List<QsoEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<QsoEntity>> = records

    override suspend fun find(id: Long): QsoEntity? = records.value.firstOrNull { it.id == id }

    override suspend fun getAll(): List<QsoEntity> = records.value.sortedByDescending { it.startUtcMillis }

    override suspend fun getDedupeKeys(): List<String> = records.value.map { it.dedupeKey }

    override suspend fun save(record: QsoEntity): Long {
        val id = record.id.takeIf { it != 0L } ?: nextId++
        records.value = records.value.filterNot { it.id == id } + record.copy(id = id)
        return id
    }

    override suspend fun importRecords(records: List<QsoEntity>): List<Long> = records.map { save(it) }

    override suspend fun markUploaded(ids: List<Long>) {
        records.value = records.value.map { record ->
            if (record.id in ids) record.copy(lotwUploaded = true) else record
        }
    }

    override suspend fun delete(id: Long) {
        records.value = records.value.filterNot { it.id == id }
    }
}
