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
class Look4SatMigrationTest {
    @Test
    fun migrationOneToTwoPreservesRowsAndAddsNdot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_V1_ENTRIES)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO entries VALUES " +
                    "('TEST', 24100.0, 15.0, 0.001, 51.6, 1.0, 2.0, 3.0, 12345, 0.0001)"
            )

            MIGRATION_1_2.migrate(database)

            database.query("PRAGMA table_info(entries)").use { cursor ->
                val names = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("ndot" in names)
            }
            database.query("SELECT catnum, ndot FROM entries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(12_345, cursor.getInt(0))
                assertEquals(0.0, cursor.getDouble(1), 0.0)
            }
        } finally {
            helper.close()
            context.deleteDatabase(TEST_DATABASE)
        }
    }

    private companion object {
        const val TEST_DATABASE = "look4sat-migration-test.db"
        const val CREATE_V1_ENTRIES =
            "CREATE TABLE entries (" +
                "name TEXT NOT NULL, epoch REAL NOT NULL, meanmo REAL NOT NULL, " +
                "eccn REAL NOT NULL, incl REAL NOT NULL, raan REAL NOT NULL, " +
                "argper REAL NOT NULL, meanan REAL NOT NULL, catnum INTEGER NOT NULL, " +
                "bstar REAL NOT NULL, PRIMARY KEY(catnum))"
    }
}
