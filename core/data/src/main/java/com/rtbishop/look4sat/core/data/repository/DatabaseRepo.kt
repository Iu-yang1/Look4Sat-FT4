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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.source.NetworkResult
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream

class DatabaseRepo(
    private val dispatcher: CoroutineDispatcher,
    private val dataParser: DataParser,
    private val localSource: ILocalSource,
    private val remoteSource: IRemoteSource,
    private val settingsRepo: ISettingsRepo
) : IDatabaseRepo {

    private val customSourceType = "Other"

    override suspend fun updateTLEFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileStream(uri)?.let { stream ->
            val entries = parseSatelliteStream(uri, unwrapIfZipped(uri, stream))
            localSource.insertEntries(entries)
            settingsRepo.setSatelliteTypeIds(customSourceType, entries.map { it.catnum })
            importedCount = entries.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    override suspend fun updateTransceiversFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileStream(uri)?.let { stream ->
            val transceivers = dataParser.parseJSONStream(unwrapIfZipped(uri, stream))
            localSource.insertRadios(transceivers, isCustom = true)
            importedCount = transceivers.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    override suspend fun updateFromRemote() = withContext(dispatcher) {
        val settings = settingsRepo.dataSourcesSettings.value
        // Keep the raw URL as the status key (matches what the settings UI
        // displays) while requesting with the normalized URL.
        val tleUrls = settings.satelliteUrls
            .filterIndexed { i, url -> url.isNotBlank() && settings.isSatelliteEnabled(i) }
            .map { it to normalizeUrl(it) }
            .distinctBy { it.second }
        val radioUrls = settings.transceiversUrls
            .filterIndexed { i, url -> url.isNotBlank() && settings.isTransceiverEnabled(i) }
            .map { it to normalizeUrl(it) }
            .distinctBy { it.second }
        val builtinTypesByUrl = Sources.satelliteDataUrls
            .filterValues { it.isNotBlank() }
            .mapValues { normalizeUrl(it.value) }
            .entries
            .associate { (type, url) -> url to type }
        val importedTypeIds = mutableMapOf<String, MutableList<Int>>()
        // launch all network requests concurrently, keeping the raw url as key for status reporting
        val tleJobs = tleUrls.map { (raw, norm) -> async { raw to remoteSource.getNetworkStream(norm) } }
        val radioJobs = radioUrls.map { (raw, norm) -> async { raw to remoteSource.getNetworkStream(norm) } }
        val tleResults = tleJobs.awaitAll()
        val radioResults = radioJobs.awaitAll()
        // report the HTTP status code of every source (200, 404, ...)
        settingsRepo.updateDataSourcesStatus(
            (tleResults + radioResults).associate { (url, result) -> url to result.code }
        )
        // parse fetched data concurrently and associate known built-in URLs with existing type filters.
        val importedEntries = tleResults.flatMap { (rawUrl, result) ->
            val normUrl = normalizeUrl(rawUrl)
            val entries = result.stream?.let { parseSatelliteStream(normUrl, unwrapIfZipped(normUrl, it)) }.orEmpty()
            val type = builtinTypesByUrl[normUrl] ?: customSourceType
            importedTypeIds.getOrPut(type) { mutableListOf() }.addAll(entries.map { it.catnum })
            entries
        }.distinctBy { it.catnum }
        importedTypeIds.forEach { (type, ids) -> settingsRepo.setSatelliteTypeIds(type, ids.distinct()) }
        val importedRadios = radioResults.flatMap { (rawUrl, result) ->
            val normUrl = normalizeUrl(rawUrl)
            result.stream?.let { dataParser.parseJSONStream(unwrapIfZipped(normUrl, it)) }.orEmpty()
        }.filter { it.uuid.isNotBlank() }.distinctBy { it.uuid }
        // insert parsed data into the database.
        // Transceivers are a full snapshot: sources publish active entries only, so a retired
        // transceiver simply disappears from the feed and has to be dropped locally as well.
        // Manually imported ones (isCustom) are kept: no source can refresh them, so nothing
        // would bring them back if they were replaced here.
        if (importedRadios.isNotEmpty()) {
            localSource.deleteManagedRadios()
            localSource.insertRadios(importedRadios)
        }
        if (importedEntries.isNotEmpty()) localSource.insertEntries(importedEntries)
        updateAmSatLiveLists()
        setUpdateSuccessful(System.currentTimeMillis())
    }

    /**
     * Refresh the AMSAT Live FM/Linear satellite lists (transponders currently
     * on the air). Best effort: any failure keeps the previous lists and never
     * blocks the rest of the data update, so the mutual-match filter simply
     * falls back to the last successful snapshot.
     */
    private suspend fun updateAmSatLiveLists() = withContext(dispatcher) {
        runCatching {
            val entries = localSource.getEntriesList() // catnum -> name
            val jobs = Sources.amSatLiveUrls.map { (type, url) ->
                async { type to remoteSource.getNetworkStream(url) }
            } + async { "Active" to fetchAmSatActiveStream() }
            val results = jobs.awaitAll()
            val fmNames = results.firstOrNull { it.first == "FM" }?.second?.stream
                ?.let { dataParser.parseAmSatLivePage(it) }.orEmpty()
            val linearNames = results.firstOrNull { it.first == "Linear" }?.second?.stream
                ?.let { dataParser.parseAmSatLivePage(it) }.orEmpty()
            // Amateur whitelist: a failed fetch yields an empty result, which
            // must NOT wipe out the last good snapshot. Fall back to the
            // persisted whitelist, and only persist when this fetch actually
            // returned a stream.
            val activeStream = results.firstOrNull { it.first == "Active" }?.second?.stream
            val activeCatnums = activeStream
                ?.let { dataParser.parseAmSatActiveCatnums(it) }
                .orEmpty()
                .ifEmpty { settingsRepo.getAmSatActiveCatnums() }
            if (activeStream != null) settingsRepo.setAmSatActiveCatnums(activeCatnums)
            val nameToCatnum = entries.associate { it.name.uppercase() to it.catnum }
            // Resolve every matching local entry per AMSAT name. A single match
            // is kept as-is (so satellites absent from the amateur whitelist,
            // e.g. JO-97/TO-108, are never dropped). Only when several local
            // entries share the name (ISS station modules ZARYA/UNITY/ZVEZDA/
            // DESTINY/NAUKA) is the whitelist used to pick the primary one.
            fun resolvePerName(name: String): Set<Int> {
                val keys = dataParser.normalizeAmSatName(name)
                val all = nameToCatnum.filter { (localName, _) ->
                    dataParser.matchesAmSatName(localName, keys)
                }.values.toSet()
                if (all.size <= 1) return all
                val preferred = all.intersect(activeCatnums)
                if (preferred.isNotEmpty()) return preferred
                // Whitelist unavailable: keep only the primary entry (smallest
                // catnum — ISS ZARYA=25544 is the smallest of its five module
                // entries) instead of admitting every alias (DESTINY etc.).
                return setOf(all.minOrNull() ?: all.first())
            }
            // A failed FM/Linear page fetch yields an empty list here; do NOT
            // keep the stale list verbatim (it may predate the whitelist and
            // still contain ISS module aliases like DESTINY). Instead re-run
            // the multi-match disambiguation on the previous catnums via their
            // local names, so the whitelist / smallest-catnum rules clean it.
            fun cleanStaleList(previous: Set<Int>): Set<Int> =
                previous.mapNotNull { catnum ->
                    nameToCatnum.entries.firstOrNull { it.value == catnum }?.key
                }.flatMap { resolvePerName(it) }.toSet()
            val fmCatnums = fmNames.flatMap { resolvePerName(it) }.toSet()
                .ifEmpty { cleanStaleList(settingsRepo.getAmSatFmCatnums()) }
            val linearCatnums = linearNames.flatMap { resolvePerName(it) }.toSet()
                .ifEmpty { cleanStaleList(settingsRepo.getAmSatLinearCatnums()) }
            settingsRepo.setAmSatCatnums(fmCatnums, linearCatnums)
            println("AMSAT live lists updated: FM=${fmCatnums.size}, Linear=${linearCatnums.size}, Active=${activeCatnums.size}")
        }.onFailure {
            // Keep the previous lists; the mutual filter stays on the last good snapshot.
            println("AMSAT live lists update failed: $it")
        }
    }

    /** Try the amateur-whitelist sources in order and return the first
     *  NetworkResult that carried a stream (or null if all failed). */
    private suspend fun fetchAmSatActiveStream(): NetworkResult? {
        for (url in Sources.amSatActiveUrls) {
            val result = remoteSource.getNetworkStream(url)
            if (result.stream != null) return result
        }
        return null
    }

    override suspend fun clearAllData() = withContext(dispatcher) {
        localSource.deleteEntries()
        localSource.deleteRadios()
        settingsRepo.setAmSatCatnums(emptySet(), emptySet())
        setUpdateSuccessful(0L)
    }

    private fun normalizeUrl(url: String): String =
        if (url.startsWith("http", ignoreCase = true)) url else "https://$url"

    private suspend fun parseSatelliteStream(url: String, stream: InputStream): List<OrbitalData> {
        val bufferedStream = stream.buffered()
        return when {
            hasCsvHint(url) || looksLikeCsv(bufferedStream) -> dataParser.parseCSVStream(bufferedStream)
            else -> dataParser.parseTLEStream(bufferedStream)
        }
    }

    private fun hasCsvHint(url: String): Boolean {
        return url.contains("FORMAT=csv", ignoreCase = true) ||
            url.endsWith(".csv", ignoreCase = true) ||
            url.endsWith(".csv.zip", ignoreCase = true)
    }

    private fun looksLikeCsv(stream: InputStream): Boolean {
        if (!stream.markSupported()) return false
        stream.mark(4096)
        val preview = ByteArray(4096)
        val length = stream.read(preview)
        stream.reset()
        if (length <= 0) return false
        val line = preview.decodeToString(0, length).lineSequence().firstOrNull()?.trim().orEmpty()
        return line.contains("OBJECT_NAME", ignoreCase = true) ||
            line.contains("NORAD_CAT_ID", ignoreCase = true) ||
            line.count { it == ',' } >= 4
    }

    private suspend fun setUpdateSuccessful(timestamp: Long) {
        settingsRepo.updateDatabaseState(
            DatabaseState(localSource.getRadiosTotal(), localSource.getEntriesTotal(), timestamp)
        )
    }

    private fun unwrapIfZipped(url: String, stream: InputStream): InputStream =
        if (url.endsWith(".zip", ignoreCase = true)) ZipInputStream(stream).apply { nextEntry } else stream
}
