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
import com.rtbishop.look4sat.core.domain.utility.downlinkHz
import com.rtbishop.look4sat.core.domain.utility.uplinkHz
import com.rtbishop.look4sat.core.domain.utility.voiceRepeater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures are the real SatNOGS transceiver rows of the three hardcoded AMSAT Live FM
 *  satellites (fetched 2026-09-27), so the selection rules are tested against live data. */
class VoiceRepeaterTest {

    private fun radio(
        uuid: String,
        info: String,
        up: Long? = null,
        upHigh: Long? = null,
        down: Long? = null,
        downHigh: Long? = null,
        upMode: String? = null,
        downMode: String? = null,
        alive: Boolean = true,
        service: String? = null,
        catnum: Int = 12345
    ) = SatRadio(
        uuid = uuid, info = info, isAlive = alive,
        downlinkLow = down, downlinkHigh = downHigh, downlinkMode = downMode,
        uplinkLow = up, uplinkHigh = upHigh, uplinkMode = upMode,
        isInverted = false, catnum = catnum, service = service
    )

    // SO-50 (27607)
    private val so50Tlm = radio("so50-tlm", "Mode V TLM", down = 149_025_000L, downMode = "FM")
    private val so50Voice = radio(
        "so50-voice", "Mode V/U FM Voice CTCSS 67.0 Hz",
        up = 145_850_000L, down = 436_795_000L, upMode = "FM", downMode = "FM", catnum = 27607
    )

    // ISS (25544): a voice repeater plus two crew V/V channels and an APRS transceiver
    private val issCrew2 = radio(
        "iss-crew2", "Mode V/V FM (crew R2+3)",
        up = 144_490_000L, down = 145_800_000L, upMode = "FM", downMode = "FM", catnum = 25544
    )
    private val issCrew1 = radio(
        "iss-crew1", "Mode V/V FM (crew R1)",
        up = 145_200_000L, down = 145_800_000L, upMode = "FM", downMode = "FM", catnum = 25544
    )
    private val issVoice = radio(
        "iss-voice", "Mode V/U FM - Voice Repeater CTCSS 67.0 Hz",
        up = 145_990_000L, down = 437_800_000L, upMode = "FM", downMode = "FM",
        service = "Amateur", catnum = 25544
    )
    private val issAprs = radio(
        "iss-aprs", "Mode V APRS",
        up = 145_825_000L, down = 145_825_000L, upMode = "AFSK", downMode = "AFSK", catnum = 25544
    )

    // AO-123 / ASRTU-1 (61781)
    private val ao123Voice = radio(
        "ao123-voice", "Mode V/U - FM Transceiver",
        up = 145_850_000L, down = 435_400_000L, upMode = "FM", downMode = "FM",
        service = "Amateur", catnum = 61781
    )

    @Test
    fun voiceRepeater_picksSo50VoicePair() {
        val repeater = listOf(so50Tlm, so50Voice).voiceRepeater()
        assertEquals("so50-voice", repeater?.uuid)
        assertEquals(145_850_000L, repeater?.uplinkHz())
        assertEquals(436_795_000L, repeater?.downlinkHz())
    }

    @Test
    fun voiceRepeater_prefersVoiceChannelOverCrewChannels() {
        val transmitterList = listOf(issCrew2, issAprs, issVoice, issCrew1)
        // Order must not matter - ranking decides.
        assertEquals("iss-voice", transmitterList.voiceRepeater()?.uuid)
        assertEquals("iss-voice", transmitterList.reversed().voiceRepeater()?.uuid)
    }

    @Test
    fun voiceRepeater_picksAo123Transceiver() {
        val repeater = listOf(ao123Voice).voiceRepeater()
        assertEquals(145_850_000L, repeater?.uplinkHz())
        assertEquals(435_400_000L, repeater?.downlinkHz())
    }

    @Test
    fun voiceRepeater_ignoresBeaconsWithoutUplink() {
        assertNull(listOf(so50Tlm).voiceRepeater())
    }

    @Test
    fun voiceRepeater_ignoresDeadEntries() {
        val dead = so50Voice.copy(uuid = "dead", isAlive = false)
        assertNull(listOf(dead).voiceRepeater())
    }

    @Test
    fun voiceRepeater_ignoresLinearTransponders() {
        val linear = radio(
            "lin", "Linear Transponder",
            up = 145_900_000L, upHigh = 145_950_000L, down = 435_600_000L, downHigh = 435_650_000L,
            upMode = "LSB", downMode = "USB"
        )
        assertNull(listOf(linear).voiceRepeater())
    }

    @Test
    fun uplinkHz_usesPassbandMiddleWhenPresent() {
        val wide = so50Voice.copy(uuid = "wide", uplinkLow = 145_840_000L, uplinkHigh = 145_860_000L)
        assertEquals(145_850_000L, wide.uplinkHz())
        assertEquals(145_850_000L, so50Voice.uplinkHz())
    }

    @Test
    fun downlinkHz_isNullWhenNoDownlink() {
        assertNull(so50Voice.copy(downlinkLow = null).downlinkHz())
    }
}
