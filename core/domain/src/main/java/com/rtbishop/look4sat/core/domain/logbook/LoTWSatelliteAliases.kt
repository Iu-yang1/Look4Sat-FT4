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
package com.rtbishop.look4sat.core.domain.logbook

import java.util.Locale

/**
 * Satellite names of the ARRL LoTW catalogue and the tracker names that map onto them.
 *
 * ARRL's config.tq6 knows every satellite by exactly ONE name ("SO-50", "ARISS", "PO-101",
 * "BO-102"), while the TLE sources the tracker uses carry their own catalogue names: SatNOGS
 * lists SO-50 as "SAUDISAT 1C", the ISS as "ISS (ZARYA)", PO-101 as "DIWATA 2B" and AO-123 as
 * "OBJECT AY". Without this mapping a signed QSO on those satellites is rejected as an unknown
 * satellite.
 *
 * Keys are tracker names normalised by [normalize] (upper case, A-Z0-9 only); values are the
 * exact config.tq6 satellite names. Generated 2026-09-27 from:
 *  - SatNOGS DB satellites (`name` + `names` alias list) joined to config.tq6 by exact alias,
 *  - live SatNOGS 3LE names and Celestrak amateur names joined to the same NORAD id,
 *  - designator tokens of the config.tq6 satellite descriptions (e.g. CAS-7B -> BO-102),
 *  - the ISS family, which SatNOGS does not list under its ARRL name "ARISS".
 * Added 2026-10-08: config.tq6 v11.35 lists QMR-KWT 2 as "RS95S"; SatNOGS DB carries it as
 * "QMR-KWT 2" (alias "RS95S") and other sources spell it "RS-95S", hence the identity entry.
 * Placeholder names ("OBJECT AY" and friends) are deliberately NOT mapped: they are recycled
 * between objects and would sign the wrong satellite. LoTWConfigTest asserts that every target
 * here is a real config.tq6 name.
 */
object LoTWSatelliteAliases {

    val table: Map<String, String> = mapOf(
        "AMSATOSCAR7" to "AO-7", "AO123" to "AO-123", "AO16" to "AO-16",
        "AO27" to "AO-27", "AO7" to "AO-7", "AO73" to "AO-73",
        "AO85" to "AO-85", "AO91" to "AO-91", "AO92" to "AO-92",
        "ARISSAT1" to "KEDR", "ASRTU1" to "AO-123", "ASRTUFRIENDSHIP" to "AO-123",
        "BJ2CR" to "AO-123", "BREEZEKMRB" to "RS-44", "BRICSAT2" to "NO-103",
        "BRICSATP" to "NO-83", "BY701" to "BY70-1", "CAMSAT" to "FO-118",
        "CAS10" to "HO-119", "CAS2F" to "XW-2F", "CAS2T" to "CAS-2T",
        "CAS3A" to "XW-2A", "CAS3B" to "XW-2B", "CAS3C" to "XW-2C",
        "CAS3CBJ1SD" to "XW-2C", "CAS3D" to "XW-2D", "CAS3E" to "XW-2E",
        "CAS3F" to "XW-2F", "CAS4A" to "CAS-4A", "CAS4B" to "CAS-4B",
        "CAS5A" to "FO-118", "CAS6" to "TO-108", "CAS7B" to "BO-102",
        "CAS9" to "HO-113", "CZ11RB" to "CAS-2T", "DELFIC3" to "DO-64",
        "DIWATA2" to "PO-101", "DIWATA2B" to "PO-101", "DO64" to "DO-64",
        "DOSAAF85" to "RS-44", "DRUZHBAATURK" to "AO-123", "EO79" to "EO-79",
        "EO88" to "EO-88", "ESHAIL2" to "QO-100", "EUROPEANOSCAR79" to "EO-79",
        "EYESAT1" to "AO-27", "EYESATA" to "AO-27", "FENGTAIOSCAR118" to "FO-118",
        "FO118" to "FO-118", "FO29" to "FO-29", "FOX1" to "AO-85",
        "FOX1A" to "AO-85", "FOX1B" to "AO-91", "FOX1D" to "AO-92",
        "FRESCO" to "LO-87", "FUNCUBE1" to "AO-73", "FUNCUBE2" to "UKUBE1",
        "FUNCUBE3" to "EO-79", "FUNCUBE5" to "EO-88", "GREENCUBE" to "IO-117",
        "HADESD" to "SO-121", "HADESICM" to "SO-125", "HADESR" to "SO-124",
        "HAIL2" to "QO-100", "HAMSAT" to "VO-52", "HO113" to "HO-113",
        "HO119" to "HO-119", "HOPE4" to "HO-119", "HYDRA1" to "SO-121",
        "INSPIRESAT7" to "INSPR7", "IO117" to "IO-117", "IO86" to "IO-86",
        "ISS" to "ARISS", "ISSZARYA" to "ARISS", "JAS2" to "FO-29",
        "JINNIUZUO1" to "TAURUS", "JO97" to "JO-97", "JORDANOSCAR97" to "JO-97",
        "JY1SAT" to "JO-97", "LAPANA2" to "IO-86", "LILACSAT1" to "LO-90",
        "LILACSAT2" to "CAS-3H", "LITUANICASAT1" to "LO-78", "LO19" to "LO-19",
        "LO87" to "LO-87", "LUSAT" to "LO-19", "LUSEX" to "LO-87",
        "MAYA3" to "MAYA-3", "MAYA4" to "MAYA-4", "MESAT1" to "MO-122",
        "MESAT1OSCAR" to "MO-122", "MESAT1OSCAR122" to "MO-122", "MIRSAT1" to "MO-112",
        "MO122" to "MO-122", "MTCUBE2" to "IO-117", "NA1SS" to "ARISS",
        "NAYIF1" to "EO-88", "NEWSAT1" to "LO-87", "NO103" to "NO-103",
        "NO104" to "NO-104", "NO44" to "NO-44", "NO83" to "NO-83",
        "NO84" to "NO-84", "NUSAT1" to "LO-87", "ORARI" to "IO-86",
        "OSCAR16PACSAT" to "AO-16", "OSCAR19LUSAT" to "LO-19", "OSCAR64" to "DO-64",
        "OSCAR7" to "AO-7", "PACSAT" to "AO-16", "PARKINSONSAT" to "NO-84",
        "PCSAT" to "NO-44", "PHILLIPINESOSCAR101" to "PO-101", "PO101" to "PO-101",
        "PSAT" to "NO-84", "PSAT2" to "NO-104", "QB50P1" to "EO-79", "QMRKWT2" to "RS95S",
        "QO100" to "QO-100", "RADFXSAT" to "AO-91", "RADIOROSTO" to "RS-15",
        "RS0ISS" to "ARISS", "RS14" to "AO-21", "RS15" to "RS-15",
        "RS44" to "RS-44", "RS64S" to "AO-123", "RS95S" to "RS95S",
        "SAUDISAT1C" to "SO-50", "SO121" to "SO-121", "SO124" to "SO-124", "SO125" to "SO-125",
        "SO50" to "SO-50", "SOLUTUSNANOSATTELITE" to "SONATE", "SONATE2" to "SONATE",
        "TAURUS1" to "TAURUS", "TEVEL21" to "TEV2-1", "TEVEL22" to "TEV2-2",
        "TEVEL23" to "TEV2-3", "TEVEL24" to "TEV2-4", "TEVEL25" to "TEV2-5",
        "TEVEL26" to "TEV2-6", "TEVEL27" to "TEV2-7", "TEVEL28" to "TEV2-8",
        "TEVEL29" to "TEV2-9", "USAT1" to "LO-87", "USNAP1" to "NO-103",
        "VO52" to "VO-52", "W3ADO" to "NO-44", "XW2A" to "XW-2A",
        "XW2B" to "XW-2B", "XW2C" to "XW-2C", "XW2D" to "XW-2D",
        "XW2E" to "XW-2E", "XW2F" to "XW-2F", "XW3" to "HO-113",
        "XW3CAS9" to "HO-113", "XW4" to "HO-119", "YB0X" to "IO-86",
        "ZARYA" to "ARISS",

    )

