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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.repository.IWavelogSyncRepository
import com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Pulls QSO records from a self-hosted Wavelog via the v1 API.
 *
 * Verified against Wavelog 3.0.2 (`Api.php::get_contacts_adif`):
 * - `POST {base}/index.php/api/get_contacts_adif`
 *   with `{"key", "station_id": [id...], "fetchfromid": <last id>, "limit"}`.
 * - Responds `{"status":"successful","lastfetchedid":<id>,"exported_qsos":N,"adif":"..."}`;
 *   only rows whose internal id is greater than `fetchfromid` are exported, so the cursor
 *   makes the pull incremental. Each station keeps its own cursor: a station profile added
 *   later starts from 0 and pulls its full history without touching the others'.
 * - The export is a full ADIF document; decoding reuses [AdifCodec].
 */
class WavelogSyncRepository internal constructor(
    client: OkHttpClient = OkHttpClient()
) : IWavelogSyncRepository {

    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun fetchNewRecords(
        url: String,
        apiKey: String,
        stations: List<WavelogStationInfo>,
        cursors: Map<String, Long>,
        full: Boolean
    ): WavelogSyncFetch? = withContext(Dispatchers.IO) {
        val base = url.trim().trimEnd('/')
        val key = apiKey.trim()
        if (base.isBlank() || key.isBlank() || stations.isEmpty()) return@withContext null
        val records = mutableListOf<QsoRecord>()
        val newCursors = mutableMapOf<String, Long>()
        val failed = mutableListOf<String>()
        // Every station profile is fetched (including ones the server marks inactive:
        // their historical QSOs still belong to the account and the logbook shows them).
        stations.forEach { station ->
            var cursor = if (full) 0L else cursors[station.id] ?: 0L
            var advanced = false
            var stationFailed = false
            try {
                var guard = 0
                while (guard++ < MAX_CHUNKS_PER_STATION) {
                    val page = fetchPage(base, key, station.id, cursor)
                    if (page == null) {
                        stationFailed = true
                        break
                    }
                    cursor = maxOf(cursor, page.cursor)
                    advanced = true
                    page.records.forEach { records += it.copy(wavelogStation = station.id) }
                    if (page.exported < CHUNK_LIMIT) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                stationFailed = true
            }
            // A partially fetched station keeps the cursor of its last good page, so the
            // next sync resumes exactly where this one stopped — nothing is fetched twice
            // and nothing is skipped.
            if (advanced) newCursors[station.id] = cursor
            if (stationFailed) failed += station.id
        }
        WavelogSyncFetch(records, newCursors, failed)
    }

    internal data class Page(val records: List<QsoRecord>, val cursor: Long, val exported: Int)

    private fun fetchPage(base: String, key: String, stationId: String, fromId: Long): Page? {
        val body = JSONObject().apply {
            put("key", key)
            put("station_id", JSONArray().put(stationId))
            put("fetchfromid", fromId)
            put("limit", CHUNK_LIMIT)
        }.toString()
        val request = Request.Builder()
            .url("$base/index.php/api/get_contacts_adif")
            .post(body.toRequestBody(jsonType))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            parsePage(response.body.string())
        }
    }

    internal fun parsePage(text: String): Page? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("status") != "successful") return null
        if (!json.has("lastfetchedid")) return null
        val cursor = json.optLong("lastfetchedid", 0L)
        val exported = json.optInt("exported_qsos", 0)
        val adif = json.optString("adif")
        if (adif.isBlank()) return Page(emptyList(), cursor, exported)
        return Page(AdifCodec.decode(adif), cursor, exported)
    }

    private companion object {
        const val CHUNK_LIMIT = 5000
        const val MAX_CHUNKS_PER_STATION = 200
    }
}
