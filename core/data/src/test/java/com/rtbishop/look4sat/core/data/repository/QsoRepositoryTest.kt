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
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import com.rtbishop.look4sat.core.domain.model.GridQso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logbook split reported 2026-09-28: a QSO uploaded from the app and the confirmation LoTW
 * reported for it ended up as two rows, because the record keeps the tracking-source name
 * ("SAUDISAT 1C") and the uplink band while the report carries the ARRL name ("SO-50") — and,
 * before the direction fix, the mirrored band pair.
 */
class QsoRepositoryTest {

    private val dao = FakeQsoDao()
    private val repository = QsoRepository(dao, Dispatchers.Unconfined)

    /** 2026-09-16 07:45Z, an SO-50 contact of the user's own report. */
    private val qsoStart = 1_789_544_700_000L

    @Test
    fun mergeLoTW_confirmsTheUploadedRecordInsteadOfAddingASecondRow() = runBlocking {
        val uploadedId = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity()) // 145.850 -> 2M

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(uploadedId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // The row is also renamed to the ARRL spelling while it is merged.
        assertEquals("SO-50", rows.first().satelliteName)
        assertEquals(145_850_000L, rows.first().txFrequencyHz)
    }

    @Test
    fun mergeLoTW_foldsRowsSplitByTheOldNameAndBandMismatch() = runBlocking {
        val uploadedId = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())
        // Row imported before the fix: ARRL name and the mirrored band pair (BAND_RX read as
        // the uplink), so it never folded into the local record.
        dao.save(
            reportConfirmation(satName = "SO-50").copy(band = "70CM", rxBand = "2M").toEntity()
        )

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(uploadedId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // The operator's own frequencies survive the consolidation.
        assertEquals(145_850_000L, rows.first().txFrequencyHz)
        assertEquals(436_795_000L, rows.first().rxFrequencyHz)
    }

    @Test
    fun mergeLoTW_keepsAnotherContactOfTheSamePassApart() = runBlocking {
        dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())
        val otherOperator = reportConfirmation(satName = "SO-50").copy(theirCallsign = "BG5JVM")

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50"), otherOperator))

