/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

import kotlinx.coroutines.flow.Flow

interface IQsoRepository {
    val records: Flow<List<QsoRecord>>

    suspend fun find(id: Long): QsoRecord?
    suspend fun save(record: QsoRecord): Long
    suspend fun delete(id: Long)
    suspend fun markUploaded(ids: List<Long>)
    suspend fun exportAdi(ids: Set<Long>? = null, includeIncomplete: Boolean = false): String
    suspend fun importAdi(content: String): AdifImportResult
    suspend fun mergeConfirmed(records: List<QsoRecord>): AdifImportResult
    suspend fun mergeLoTW(records: List<QsoRecord>): AdifImportResult
}
