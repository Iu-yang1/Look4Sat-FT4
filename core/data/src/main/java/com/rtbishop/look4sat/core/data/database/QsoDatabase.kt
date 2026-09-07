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

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rtbishop.look4sat.core.data.database.entity.QsoEntity

@Database(entities = [QsoEntity::class], version = 2, exportSchema = false)
abstract class QsoDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao
}

val QSO_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE qso_records ADD COLUMN mode TEXT NOT NULL DEFAULT 'MFSK'")
        db.execSQL("ALTER TABLE qso_records ADD COLUMN submode TEXT NOT NULL DEFAULT 'FT4'")
        db.execSQL("ALTER TABLE qso_records ADD COLUMN dedupeKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE qso_records ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE qso_records ADD COLUMN messageEvents TEXT NOT NULL DEFAULT '[]'")
        db.execSQL(
            "UPDATE qso_records SET dedupeKey = " +
                "CAST(startUtcMillis AS TEXT) || '|' || UPPER(TRIM(theirCallsign)) || '|' || " +
                "UPPER(TRIM(myCallsign)) || '|' || IFNULL(CAST(txFrequencyHz AS TEXT), '') || '|' || " +
                "UPPER(TRIM(mode)) || '|' || UPPER(TRIM(submode)) || '|' || UPPER(TRIM(satelliteName))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_qso_records_dedupeKey ON qso_records(dedupeKey)")
    }
}
