/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.time

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.rtbishop.look4sat.core.domain.time.GnssDisciplineStatus
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.domain.time.ITimeSynchronizationService
import com.rtbishop.look4sat.core.domain.time.TimeSynchronizationState
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidTimeSynchronizationService(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: IDisciplinedClock,
    private val ntpDiscipline: MultiSourceNtpDiscipline = MultiSourceNtpDiscipline()
) : ITimeSynchronizationService {
    private val applicationContext = context.applicationContext
    private val connectivityManager =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val gnss = AndroidGnssTimeDiscipline(applicationContext, clock::submitSample)
    private val synchronizationMutex = Mutex()
    private val _state = MutableStateFlow(TimeSynchronizationState())
    private var periodicJob: Job? = null
    private var closed = false

    override val state: StateFlow<TimeSynchronizationState> = _state.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (_state.value.ntpEnabled) scope.launch { synchronizeNow() }
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun setNtpEnabled(enabled: Boolean) {
        if (closed || _state.value.ntpEnabled == enabled) return
        _state.update { it.copy(ntpEnabled = enabled, lastError = "") }
        periodicJob?.cancel()
        periodicJob = null
        if (enabled) {
            scope.launch { synchronizeNow() }
            periodicJob = scope.launch {
                while (isActive) {
                    delay(PERIODIC_SYNC_MILLIS)
                    synchronizeNow()
                }
            }
        }
    }

    override fun setGnssEnabled(enabled: Boolean) {
        if (closed) return
        if (!enabled) {
            gnss.stop()
            _state.update {
                it.copy(gnssEnabled = false, gnssStatus = GnssDisciplineStatus.DISABLED)
            }
            return
        }
        val result = gnss.start()
        val status = when (result) {
            AndroidGnssTimeDiscipline.StartResult.STARTED,
            AndroidGnssTimeDiscipline.StartResult.ALREADY_STARTED -> GnssDisciplineStatus.STARTED
            AndroidGnssTimeDiscipline.StartResult.PERMISSION_REQUIRED ->
                GnssDisciplineStatus.PERMISSION_REQUIRED
            AndroidGnssTimeDiscipline.StartResult.PROVIDER_DISABLED ->
                GnssDisciplineStatus.PROVIDER_DISABLED
            AndroidGnssTimeDiscipline.StartResult.REGISTRATION_FAILED ->
                GnssDisciplineStatus.REGISTRATION_FAILED
        }
        _state.update {
            it.copy(
                gnssEnabled = status == GnssDisciplineStatus.STARTED,
                gnssStatus = status,
                lastError = if (status == GnssDisciplineStatus.STARTED) "" else status.name
            )
        }
    }

    override suspend fun synchronizeNow(): Boolean {
        if (closed) return false
        return synchronizationMutex.withLock {
            _state.update { it.copy(synchronizing = true, lastError = "") }
            try {
                val measurement = withContext(Dispatchers.IO) { ntpDiscipline.synchronize() }
                val accepted = clock.submitSample(measurement.sample)
                _state.update {
                    it.copy(
                        synchronizing = false,
                        lastSuccessfulSyncMillis = if (accepted) clock.nowMillis() else it.lastSuccessfulSyncMillis,
                        lastError = if (accepted) "" else clock.snapshot().lastRejectedReason
                    )
                }
                accepted
            } catch (error: IOException) {
                _state.update {
                    it.copy(synchronizing = false, lastError = error.message ?: "NTP 同步失败")
                }
                false
            } catch (error: RuntimeException) {
                _state.update {
                    it.copy(synchronizing = false, lastError = error.message ?: "NTP 同步失败")
                }
                false
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        periodicJob?.cancel()
        periodicJob = null
        gnss.stop()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    companion object {
        private const val PERIODIC_SYNC_MILLIS = 30 * 60 * 1_000L
    }
}
