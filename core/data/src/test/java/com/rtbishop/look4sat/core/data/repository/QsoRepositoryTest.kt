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

    override suspend fun delete(id: Long) {
        records.value = records.value.filterNot { it.id == id }
    }
}
