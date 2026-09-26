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

/**
 * Separate database for the logbook (QSO records, LoTW confirmations and
 * upload receipts). Kept apart from [Look4SatDb] so existing installations
 * never need a migration of the satellite database.
 */
@Database(entities = [QsoEntity::class], version = 5, exportSchema = false)
abstract class QsoDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao
}

val QSO_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE qso_records ADD COLUMN lotwReceived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE qso_records SET lotwReceived = 1 WHERE lotwConfirmed = 1")
    }
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

val QSO_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("propagationMode", "lotwQslDate", "vuccGrids", "country", "region", "comment").forEach { column ->
            db.execSQL("ALTER TABLE qso_records ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL("ALTER TABLE qso_records ADD COLUMN lotwConfirmed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE qso_records ADD COLUMN dxcc INTEGER")
        db.execSQL("ALTER TABLE qso_records ADD COLUMN cqZone INTEGER")
        db.execSQL("UPDATE qso_records SET propagationMode = 'SAT' WHERE TRIM(satelliteName) != ''")
    }
}

/** v4 -> v5: distinguish a submitted QSO from a confirmed QSO. */
val QSO_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE qso_records ADD COLUMN lotwUploaded INTEGER NOT NULL DEFAULT 0")
    }
}
