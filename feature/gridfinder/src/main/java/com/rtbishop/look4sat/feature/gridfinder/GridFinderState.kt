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
package com.rtbishop.look4sat.feature.gridfinder

import com.rtbishop.look4sat.core.domain.repository.CompassAccuracy
import com.rtbishop.look4sat.core.domain.repository.LocationFix
import com.rtbishop.look4sat.core.domain.utility.GridFixStatus
import com.rtbishop.look4sat.core.domain.utility.GridGeometry

/**
 * How many consecutive precise fixes are required before a grid-line / corner
 * claim is presented as confirmed. A single fix is not trustworthy enough to
 * tell a walker that they are standing on a 6 m boundary.
 */
const val PRECISE_FIXES_REQUIRED = 3

/** A fix must be at least this good before its grid line/corner claim counts. */
const val VUCC_FIX_ACCURACY_LIMIT_METERS = 6.1f

/** Outcome of pushing one fix through the 20 ft precision gate. */
data class FixGateResult(val streak: Int, val isConfirmed: Boolean)

/**
 * Counts consecutive fixes that are both good to the 20 ft rule and inside a
 * grid-square boundary. Any one bad fix resets the counter, so a claim is only
 * confirmed once the walker has held it for several readings.
 */
fun nextFixGate(previousStreak: Int, fix: LocationFix): FixGateResult {
    val streak = if (fix.isPreciseEnough(VUCC_FIX_ACCURACY_LIMIT_METERS)) {
        (previousStreak + 1).coerceAtMost(PRECISE_FIXES_REQUIRED)
    } else {
        0
    }
    return FixGateResult(streak = streak, isConfirmed = streak >= PRECISE_FIXES_REQUIRED)
}

/**
 * Grid Finder screen state. [status] and [claimableGrids] are always derived
 * from the latest fix's distance to the boundary; [isStandingConfirmed] says
 * whether that reading is backed by [PRECISE_FIXES_REQUIRED] consecutive fixes
 * that are themselves good to the 20 ft rule.
 */
data class GridFinderState(
    val permissionGranted: Boolean = false,
    val providerEnabled: Boolean = true,
    val providerReady: Boolean = true,
    val fix: LocationFix? = null,
    val geometry: GridGeometry? = null,
    val claimableGrids: List<String> = emptyList(),
    val status: GridFixStatus? = null,
    val preciseFixesInARow: Int = 0,
    val isStandingConfirmed: Boolean = false,
    val headingDegrees: Float? = null,
    val compassAccuracy: CompassAccuracy = CompassAccuracy.UNAVAILABLE,
    val isCompassEnabled: Boolean = false,
    val isStationSaved: Boolean = false
) {
    val isWaitingForFix: Boolean get() = fix == null
}

sealed interface GridFinderAction {
    /** Called when the screen appears: starts location and (if enabled) compass updates. */
    data object StartUpdates : GridFinderAction

    /** Called when the screen goes away: releases both listeners. */
    data object StopUpdates : GridFinderAction

    data class LocationPermissionResult(val granted: Boolean) : GridFinderAction

    /** Copies the current fix into the station position that drives pass predictions. */
    data object SetAsStation : GridFinderAction

    data object StationSavedShown : GridFinderAction

    /** Saves the compass trim, shared with the radar page's calibration. */
    data class SetCompassOffset(val offsetDegrees: Float) : GridFinderAction
}
