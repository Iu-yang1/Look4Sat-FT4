/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.time

import kotlinx.coroutines.flow.StateFlow

enum class GnssDisciplineStatus {
    DISABLED,
    STARTED,
    PERMISSION_REQUIRED,
    PROVIDER_DISABLED,
    REGISTRATION_FAILED
}

data class TimeSynchronizationState(
    val ntpEnabled: Boolean = false,
    val gnssEnabled: Boolean = false,
    val synchronizing: Boolean = false,
    val gnssStatus: GnssDisciplineStatus = GnssDisciplineStatus.DISABLED,
    val lastSuccessfulSyncMillis: Long? = null,
    val lastError: String = ""
)

interface ITimeSynchronizationService {
    val state: StateFlow<TimeSynchronizationState>
    fun setNtpEnabled(enabled: Boolean)
    fun setGnssEnabled(enabled: Boolean)
    suspend fun synchronizeNow(): Boolean
    fun close()
}
