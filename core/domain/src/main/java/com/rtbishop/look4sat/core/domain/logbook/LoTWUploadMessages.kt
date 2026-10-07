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
package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadAudit

/**
 * Wording for the reasons a QSO cannot be signed for LoTW.
 *
 * The logbook used to report every one of these as "invalid call/date", which sent the operator
 * looking at the callsign and date even when the real cause was a missing frequency (no BAND) or
 * a satellite name ARRL does not know.
 */
fun LoTWProblem.label(): String = when (this) {
    LoTWProblem.BAND -> "missing frequency/band"
    LoTWProblem.SATELLITE -> "satellite name not in ARRL's list"
    LoTWProblem.MODE -> "mode not accepted by LoTW"
    LoTWProblem.QSO_DATE -> "date outside the certificate"
    LoTWProblem.QSO_FUTURE -> "record time is in the future"
    LoTWProblem.CALLSIGN_MISMATCH -> "MY callsign does not match the certificate"
    LoTWProblem.INVALID_CONTACT -> "invalid callsign/record"
    LoTWProblem.STATION_GRID -> "station grid missing"
    LoTWProblem.STATION_REGION -> "station region missing"
    LoTWProblem.STATION_ZONE -> "station zones missing"
    LoTWProblem.STATION_IOTA -> "station IOTA missing"
    LoTWProblem.TOO_MANY_CONTACTS -> "too many contacts in one batch"
    LoTWProblem.EMPTY_SELECTION -> "nothing selected"
    else -> name.lowercase().replace('_', ' ')
}

/**
 * One-line explanation of why records were left out of an upload, e.g.
 * "3 QSO(s) can't be uploaded — 2× missing frequency/band, 1× satellite name not in ARRL's list (SAUDISAT 1C)".
 * Returns an empty string when nothing was skipped.
 */
fun unavailableUploadSummary(
    total: Int,
    reasons: Map<LoTWProblem, Int> = emptyMap(),
    details: Map<LoTWProblem, String> = emptyMap(),
    duplicates: Int = 0,
    incomplete: Int = 0
): String {
    if (total <= 0) return ""
    val parts = reasons.entries
        .sortedByDescending { it.value }
        .map { (problem, count) ->
            val detail = details[problem]?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            "$count× ${problem.label()}$detail"
        }
        .toMutableList()
    if (duplicates > 0) parts += "$duplicates× duplicate of another record in the batch"
    if (incomplete > 0) parts += "$incomplete× record not completed"
    val subject = if (total == 1) "1 QSO can't be uploaded" else "$total QSO(s) can't be uploaded"
    val detail = parts.joinToString(", ").ifBlank { "check the logbook" }
    return "$subject — $detail"
}

/**
 * True when a batch that has nothing uploadable still has to reach the preview dialog.
 *
 * The preview is the only place that carries the callsign-conflict actions (rewrite the records, or
 * upload them under another certificate). Short-circuiting such a batch into the plain "can't be
 * uploaded" message strands exactly the records the operator needs to act on — the message has no
 * buttons to get out of, so those QSOs could never be uploaded.
 */
fun LoTWUploadAudit.needsPreviewForConflicts(): Boolean =
    pending == 0 && (reasons[LoTWProblem.CALLSIGN_MISMATCH] ?: 0) > 0
