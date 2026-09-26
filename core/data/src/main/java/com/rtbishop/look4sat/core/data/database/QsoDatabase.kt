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
@Database(entities = [QsoEntity::class], version = 2, exportSchema = false)
abstract class QsoDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao
}

/** v1 → v2: track whether a QSO was uploaded to LoTW (distinct from confirmed). */
val MIGRATION_QSO_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE qso_records ADD COLUMN lotwUploaded INTEGER NOT NULL DEFAULT 0")
    }
}
