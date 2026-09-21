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

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlinx.coroutines.flow.StateFlow

/** 指南针校准精度等级, 由磁场传感器 accuracy 事件映射而来. */
enum class CompassAccuracy {
    UNAVAILABLE,
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH
}

interface ISensorsRepo {
    val sensorData: StateFlow<Pair<Float, Float>>
    /** 指南针当前校准精度 (磁场传感器 accuracy), 用于校准对话框进度. */
    val compassAccuracy: StateFlow<CompassAccuracy>
    fun getMagDeclination(geoPos: GeoPos, time: Long = System.currentTimeMillis()): Float
    fun enableSensor()
    fun disableSensor()
}