        val rows = dao.getAll()
        assertEquals(2, rows.size)
        // The local row is confirmed by its own report entry; the second operator's contact
        // is a separate QSO and stays a separate, separately confirmed row.
        assertEquals(setOf("BG5JSB", "BG5JVM"), rows.map { it.theirCallsign }.toSet())
        assertTrue(rows.all { it.lotwConfirmed })
    }

    @Test
    fun consolidateConfirmations_repairsAnAlreadySplitLogbook() = runBlocking {
        val uploadedId = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())
        // Row imported before the fix: ARRL name + mirrored band pair.
        dao.save(reportConfirmation(satName = "SO-50").copy(band = "70CM", rxBand = "2M").toEntity())

        assertEquals(1, repository.consolidateConfirmations())

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(uploadedId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // Nothing left to fold on a second pass.
        assertEquals(0, repository.consolidateConfirmations())
    }

    @Test
    fun consolidateConfirmations_renamesRowsImportedUnderATrackerName() = runBlocking {
        // A record logged before names were normalised at write time.
        val id = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).toEntity())

        repository.consolidateConfirmations()

        val row = dao.getAll().single()
        assertEquals(id, row.id)
        assertEquals("SO-50", row.satelliteName)
        // Upload state and the operator's own frequencies survive the rename.
        assertTrue(row.lotwUploaded)
        assertEquals(145_850_000L, row.txFrequencyHz)
        // A satellite ARRL does not list keeps whatever the tracker published.
        dao.save(loggedInApp("FOO-1", lotwUploaded = false).toEntity())
        repository.consolidateConfirmations()
        assertEquals(setOf("SO-50", "FOO-1"), dao.getAll().map { it.satelliteName }.toSet())
    }

    @Test
    fun mergeLoTW_fixesBandDirectionOfAStaleConfirmationRow() = runBlocking {
        // A confirmation downloaded by the pre-fix parser: ARRL name but the band direction
        // mirrored (downlink stored as the uplink), and no local row to fold it into, so it
        // sits as its own QSL row.
        val staleId = dao.save(reportConfirmation(satName = "SO-50").copy(band = "70CM", rxBand = "2M").toEntity())

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(staleId, rows.first().id)
        assertTrue(rows.first().lotwConfirmed)
        // The stale row adopts the report's (uplink-first) direction instead of being skipped
        // forever by the dedupe key.
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
    }

    @Test
    fun save_editingAnUploadedRecordCreatesANewRecord() = runBlocking {
        val originalId = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true))
        val edited = loggedInApp("SAUDISAT 1C", lotwUploaded = true).copy(id = originalId, theirCallsign = "BG5JVM")

        val newId = repository.save(edited)

        assertNotEquals(originalId, newId)
        val rows = dao.getAll()
        assertEquals(2, rows.size)
        // The original keeps its identity and upload state, untouched.
        val original = rows.first { it.id == originalId }
        assertEquals("BG5JSB", original.theirCallsign)
        assertTrue(original.lotwUploaded)
        // The edited content is a fresh, uploadable record.
        val created = rows.first { it.id == newId }
        assertEquals("BG5JVM", created.theirCallsign)
        assertFalse(created.lotwUploaded)
        assertFalse(created.lotwConfirmed)
    }

    @Test
    fun save_editingAnUnuploadedRecordUpdatesInPlace() = runBlocking {
        val id = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = false))
        repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = false).copy(id = id, theirCallsign = "BG5JVM"))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("BG5JVM", rows.first().theirCallsign)
    }

    @Test
    fun save_editingAnUploadedRecordWithoutChangesKeepsOneRow() = runBlocking {
        val id = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true))
        repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).copy(id = id))

        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun save_editingAnUploadedRecordWithOnlySecondsChangedKeepsOneRow() = runBlocking {
        val id = repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true))
        // The edit dialog edits whole minutes and rounds the seconds away on save, so a
        // seconds-only difference must not be treated as an edit (no new record).
        repository.save(loggedInApp("SAUDISAT 1C", lotwUploaded = true).copy(id = id, startUtcMillis = qsoStart + 10_000L))

        assertEquals(1, dao.getAll().size)
    }

    /** A record as the log page creates it: tracker name, repeater pair, uplink band. */
    private fun loggedInApp(satelliteName: String, lotwUploaded: Boolean) = QsoRecord(
        startUtcMillis = qsoStart,
        theirCallsign = "BG5JSB",
        myCallsign = "BA7OPF",
        txFrequencyHz = 145_850_000L,
        rxFrequencyHz = 436_795_000L,
        band = "2M",
        rxBand = "70CM",
        mode = "FM",
        satelliteName = satelliteName,
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE,
        lotwUploaded = lotwUploaded
    )

    /** A confirmation as the report parser + [toConfirmedRecord] build it (BAND carries the uplink). */
    private fun reportConfirmation(satName: String) = GridQso(
        call = "BG5JSB",
        epochMs = qsoStart,
        satName = satName,
        mode = "FM",
        bandUp = "2M",
        bandDown = "70CM",
        dxcc = 318,
        country = "China",
        cqz = 24,
        state = "GD",
        myGrid = "OL62",
        myGrids = setOf("OL62", "OL63")
    ).toConfirmedRecord("BA7OPF")

    private fun QsoRecord.toEntity() = QsoEntity(
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
        automatic = automatic,
        status = status.name,
        dedupeKey = listOf(startUtcMillis, theirCallsign, myCallsign, txFrequencyHz, mode, submode, satelliteName)
            .joinToString("|"),
        propagationMode = propagationMode,
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

    private class FakeQsoDao : QsoDao {
        private val rows = linkedMapOf<Long, QsoEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<QsoEntity>> = flowOf(rows.values.toList())

        override suspend fun find(id: Long): QsoEntity? = rows[id]

        override suspend fun getAll(): List<QsoEntity> = rows.values.toList()

        override suspend fun getDedupeKeys(): List<String> = rows.values.map { it.dedupeKey }

        override suspend fun save(record: QsoEntity): Long {
            val id = if (record.id == 0L) nextId++ else record.id
            rows[id] = record.copy(id = id)
            return id
        }

        override suspend fun importRecords(records: List<QsoEntity>): List<Long> = records.map { save(it) }

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }

        override suspend fun markUploaded(ids: List<Long>) {
            ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(lotwUploaded = true) } }
        }
    }
}
