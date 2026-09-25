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
    fun migrationOneThroughFourPreservesRowsAndAddsColumns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_V1_ENTRIES)
                        db.execSQL(CREATE_V1_RADIOS)
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
            database.execSQL(
                "INSERT INTO radios VALUES " +
                    "('radio-1', 'FM', 1, 145800000, 145900000, 'FM', " +
                    "435000000, 435100000, 'FM', 0, 12345)"
            )

            MIGRATION_1_2.migrate(database)
            MIGRATION_2_3.migrate(database)
            MIGRATION_3_4.migrate(database)

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
            database.query("SELECT uuid, isCustom, service FROM radios").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("radio-1", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
            }
        } finally {
            helper.close()
            context.deleteDatabase(TEST_DATABASE)
        }
    }

    @Test
    fun migrationTwoToThreeAcceptsSchemaThatAlreadyHasIsCustom() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_WITH_COLUMN)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE_WITH_COLUMN)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_V2_RADIOS_WITH_IS_CUSTOM)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO radios VALUES " +
                    "('custom-1', 'Imported', 1, NULL, NULL, NULL, NULL, NULL, NULL, 0, 12345, 1)"
            )

            MIGRATION_2_3.migrate(database)
            MIGRATION_3_4.migrate(database)

            database.query("SELECT uuid, isCustom, service FROM radios").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("custom-1", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
            }
            database.query("PRAGMA table_info(radios)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                var isCustomCount = 0
                var serviceCount = 0
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "isCustom") isCustomCount += 1
                    if (cursor.getString(nameIndex) == "service") serviceCount += 1
                }
                assertEquals(1, isCustomCount)
                assertEquals(1, serviceCount)
            }
        } finally {
            helper.close()
            context.deleteDatabase(TEST_DATABASE_WITH_COLUMN)
        }
    }

    private companion object {
        const val TEST_DATABASE = "look4sat-migration-test.db"
        const val TEST_DATABASE_WITH_COLUMN = "look4sat-migration-with-column-test.db"
        const val CREATE_V1_ENTRIES =
            "CREATE TABLE entries (" +
                "name TEXT NOT NULL, epoch REAL NOT NULL, meanmo REAL NOT NULL, " +
                "eccn REAL NOT NULL, incl REAL NOT NULL, raan REAL NOT NULL, " +
                "argper REAL NOT NULL, meanan REAL NOT NULL, catnum INTEGER NOT NULL, " +
                "bstar REAL NOT NULL, PRIMARY KEY(catnum))"
        const val CREATE_V1_RADIOS =
            "CREATE TABLE radios (" +
                "uuid TEXT NOT NULL, info TEXT NOT NULL, isAlive INTEGER NOT NULL, " +
                "downlinkLow INTEGER, downlinkHigh INTEGER, downlinkMode TEXT, " +
                "uplinkLow INTEGER, uplinkHigh INTEGER, uplinkMode TEXT, " +
                "isInverted INTEGER NOT NULL, catnum INTEGER, PRIMARY KEY(uuid))"
        const val CREATE_V2_RADIOS_WITH_IS_CUSTOM =
            "CREATE TABLE radios (" +
                "uuid TEXT NOT NULL, info TEXT NOT NULL, isAlive INTEGER NOT NULL, " +
                "downlinkLow INTEGER, downlinkHigh INTEGER, downlinkMode TEXT, " +
                "uplinkLow INTEGER, uplinkHigh INTEGER, uplinkMode TEXT, " +
                "isInverted INTEGER NOT NULL, catnum INTEGER, " +
                "isCustom INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid))"
    }
}
