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
import com.rtbishop.look4sat.core.data.database.QSO_MIGRATION_5_6
import com.rtbishop.look4sat.core.data.database.entity.QsoEntity
import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import com.rtbishop.look4sat.core.domain.model.GridQso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
import java.lang.reflect.Proxy

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
    }

    @Test
    fun editingConfirmedContactCreatesAnUnconfirmedRecord() = runTest {
        val repository = QsoRepository(FakeQsoDao(), UnconfinedTestDispatcher(testScheduler))
        val id = repository.save(record(QsoStatus.COMPLETE).copy(lotwConfirmed = true, dxcc = 291))
        val existing = repository.find(id)!!
        val editedId = repository.save(existing.copy(theirCallsign = "JA1ABC"))
        assertTrue(editedId != id)
        assertEquals(true, repository.find(id)!!.lotwConfirmed)
        assertEquals(false, repository.find(editedId)!!.lotwConfirmed)
        assertEquals(null, repository.find(editedId)!!.dxcc)
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

        override suspend fun markWavelogUploaded(ids: List<Long>, stationId: String) {
            records.value = records.value.map { record ->
                if (record.id in ids) record.copy(
                    wavelogUploaded = true,
                    wavelogStation = stationId.ifBlank { record.wavelogStation }
                ) else record
            }
        }

        override suspend fun delete(id: Long) {
            records.value = records.value.filterNot { it.id == id }
        }
    }
}

/**
 * The logbook split reported 2026-09-28: a QSO uploaded from the app and the confirmation LoTW
 * reported for it ended up as two rows, because the record keeps the tracking-source name
 * ("SAUDISAT 1C") and the uplink band while the report carries the ARRL name ("SO-50") — and,
 * before the direction fix, the mirrored band pair.
 */
class QsoRepositoryUpstreamTest {

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
        // The opposite station's grid set from the report lands on the row.
        assertEquals("EN52", rows.first().theirGrid)
        assertEquals("EN52,EN53", rows.first().theirVuccGrids)
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
    fun consolidateConfirmations_foldsADoubledConfirmedPairKeepingTheRicherRow() = runBlocking {
        // The mirrored import the old parser created (no frequencies, no report-side grids —
        // the pre-fix parser filled neither — band direction reversed)... seeded FIRST so it
        // carries the lower id.
        dao.save(
            reportConfirmation(satName = "SO-50")
                .copy(band = "70CM", rxBand = "2M", theirGrid = "", theirVuccGrids = emptyList())
                .toEntity()
        )
        // ...plus the app row that was confirmed on its own later (frequencies, upload state).
        val localId = dao.save(
            loggedInApp("SO-50", lotwUploaded = true).copy(lotwConfirmed = true).toEntity()
        )

        assertEquals(1, repository.consolidateConfirmations())

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(localId, rows.first().id)
        // The surviving row keeps the correct uplink-first bands and gains the report fills.
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        assertEquals("OL62", rows.first().myGrid)
        assertEquals(318, rows.first().dxcc)
        assertEquals("China", rows.first().country)
        assertTrue(rows.first().lotwUploaded)
    }

    @Test
    fun mergeLoTW_healsADoubledMirroredContact() = runBlocking {
        // Two mirrored copies of one confirmation (as the pre-fix parser imported on separate
        // syncs — no frequencies, no report-side grids): the full sync must consolidate them
        // into one row with the report's direction.
        val stale = reportConfirmation(satName = "SO-50")
            .copy(band = "70CM", rxBand = "2M", theirGrid = "", theirVuccGrids = emptyList())
        dao.save(stale.toEntity())
        dao.save(stale.copy(id = 0L).toEntity())

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        // The report's fills land on the healed row.
        assertEquals("EN52", rows.first().theirGrid)
    }

    @Test
    fun mergeLoTW_healsAMirroredRowWhoseDedupeKeyDivergedFromTheReport() = runBlocking {
        // The doubled contacts of the reported bug: a mirrored row written by an older release
        // (millisecond-precision timestamp here — enough to make the dedupe key, which holds
        // the exact time, differ from the report's) used to gain a second row next to it
        // instead of being healed, because every net relied on the key or the strict bands.
        val staleId = dao.save(
            reportConfirmation(satName = "SO-50")
                .copy(
                    band = "70CM", rxBand = "2M", theirGrid = "", theirVuccGrids = emptyList(),
                    startUtcMillis = qsoStart + 456L
                )
                .toEntity()
        )

        repository.mergeLoTW(listOf(reportConfirmation(satName = "SO-50")))

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(staleId, rows.first().id)
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        assertTrue(rows.first().lotwConfirmed)
        assertEquals("EN52", rows.first().theirGrid)
    }

