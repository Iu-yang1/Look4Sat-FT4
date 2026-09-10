package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.LoTWDownloadRequest
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LoTWDownloadTest {
    private fun field(key: String, value: String) = "<$key:${value.length}>$value"
    private fun contact(confirmed: Boolean, satellite: Boolean = true): String = listOf(
        "CALL" to "K1ABC", "STATION_CALLSIGN" to "BA7OPF", "QSO_DATE" to "20260909", "TIME_ON" to "101530",
        "APP_LOTW_MODE" to "FT4", "BAND" to "2M", "QSL_RCVD" to if (confirmed) "Y" else "N",
        "QSLRDATE" to if (confirmed) "20260910" else "", "PROP_MODE" to if (satellite) "SAT" else "TR",
        "SAT_NAME" to if (satellite) "AO-123" else ""
    ).joinToString("") { field(it.first, it.second) } + "<EOR>"
    private fun report(vararg records: String) = field("APP_LOTW_NUMREC", records.size.toString()) + "<EOH>" + records.joinToString("") + "<APP_LOTW_EOF>"
    private val query = LoTWDownloadRequest("MixedCaseUser", "not-a-real-password")

    @Test fun allQsosKeepsUnconfirmedAndTerrestrialContacts() {
        val result = parseLoTWReport(report(contact(false), contact(true, false)), query) as LoTWResult.Success
        assertEquals(2, result.downloaded)
        assertEquals(listOf(false, true), result.records.map { it.lotwConfirmed })
        assertTrue(result.records.all { it.lotwReceived })
        assertEquals("FT4", result.records.first().mode)
        assertEquals("20260910", result.records.last().lotwQslDate)
    }
    @Test fun satelliteFilterReportsBothDownloadedAndKeptCounts() {
        val result = parseLoTWReport(report(contact(false), contact(true, false)), query.copy(satellitesOnly = true)) as LoTWResult.Success
        assertEquals(2, result.downloaded)
        assertEquals(1, result.records.size)
    }
    @Test fun loginIsNeverUsedAsStationCallsign() {
        val text = report(contact(false).replace(field("STATION_CALLSIGN", "BA7OPF"), ""))
        val result = parseLoTWReport(text, query) as LoTWResult.Success
        assertEquals("", result.records.single().myCallsign)
    }
    @Test fun truncationAndCountMismatchAreRejectedWithoutPartialImport() {
        val full = report(contact(false), contact(true))
        listOf(full.substringBeforeLast("<EOR>"), full.replace("NUMREC:1>2", "NUMREC:1>3"),
            full.replace("<CALL:5>K1ABC", "<CALL:999999>K1ABC"), "<EOH>").forEach {
            assertEquals(LoTWResult.InvalidReport, parseLoTWReport(it, query))
        }
    }
    @Test fun zeroRecordsIsASuccess() {
        assertEquals(0, (parseLoTWReport(report(), query) as LoTWResult.Success).downloaded)
    }
    @Test fun rateLimitWinsOverGenericLoginPageText() {
        assertEquals(LoTWResult.RateLimited, parseLoTWReport("Page Request Limit - login later", query))
    }
    @Test fun preservesUsernameAndEncodesPasswordAndRequestsOwnCall() = runBlocking {
        var intercepted = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            assertEquals("https", url.scheme)
            assertEquals("lotw.arrl.org", url.host)
            assertEquals("MixedCaseUser", url.queryParameter("login"))
            assertEquals("a&b +/%", url.queryParameter("password"))
            assertEquals("no", url.queryParameter("qso_qsl"))
            assertEquals("yes", url.queryParameter("qso_withown"))
            assertEquals("1900-01-01", url.queryParameter("qso_qsorxsince"))
            assertNull(url.queryParameter("qso_qslsince"))
            intercepted = true
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(report().toResponseBody()).build()
        }.build()
        assertTrue(LoTWRepository(client).download(query.copy(password = "a&b +/%")) is LoTWResult.Success)
        assertTrue(intercepted)
    }
    @Test fun confirmedRequestUsesQslSinceAndOwnCallFilter() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            assertEquals("yes", url.queryParameter("qso_qsl"))
            assertEquals("2026-01-01", url.queryParameter("qso_qslsince"))
            assertEquals("BA7OPF/P", url.queryParameter("qso_owncall"))
            assertNull(url.queryParameter("qso_qsorxsince"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(report().toResponseBody()).build()
        }.build()
        assertTrue(LoTWRepository(client).download(query.copy(confirmedOnly = true, since = "2026-01-01", stationCallsign = "BA7OPF/P")) is LoTWResult.Success)
    }
    @Test fun invalidDatesNeverMakeANetworkRequest() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Must not send") }.build()
        assertEquals(LoTWResult.InvalidDate, LoTWRepository(client).download(query.copy(since = "2026-02-30")))
    }
    @Test fun redirectsDoNotForwardCredentialsAndReportHttpStatus() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://example.com/").body("".toResponseBody()).build()
        }.build()
        assertEquals(LoTWResult.ServerError(302), LoTWRepository(client).download(query))
        assertEquals(1, calls)
    }
}
