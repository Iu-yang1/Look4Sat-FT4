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

import com.rtbishop.look4sat.core.domain.repository.WavelogProblem
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadOutcome
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview

/**
 * The records a Wavelog upload may submit: locally recorded contacts only.
 *
 * Pure LoTW-imported confirmation rows never carry [QsoRecord.lotwUploaded] (the ADIF decoder
 * does not set it), while a locally recorded row keeps its value through a confirmation merge —
 * [withConfirmation] leaves `lotwUploaded` untouched. Confirmed local rows uploaded to LoTW
 * from this app therefore stay eligible; imported history stays out of the upload.
 */
fun wavelogUploadCandidates(records: List<QsoRecord>): List<QsoRecord> = records.filter {
    it.status == QsoStatus.COMPLETE && !it.wavelogUploaded && (!it.lotwConfirmed || it.lotwUploaded)
}

/** Operator-facing wording for why a record cannot enter a Wavelog upload. */
fun WavelogProblem.label(): String = when (this) {
    WavelogProblem.NOT_CONFIGURED -> "Wavelog upload not configured"
    WavelogProblem.NO_STATION -> "no station profile selected"
    WavelogProblem.GRID_MISMATCH -> "logged grid outside the Wavelog station grid"
}

/** One-line summary of the records excluded before a Wavelog upload (empty when none). */
fun wavelogSkipSummary(preview: WavelogUploadPreview): String {
    if (preview.skipped <= 0) return ""
    val detail = preview.reasons.entries.sortedByDescending { it.value }
        .joinToString(", ") { (problem, count) -> "$count× ${problem.label()}" }
    return "${preview.skipped} QSO(s) skipped" + if (detail.isNotBlank()) " — $detail" else ""
}

/** One-line result wording for a finished Wavelog upload. */
fun wavelogResultMessage(result: WavelogUploadOutcome): String = when (result) {
    is WavelogUploadOutcome.Imported -> buildString {
        if (result.imported == 0 && result.duplicates > 0) {
            append("All ${result.markIds.size} record(s) already in Wavelog")
        } else {
            append("Uploaded ${result.imported} QSO(s) to Wavelog")
            if (result.duplicates > 0) append(" — ${result.duplicates} already there")
        }
        if (result.skipped > 0) append(" — ${result.skipped} skipped by Wavelog")
    }
    is WavelogUploadOutcome.Rejected -> result.message
    is WavelogUploadOutcome.Failed -> result.message
    WavelogUploadOutcome.Expired -> "Preview expired — tap upload again"
}

/**
 * Message for the piggybacked Wavelog side when nothing can be submitted —
 * null when there is nothing to say (fully caught up, no held-back records).
 */
fun wavelogIdleSegment(preview: WavelogUploadPreview): String? = when {
    preview.blockedBy != null -> "Wavelog: ${preview.blockedBy.label()}"
    preview.skipped > 0 -> "Wavelog: ${wavelogSkipSummary(preview)}"
    else -> null
}

/**
 * Message for a finished piggybacked Wavelog upload: the server outcome plus
 * the records held back before the POST (e.g. grids outside the station profile).
 */
fun wavelogConfirmedSegment(outcome: WavelogUploadOutcome, preview: WavelogUploadPreview): String {
    val base = wavelogResultMessage(outcome)
    val head = if (outcome is WavelogUploadOutcome.Imported) base else "Wavelog: $base"
    val skip = wavelogSkipSummary(preview)
    return if (skip.isNotBlank()) "$head\n$skip" else head
}

/** Message when the piggybacked upload threw outright — records stay unmarked for retry. */
fun wavelogTransportFailureSegment(): String = "Wavelog: upload failed — it will retry with your next upload"
