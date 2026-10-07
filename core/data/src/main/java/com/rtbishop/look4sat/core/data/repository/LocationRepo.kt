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

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import com.rtbishop.look4sat.core.domain.repository.ILocationRepo
import com.rtbishop.look4sat.core.domain.repository.LocationFix
import com.rtbishop.look4sat.core.domain.repository.LocationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val UPDATE_INTERVAL_MS = 1_000L
private const val UPDATE_MIN_DISTANCE_M = 0f

/**
 * Live-location adapter for the field tools. Deliberately independent of
 * [SettingsRepo]'s station position: a walk around a grid corner must not
 * rewrite the station that every pass prediction is built on.
 *
 * Unlike [SettingsRepo.setStationPosition], this class always calls
 * [LocationManager.removeUpdates] in [stopUpdates], so the listener is only
 * alive while a screen is actually collecting fixes.
 */
class LocationRepo(
    private val locationManager: LocationManager,
    private val permissionGranted: () -> Boolean
) : ILocationRepo, LocationListener {

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<LocationState> = _state.asStateFlow()

    private var isRegistered = false

    override fun hasPermission(): Boolean = permissionGranted()

    override fun startUpdates() {
        if (isRegistered) return
        val request = describeProvider() ?: return
        if (!request.permissionGranted || !request.providerEnabled) return
        try {
            // A last-known fix is better than an empty screen while GPS warms up.
            locationManager.getLastKnownLocation(request.provider)?.let(::publish)
            locationManager.requestLocationUpdates(
                request.provider, UPDATE_INTERVAL_MS, UPDATE_MIN_DISTANCE_M, this
            )
            isRegistered = true
        } catch (exception: SecurityException) {
            println("LocationRepo: no permission - $exception")
        } catch (exception: IllegalArgumentException) {
            println("LocationRepo: provider unavailable - $exception")
        }
    }

    override fun stopUpdates() {
        if (!isRegistered) return
        isRegistered = false
        try {
            locationManager.removeUpdates(this)
        } catch (exception: SecurityException) {
            println("LocationRepo: removing updates without permission - $exception")
        }
    }

    override fun onLocationChanged(location: Location) = publish(location)

    override fun onProviderDisabled(provider: String) = refreshWaitingState()

    override fun onProviderEnabled(provider: String) = refreshWaitingState()

    private fun publish(location: Location) {
        _state.value = LocationState.Fix(
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() } ?: 0.0,
                // Providers that cannot report accuracy use -1; treat that as
                // useless rather than perfect.
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() } ?: Float.MAX_VALUE,
                epochMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        )
    }

    private fun initialState(): LocationState {
        val request = describeProvider()
        return LocationState.Waiting(
            permissionGranted = request?.permissionGranted ?: false,
            providerEnabled = request?.providerEnabled ?: false
        )
    }

    private fun refreshWaitingState() {
        if (_state.value is LocationState.Fix) return
        _state.value = initialState()
    }

    private fun describeProvider(): ProviderRequest? {
        if (!permissionGranted()) {
            return ProviderRequest(LocationManager.GPS_PROVIDER, false, false)
        }
        val enabled = LocationManagerCompat.isLocationEnabled(locationManager)
        val provider = when {
            locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return null
        return ProviderRequest(provider, true, enabled)
    }

    private data class ProviderRequest(
        val provider: String,
        val permissionGranted: Boolean,
        val providerEnabled: Boolean
    )
}
