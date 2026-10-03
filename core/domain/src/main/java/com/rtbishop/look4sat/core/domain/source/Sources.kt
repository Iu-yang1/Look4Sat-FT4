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
    // Default TLE sources, aligned with upstream's "most specific to most generic" order:
    // the first source that knows a satellite gets to name it, with the full catalogue
    // (CelesTrak active) as the fallback. Upstream dropped the old CelesTrak category
    // groups (amateur/cubesat/weather/...) — a single `active` group covers them and keeps
    // the request count (and CelesTrak's 2-hourly "already downloaded" 403s) down.
    // The three BA7OPF additions keep their own spots: Kaggle LAPAN-A2 first and the two
    // AutoTLE mirrors next to R4UAB (Chinese amateur catalogues, freshest for CN sats).
    val satelliteDataUrls = mapOf(
        "LAPAN-A2" to "https://www.kaggle.com/api/v1/datasets/download/muazamnugroho/lapan-a2-satellite-two-line-element-tle-dataset/LAPAN-A2_TLE_latest.txt",
        "ARISS" to "https://live.ariss.org/iss.txt",
        "R4UAB" to "https://r4uab.ru/satonline.txt",
        "BI4PYM AutoTLE (GitHub)" to "https://raw.githubusercontent.com/BI4PYM/AutoTLE/refs/heads/master/AutoTLE.txt",
        "BI4PYM AutoTLE (Mirror)" to "https://autotle.bi4pym.cn/AutoTLE.txt",
        "Classified" to "https://www.mmccants.org/tles/classfd.zip",
        "Amsat" to "https://amsat.org/tle/current/nasabare.txt",
        "All" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv",
        "SatNOGS" to "https://db.satnogs.org/api/tle/?format=3le",
        "Other" to "" // key for sats filter
    )
    // Transceiver sources. Both publish the same SatNOGS-uuid records (measured 2026-10-04:
    // 2760 shared uuids), and the sync keeps the FIRST source's copy for a shared uuid
    // (distinctBy { uuid }). SatNOGS is kept first on purpose: its copies carry the
    // up-to-date descriptions (e.g. AO-91 "no CTCSS any longer") and it is the only source
    // with the newest satellites' transmitters (T-18 objects etc.), while R4UAB mainly adds
    // long-dead entries plus a handful of live extras. Upstream lists R4UAB first; this fork
    // intentionally differs.
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

    /** Key of the CelesTrak "active" group source in [satelliteDataUrls] — the whole
     *  catalogue. Selecting it means "all satellites", so when its persisted id list
     *  is empty the picker must fall back to no filtering instead of showing an empty
     *  list. The list can legitimately be empty: CelesTrak answers 403 ("GP data has
     *  not updated since your last successful download", groups update every 2 hours)
     *  when a group is re-requested, and the sync then persists nothing for that type. */
    val allSourceType = "All"
}
