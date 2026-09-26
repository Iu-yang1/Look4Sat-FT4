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
import com.rtbishop.look4sat.core.domain.logbook.sameConfirmedContact
import com.rtbishop.look4sat.core.domain.logbook.withConfirmation
import com.rtbishop.look4sat.core.domain.logbook.confirmationLookupKey
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

    override suspend fun save(record: QsoRecord): Long = withContext(dispatcher) {
        importMutex.withLock {
            val previous = if (record.id != 0L) dao.find(record.id)?.toDomain() else null
            val saved = if (previous?.lotwConfirmed == true && sameConfirmedContact(record, previous)) {
                record.withConfirmation(previous)
            } else if (previous?.lotwConfirmed == true) record.copy(
                lotwConfirmed = false, lotwQslDate = "", vuccGrids = emptyList(),
                dxcc = null, country = "", cqZone = null, region = ""
            ) else record
            val received = if (previous != null && (
                stableQsoKey(record) != stableQsoKey(previous) || record.myGrid != previous.myGrid ||
                    record.rxFrequencyHz != previous.rxFrequencyHz || record.band != previous.band ||
                    record.rxBand != previous.rxBand || record.propagationMode != previous.propagationMode
                )) false else saved.lotwReceived
            dao.save(saved.copy(lotwReceived = received).toEntity())
        }
    }

    override suspend fun delete(id: Long) = withContext(dispatcher) { importMutex.withLock { dao.delete(id) } }

    override suspend fun markUploaded(ids: List<Long>) = withContext(dispatcher) {
        if (ids.isEmpty()) return@withContext
        dao.markUploaded(ids)
    }

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
            mergeRecords(AdifCodec.decode(content))
        }
    }

    override suspend fun mergeConfirmed(records: List<QsoRecord>): AdifImportResult = withContext(dispatcher) {
        importMutex.withLock { mergeRecords(records.filter { it.lotwConfirmed }) }
    }

    override suspend fun mergeLoTW(records: List<QsoRecord>): AdifImportResult = withContext(dispatcher) {
        importMutex.withLock { mergeRecords(records, fromLoTW = true) }
    }

    private suspend fun mergeRecords(records: List<QsoRecord>, fromLoTW: Boolean = false): AdifImportResult {
        val working = dao.getAll().map(QsoEntity::toDomain).toMutableList()
        val lookup = working.indices.groupBy { working[it].confirmationLookupKey() }
            .mapValues { it.value.toMutableList() }.toMutableMap()
        val knownKeys = working.mapTo(mutableSetOf(), ::stableQsoKey)
        val changes = linkedMapOf<Int, QsoRecord>()
        var imported = 0
        var updated = 0
        var skipped = 0
        records.forEach { remote ->
            if (!fromLoTW && !remote.lotwConfirmed && stableQsoKey(remote) in knownKeys) { skipped++; return@forEach }
            val candidates = if (fromLoTW || remote.lotwConfirmed) lookup[remote.confirmationLookupKey()].orEmpty()
                .filter { sameConfirmedContact(working[it], remote) } else emptyList()
            val exact = candidates.filter { working[it].startUtcMillis == remote.startUtcMillis }
            val match = exact.singleOrNull() ?: candidates.singleOrNull()
            if (match == null) {
                if (stableQsoKey(remote) in knownKeys) { skipped++; return@forEach }
                val added = remote.copy(id = 0L)
                changes[working.size] = added
                lookup.getOrPut(added.confirmationLookupKey()) { mutableListOf() }.add(working.size)
                knownKeys += stableQsoKey(added)
                working += added
                imported++
            } else {
                val previous = working[match]
                val merged = previous.withConfirmation(remote)
                if (merged == previous) skipped++ else {
                    working[match] = merged
                    changes[match] = merged
                    updated++
                }
            }
        }
        dao.saveBatch(changes.values.map(QsoRecord::toEntity))
        return AdifImportResult(imported, skipped, updated)
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
    messageEvents = QsoEventCodec.decode(messageEvents),
    propagationMode = propagationMode,
    lotwConfirmed = lotwConfirmed,
    lotwUploaded = lotwUploaded,
    lotwReceived = lotwReceived,
    lotwQslDate = lotwQslDate,
    vuccGrids = vuccGrids.split(',').filter(String::isNotBlank),
    dxcc = dxcc,
    country = country,
    cqZone = cqZone,
    region = region,
    comment = comment
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
    messageEvents = QsoEventCodec.encode(messageEvents),
    propagationMode = propagationMode.ifBlank { if (satelliteName.isNotBlank()) "SAT" else "" },
    lotwConfirmed = lotwConfirmed,
    lotwUploaded = lotwUploaded,
    lotwReceived = lotwReceived,
    lotwQslDate = lotwQslDate,
    vuccGrids = vuccGrids.joinToString(","),
    dxcc = dxcc,
    country = country,
    cqZone = cqZone,
    region = region,
    comment = comment
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
