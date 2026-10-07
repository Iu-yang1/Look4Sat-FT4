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

import com.rtbishop.look4sat.core.domain.model.AmSatReportSubmission
import com.rtbishop.look4sat.core.domain.model.AmSatReportSubmitResult
import com.rtbishop.look4sat.core.domain.model.SatStatusPage

/** AMSAT satellite status data source */
interface IAmSatRepository {
    /** Cached AMSAT status page for the current foreground session, if any. */
    fun getCachedStatus(): SatStatusPage?

    /** Fetch and parse the AMSAT status page; null on failure. */
    suspend fun fetchStatus(forceRefresh: Boolean = false): SatStatusPage?

    /** Warm the foreground-session cache without forcing a network reload. */
    suspend fun prefetchStatus()

    /** Clear the foreground-session status cache. */
    fun clearStatusCache()

    /** Submit a public AMSAT satellite status report. */
    suspend fun submitReport(submission: AmSatReportSubmission): AmSatReportSubmitResult
}
