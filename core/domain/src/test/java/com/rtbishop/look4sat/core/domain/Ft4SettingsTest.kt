/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.model.Ft4Settings
import org.junit.Assert.assertTrue
import org.junit.Test

class Ft4SettingsTest {
    @Test
    fun decodingIsEnabledForNewInstallations() {
        assertTrue(Ft4Settings().decodeEnabled)
    }
}
