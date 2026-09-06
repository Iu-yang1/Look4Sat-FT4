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

data class RadioProfile(val model: String, val isIcom: Boolean, val civAddress: Byte? = null)

fun radioProfile(model: String): RadioProfile = when (model) {
    RadioControlSettings.MODEL_ICOM_IC705 -> RadioProfile(model, true, 0xA4.toByte())
    RadioControlSettings.MODEL_ICOM_IC9700 -> RadioProfile(model, true, 0xA2.toByte())
    else -> RadioProfile(model, false)
}