    @Test
    fun mergeLoTW_foldsAMirroredRowSpelledAsPlainMfskIntoItsFt4Report() = runBlocking {
        // Releases that persisted no sub-mode stored FT4 rows as plain MFSK while the report
        // carries MFSK + FT4. The spelling difference must not block the heal either.
        dao.save(
            reportConfirmation(satName = "SO-50")
                .copy(
                    band = "70CM", rxBand = "2M", mode = "MFSK", submode = "",
                    theirGrid = "", theirVuccGrids = emptyList()
                )
                .toEntity()
        )

        repository.mergeLoTW(
            listOf(reportConfirmation(satName = "SO-50").copy(mode = "MFSK", submode = "FT4"))
        )

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("2M", rows.first().band)
        assertEquals("70CM", rows.first().rxBand)
        assertTrue(rows.first().lotwConfirmed)
        assertEquals("EN52", rows.first().theirGrid)
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

    @Test
    fun markUploaded_fillsBlankCallsignsAndKeepsTheOthers() = runBlocking {
        val blank = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "").toEntity())
        val named = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "BH3HCF").toEntity())

        repository.markUploaded(listOf(blank, named), emptyList(), "BA7OPF")

        val rows = dao.getAll().associateBy { it.id }
        // Records logged before a certificate existed get the certificate's callsign; a record that
        // names another callsign is not touched (they are refused, not rewritten, by the uploader).
        assertEquals("BA7OPF", rows[blank]!!.myCallsign)
        assertEquals("BH3HCF", rows[named]!!.myCallsign)
        assertTrue(rows[blank]!!.lotwUploaded)
        assertTrue(rows[named]!!.lotwUploaded)
    }

    @Test
    fun rewriteMyCallsign_rewritesTheConflictRecordsOnly() = runBlocking {
        val conflict = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "XX0YY").toEntity())
        val kept = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(myCallsign = "BA7OPF").toEntity())

        val changed = repository.rewriteMyCallsign(listOf(conflict, kept), "ba7opf")

        assertEquals(1, changed)
        val rows = dao.getAll().associateBy { it.id }
        assertEquals("BA7OPF", rows[conflict]!!.myCallsign)
        assertEquals("BA7OPF", rows[kept]!!.myCallsign)
        assertFalse(rows[conflict]!!.lotwUploaded)
    }

    @Test
    fun markWavelogUploaded_setsTheFlagAndTheStationStamp() = runBlocking {
        val id = dao.save(loggedInApp("SO-50", lotwUploaded = false).toEntity())

        repository.markWavelogUploaded(listOf(id), "3")

        val row = dao.getAll().single()
        assertTrue(row.wavelogUploaded)
        assertFalse(row.lotwUploaded)
        assertEquals("3", row.wavelogStation)
    }

    @Test
    fun save_editingAWavelogUploadedRecordCreatesANewRecord() = runBlocking {
        val id = dao.save(loggedInApp("SO-50", lotwUploaded = false).copy(wavelogUploaded = true).toEntity())

        repository.save(loggedInApp("SO-50", lotwUploaded = false).copy(id = id, theirCallsign = "BG5JVM"))

        assertEquals(2, dao.getAll().size)
        assertEquals(1, dao.getAll().count { it.wavelogUploaded })
    }

    @Test
    fun mergeWavelog_insertsNewRecordsAlreadyMarkedAndStationTagged() = runBlocking {
        val result = repository.mergeWavelog(
            listOf(loggedInApp("SO-50", lotwUploaded = false).copy(wavelogStation = "2"))
        )

        assertEquals(1, result.imported)
        val row = dao.getAll().single()
        assertTrue(row.wavelogUploaded)
        assertEquals("2", row.wavelogStation)
        assertFalse(row.lotwUploaded)
    }

    @Test
    fun mergeWavelog_matchesALocalRowInsteadOfDuplicating() = runBlocking {
        val id = dao.save(loggedInApp("SAUDISAT 1C", lotwUploaded = false).copy(wavelogUploaded = true).toEntity())

        repository.mergeWavelog(
            listOf(loggedInApp("SAUDISAT 1C", lotwUploaded = false).copy(wavelogStation = "1", theirGrid = "EN52"))
        )

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals(id, rows.first().id)
        assertEquals("1", rows.first().wavelogStation)
        assertEquals("EN52", rows.first().theirGrid)
        // The row joins the ARRL spelling like every other import path.
        assertEquals("SO-50", rows.first().satelliteName)
    }

    @Test
    fun mergeWavelog_isIdempotentAcrossSyncs() = runBlocking {
        val records = listOf(loggedInApp("SO-50", lotwUploaded = false).copy(wavelogStation = "1"))

        repository.mergeWavelog(records)
        repository.mergeWavelog(records)

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
        myGrids = setOf("OL62", "OL63"),
        theirGrids = listOf("EN52", "EN53")
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
        ft4AudioFrequencyHz = null,
        automatic = automatic,
        status = status.name,
        rawMessages = "",
        dedupeKey = listOf(startUtcMillis, theirCallsign, myCallsign, txFrequencyHz, mode, submode, satelliteName)
            .joinToString("|"),
        sessionId = "",
        messageEvents = "[]",
        propagationMode = propagationMode,
        lotwConfirmed = lotwConfirmed,
        lotwUploaded = lotwUploaded,
        wavelogUploaded = wavelogUploaded,
        wavelogStation = wavelogStation,
        lotwReceived = lotwReceived,
        lotwQslDate = lotwQslDate,
        vuccGrids = vuccGrids.joinToString(","),
        theirVuccGrids = theirVuccGrids.joinToString(","),
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

        override suspend fun markWavelogUploaded(ids: List<Long>, stationId: String) {
            ids.forEach { id ->
                rows[id]?.let {
                    rows[id] = it.copy(
                        wavelogUploaded = true,
                        wavelogStation = if (stationId.isBlank()) it.wavelogStation else stationId
                    )
                }
            }
        }
    }
}

