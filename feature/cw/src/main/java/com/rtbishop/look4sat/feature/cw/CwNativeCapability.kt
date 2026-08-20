/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.cw

import android.os.Build
import android.os.Process

/** Morse Expert 1.15 原生解码器当前随应用提供 ARM32 与 ARM64 版本。 */
object CwNativeCapability {
    val isAvailable: Boolean
        get() = when {
            Process.is64Bit() -> Build.SUPPORTED_ABIS.firstOrNull() == ABI_ARM64_V8A
            else -> Build.SUPPORTED_ABIS.firstOrNull() == ABI_ARMEABI_V7A
        }

    private const val ABI_ARM64_V8A = "arm64-v8a"
    private const val ABI_ARMEABI_V7A = "armeabi-v7a"
}
