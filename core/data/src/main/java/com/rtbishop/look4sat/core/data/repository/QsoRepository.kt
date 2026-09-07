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
import com.rtbishop.look4sat.core.domain.logbook.AdifImportResult
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.QsoEventCodec
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

class QsoRepository(
    private val dao: QsoDao,
    private val dispatcher: CoroutineDispatcher
) : IQsoRepository {
    private val importMutex = Mutex()

    override val records: Flow<List<QsoRecord>> = dao.observeAll().map { records -> records.map(QsoEntity::toDomain) }

    override suspend fun find(id: Long): QsoRecord? = withContext(dispatcher) { dao.find(id)?.toDomain() }

    override suspend fun save(record: QsoRecord): Long = withContext(dispatcher) { dao.save(record.toEntity()) }

    override suspend fun delete(id: Long) = withContext(dispatcher) { dao.delete(id) }

    override suspend fun exportAdi(ids: Set<Long>?, includeIncomplete: Boolean): String = withContext(dispatcher) {
        val records = dao.getAll()
            .asSequence()
            .filter { ids == null || it.id in ids }
            .map(QsoEntity::toDomain)
            .filter { includeIncomplete || it.status == QsoStatus.COMPLETE }
            .toList()
        AdifCodec.encode(records)
    }

    override suspend fun importAdi(content: String): AdifImportResult = withContext(dispatcher) {
        importMutex.withLock {
            val decoded = AdifCodec.decode(content)
            val knownKeys = dao.getDedupeKeys().toMutableSet()
            val uniqueRecords = decoded.filter { knownKeys.add(stableQsoKey(it)) }
            val inserted = dao.importRecords(uniqueRecords.map { it.copy(id = 0L).toEntity() }).count { it != -1L }
            AdifImportResult(imported = inserted, skipped = decoded.size - inserted)
        }
    }
}

private fun QsoEntity.toDomain() = QsoRecord(
    id = id,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    theirCallsign = theirCallsign,
    myCallsign = myCallsign,
    theirGrid = theirGrid,
    myGrid = myGrid,
    sentReport = sentReport,
    receivedReport = receivedReport,
    txFrequencyHz = txFrequencyHz,
    rxFrequencyHz = rxFrequencyHz,
    band = band,
    rxBand = rxBand,
    mode = mode,
    submode = submode,
    satelliteName = satelliteName,
    transponderName = transponderName,
    satelliteMode = satelliteMode,
    passAosUtcMillis = passAosUtcMillis,
    ft4AudioFrequencyHz = ft4AudioFrequencyHz,
    automatic = automatic,
    status = runCatching { QsoStatus.valueOf(status) }.getOrDefault(QsoStatus.DRAFT),
    rawMessages = rawMessages.lineSequence().filter(String::isNotBlank).toList(),
    sessionId = sessionId,
    messageEvents = QsoEventCodec.decode(messageEvents)
)

private fun QsoRecord.toEntity() = QsoEntity(
    id = id,
    startUtcMillis = startUtcMillis,
    endUtcMillis = endUtcMillis,
    theirCallsign = theirCallsign.trim().uppercase(Locale.US),
    myCallsign = myCallsign.trim().uppercase(Locale.US),
    theirGrid = theirGrid.trim().uppercase(Locale.US),
    myGrid = myGrid.trim().uppercase(Locale.US),
    sentReport = sentReport.trim(),
    receivedReport = receivedReport.trim(),
    txFrequencyHz = txFrequencyHz,
    rxFrequencyHz = rxFrequencyHz,
    band = band,
    rxBand = rxBand,
    mode = mode.trim(),
    submode = submode.trim(),
    satelliteName = satelliteName,
    transponderName = transponderName,
    satelliteMode = satelliteMode,
    passAosUtcMillis = passAosUtcMillis,
    ft4AudioFrequencyHz = ft4AudioFrequencyHz,
    automatic = automatic,
    status = status.name,
    rawMessages = rawMessages.joinToString("\n"),
    dedupeKey = stableQsoKey(this),
    sessionId = sessionId,
    messageEvents = QsoEventCodec.encode(messageEvents)
)

internal fun stableQsoKey(record: QsoRecord): String = listOf(
    record.startUtcMillis.toString(),
    record.theirCallsign.trim().uppercase(Locale.US),
    record.myCallsign.trim().uppercase(Locale.US),
    record.txFrequencyHz?.toString().orEmpty(),
    record.mode.trim().uppercase(Locale.US),
    record.submode.trim().uppercase(Locale.US),
    record.satelliteName.trim().uppercase(Locale.US)
).joinToString("|")
