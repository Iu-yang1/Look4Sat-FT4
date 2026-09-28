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
package com.rtbishop.look4sat.core.domain.logbook

import java.util.Locale
import kotlin.math.abs

/** LoTW official name vs tracking-source name ("SO-50 (SaudiOSCAR 50)"): compare the
 *  parenthetical-free prefix. Kept as the last-resort identity for names the alias
 *  table does not know (see [satelliteIdentity]). */
fun satMatchKey(name: String): String = name.substringBefore('(').trim().uppercase(Locale.US)

/**
 * The satellite identity both sides of a match resolve to.
 *
 * A local record keeps whatever the tracking source published ("SAUDISAT 1C",
 * "ISS (ZARYA)", "DIWATA 2B", "JAS 2") while the LoTW report always carries the ARRL
 * name ("SO-50", "ARISS", "PO-101", "FO-29"). Comparing the raw names made the two
 * sides of the SAME contact unequal, so a downloaded confirmation was stored as a
 * second, separate QSO instead of confirming the uploaded one.
 *
 * Both sides are therefore resolved through [LoTWSatelliteAliases], which maps the
 * tracker names onto the ARRL names and every ARRL name onto itself. Names the table
 * does not know (e.g. the recycled "OBJECT xx" placeholders) keep falling back to the
 * parenthetical-free prefix, i.e. they simply never match anything else.
 */
fun satelliteIdentity(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    val prefix = satMatchKey(trimmed)
    return LoTWSatelliteAliases.lookup(trimmed)
        ?: LoTWSatelliteAliases.lookup(prefix)
        ?: prefix
}

/**
 * The name a QSO is stored under: the ARRL name whenever the tracking source's name maps onto
 * one, otherwise the name as published.
 *
 * Records used to keep the tracker's spelling ("SAUDISAT 1C", "ISS (ZARYA)", "OBJECT AY") while
 * every signed ADIF carries the ARRL name, so the logbook showed a different satellite than the
 * one LoTW knows and only the upload path resolved the two. Storing the ARRL name keeps one name
 * for the logbook, the ADIF export and the signature. Names the alias table does not know are
 * left exactly as they are: satellites ARRL does not list have no official name to store, and the
 * recycled "OBJECT xx" placeholders must never be mapped onto a real satellite.
 */
fun officialSatelliteName(name: String, officialNames: Collection<String> = emptyList()): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    LoTWSatelliteAliases.resolve(trimmed, officialNames)?.let { return it }
    val prefix = satMatchKey(trimmed)
    return LoTWSatelliteAliases.lookup(trimmed) ?: LoTWSatelliteAliases.lookup(prefix) ?: trimmed
}

/**
 * Everything about a contact except its bands: the same opposite station, the same
 * satellite, the same mode and less than a minute apart. LoTW reports the minute, so
 * the time window is what makes a match unambiguous.
 */
fun sameContactIdentity(local: QsoRecord, remote: QsoRecord): Boolean {
    val localSat = satelliteIdentity(local.satelliteName)
    return localSat.isNotBlank() &&
        localSat == satelliteIdentity(remote.satelliteName) &&
        local.theirCallsign.trim().equals(remote.theirCallsign.trim(), true) &&
        (local.myCallsign.isBlank() || remote.myCallsign.isBlank() || local.myCallsign.equals(remote.myCallsign, true)) &&
        local.isSatellite == remote.isSatellite &&
        local.displayMode == remote.displayMode &&
        abs(local.startUtcMillis - remote.startUtcMillis) < 60_000L
}

/** Bands agree when both sides name one and they are the same; a missing band is not a mismatch. */
private fun sameBandOrBlank(one: String, other: String): Boolean =
    one.isBlank() || other.isBlank() || one.equals(other, true)

/** LoTW omits frequency and may round time to a minute. Only merge an unambiguous contact. */
fun sameConfirmedContact(local: QsoRecord, remote: QsoRecord): Boolean =
    sameContactIdentity(local, remote) && sameBandOrBlank(local.band, remote.band)

fun QsoRecord.confirmationLookupKey(): String = listOf(
    theirCallsign.trim().uppercase(Locale.US), satelliteIdentity(satelliteName), displayMode
).joinToString("|")

/** A local row and the confirmation row that belongs to it, as indices into one list. */
data class SplitConfirmationPair(val localIndex: Int, val confirmationIndex: Int)

