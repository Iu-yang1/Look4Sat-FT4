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
import java.util.Locale

interface ILoTWUploadRepository {
    suspend fun certificate(): LoTWCertificate?
    suspend fun station(): LoTWStation?
    suspend fun importCertificate(data: ByteArray, password: CharArray): LoTWCertificate
    suspend fun saveCertificatePassword(password: CharArray): LoTWCertificate
    suspend fun saveStation(station: LoTWStation): LoTWStation
    suspend fun removeCertificate()
    /** Region field (State/Province/Prefecture…) and national CQZ/ITUZ map for a DXCC entity. */
    suspend fun stationMeta(dxcc: Int): LoTWStationMeta
    /** Every satellite name ARRL accepts (config.tq6), sorted. Only these names may be
     *  signed, so the logbook UI validates/picks satellite names from this list. */
    suspend fun satelliteCatalog(): List<String>
    suspend fun audit(records: List<QsoRecord>): LoTWUploadAudit
    suspend fun prepare(records: List<QsoRecord>, resubmit: Boolean): LoTWUploadPreview
    suspend fun upload(previewId: String): LoTWUploadResult
    fun discardPreview()
}

data class LoTWCertificate(
    val callsign: String,
    val dxcc: Int,
    val serial: String,
    val expires: String,
    val firstQsoDate: String,
    val lastQsoDate: String,
    val passwordSaved: Boolean = false
)

/** The location is explicitly confirmed for the selected contacts, not inferred from today's GPS. */
data class LoTWStation(
    val grid: String = "",
    val cqZone: String = "",
    val ituZone: String = "",
    val region: String = "",
    val county: String = "",
    val iota: String = ""
)

/** One (ITU:CQ) pair from a LoTW zonemap, e.g. Guangdong is 44:24. */
data class LoTWZonePair(val itu: Int, val cq: Int)

/** One selectable region code with the zones it maps to (cross-zone regions carry several pairs). */
data class LoTWRegionOption(val code: String, val name: String, val zones: List<LoTWZonePair>)

/** Country-dependent region field (US_STATE, CN_PROVINCE, …) with its selectable options. */
data class LoTWRegionField(val id: String, val label: String, val options: List<LoTWRegionOption>)

/**
 * Everything the station-location UI needs for one DXCC entity:
 * the region field to show (null when the country has none) and the national
 * zonemap (used to prefill CQZ/ITUZ when the country has a single zone pair).
 */
data class LoTWStationMeta(
    val regionField: LoTWRegionField? = null,
    val countryZones: List<LoTWZonePair> = emptyList()
)

data class LoTWUploadPreview(
    val id: String,
    val callsign: String,
    val dxcc: Int,
    val grid: String,
    val count: Int,
    val skipped: Int,
    val firstUtc: String,
    val lastUtc: String,
    val contacts: List<String>,
    val unknownSkipped: Int = 0,
    /** Un-signable records (invalid call/date/…) skipped instead of aborting the batch. */
    val unavailableSkipped: Int = 0,
    /** Why those records were un-signable, so the operator sees the real cause. */
    val unavailableReasons: Map<LoTWProblem, Int> = emptyMap(),
    /** Records dropped because the same contact already appears earlier in the batch. */
    val duplicateSkipped: Int = 0,
    /** Ids of the records that actually made it into this TQ8 batch. Only these
     *  may be marked "uploaded" after an accepted POST — never the full candidate list. */
    val submittedIds: List<Long> = emptyList(),
    /** The full station-location grid set this batch is signed with (1 inside a grid,
     *  2 on a line, 4 on a corner). Stamped onto the uploaded records so the logbook
     *  can show which gridsquares the QSO went out under. */
    val grids: List<String> = emptyList(),
    /** Records whose own grids fall outside this batch's station location — the fingerprint
     *  of uploading while roaming with a stale station location. Null when all covered. */
    val gridWarning: LoTWGridWarning? = null,
    /** True when this batch is a resubmit: already-uploaded/confirmed records were allowed
     *  through so the corrected station location reaches LoTW as an update of the contact. */
    val resubmit: Boolean = false
)

/** Pre-upload grid audit result: [count] records carry own grids outside the station grids. */
data class LoTWGridWarning(
    val count: Int,
    /** Distinct own grids of the affected records (normalized, 4 characters). */
    val recordGrids: List<String>,
    /** Distinct station-location grids this batch would be signed with. */
    val stationGrids: List<String>
)

/** Why the un-uploadable records of a selection cannot be signed, and what was left out. */
data class LoTWUploadAudit(
    val total: Int,
    val pending: Int,
    val uploaded: Int,
    val unknown: Int,
    val unavailable: Int,
    /** Signing failures counted per reason (see [com.rtbishop.look4sat.core.domain.logbook.label]). */
    val reasons: Map<LoTWProblem, Int> = emptyMap(),
    /** First offending value per reason, e.g. SATELLITE -> "SAUDISAT 1C". */
    val details: Map<LoTWProblem, String> = emptyMap(),
    /** Records dropped as duplicates of an earlier record in the same selection. */
    val duplicates: Int = 0,
    /** Records that are not marked complete, so they are not uploadable at all. */
    val incomplete: Int = 0
)

sealed interface LoTWUploadResult {
    data class Accepted(val count: Int) : LoTWUploadResult
    data class Rejected(val message: String = "") : LoTWUploadResult
    /** May have reached LoTW. Do not retry automatically. */
    data object Unknown : LoTWUploadResult
    data object ExpiredPreview : LoTWUploadResult
}

class LoTWOperationException(val reason: LoTWProblem, val detail: String = "") : Exception(reason.name)

enum class LoTWProblem {
    CERTIFICATE_PASSWORD, CERTIFICATE_INVALID, CERTIFICATE_EXPIRED, CERTIFICATE_MISSING,
    CERTIFICATE_FORMAT, STORAGE, EMPTY_SELECTION, CALLSIGN_MISMATCH, QSO_DATE, QSO_FUTURE, STATION_GRID,
    STATION_REGION, STATION_ZONE, STATION_IOTA, MODE, BAND, SATELLITE, INVALID_CONTACT,
    LOCATION_MISMATCH, TOO_MANY_CONTACTS
}

/**
 * Records whose own grids (the set a previous upload stamped, else the logged grid) do not
 * appear at all in the station-location grids an upload goes out under — the signature of
 * uploading while roaming with a station location that was never updated. Records without
 * any grid are not flagged, and a partially covered set (boundary operations) is not
 * flagged either: only a fully disjoint pair, which is always a mistake, warns.
 */
fun uploadGridWarning(records: List<QsoRecord>, stationGrids: List<String>): LoTWGridWarning? {
    val station = stationGrids.mapNotNull { it.grid4() }.toSet()
    if (station.isEmpty()) return null
    val affected = records.filter { record ->
        val own = record.ownGrids()
        own.isNotEmpty() && own.none { it in station }
    }
    if (affected.isEmpty()) return null
    return LoTWGridWarning(
        affected.size,
        affected.flatMap { it.ownGrids() }.distinct().sorted(),
        station.sorted()
    )
}

/** The grid set a previous upload stamped onto the record, else the grid it was logged under. */
private fun QsoRecord.ownGrids(): Set<String> =
    (vuccGrids.ifEmpty { listOf(myGrid) }).mapNotNull { it.grid4() }.toSet()

/** Normalized 4-character grid, null when the value is not a usable grid. */
private fun String.grid4(): String? = trim().uppercase(Locale.US).take(4).takeIf { it.length >= 4 }
