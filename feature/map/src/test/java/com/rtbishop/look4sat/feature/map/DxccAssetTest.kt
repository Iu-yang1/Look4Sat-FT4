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
package com.rtbishop.look4sat.feature.map

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards `assets/awards/dxcc.json`, the DXCC boundary asset behind the grid-mode
 * award overlay (`AwardBoundaryOverlay` matches worked codes against
 * `region.code`). The 2026-10 rebuild split Russia into European (54) /
 * Asiatic (15), extracted sub-entities (Alaska, Hawaii, East Malaysia,
 * French Guiana, Corsica, Crete, Sardinia, Kaliningrad, Franz Josef Land)
 * and partitioned the UK into England/Scotland/Wales/Northern Ireland —
 * and re-keyed Germany (81 -> 230) / Palestine (196 -> 510) to current ADIF
 * codes — these tests pin the invariants that regeneration must keep.
 */
class DxccAssetTest {

    private val assetRelPath = "src/main/assets/awards/dxcc.json"

    private data class CityCheck(
        val name: String, val lon: Double, val lat: Double,
        val inside: Int, val outside: Int
    )

    private fun loadRegions(): List<JSONObject> {
        // Unit-test working dir varies (module dir vs repo root) — walk upwards.
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { base ->
                listOf(File(base, assetRelPath), File(base, "feature/map/$assetRelPath"))
                    .firstOrNull { it.isFile }
            }
            .firstOrNull()
            ?: error("dxcc.json not found starting from ${File("").absolutePath}")
        val root = JSONObject(file.readText())
        val arr = root.getJSONArray("regions")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun List<JSONObject>.byCode(code: Int): JSONObject =
        first { it.getInt("code") == code }

    /** Even-odd ray casting across all rings of a region (rings are disjoint parts). */
    private fun regionContains(region: JSONObject, lon: Double, lat: Double): Boolean =
        ringsContain(region.getJSONArray("rings"), lon, lat)

    private fun ringsContain(rings: JSONArray, lon: Double, lat: Double): Boolean {
        var inside = false
        for (r in 0 until rings.length()) {
            val ring = rings.getJSONArray(r)
            val n = ring.length()
            for (j in 0 until n) {
                val a = ring.getJSONArray(j)
                val b = ring.getJSONArray((j + 1) % n)
                val y1 = a.getDouble(1)
                val y2 = b.getDouble(1)
                if ((y1 > lat) != (y2 > lat)) {
                    val x = a.getDouble(0) + (lat - y1) / (y2 - y1) * (b.getDouble(0) - a.getDouble(0))
                    if (x > lon) inside = !inside
                }
            }
        }
        return inside
    }

    @Test
    fun `required ADIF entity codes are present exactly once`() {
        val regions = loadRegions()
        val codes = regions.map { it.getInt("code") }
        assertEquals("duplicate codes", codes.size, codes.toSet().size)
        val required = listOf(
            15, 54,                     // Asiatic / European Russia
            6, 110, 291,                // Alaska / Hawaii / rest of USA
            46, 299,                    // East / West Malaysia
            63, 214, 227,               // French Guiana / Corsica / rest of France
            40, 236,                    // Crete / rest of Greece
            225, 248,                   // Sardinia / rest of Italy
            126, 61,                    // Kaliningrad / Franz Josef Land
            223, 279, 294, 265,         // England / Scotland / Wales / N. Ireland
            230, 510                    // Germany / Palestine (current ADIF codes)
        )
        required.forEach { code -> assertTrue("missing code $code", code in codes) }
    }

    @Test
    fun `island and microstate batch is present`() {
        // 2026-10 additions (Natural Earth 1:10m admin-0 map units): common
        // island / microstate DXCC entities that 1:110m never carried, so a
        // worked entity like Singapore (381) could not light up at all.
        val regions = loadRegions()
        val codes = regions.map { it.getInt("code") }
        val batch = listOf(
            381, 304,                                   // Singapore / Bahrain
            203, 260, 278, 251, 295, 233,               // European microstates + Gibraltar
            257, 21, 29, 149, 256, 222, 114, 122, 106,  // Malta / Balearic / Canary / Azores / Madeira / Faroe / Man / Jersey / Guernsey
            62, 64, 65, 285, 69, 89, 91, 517, 79, 84,   // Caribbean
            94, 95, 97, 77, 98, 249, 96,
            103, 166, 9, 190, 160, 175, 191, 234        // Pacific
        )
        batch.forEach { code -> assertTrue("missing code $code", code in codes) }
        assertEquals("Singapore", regions.byCode(381).getString("name"))
        assertEquals("Canary Is.", regions.byCode(29).getString("name"))
    }

    @Test
    fun `B-plan island batch is present`() {
        // 2026-10 B plan: every remaining DXCC entity that has a REAL Natural
        // Earth 1:10m polygon (own feature or a ring window split out of a
        // parent: Rodrigues/Agalega of Mauritius, Ducie of Pitcairn, South
        // Georgia/Sandwich, the St. Helena family). Atoll/reef entities
        // without land polygons stay missing by design.
        val regions = loadRegions()
        val codes = regions.map { it.getInt("code") }
        val batch = listOf(
            4, 5, 12, 20, 22, 24, 35, 36, 38, 105, 111, 118, 123, 157, 159,
            165, 168, 169, 172, 173, 174, 182, 188, 189, 197, 205, 207,
            213, 219, 235, 240, 247, 250, 259, 270, 274, 277, 282, 297,
            298, 379, 409, 411, 453, 506, 513, 516, 518, 522
        )
        batch.forEach { code -> assertTrue("missing code $code", code in codes) }
        assertEquals("Wake I.", regions.byCode(297).getString("name"))
        assertEquals("Rodrigues I.", regions.byCode(207).getString("name"))
    }

    @Test
    fun `no region uses an ADIF-deleted entity code`() {
        // ADIF 3.1.7 deleted DXCC codes. A polygon keyed to a deleted code
        // never matches LoTW exports (they carry the active code, e.g.
        // Germany 81 -> 230, Palestine 196 -> 510), so the award never fills.
        val deleted = setOf(
            2, 8, 19, 23, 25, 26, 28, 30, 39, 42, 44, 55, 57, 58, 59, 67, 68,
            81, 85, 93, 101, 102, 113, 115, 119, 127, 128, 134, 139, 151, 154, 155,
            164, 178, 183, 184, 186, 193, 194, 196, 198, 200, 208, 210, 218, 220,
            226, 228, 229, 231, 243, 244, 255, 258, 261, 264, 267, 268, 271, 307,
            488, 493
        )
        loadRegions().forEach { region ->
            val code = region.getInt("code")
            assertTrue("${region.getString("name")} uses deleted code $code", code !in deleted)
        }
    }

    @Test
    fun `entities carry their ADIF names`() {
        val regions = loadRegions()
        assertEquals("England", regions.byCode(223).getString("name"))
        assertEquals("European Russia", regions.byCode(54).getString("name"))
        assertEquals("Asiatic Russia", regions.byCode(15).getString("name"))
        assertEquals("Scotland", regions.byCode(279).getString("name"))
        assertEquals("Alaska", regions.byCode(6).getString("name"))
    }

    @Test
    fun `every region has drawable rings within coordinate bounds`() {
        loadRegions().forEach { region ->
            val name = region.getString("name")
            val rings = region.getJSONArray("rings")
            assertTrue("$name: no rings", rings.length() >= 1)
            for (r in 0 until rings.length()) {
                val ring = rings.getJSONArray(r)
                assertTrue("$name: ring $r has ${ring.length()} pts", ring.length() >= 3)
                for (p in 0 until ring.length()) {
                    val pt = ring.getJSONArray(p)
                    val lon = pt.getDouble(0)
                    val lat = pt.getDouble(1)
                    assertTrue("$name: lon out of range $lon", lon in -180.0..180.0)
                    assertTrue("$name: lat out of range $lat", lat in -90.0..90.0)
                }
            }
        }
    }

    @Test
    fun `cities fall on the correct side of the rebuilt boundaries`() {
        val regions = loadRegions()
        val checks = listOf(
            // European/Asiatic Russia split along the Urals (OSM ridge + Ural river)
            CityCheck("Moscow", 37.61, 55.75, inside = 54, outside = 15),
            CityCheck("Perm", 56.24, 58.01, inside = 54, outside = 15),
            CityCheck("Yekaterinburg", 60.61, 56.84, inside = 15, outside = 54),
            CityCheck("Novosibirsk", 82.93, 55.03, inside = 15, outside = 54),
            CityCheck("Kaliningrad", 20.51, 54.71, inside = 126, outside = 54),
            // UK four-entity partition
            CityCheck("London", -0.13, 51.51, inside = 223, outside = 279),
            CityCheck("Edinburgh", -3.19, 55.95, inside = 279, outside = 223),
            CityCheck("Cardiff", -3.18, 51.48, inside = 294, outside = 223),
            CityCheck("Belfast", -5.93, 54.60, inside = 265, outside = 223),
            // Extracted sub-entities
            CityCheck("Anchorage", -149.90, 61.22, inside = 6, outside = 291),
            CityCheck("Central Oahu", -157.90, 21.45, inside = 110, outside = 291),
            CityCheck("Los Angeles", -118.24, 34.05, inside = 291, outside = 6),
            CityCheck("Kuching", 110.34, 1.55, inside = 46, outside = 299),
            CityCheck("Kuala Lumpur", 101.69, 3.14, inside = 299, outside = 46),
            CityCheck("Cayenne", -52.32, 4.94, inside = 63, outside = 227),
            CityCheck("Ajaccio", 8.74, 41.93, inside = 214, outside = 227),
            CityCheck("Heraklion", 25.14, 35.34, inside = 40, outside = 236),
            CityCheck("Cagliari", 9.12, 39.22, inside = 225, outside = 248)
        )
        for (c in checks) {
            assertTrue("${c.name} should be in ${c.inside}",
                regionContains(regions.byCode(c.inside), c.lon, c.lat))
            assertTrue("${c.name} should NOT be in ${c.outside}",
                !regionContains(regions.byCode(c.outside), c.lon, c.lat))
        }
    }

    @Test
    fun `merged map units are framed by their DXCC parent`() {
        // Natural Earth draws Somaliland and N. Cyprus as separate map units.
        // Both belong to their DXCC entity (6O Somalia / 5B Cyprus) and are
        // merged into the parent rings — without the merge the northern
        // territories had no boundary at all (nothing drawn, never greened).
        val regions = loadRegions()
        val checks = listOf(
            Triple("Hargeisa", 44.06 to 9.56, 232),
            Triple("Berbera", 45.02 to 10.44, 232),
            Triple("Garowe", 48.48 to 8.40, 232),
            Triple("Kyrenia", 33.32 to 35.34, 215),
            Triple("Nicosia", 33.38 to 35.19, 215)
        )
        for ((city, pos, code) in checks) {
            assertTrue("$city should be inside $code",
                regionContains(regions.byCode(code), pos.first, pos.second))
        }
    }

    @Test
    fun `Norway keeps only its mainland ring`() {
        // Norway's Natural Earth geometry carries coarse Svalbard rings (7-17 pts).
        // Svalbard (259) is a separate DXCC entity with its own detailed rings, so
        // the coarse copies were deleted — they drew every island twice (a simple
        // and a coast-fitting outline) and greening Norway also greened JW.
        val norway = loadRegions().byCode(266)
        val rings = norway.getJSONArray("rings")
        assertEquals("Norway ring count", 1, rings.length())
        for (r in 0 until rings.length()) {
            val ring = rings.getJSONArray(r)
            for (p in 0 until ring.length()) {
                val lat = ring.getJSONArray(p).getDouble(1)
                assertTrue("Norway vertex at $lat — Svalbard ring leftover", lat <= 73.0)
            }
        }
    }

    @Test
    fun `worked fill knockouts cover the vetted parent child pairs`() {
        // fill_rings = parent territory minus separate child DXCC entities, so a
        // worked parent (e.g. South Africa 462) never greens a child entity
        // (Lesotho 432) that has its own code. Pairs were vetted geometrically
        // (child area >=50% inside the parent); this test pins them so future
        // asset edits re-trigger the review.
        val expected = mapOf(
            462 to setOf(432),          // South Africa - Lesotho
            318 to setOf(321),          // China - Hong Kong
            299 to setOf(381),          // Malaysia - Singapore
            227 to setOf(203, 260),     // France - Andorra, Monaco
            248 to setOf(278, 295),     // Italy - San Marino, Vatican
            206 to setOf(251),          // Austria - Liechtenstein
            287 to setOf(251),          // Switzerland - Liechtenstein
            281 to setOf(203, 233),     // Spain - Andorra, Gibraltar
            70 to setOf(105)            // Cuba - Guantanamo Bay
        )
        val regions = loadRegions()
        val withFill = regions.filter { it.has("fill_rings") }.map { it.getInt("code") }.toSet()
        assertEquals("regions carrying fill_rings", expected.keys, withFill)
        for ((parentCode, childCodes) in expected) {
            val parent = regions.byCode(parentCode)
            val fillRings = parent.getJSONArray("fill_rings")
            assertTrue("${parent.getString("name")}: empty fill_rings", fillRings.length() >= 1)
            // The parent's own anchor must survive the knockout (no over-cutting).
            val pLon = parent.getDouble("label_lon")
            val pLat = parent.getDouble("label_lat")
            assertTrue("${parent.getString("name")}: anchor lost in fill",
                ringsContain(fillRings, pLon, pLat))
            for (childCode in childCodes) {
                val child = regions.byCode(childCode)
                val childRings = child.getJSONArray("rings")
                // Probes: the label anchor plus each ring's centroid. Some anchors
                // drift outside their own ring (Guantanamo Bay), so a probe only
                // counts when it really sits inside the child's own geometry.
                val probes = mutableListOf(
                    child.getDouble("label_lon") to child.getDouble("label_lat")
                )
                for (r in 0 until childRings.length()) {
                    val ring = childRings.getJSONArray(r)
                    var sx = 0.0; var sy = 0.0
                    for (p in 0 until ring.length()) {
                        sx += ring.getJSONArray(p).getDouble(0)
                        sy += ring.getJSONArray(p).getDouble(1)
                    }
                    probes.add(sx / ring.length() to sy / ring.length())
                }
                for ((cLon, cLat) in probes) {
                    if (regionContains(child, cLon, cLat) && regionContains(parent, cLon, cLat)) {
                        assertFalse("${parent.getString("name")} fill still covers ${child.getString("name")}",
                            ringsContain(fillRings, cLon, cLat))
                    }
                }
            }
        }
    }
}
