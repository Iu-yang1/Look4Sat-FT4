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

/** Fetches confirmed gridsquares directly from ARRL LoTW. */
interface ILoTWRepository {

    /**
     * Fetch all confirmed (QSL_RCVD=Y) gridsquares for the given LoTW account.
     * Returns the 4-char grid set with an explicit failure cause on error.
     */
    suspend fun fetchConfirmedGrids(callsign: String, password: String): LoTWResult

    /**
     * Same report, but keeps the per-QSO detail of every confirmed satellite
     * QSO (call / time / satellite / mode / bands), grouped by worked 4-char
     * gridsquare. Failure cause is kept explicit.
     *
     * @param since   QSL-since date ("yyyyMMdd" or "yyyy-MM-dd"); use an early
     *                date for a full report, or the last sync date for an
     *                incremental pull. Empty falls back to a full report.
     * @param onProgress  streamed download progress (phase + bytes + estimate),
     *                invoked from the IO dispatcher; may be called on every
     *                read chunk.
     */
    suspend fun fetchConfirmedGridQsos(
        callsign: String,
        password: String,
        since: String = "",
        onProgress: (LoTWProgress) -> Unit = {}
    ): LoTWResult
}

/** Streamed progress of a LoTW report download. */
data class LoTWProgress(
    val phase: LoTWPhase,
    /** Bytes read so far (uncompressed body). */
    val bytesRead: Long = 0,
    /** Estimated total body bytes, derived from <APP_LoTW_NUMREC>; 0 before known. */
    val expectedBytes: Long = 0,
    /** Smoothed download speed in bytes/second; 0 while unknown. */
    val speedBps: Long = 0,
    /** Total QSL records reported in the header (<APP_LoTW_NUMREC>); 0 before known. */
    val qsoCount: Long = 0
) {
    /** Fraction 0..1 of the estimated body already read; 0 while unknown. */
    val fraction: Float
        get() = if (expectedBytes > 0 && bytesRead > 0)
            (bytesRead.toFloat() / expectedBytes).coerceIn(0f, 1f) else 0f

    /** Estimated seconds remaining; 0 while size or speed is unknown. */
    val remainingSeconds: Long
        get() = if (expectedBytes > 0 && speedBps > 0)
            ((expectedBytes - bytesRead) / speedBps).coerceAtLeast(0) else 0L
}

enum class LoTWPhase { Connecting, Downloading }