/**
 * Rows that a confirmation should have been folded into but was not, so they can be
 * consolidated back into one.
 *
 * Confirmations merged before the identity fix landed as their own rows: the tracker
 * name vs ARRL name comparison failed, and the band direction of the report was read
 * mirrored (BAND_RX was taken as the uplink), so even satellites with matching names
 * were stored twice. The pair is only accepted when the contact identity matches and
 * the bands are either identical or exactly mirrored — the signature of that second
 * mismatch — which keeps unrelated QSOs of the same operator and minute apart.
 */
fun splitConfirmationPairs(records: List<QsoRecord>): List<SplitConfirmationPair> {
    val pendingIndices = records.indices.filter { !records[it].lotwConfirmed }
    val taken = mutableSetOf<Int>()
    return records.indices.filter { records[it].lotwConfirmed }.mapNotNull { confirmationIndex ->
        val confirmation = records[confirmationIndex]
        val localIndex = pendingIndices.firstOrNull { index ->
            index !in taken && sameContactIdentity(records[index], confirmation) &&
                bandsMatchOrMirror(records[index], confirmation)
        } ?: return@mapNotNull null
        taken += localIndex
        SplitConfirmationPair(localIndex, confirmationIndex)
    }
}

/** Same band on both sides, or the mirrored pair the pre-fix parser produced. */
private fun bandsMatchOrMirror(local: QsoRecord, confirmation: QsoRecord): Boolean =
    (sameBandOrBlank(local.band, confirmation.band) && sameBandOrBlank(local.rxBand, confirmation.rxBand)) ||
        (sameBandOrBlank(local.band, confirmation.rxBand) && sameBandOrBlank(local.rxBand, confirmation.band))

fun QsoRecord.withConfirmation(confirmed: QsoRecord): QsoRecord = copy(
    myCallsign = myCallsign.ifBlank { confirmed.myCallsign },
    theirGrid = confirmed.theirGrid.ifBlank { theirGrid },
    myGrid = myGrid.ifBlank { confirmed.myGrid },
    sentReport = sentReport.ifBlank { confirmed.sentReport },
    receivedReport = receivedReport.ifBlank { confirmed.receivedReport },
    txFrequencyHz = txFrequencyHz ?: confirmed.txFrequencyHz,
    rxFrequencyHz = rxFrequencyHz ?: confirmed.rxFrequencyHz,
    band = band.ifBlank { confirmed.band },
    rxBand = rxBand.ifBlank { confirmed.rxBand },
    propagationMode = propagationMode.ifBlank { confirmed.propagationMode },
    status = QsoStatus.COMPLETE,
    lotwConfirmed = lotwConfirmed || confirmed.lotwConfirmed,
    lotwReceived = lotwReceived || confirmed.lotwReceived || confirmed.lotwConfirmed,
    lotwQslDate = confirmed.lotwQslDate.ifBlank { lotwQslDate },
    vuccGrids = confirmed.vuccGrids.ifEmpty { vuccGrids },
    dxcc = confirmed.dxcc ?: dxcc,
    country = confirmed.country.ifBlank { country },
    cqZone = confirmed.cqZone ?: cqZone,
    region = confirmed.region.ifBlank { region }
)

val QsoRecord.displayMode: String
    get() {
        // Satellite FT4 is MODE=MFSK + SUBMODE=FT4; any other mode keeps its own
        // label even when a stale submode default ("FT4") was persisted.
        val label = if (mode.equals("MFSK", true) && submode.isNotBlank()) submode else mode
        return label.trim().uppercase(Locale.US)
    }

val QsoRecord.isSatellite: Boolean
    get() = propagationMode.equals("SAT", true) || (propagationMode.isBlank() && satelliteName.isNotBlank())

fun frequencyBand(hz: Long?): String = when (hz) {
    null -> ""
    in 28_000_000L..29_700_000L -> "10M"
    in 50_000_000L..54_000_000L -> "6M"
    in 144_000_000L..148_000_000L -> "2M"
    in 219_000_000L..225_000_000L -> "1.25M"
    in 420_000_000L..450_000_000L -> "70CM"
    in 902_000_000L..928_000_000L -> "33CM"
    in 1_240_000_000L..1_300_000_000L -> "23CM"
    in 2_300_000_000L..2_450_000_000L -> "13CM"
    in 5_650_000_000L..5_925_000_000L -> "6CM"
    in 10_000_000_000L..10_500_000_000L -> "3CM"
    else -> ""
}
