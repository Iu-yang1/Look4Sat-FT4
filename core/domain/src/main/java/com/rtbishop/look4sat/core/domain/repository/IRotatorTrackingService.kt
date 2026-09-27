/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.rotator.RotatorPosition
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingState
import kotlinx.coroutines.flow.StateFlow

interface IRotatorTrackingService {
    val state: StateFlow<RotatorTrackingState>

    suspend fun connect()

    suspend fun disconnect(park: Boolean = false)

    fun startTracking(pass: OrbitalPass)

    fun stopTracking(park: Boolean = true)

    suspend fun point(position: RotatorPosition)

    suspend fun park()

    suspend fun emergencyStop()
}
