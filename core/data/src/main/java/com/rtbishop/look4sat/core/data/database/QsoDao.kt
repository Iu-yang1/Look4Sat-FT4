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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rtbishop.look4sat.core.data.database.entity.QsoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QsoDao {
    @Query("SELECT * FROM qso_records ORDER BY startUtcMillis DESC, id DESC")
    fun observeAll(): Flow<List<QsoEntity>>

    @Query("SELECT * FROM qso_records WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): QsoEntity?

    @Query("SELECT * FROM qso_records ORDER BY startUtcMillis DESC, id DESC")
    suspend fun getAll(): List<QsoEntity>

    @Query("SELECT dedupeKey FROM qso_records")
    suspend fun getDedupeKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(record: QsoEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importRecords(records: List<QsoEntity>): List<Long>

    @Query("DELETE FROM qso_records WHERE id = :id")
    suspend fun delete(id: Long)
}
