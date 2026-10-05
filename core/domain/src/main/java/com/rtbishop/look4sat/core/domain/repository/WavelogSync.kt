/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.model.GridQso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Trimmed base form of a Wavelog server URL (".../wavelog/" -> ".../wavelog"). */
fun normalizeWavelogUrl(url: String): String = url.trim().trimEnd('/')

/**
 * Resolves the effective sync mode for a request:
 * - an explicit [WavelogSyncMode.Full] is always honored;
 * - an [WavelogSyncMode.Incremental] request is honored only when the stored sync URL
 *   matches the current one — a first sync or a server switch falls back to
 *   [WavelogSyncMode.Full], so cursors from another server never leak in.
 */
fun resolveWavelogSyncMode(storedUrl: String, currentUrl: String, requested: WavelogSyncMode?): WavelogSyncMode {
    if (requested == WavelogSyncMode.Full) return WavelogSyncMode.Full
    val sameServer = storedUrl.isNotBlank() && storedUrl == normalizeWavelogUrl(currentUrl)
    return if (sameServer) WavelogSyncMode.Incremental else WavelogSyncMode.Full
}

/** Grid data derived from Wavelog-synced records, in the same shape the LoTW sync produces. */
data class WavelogGridData(
    val grids: Set<String>,
    val gridQsos: Map<String, List<GridQso>>,
    val roamedGrids: Set<String>
)

internal fun isValidGrid4(grid: String): Boolean =
    grid.length == 4 && grid[0] in 'A'..'R' && grid[1] in 'A'..'R' &&
        grid[2] in '0'..'9' && grid[3] in '0'..'9'

/**
 * Derives the worked-grid set, the per-grid QSO detail and the roamed (own) grids from
 * records pulled by a Wavelog sync — mirroring what the LoTW report parser produces, so
 * both sync sources feed the map's highlight and its 台址 selector the same data shape.
 */
fun buildWavelogGridData(records: List<QsoRecord>): WavelogGridData {
    val grids = linkedSetOf<String>()
    val roamed = linkedSetOf<String>()
    val gridQsos = linkedMapOf<String, MutableList<GridQso>>()
    records.forEach { record ->
        val isSatellite = record.satelliteName.isNotBlank() || record.propagationMode.equals("SAT", true)
        if (!isSatellite) return@forEach
        val theirGrids = (record.theirVuccGrids.ifEmpty { listOf(record.theirGrid) })
            .map { it.trim().uppercase(Locale.US).take(4) }
            .filter(::isValidGrid4)
            .distinct()
        if (theirGrids.isEmpty()) return@forEach
        val myGrids = (record.vuccGrids.ifEmpty { listOf(record.myGrid) })
            .map { it.trim().uppercase(Locale.US).take(4) }
            .filter(::isValidGrid4)
            .toSet()
        roamed += myGrids
        val qso = GridQso(
            call = record.theirCallsign,
            epochMs = record.startUtcMillis,
            satName = record.satelliteName,
            mode = record.mode,
            bandUp = record.band,
            bandDown = record.rxBand,
            dxcc = record.dxcc,
            country = record.country.ifBlank { null },
            cqz = record.cqZone,
            state = record.region.ifBlank { null },
            myGrid = myGrids.sorted().firstOrNull(),
            myGrids = myGrids,
            myCallsign = record.myCallsign.ifBlank { null },
            theirGrids = theirGrids
        )
        theirGrids.forEach { grid ->
            grids += grid
            gridQsos.getOrPut(grid) { mutableListOf() }.add(qso)
        }
    }
    return WavelogGridData(grids, gridQsos, roamed)
}

/**
 * Applies a successful Wavelog sync to the stored map data and sync bookkeeping.
 *
 * Grids, per-grid QSO detail and roamed grids only ever GROW (union): both the
 * incremental and the full re-pull merge into the stored sets, matching how the LoTW
 * sync treats stale data — a full re-pull heals missing records but never deletes map
 * data the app already knows about. Cursors are replaced wholesale ([cursors] covers
 * every station just fetched), and the sync URL/epoch advance so the next run resumes
 * incrementally. Persistence runs on the IO dispatcher — building the per-grid QSO
 * JSON blocks the caller, and for large accounts that froze the main thread after a
 * manual sync.
 *
 * @return the resulting number of worked grids.
 */
suspend fun applyWavelogSyncResult(
    settingsRepo: ISettingsRepo,
    records: List<QsoRecord>,
    cursors: Map<String, Long>,
    url: String,
    now: Long = System.currentTimeMillis()
): Int {
    val data = buildWavelogGridData(records)
    val existingGrids = settingsRepo.getWorkedGrids()
    val existingQsos = settingsRepo.getWorkedGridQsos()
    val existingRoamed = settingsRepo.getRoamedGrids()
    val mergedGrids = existingGrids + data.grids
    val mergedQsos = mergeGridQsos(existingQsos, data.gridQsos)
    val mergedRoamed = existingRoamed + data.roamedGrids
    settingsRepo.setWavelogSyncCursors(cursors)
    settingsRepo.setWavelogSyncUrl(normalizeWavelogUrl(url))
    settingsRepo.setLastWavelogSyncEpochMs(now)
    withContext(Dispatchers.IO) {
        settingsRepo.setWorkedGrids(mergedGrids)
        settingsRepo.setWorkedGridQsos(mergedQsos)
        settingsRepo.setRoamedGrids(mergedRoamed)
    }
    return mergedGrids.size
}
