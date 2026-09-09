/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassesSettingsPersistenceTest {
    @Test
    fun missingModePreferenceDoesNotEnableFiltering() {
        assertTrue(parseSelectedModes(null).isEmpty())
    }

    @Test
    fun savingAndReloadingClearedModeFilterKeepsItDisabled() {
        val savedModes = emptyList<String>().joinToString(",")

        assertTrue(parseSelectedModes(savedModes).isEmpty())
    }

    @Test
    fun legacyBlankEntriesDoNotEnableAnInvisibleFilter() {
        assertTrue(parseSelectedModes(" , , ").isEmpty())
    }

    @Test
    fun realModeFiltersSurviveReloadAlongsideEmptyEntries() {
        assertEquals(listOf("FM", "USB"), parseSelectedModes("USB, ,FM,,USB"))
    }
}
