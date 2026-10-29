// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RadarOrientationState(val angles: Pair<Float,Float> = 0f to 0f, val enabled: Boolean = false)

/** Owns one screen's compass subscription independently of its audio permissions. */
class RadarOrientationSession(
    scope: CoroutineScope,
    private val sensors: ISensorsRepo,
    compassEnabled: Flow<Boolean>,
    offsetDegrees: Flow<Float>,
    station: Flow<GeoPos>
) {
    private val foreground=MutableStateFlow(false)
    private val mutable=MutableStateFlow(RadarOrientationState())
    val state=mutable.asStateFlow()
    private val job=scope.launch {
        combine(foreground,compassEnabled) { active,enabled -> active && enabled }
            .distinctUntilChanged().collectLatest { enabled ->
                if(!enabled) { mutable.update {it.copy(enabled=false)};return@collectLatest }
                try {
                    sensors.enableSensor()
                    val declination=station.map { sensors.getMagDeclination(it) }.distinctUntilChanged()
                    combine(sensors.sensorData,offsetDegrees.distinctUntilChanged(),declination,sensors.compassAccuracy) { angles,offset,magneticCorrection,accuracy ->
                        val azimuth=((angles.first+magneticCorrection+offset)%360f+360f)%360f
                        RadarOrientationState(azimuth to angles.second,accuracy!=CompassAccuracy.UNAVAILABLE)
                    }.collect { mutable.value=it }
                } finally {
                    sensors.disableSensor()
                    mutable.update {it.copy(enabled=false)}
                }
            }
    }
    fun setForeground(active: Boolean) { foreground.value=active }
    fun close() { job.cancel() }
}
