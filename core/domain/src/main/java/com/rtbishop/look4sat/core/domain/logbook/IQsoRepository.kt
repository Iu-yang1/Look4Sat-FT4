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
    /**
     * Marks the batch as uploaded and stamps the station-location grids the batch was
     * signed with (1–4 gridsquares: inside a grid / on a line / on a corner), so the
     * logbook row can show which grids the QSO went out under.
     */
    suspend fun markUploaded(ids: List<Long>, grids: List<String> = emptyList())
    suspend fun exportAdi(ids: Set<Long>? = null, includeIncomplete: Boolean = false): String
    suspend fun importAdi(content: String): AdifImportResult
    suspend fun mergeConfirmed(records: List<QsoRecord>): AdifImportResult
    suspend fun mergeLoTW(records: List<QsoRecord>): AdifImportResult

    /**
     * Folds LoTW confirmations that were imported as their own rows back into the local
     * record they belong to (see [com.rtbishop.look4sat.core.domain.logbook.splitConfirmationPairs]).
     * Confirmations merged before the tracker-name/band identity fix split every contact in
     * two; this repairs the existing logbook. Returns the number of merged rows.
     */
    suspend fun consolidateConfirmations(): Int
}
