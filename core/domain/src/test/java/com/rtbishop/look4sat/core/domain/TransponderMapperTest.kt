/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransponderMapperTest {

    private fun makeRadio(
        uplinkLow: Long? = 435000000L,
        uplinkHigh: Long? = 435100000L,
        downlinkLow: Long? = 145800000L,
        downlinkHigh: Long? = 145900000L,
        isInverted: Boolean = true
    ) = SatRadio(
        uuid = "test",
        info = "Test",
        isAlive = true,
        downlinkLow = downlinkLow,
        downlinkHigh = downlinkHigh,
        downlinkMode = "USB",
        uplinkLow = uplinkLow,
        uplinkHigh = uplinkHigh,
        uplinkMode = "USB",
        isInverted = isInverted,
        catnum = 0
    )

    @Test
    fun invertedTransponder_mapsFrequencyCorrectly() {
        val radio = makeRadio(isInverted = true)
        // TX at uplinkLow → RX should be downlinkHigh
        assertEquals(145900000L, TransponderMapper.mapUplinkToDownlink(435000000L, radio))
        // TX at uplinkHigh → RX should be downlinkLow
        assertEquals(145800000L, TransponderMapper.mapUplinkToDownlink(435100000L, radio))
        // TX at center → RX at center
        assertEquals(145850000L, TransponderMapper.mapUplinkToDownlink(435050000L, radio))
    }

    @Test
    fun nonInvertedTransponder_mapsFrequencyCorrectly() {
        val radio = makeRadio(isInverted = false)
        // TX at uplinkLow → RX should be downlinkLow
        assertEquals(145800000L, TransponderMapper.mapUplinkToDownlink(435000000L, radio))
        // TX at uplinkHigh → RX should be downlinkHigh
        assertEquals(145900000L, TransponderMapper.mapUplinkToDownlink(435100000L, radio))
    }

    @Test
    fun nullFrequencies_returnsNull() {
        assertNull(TransponderMapper.mapUplinkToDownlink(435000000L, makeRadio(uplinkLow = null)))
        assertNull(TransponderMapper.mapUplinkToDownlink(435000000L, makeRadio(downlinkLow = null)))
    }

    @Test
    fun singleFrequencyTransponder_returnsDownlinkLow() {
        // FM repeater style: no passband, just single frequencies
        val radio = makeRadio(
            uplinkLow = 145990000L, uplinkHigh = null,
            downlinkLow = 436300000L, downlinkHigh = null,
            isInverted = false
        )
        assertEquals(436300000L, TransponderMapper.mapUplinkToDownlink(145990000L, radio))
    }

    @Test
    fun equalLowHighTransponder_returnsDownlinkLow() {
        val radio = makeRadio(
            uplinkLow = 145990000L, uplinkHigh = 145990000L,
            downlinkLow = 436300000L, downlinkHigh = 436300000L,
            isInverted = false
        )
        assertEquals(436300000L, TransponderMapper.mapUplinkToDownlink(145990000L, radio))
    }

    @Test
    fun invertedMode_swapsUsbLsb() {
        assertEquals("LSB", TransponderMapper.mapUplinkModeToDownlinkMode("USB", true))
        assertEquals("USB", TransponderMapper.mapUplinkModeToDownlinkMode("LSB", true))
        assertEquals("FM", TransponderMapper.mapUplinkModeToDownlinkMode("FM", true))
    }

    @Test
    fun nonInvertedMode_keepsSame() {
        assertEquals("USB", TransponderMapper.mapUplinkModeToDownlinkMode("USB", false))
        assertEquals("LSB", TransponderMapper.mapUplinkModeToDownlinkMode("LSB", false))
        assertEquals("FM", TransponderMapper.mapUplinkModeToDownlinkMode("FM", false))
    }
}
