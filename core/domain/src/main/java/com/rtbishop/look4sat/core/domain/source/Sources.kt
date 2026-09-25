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
package com.rtbishop.look4sat.core.domain.source

object Sources {
    val satelliteDataUrls = mapOf(
        "LAPAN-A2" to "https://www.kaggle.com/api/v1/datasets/download/muazamnugroho/lapan-a2-satellite-two-line-element-tle-dataset/LAPAN-A2_TLE_latest.txt",
        "R4UAB" to "https://r4uab.ru/satonline.txt",
        "BI4PYM AutoTLE (GitHub)" to "https://raw.githubusercontent.com/BI4PYM/AutoTLE/refs/heads/master/AutoTLE.txt",
        "BI4PYM AutoTLE (Mirror)" to "https://autotle.bi4pym.cn/AutoTLE.txt",
        "All" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv",
        "SatNOGS" to "https://db.satnogs.org/api/tle/?format=3le",
        "Amateur" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=csv",
        "Brightest" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=visual&FORMAT=csv",
        "Cubesat" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=cubesat&FORMAT=csv",
        "Education" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=education&FORMAT=csv",
        "Engineer" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=engineering&FORMAT=csv",
        "Geostationary" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=geo&FORMAT=csv",
        "Globalstar" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=globalstar&FORMAT=csv",
        "GNSS" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=gnss&FORMAT=csv",
        "Intelsat" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=intelsat&FORMAT=csv",
        "Iridium" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=iridium-NEXT&FORMAT=csv",
        "Military" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=military&FORMAT=csv",
        "New" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=last-30-days&FORMAT=csv",
        "OneWeb" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=oneweb&FORMAT=csv",
        "Orbcomm" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=orbcomm&FORMAT=csv",
        "Resource" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=resource&FORMAT=csv",
        "CelesTrak SatNOGS" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=satnogs&FORMAT=csv",
        "Science" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=science&FORMAT=csv",
        "Spire" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=spire&FORMAT=csv",
        "Starlink" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=starlink&FORMAT=csv",
        "Swarm" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=swarm&FORMAT=csv",
        "Weather" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=weather&FORMAT=csv",
        "X-Comm" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=x-comm&FORMAT=csv",
        "Amsat" to "https://amsat.org/tle/current/nasabare.txt",
        "Classified" to "https://www.mmccants.org/tles/classfd.zip",
        "McCants" to "https://www.mmccants.org/tles/inttles.zip",
        "ARISS" to "https://live.ariss.org/iss.txt",
        "Other" to "" // key for sats filter
    )
    val transceiversDataUrls = mapOf(
        "SatNOGS" to "https://db.satnogs.org/api/transmitters/?format=json&status=active",
        "R4UAB" to "https://r4uab.ru/transmitters.json"
    )
    /**
     * Hardcoded AMSAT Live FM satellites (NORAD catnums):
     * SO-50 (27607), ISS ZARYA (25544), AO-123 ASRTU-1 (61781).
     * Replaces the AMSAT live-page fetch: stable on any network, no
     * sync-time dependency, no stale-list or timeout failure modes.
     */
    val amSatFmCatnums = setOf(27607, 25544, 61781)

    /**
     * Hardcoded AMSAT Live linear (SSB/CW) satellites:
     * RS-44 (44909), FO-29 (24278), AO-7 (7530),
     * AO-73 FUNcube-1 (39444), JO-97 JY1SAT (43803).
     */
    val amSatLinearCatnums = setOf(44909, 24278, 7530, 39444, 43803)

    /** Virtual satellite-selection types: transponder/activity filters shown
     *  at the top of the type picker. They resolve to live lists (hardcoded
     *  FM/Linear catnum sets or mode=SSTV radios) instead of persisted
     *  per-type IDs, and are mutually exclusive with the regular TLE-source
     *  types in the picker. */
    val virtualTypeNames = listOf("AMSAT Live FM", "AMSAT Live Linear", "Live SSTV")
}
