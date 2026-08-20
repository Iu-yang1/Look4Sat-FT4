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

import android.os.SystemClock
import com.rtbishop.look4sat.core.domain.time.MonotonicTimeSource

object AndroidMonotonicTimeSource : MonotonicTimeSource {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
    override fun wallClockMillis(): Long = System.currentTimeMillis()
}
