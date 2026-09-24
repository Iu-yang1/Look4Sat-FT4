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
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.InputStream
import kotlin.math.pow

class DataParser(private val dispatcher: CoroutineDispatcher) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun parseCSVStream(stream: InputStream): List<OrbitalData> = withContext(dispatcher) {
        stream.bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { parseCSV(it.split(",")) }.toList()
        }
    }

    /** Parse AMSAT's active-transponder CSV (palewire mirror) and return the
     *  set of NORAD catnums of active *amateur* satellites. Header row has a
     *  norad_id column; some rows carry alphanumeric IDs (Bluebird A0241 etc.)
     *  which are skipped. */
    suspend fun parseAmSatActiveCatnums(stream: InputStream): Set<Int> = withContext(dispatcher) {
        stream.bufferedReader().useLines { lines ->
            val header = lines.firstOrNull() ?: return@useLines emptySet()
            val noradIdx = header.split(",").indexOfFirst { it.trim().equals("norad_id", true) }
            if (noradIdx < 0) return@useLines emptySet()
            lines.mapNotNull { line ->
                line.split(",").getOrNull(noradIdx)?.trim()?.toIntOrNull()
            }.toSet()
        }
    }

    suspend fun parseTLEStream(stream: InputStream): List<OrbitalData> = withContext(dispatcher) {
        stream.bufferedReader().readLines()
            .chunked(3)
            .filter { it.size == 3 && it[1].startsWith("1") && it[2].startsWith("2") }
            .mapNotNull { parseTLE(it) }
    }

    suspend fun parseJSONStream(stream: InputStream): List<SatRadio> = withContext(dispatcher) {
        runCatching {
            val root = json.parseToJsonElement(stream.bufferedReader().readText())
            (root as? JsonArray)?.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<SatRadio>(element) }
                    .onFailure { println("JSON parsing exception: $it") }
                    .getOrNull()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Parse an AMSAT "Live FM/Linear Satellites" page into satellite names.
     * The page is an HTML table whose first column is the satellite name,
     * sometimes written as an alias list ("AO-91(RadFxSat / Fox-1B)") or a
     * range ("TEVEL2-1 thru TEVEL2-9"). Returns the distinct expanded names.
     */
    suspend fun parseAmSatLivePage(stream: InputStream): List<String> = withContext(dispatcher) {
        runCatching {
            val html = stream.bufferedReader().readText()
            // Grab table rows, take the first cell of each row, keep rows that
            // look like satellite names (start with letters/digits, not a header).
            val rows = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { match ->
                    val cells = Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.IGNORE_CASE)
                        .findAll(match.groupValues[1])
                        .map { cell -> stripHtml(cell.groupValues[1]).trim() }
                        .toList()
                    cells.firstOrNull()?.takeIf { it.isNotBlank() && !it.equals("Satellite", ignoreCase = true) }
                }
                .toList()
            rows.flatMap { expandNameRange(it) }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    /** Expand "NAME1 thru NAME9" ranges (e.g. TEVEL2-1 thru TEVEL2-9). */
    private fun expandNameRange(name: String): List<String> {
        val m = Regex("^(.*?)(\\d+)\\s+thru\\s+(.*?)(\\d+)$", RegexOption.IGNORE_CASE).find(name)
        if (m == null) return listOf(name)
        val prefix1 = m.groupValues[1].trim()
        val startNum = m.groupValues[2].toIntOrNull() ?: return listOf(name)
        val endNum = m.groupValues[4].toIntOrNull() ?: return listOf(name)
        val prefix2 = m.groupValues[3].trim()
        if (startNum > endNum) return listOf(name)
        // Suffix after the end number, if any (e.g. "TEVEL2-1 thru TEVEL2-9 ").
        val suffix = name.substringAfterLast(m.groupValues[4]).trim()
        return (startNum..endNum).map { "$prefix1$it$suffix" }
    }

    /**
     * Normalize a satellite name from the AMSAT live pages into lookup keys
     * used to match against the local entries table:
     *  - primary key: the leading designator ("AO-91" from "AO-91 (RadFxSat / Fox-1B)")
     *  - alias keys: every bracketed alias, uppercased, digits preserved
     * The local entry names are normalized the same way in [matchesAmSatName].
     */
    fun normalizeAmSatName(name: String): List<String> {
        val keys = mutableListOf<String>()
        // Primary: everything before the first '(' (or '['), then the first token.
        val primary = name.substringBefore('(').substringBefore('[').trim()
        primary.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            keys += it.uppercase()
        }
        // Aliases inside parentheses / brackets: "RadFxSat / Fox-1B" -> two keys.
        Regex("\\(([^)]*)\\)").findAll(name).forEach { m ->
            m.groupValues[1].split('/', '|').forEach { alias ->
                alias.trim().takeIf { it.isNotBlank() }?.let { keys += it.uppercase() }
            }
        }
        Regex("\\[([^]]*)]").findAll(name).forEach { m ->
            m.groupValues[1].split('/', '|').forEach { alias ->
                alias.trim().takeIf { it.isNotBlank() }?.let { keys += it.uppercase() }
            }
        }
        return keys.distinct()
    }

    /** True if a local entry name matches any of the AMSAT normalized keys.
     *  Token-based exact match (split on spaces/brackets/slashes), so a key
     *  like "ISS" does not substring-match "AISSAT-1" — the key must equal a
     *  whole name token (case-insensitive). OSCAR designators are expanded so
     *  that "AO-7" also matches local names spelled "OSCAR 7" / "AMSAT-OSCAR 7"
     *  (the form used by CelesTrak/SatNOGS TLE sources). */
    fun matchesAmSatName(localName: String, amSatKeys: List<String>): Boolean {
        val localUpper = localName.uppercase()
        val localTokens = localUpper
            .split(Regex("[\\s()\\[\\]/]+"))
            .filter { it.isNotBlank() }
            .toSet()
        return amSatKeys.any { key ->
            val k = key.uppercase()
            if (k in localTokens) return@any true
            // "AO-7" -> also match local "OSCAR 7" or "AMSAT-OSCAR 7".
            val oscar = Regex("^([A-Z]{1,3})-(\\d+)$").find(k)
            if (oscar != null) {
                val num = oscar.groupValues[2]
                if ("OSCAR $num" in localUpper || "AMSAT-OSCAR $num" in localUpper) return@any true
            }
            false
        }
    }

    private fun parseCSV(values: List<String>): OrbitalData? = runCatching {
        val name = values[0]
        val timestamp = values[2]
        val year = timestamp.substring(0, 4)
        val month = timestamp.substring(5, 7).toInt()
        val dayOfMonth = timestamp.substring(8, 10).toInt()
        val dayInt = getDayOfYear(year.toInt(), month, dayOfMonth)
        val day = dayInt.toString().padStart(3, '0')
        val hour = timestamp.substring(11, 13).toInt() * 3600000
        val min = timestamp.substring(14, 16).toInt() * 60000
        val sec = timestamp.substring(17, 19).toInt() * 1000
        val ms = timestamp.substring(20, 26).toInt() / 1000.0
        val frac = ((hour + min + sec + ms) / 86400000.0).toString().substring(1)
        val epoch = "${year.substring(2)}$day$frac".toDouble()
        OrbitalData(
            name = name,
            epoch = epoch,
            meanmo = values[3].toDouble(),
            eccn = values[4].toDouble(),
            incl = values[5].toDouble(),
            raan = values[6].toDouble(),
            argper = values[7].toDouble(),
            meanan = values[8].toDouble(),
            catnum = values[11].toInt(),
            bstar = values[14].toDouble(),
            ndot = values[15].toDouble()
        )
    }.onFailure { println("CSV parsing exception: $it") }.getOrNull()

    private fun parseTLE(tle: List<String>): OrbitalData? = runCatching {
        val line1 = tle[1]
        val line2 = tle[2]
        OrbitalData(
            name = tle[0].trim(),
            epoch = line1.substring(18, 32).toDouble(),
            meanmo = line2.substring(52, 63).toDouble(),
            eccn = line2.substring(26, 33).toDouble() / 1e7,
            incl = line2.substring(8, 16).toDouble(),
            raan = line2.substring(17, 25).toDouble(),
            argper = line2.substring(34, 42).toDouble(),
            meanan = line2.substring(43, 51).toDouble(),
            catnum = line1.substring(2, 7).trim().toInt(),
            bstar = 1e-5 * line1.substring(53, 59).toDouble() / 10.0.pow(line1.substring(60, 61).toDouble()),
            ndot = line1.substring(33, 43).trim().toDouble()
        )
    }.onFailure { println("TLE parsing exception: $it") }.getOrNull()

    fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun getDayOfYear(year: Int, month: Int, dayOfMonth: Int): Int {
        val daysInMonth = intArrayOf(31, if (isLeapYear(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return daysInMonth.take(month - 1).sum() + dayOfMonth
    }
}
