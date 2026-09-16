/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
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

import com.rtbishop.look4sat.core.domain.model.GridQso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared LoTW sync orchestration used by BOTH the manual sync (settings screen)
 * and the automatic sync on app start (MainApplication), so the two paths can
 * never drift apart:
 *
 * - [LoTWSyncMode.Incremental] pulls only the confirmations since the last sync
 *   and MERGES them into the stored grids/QSOs (grids only grow; QSO detail is
 *   deduped by call + QSO time).
 * - [LoTWSyncMode.Full] pulls everything and REPLACES the stored data.
 */

/** UTC date ("yyyyMMdd") of the given instant — the LoTW sync cursor granularity. */
fun lotwSyncToday(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyyMMdd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(now))

/**
 * Resolves the effective sync mode for a given request:
 * - an explicit [LoTWSyncMode.Full] request is always honored;
 * - an [LoTWSyncMode.Incremental] request is honored only when the callsign
 *   matches the last sync — a first sync, an unknown/empty record or a
 *   callsign change falls back to [LoTWSyncMode.Full] so no stale grids from
 *   another account linger.
 * With [requested] == null (automatic sync) it picks incremental whenever
 * possible and full otherwise.
 */
fun resolveLoTWSyncMode(
    lastSyncCallsign: String,
    callsign: String,
    requested: LoTWSyncMode?
): LoTWSyncMode {
    if (requested == LoTWSyncMode.Full) return LoTWSyncMode.Full
    val sameCall = lastSyncCallsign.isNotBlank() && lastSyncCallsign == callsign
    return if (sameCall) LoTWSyncMode.Incremental else LoTWSyncMode.Full
}

/**
 * Whether the automatic LoTW sync should run now — mirrors the ephemeris
 * auto-update check: credentials configured, the LoTW auto-sync toggle on, at
 * least one prior manual sync (the first sync stays manual/full), and not
 * already synced today (ARRL limits report pulls, ~once a day per account).
 */
fun shouldAutoSyncLoTW(
    isConfigured: Boolean,
    autoLotwSyncEnabled: Boolean,
    lastSyncDate: String,
    today: String
): Boolean = isConfigured && autoLotwSyncEnabled && lastSyncDate.isNotBlank() && lastSyncDate != today

/**
 * Applies a successful LoTW report to the stored grid data and advances the
 * sync cursor so the next incremental pull asks LoTW for only the
 * confirmations since today. Persistence runs on the IO dispatcher — building
 * the per-grid QSO JSON and writing SharedPreferences blocks the calling
 * thread, and for large accounts (multi-MB JSON) that froze the main thread
 * after a manual sync ('bar done but app stuck').
 *
 * @return the resulting number of worked grids.
 */
suspend fun applyLoTWGridResult(
    settingsRepo: ISettingsRepo,
    result: LoTWResult.Success,
    mode: LoTWSyncMode,
    callsign: String,
    now: Long = System.currentTimeMillis()
): Int {
    val existingGrids = settingsRepo.getWorkedGrids()
    val existingQsos = settingsRepo.getWorkedGridQsos()
    val existingRoamed = settingsRepo.getRoamedGrids()
    val mergedGrids = if (mode == LoTWSyncMode.Incremental) existingGrids + result.grids else result.grids
    val mergedQsos = if (mode == LoTWSyncMode.Incremental) mergeGridQsos(existingQsos, result.qsos) else result.qsos
    val mergedRoamed = if (mode == LoTWSyncMode.Incremental) existingRoamed + result.roamedGrids else result.roamedGrids
    settingsRepo.setLastLotwSyncDate(lotwSyncToday(now))
    settingsRepo.setLastLotwSyncCallsign(callsign)
    withContext(Dispatchers.IO) {
        settingsRepo.setWorkedGrids(mergedGrids)
        settingsRepo.setWorkedGridQsos(mergedQsos)
        settingsRepo.setRoamedGrids(mergedRoamed)
    }
    return mergedGrids.size
}

/** Merge fresh QSO detail into existing per-grid lists, dedup by call + QSO time. */
fun mergeGridQsos(
    existing: Map<String, List<GridQso>>,
    fresh: Map<String, List<GridQso>>
): Map<String, List<GridQso>> {
    val merged = existing.mapValues { (_, list) -> list.toMutableList() }.toMutableMap()
    fresh.forEach { (grid, list) ->
        val target = merged.getOrPut(grid) { mutableListOf() }
        val known = target.mapTo(mutableSetOf()) { it.call to it.epochMs }
        list.forEach { qso ->
            if (known.add(qso.call to qso.epochMs)) target.add(qso)
        }
    }
    return merged
}
