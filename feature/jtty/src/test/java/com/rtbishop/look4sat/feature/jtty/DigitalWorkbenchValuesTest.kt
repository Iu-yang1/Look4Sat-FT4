// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.feature.jtty

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.presentation.digitalLogRecords
import com.rtbishop.look4sat.core.presentation.digitalReferenceText
import com.rtbishop.look4sat.core.presentation.parseDigitalReference
import org.junit.Assert.*
import org.junit.Test

class DigitalWorkbenchValuesTest {
    @Test fun referenceDisplayRoundsButEditorRetainsHertz() {
        assertEquals("435.400", digitalReferenceText(435_399_999))
        assertEquals(435_399_999L, parseDigitalReference("435.399999"))
        assertNull(parseDigitalReference("435.3999999"))
        assertNull(parseDigitalReference("NaN"))
        assertNull(parseDigitalReference("-1"))
        assertNull(parseDigitalReference("10000"))
    }

    @Test fun currentSatelliteFilteringPrecedesLimitAndKeepsGridSets() {
        val expected = QsoRecord(id = 200, startUtcMillis = 500, theirCallsign = "BG5JSU",
            myCallsign = "BA7OPF", satelliteName = "RS-44", myGrid = "OL87",
            vuccGrids = listOf("OL87", "OL88"))
        val unrelated = (1L..150L).map { QsoRecord(it, it, theirCallsign = "TEST$it",
            myCallsign = "BA7OPF", satelliteName = "SO-50") }
        assertEquals(listOf(expected), digitalLogRecords(unrelated + expected, "rs-44", false))
        assertEquals(100, digitalLogRecords(unrelated + expected, null, true).size)
        assertEquals(expected, digitalLogRecords(unrelated + expected, null, true).first())
        assertTrue(digitalLogRecords(unrelated, null, false).isEmpty())
    }
}
