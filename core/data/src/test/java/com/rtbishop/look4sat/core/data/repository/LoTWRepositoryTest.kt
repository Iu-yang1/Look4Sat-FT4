package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoTWRepositoryTest {
    private fun field(key: String, value: String) = "<$key:${value.length}>$value"

    @Test fun acceptsMixedCaseReorderedFieldsAndMultipleFieldsPerLine() {
        val body = "<APP_LOTW_NUMREC:1>1<eoh>" + listOf(
            "call" to "JA1ABC", "qso_date" to "20260909", "time_on" to "1015", "mode" to "FM",
            "vucc_grids" to "PM74ab,PM75cd", "dxcc" to "339", "cqz" to "25", "state" to "34 // Tottori",
            "band" to "2M", "band_rx" to "70CM", "sat_name" to "SO-50", "prop_mode" to "SAT"
        ).joinToString("") { field(it.first, it.second) } + "<eor>"
        val result = parseLoTWReport(body, "BA7OPF") as LoTWResult.Success
        val record = result.records.single()
        assertTrue(record.lotwConfirmed)
        assertEquals(listOf("PM74AB", "PM75CD"), record.vuccGrids)
        assertEquals("34", record.region)
        assertEquals("BA7OPF", record.myCallsign)
        assertEquals("2M", record.band)
        assertEquals("70CM", record.rxBand)
    }

    @Test fun excludesTerrestrialContactsAndKeepsSatelliteContactsWithoutGrid() {
        fun contact(propagation: String) = listOf(
            "CALL" to "K1ABC", "QSO_DATE" to "20260909", "TIME_ON" to "101500", "PROP_MODE" to propagation
        ).joinToString("") { field(it.first, it.second) } + "<EOR>"
        val result = parseLoTWReport("<APP_LOTW_NUMREC:1>2<EOH>" + contact("SAT") + contact("TR"), "BA7OPF") as LoTWResult.Success
        assertEquals(1, result.records.size)
        assertEquals("", result.records.single().theirGrid)
    }

    @Test fun distinguishesLoginRateLimitAndInvalidReport() {
        assertEquals(LoTWResult.BadCredentials, parseLoTWReport("<html>Incorrect password</html>", "BA7OPF"))
        assertEquals(LoTWResult.RateLimited, parseLoTWReport("Page request limit", "BA7OPF"))
        assertEquals(LoTWResult.InvalidReport, parseLoTWReport("<EOH><CALL:5>K1ABC<EOR>", "BA7OPF"))
        assertEquals(LoTWResult.InvalidReport, parseLoTWReport("<EOH>", "BA7OPF"))
        assertTrue((parseLoTWReport("<APP_LOTW_NUMREC:1>0<EOH>", "BA7OPF") as LoTWResult.Success).records.isEmpty())
    }
}
