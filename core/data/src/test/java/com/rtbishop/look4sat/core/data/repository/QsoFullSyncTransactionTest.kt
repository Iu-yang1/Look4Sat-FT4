package com.rtbishop.look4sat.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.data.database.QsoDatabase
import com.rtbishop.look4sat.core.data.database.QSO_MIGRATION_7_8
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class QsoFullSyncTransactionTest {
    private lateinit var db:QsoDatabase
    private lateinit var repository:QsoRepository
    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),QsoDatabase::class.java)
            .allowMainThreadQueries().build()
        repository=QsoRepository(db.qsoDao(),Dispatchers.IO)
    }
    @After fun close(){db.close()}
    private fun record(call:String)=QsoRecord(startUtcMillis=1_780_000_000_000,
        theirCallsign=call,myCallsign="BA7OPF",satelliteName="FO-29",mode="CW",band="2m",status=QsoStatus.COMPLETE)

    @Test
    fun batchDeleteRemovesOnlySelectedRecords() = runBlocking {
        val first = repository.save(record("FIRST"))
        repository.save(record("KEEP"))
        val last = repository.save(record("LAST"))

        repository.deleteMany(setOf(first, last))

        assertEquals(listOf("KEEP"), repository.records.first().map { it.theirCallsign })
    }

    @Test
    fun failedBatchDeletionRollsBackEverySelectedRecord() = runBlocking {
        val first = repository.save(record("FIRST"))
        val second = repository.save(record("FAIL"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_delete BEFORE DELETE ON qso_records " +
                "WHEN OLD.theirCallsign = 'FAIL' BEGIN SELECT RAISE(ABORT, 'simulated deletion failure'); END"
        )
        var failed = false
        try {
            repository.deleteMany(linkedSetOf(first, second))
        } catch (_: Exception) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(setOf("FIRST", "FAIL"), repository.records.first().map { it.theirCallsign }.toSet())
    }

    @Test
    fun emptyBatchDoesNotDeleteAnything() = runBlocking {
        repository.save(record("KEEP"))

        repository.deleteMany(emptySet())

        assertEquals(listOf("KEEP"), repository.records.first().map { it.theirCallsign })
    }

    @Test fun fullLotwReplacesOnlyOwnedRowsAndProtectsOtherAccountsAndManualRecords()=runBlocking {
        repository.save(record("MANUAL"))
        repository.mergeLoTW(listOf(record("OLD")),"BA7OPF",false)
        repository.mergeLoTW(listOf(record("OTHER")),"BG5JSU",false)
        repository.mergeWavelog(listOf(record("WAVELOG")),"https://radio.example",false)
        repository.mergeLoTW(listOf(record("NEW")),"BA7OPF",true)
        assertEquals(setOf("MANUAL","OTHER","WAVELOG","NEW"),repository.records.first().map {it.theirCallsign}.toSet())
    }

    @Test fun successfulEmptyFullReportRemovesOnlyThatSource()=runBlocking {
        repository.save(record("MANUAL"))
        repository.mergeWavelog(listOf(record("OLD")),"https://radio.example/",false)
        repository.mergeWavelog(emptyList(),"https://radio.example",true)
        assertEquals(listOf("MANUAL"),repository.records.first().map {it.theirCallsign})
    }

    @Test fun incrementalSyncDoesNotDeletePreviouslyDownloadedRows()=runBlocking {
        repository.mergeLoTW(listOf(record("OLD")),"BA7OPF",false)
        repository.mergeLoTW(listOf(record("NEW")),"BA7OPF",false)
        assertEquals(setOf("OLD","NEW"),repository.records.first().map {it.theirCallsign}.toSet())
    }

    @Test fun writeFailureRollsBackSourceDeletionInTheRealRoomTransaction()=runBlocking {
        repository.mergeLoTW(listOf(record("OLD")),"BA7OPF",false)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_sync BEFORE INSERT ON qso_records " +
            "WHEN NEW.theirCallsign = 'FAIL' BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END")
        var failed=false
        try {repository.mergeLoTW(listOf(record("FAIL")),"BA7OPF",true)}catch(_:Exception){failed=true}
        assertTrue(failed)
        assertEquals(listOf("OLD"),repository.records.first().map {it.theirCallsign})
    }

    @Test fun rowsSharedWithAnotherProviderAreNotDeletedByEitherFullSync()=runBlocking {
        repository.mergeLoTW(listOf(record("SHARED")),"BA7OPF",false)
        repository.mergeWavelog(listOf(record("SHARED")),"https://radio.example",false)
        assertEquals("",repository.records.first().single().syncOrigin)
        repository.mergeLoTW(emptyList(),"BA7OPF",true)
        repository.mergeWavelog(emptyList(),"https://radio.example",true)
        assertEquals(listOf("SHARED"),repository.records.first().map {it.theirCallsign})
    }

    @Test fun editingDownloadedRecordMakesItLocalAndProtectsItFromReplacement()=runBlocking {
        repository.mergeLoTW(listOf(record("OLD")),"BA7OPF",false)
        val old=repository.records.first().single()
        repository.save(old.copy(comment="my edited contact"))
        repository.mergeLoTW(emptyList(),"BA7OPF",true)
        assertEquals("my edited contact",repository.records.first().single().comment)
    }

    @Test fun migrationDefaultsOldRowsToUnknownOriginWithoutDeletingThem()=runBlocking {
        repository.save(record("LEGACY"))
        val sql=db.openHelper.writableDatabase
        sql.execSQL("ALTER TABLE qso_records RENAME COLUMN syncOrigin TO oldSyncOrigin")
        QSO_MIGRATION_7_8.migrate(sql)
        sql.query("PRAGMA table_info(qso_records)").use {cursor->
            var found=false
            while(cursor.moveToNext())if(cursor.getString(cursor.getColumnIndexOrThrow("name"))=="syncOrigin") {
                found=true;assertEquals("''",cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
            }
            assertTrue(found)
        }
        sql.query("SELECT theirCallsign,syncOrigin FROM qso_records").use {cursor->
            assertTrue(cursor.moveToFirst());assertEquals("LEGACY",cursor.getString(0));assertEquals("",cursor.getString(1))
        }
    }
}
