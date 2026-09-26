/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.repository.LoTWZonePair
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses the real bundled ARRL config.tq6 to verify region-field and zonemap extraction. */
class LoTWConfigTest {

    private val config = LoTWConfig(File("src/main/assets/lotw/config.tq6").inputStream())

    @Test
    fun `china exposes province field with all 31 options and zonemaps`() {
        val meta = config.stationMeta(318)
        assertEquals("CN_PROVINCE", meta.regionField?.id)
        assertEquals("Province", meta.regionField?.label)
        assertEquals(31, meta.regionField?.options?.size)
        val gd = meta.regionField?.options?.first { it.code == "GD" }
        assertEquals("Guangdong", gd?.name)
        assertEquals(listOf(LoTWZonePair(44, 24)), gd?.zones)
        val gx = meta.regionField?.options?.first { it.code == "GX" }
        assertEquals(listOf(LoTWZonePair(43, 24), LoTWZonePair(44, 24)), gx?.zones)
        // China spans 8 zone pairs — the UI must NOT prefill from the national map.
        assertTrue(meta.countryZones.size > 1)
    }

    @Test
    fun `usa exposes state field with 49 continental options`() {
        val meta = config.stationMeta(291)
        assertEquals("US_STATE", meta.regionField?.id)
        assertEquals("State", meta.regionField?.label)
        // AK and HI are separate DXCC entities (6 and 110) with their own 1-option blocks.
        assertEquals(49, meta.regionField?.options?.size)
        assertTrue(meta.regionField!!.options.none { it.code == "AK" || it.code == "HI" })
        assertTrue(meta.countryZones.size > 1)
    }

    @Test
    fun `alaska and hawaii each expose their own single-state block`() {
        val ak = config.stationMeta(6)
        assertEquals(listOf("AK"), ak.regionField?.options?.map { it.code })
        val hi = config.stationMeta(110)
        assertEquals(listOf("HI"), hi.regionField?.options?.map { it.code })
    }

    @Test
    fun `germany has no region field and one national zone pair`() {
        val meta = config.stationMeta(230)
        assertNull(meta.regionField)
        assertEquals(listOf(LoTWZonePair(28, 14)), meta.countryZones)
    }

    @Test
    fun `india has no region field and one national zone pair`() {
        val meta = config.stationMeta(324)
        assertNull(meta.regionField)
        assertEquals(listOf(LoTWZonePair(41, 22)), meta.countryZones)
    }

    @Test
    fun `unknown entity yields empty meta`() {
        val meta = config.stationMeta(999999)
        assertNull(meta.regionField)
        assertTrue(meta.countryZones.isEmpty())
    }
}
