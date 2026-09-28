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

import com.rtbishop.look4sat.core.domain.logbook.LoTWSatelliteAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alias table that maps tracker catalogue names onto the names ARRL's config.tq6 uses. */
class LoTWSatelliteAliasesTest {

    private val catalogue = listOf("SO-50", "ARISS", "PO-101", "BO-102", "AO-91", "RS-44", "IO-117")

    @Test
    fun `lookup maps SatNOGS catalogue names to ARRL names`() {
        assertEquals("SO-50", LoTWSatelliteAliases.lookup("SAUDISAT 1C"))
        assertEquals("ARISS", LoTWSatelliteAliases.lookup("ISS (ZARYA)"))
        assertEquals("PO-101", LoTWSatelliteAliases.lookup("DIWATA 2B"))
        assertEquals("AO-91", LoTWSatelliteAliases.lookup("FOX-1B"))
        assertEquals("RS-44", LoTWSatelliteAliases.lookup("DOSAAF-85"))
        assertEquals("IO-117", LoTWSatelliteAliases.lookup("GREENCUBE"))
    }

    @Test
    fun `normalize strips separators and case`() {
        assertEquals("ISSZARYA", LoTWSatelliteAliases.normalize("iss (zarya)"))
        assertEquals("SAUDISAT1C", LoTWSatelliteAliases.normalize("SaudiSat-1C"))
    }

    @Test
    fun `lookup ignores unknown and placeholder names`() {
        assertNull(LoTWSatelliteAliases.lookup("NOT A SATELLITE"))
        // Recycled temporary designations must never resolve: they would sign the wrong object.
        assertNull(LoTWSatelliteAliases.lookup("OBJECT AY"))
    }

    @Test
    fun `resolve accepts ARRL names, aliases and suffixed tracker names`() {
        assertEquals("SO-50", LoTWSatelliteAliases.resolve("SO-50", catalogue))
        assertEquals("SO-50", LoTWSatelliteAliases.resolve("saudisat 1c", catalogue))
        assertEquals("SO-50", LoTWSatelliteAliases.resolve("SAUDISAT 1C (SO-50)", catalogue))
        assertEquals("ARISS", LoTWSatelliteAliases.resolve("ISS (ZARYA)", catalogue))
        assertEquals("AO-91", LoTWSatelliteAliases.resolve("AO-91 (RadFxSat)", catalogue))
    }

    @Test
    fun `resolve rejects names that are not ARRL satellites`() {
        assertNull(LoTWSatelliteAliases.resolve("", catalogue))
        assertNull(LoTWSatelliteAliases.resolve("MARINA", catalogue))
        assertNull(LoTWSatelliteAliases.resolve("OBJECT AY", catalogue))
    }

    @Test
    fun `suggestions find the ARRL name from a tracker spelling`() {
        assertEquals(listOf("SO-50"), LoTWSatelliteAliases.suggestions("saudisat 1c", catalogue))
        assertTrue(LoTWSatelliteAliases.suggestions("ao9", catalogue).contains("AO-91"))
        assertEquals(listOf("PO-101"), LoTWSatelliteAliases.suggestions("diwata", catalogue))
        assertTrue(LoTWSatelliteAliases.suggestions("", catalogue).isEmpty())
    }
}
