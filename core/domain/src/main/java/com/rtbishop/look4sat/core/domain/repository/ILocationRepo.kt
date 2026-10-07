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

import kotlinx.coroutines.flow.StateFlow

/**
 * A single position fix from the device's location provider, with the
 * horizontal accuracy reported by the platform.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
    val accuracyMeters: Float,
    val epochMs: Long
) {
    /** ARRL VUCC work needs a fix good to the 20 ft (6.1 m) rule. */
    fun isPreciseEnough(limitMeters: Float): Boolean = accuracyMeters in 0f..limitMeters
}

/** What the live-location source is currently able to give. */
sealed interface LocationState {
    /** No fix yet - either permission is missing or the provider is warming up. */
    data class Waiting(val permissionGranted: Boolean, val providerEnabled: Boolean) : LocationState

    data class Fix(val location: LocationFix) : LocationState
}

/**
 * Live device position for field tools (currently the Grid Finder). Kept
 * separate from [ISettingsRepo.stationPosition] on purpose: collecting fixes
 * here must never move the user's station, which is what drives every pass
 * prediction in the app.
 *
 * Callers register with [startUpdates] while their screen is alive and MUST
 * call [stopUpdates] when it goes away.
 */
interface ILocationRepo {
    val state: StateFlow<LocationState>

    /** Whether the app currently holds the fine-location permission. */
    fun hasPermission(): Boolean

    /** Begins delivering fixes; no-op when permission is missing. */
    fun startUpdates()

    /** Stops delivering fixes and releases the platform listener. */
    fun stopUpdates()
}