    /**
     * Tracker name -> ARRL name. An exact table hit wins; otherwise the longest alias key
     * contained in the name matches, so a name carrying an extra suffix ("SAUDISAT 1C
     * (SO-50)") still resolves. Keys shorter than 5 characters never match loosely.
     */
    fun lookup(name: String): String? {
        val normalized = normalize(name)
        table[normalized]?.let { return it }
        return table.entries
            .filter { it.key.length >= 5 && normalized.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /** Upper case, everything that is not A-Z0-9 removed ("ISS (ZARYA)" -> "ISSZARYA"). */
    fun normalize(name: String): String = name.uppercase(Locale.US).filter { it.isLetterOrDigit() }

    /**
     * Resolve any tracker name against the ARRL catalogue: exact official name, then an alias
     * from [table], then an official name contained in the tracker name. Returns the official
     * name, or null when the tracker name is not an ARRL satellite at all.
     */
    fun resolve(name: String, officialNames: Collection<String>): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed.uppercase(Locale.US)
        officialNames.firstOrNull { it.equals(trimmed, true) }?.let { return it }
        lookup(trimmed)?.let { alias -> officialNames.firstOrNull { it.equals(alias, true) }?.let { return it } }
        return officialNames.firstOrNull { it.length > 1 && normalized.contains(it) }
    }

    /**
     * Catalogue candidates for a partly typed name. Alias keys are matched too, so the
     * operator's own spelling ("diwata", "saudisat 1c", "ao9") reaches the ARRL name even
     * when the two share no characters.
     */
    fun suggestions(query: String, officialNames: Collection<String>, limit: Int = 6): List<String> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val fromAliases = table.entries
            .filter { it.key.contains(normalized) || normalized.contains(it.key) }
            .map { it.value }
        val fromNames = officialNames.filter { normalize(it).contains(normalized) }
        return (fromAliases + fromNames + listOfNotNull(lookup(query)))
            .distinct()
            .filter { candidate -> officialNames.any { it.equals(candidate, true) } }
            .take(limit)
    }
}