class QsoDatabaseMigrationTest {
    @Test
    fun forkV5SchemaGainsOnlyUpstreamColumns() {
        val original = forkV5Columns
        val result = migrateV5(original)

        assertEquals(original + upstreamOnlyColumns, result.columns)
        assertEquals(upstreamOnlyColumns, result.addedColumns)
    }

    @Test
    fun upstreamV5SchemaGainsOnlyForkColumns() {
        val original = upstreamV5Columns
        val result = migrateV5(original)

        assertEquals(original + forkOnlyColumns, result.columns)
        assertEquals(forkOnlyColumns, result.addedColumns)
    }

    private data class MigrationResult(val columns: Set<String>, val addedColumns: Set<String>)

    private fun migrateV5(initialColumns: Set<String>): MigrationResult {
        val columns = initialColumns.toMutableSet()
        val added = mutableSetOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "query" -> nameColumnCursor(columns)
                "execSQL" -> {
                    val sql = args?.firstOrNull() as? String
                    Regex("ALTER TABLE qso_records ADD COLUMN ([A-Za-z0-9_]+) .+")
                        .find(sql.orEmpty())?.groupValues?.get(1)?.let { column ->
                            columns += column
                            added += column
                        }
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as SupportSQLiteDatabase

        QSO_MIGRATION_5_6.migrate(db)
        return MigrationResult(columns, added)
    }

    private fun nameColumnCursor(columns: Set<String>): Cursor {
        val iterator = columns.iterator()
        var current: String? = null
        return Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> if (args?.firstOrNull() == "name") 0 else -1
                "moveToNext" -> iterator.hasNext().also { if (it) current = iterator.next() }
                "getString" -> current
                else -> defaultValue(method.returnType)
            }
        } as Cursor
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }

    private companion object {
        val commonColumns = setOf(
            "id", "startUtcMillis", "endUtcMillis", "theirCallsign", "myCallsign", "theirGrid", "myGrid",
            "sentReport", "receivedReport", "txFrequencyHz", "rxFrequencyHz", "band", "rxBand", "mode",
            "submode", "satelliteName", "transponderName", "satelliteMode", "passAosUtcMillis", "automatic",
            "status", "dedupeKey", "propagationMode", "lotwConfirmed", "lotwUploaded", "lotwReceived",
            "lotwQslDate", "vuccGrids", "dxcc", "country", "cqZone", "region", "comment"
        )
        val forkOnlyColumns = setOf("ft4AudioFrequencyHz", "rawMessages", "sessionId", "messageEvents")
        val upstreamOnlyColumns = setOf("theirVuccGrids", "wavelogUploaded", "wavelogStation")
        val forkV5Columns = commonColumns + forkOnlyColumns
        val upstreamV5Columns = commonColumns + upstreamOnlyColumns
    }
}
