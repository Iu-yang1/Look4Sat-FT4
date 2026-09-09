/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QsoMigrationTest {
    @Test
    fun migrationOneThroughThreePreservesRecordsAndAddsLogMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(CREATE_V1_QSO)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO qso_records VALUES " +
                    "(1, 1725189306000, NULL, 'K1ABC', 'BA7OPF', '', '', '-08', '-12', " +
                    "145990000, 435810000, '2m', '70cm', 'AO-123', 'U/V', 'U/V', NULL, 1500, 1, " +
                    "'COMPLETE', 'K1ABC BA7OPF -08')"
            )

            QSO_MIGRATION_1_2.migrate(database)

            database.query("SELECT mode, submode, dedupeKey, sessionId, messageEvents FROM qso_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MFSK", cursor.getString(0))
                assertEquals("FT4", cursor.getString(1))
                assertEquals("1725189306000|K1ABC|BA7OPF|145990000|MFSK|FT4|AO-123", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals("[]", cursor.getString(4))
            }
            database.query("PRAGMA index_list(qso_records)").use { cursor ->
                val names = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("index_qso_records_dedupeKey" in names)
            }
            QSO_MIGRATION_2_3.migrate(database)
            database.query(
                "SELECT theirCallsign, mode, submode, propagationMode, lotwConfirmed, " +
                    "vuccGrids, dxcc, cqZone, comment, rawMessages FROM qso_records"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("K1ABC", cursor.getString(0))
                assertEquals("MFSK", cursor.getString(1))
                assertEquals("FT4", cursor.getString(2))
                assertEquals("SAT", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals("", cursor.getString(5))
                assertTrue(cursor.isNull(6))
                assertTrue(cursor.isNull(7))
                assertEquals("", cursor.getString(8))
                assertEquals("K1ABC BA7OPF -08", cursor.getString(9))
            }
        } finally {
            helper.close()
            context.deleteDatabase(TEST_DATABASE)
        }
    }

    private companion object {
        const val TEST_DATABASE = "look4sat-qso-migration-test.db"
        const val CREATE_V1_QSO =
            "CREATE TABLE qso_records (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startUtcMillis INTEGER NOT NULL, " +
                "endUtcMillis INTEGER, theirCallsign TEXT NOT NULL, myCallsign TEXT NOT NULL, " +
                "theirGrid TEXT NOT NULL, myGrid TEXT NOT NULL, sentReport TEXT NOT NULL, " +
                "receivedReport TEXT NOT NULL, txFrequencyHz INTEGER, rxFrequencyHz INTEGER, " +
                "band TEXT NOT NULL, rxBand TEXT NOT NULL, satelliteName TEXT NOT NULL, " +
                "transponderName TEXT NOT NULL, satelliteMode TEXT NOT NULL, passAosUtcMillis INTEGER, " +
                "ft4AudioFrequencyHz INTEGER, automatic INTEGER NOT NULL, status TEXT NOT NULL, " +
                "rawMessages TEXT NOT NULL)"
    }
}
