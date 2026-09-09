/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

enum class YaesuCatVariant {
    FT817,
    FT857
}

enum class IcomCivVariant {
    IC705,
    IC9700,
    IC910
}

data class RadioProfile(
    val model: String,
    val yaesuVariant: YaesuCatVariant? = null,
    val icomVariant: IcomCivVariant? = null,
    val civAddress: Byte? = null,
    val serialStopBits: Int = 1
) {
    val isIcom: Boolean get() = icomVariant != null
    val supportsSatelliteMode: Boolean
        get() = icomVariant == IcomCivVariant.IC9700 || icomVariant == IcomCivVariant.IC910
}

fun radioProfile(model: String): RadioProfile = when (model) {
    RadioControlSettings.MODEL_YAESU_FT857 -> RadioProfile(
        model = model,
        yaesuVariant = YaesuCatVariant.FT857,
        serialStopBits = 2
    )
    RadioControlSettings.MODEL_ICOM_IC705 -> RadioProfile(
        model = model,
        icomVariant = IcomCivVariant.IC705,
        civAddress = IcomCivProtocol.ADDR_IC705
    )
    RadioControlSettings.MODEL_ICOM_IC9700 -> RadioProfile(
        model = model,
        icomVariant = IcomCivVariant.IC9700,
        civAddress = IcomCivProtocol.ADDR_IC9700
    )
    RadioControlSettings.MODEL_ICOM_IC910 -> RadioProfile(
        model = model,
        icomVariant = IcomCivVariant.IC910,
        civAddress = IcomCivProtocol.ADDR_IC910
    )
    else -> RadioProfile(
        model = RadioControlSettings.MODEL_YAESU_FT817,
        yaesuVariant = YaesuCatVariant.FT817,
        serialStopBits = 2
    )
}
