package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.isSatellite
import com.rtbishop.look4sat.core.domain.repository.LoTWDownloadRequest
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import java.util.Locale

internal fun parseLoTWReport(body: String, stationCallsign: String): LoTWResult = parseLoTWReport(
    body, LoTWDownloadRequest("", "", confirmedOnly = true, satellitesOnly = true, stationCallsign = stationCallsign)
)

internal fun parseLoTWReport(body: String, request: LoTWDownloadRequest): LoTWResult {
    if (!body.contains("<eoh>", true)) return when {
        body.contains("limit", true) || body.contains("try again", true) -> LoTWResult.RateLimited
        body.contains("password", true) || body.contains("login", true) -> LoTWResult.BadCredentials
        else -> LoTWResult.InvalidReport
    }
    return try {
        val report = parseLoTWFields(body)
        val expected = report.header["APP_LOTW_NUMREC"]?.let { it.trim().toIntOrNull() ?: error("Invalid count") }
        require(expected != null && expected == report.records.size)
        val normalized = report.records.map { fields ->
            fields.toMutableMap().apply {
                put("LOTW_QSL_SENT", "Y")
                put("LOTW_QSL_RCVD", fields["QSL_RCVD"] ?: fields["LOTW_QSL_RCVD"] ?: if (request.confirmedOnly) "Y" else "N")
                put("LOTW_QSLRDATE", fields["QSLRDATE"] ?: fields["LOTW_QSLRDATE"].orEmpty())
                if (get("MODE").isNullOrBlank()) put("MODE", fields["APP_LOTW_MODE"].orEmpty())
                if (get("STATION_CALLSIGN").isNullOrBlank()) put("STATION_CALLSIGN", request.stationCallsign.trim().uppercase(Locale.US))
            }
        }
        val records = AdifCodec.decodeRecords(normalized)
        require(records.size == report.records.size)
        LoTWResult.Success(records.filter { !request.satellitesOnly || it.isSatellite }, records.size)
    } catch (_: IllegalArgumentException) {
        LoTWResult.InvalidReport
    } catch (_: IllegalStateException) {
        LoTWResult.InvalidReport
    }
}

internal data class LoTWFields(val header: Map<String, String>, val records: List<Map<String, String>>)

/** Length-aware parsing also checks the trailing record; truncated downloads must not look successful. */
internal fun parseLoTWFields(content: String): LoTWFields {
    val records = mutableListOf<Map<String, String>>()
    var header: Map<String, String>? = null
    var values = linkedMapOf<String, String>()
    var index = 0
    while (index < content.length) {
        val start = content.indexOf('<', index)
        if (start < 0) break
        val end = content.indexOf('>', start + 1)
        require(end >= 0)
        val parts = content.substring(start + 1, end).split(':')
        val name = parts.first().trim().uppercase(Locale.US)
        index = end + 1
        when (name) {
            "EOH" -> {
                require(header == null && records.isEmpty())
                header = values.toMap()
                values = linkedMapOf()
            }
            "EOR" -> {
                require(header != null && values.isNotEmpty())
                records += values.toMap()
                values = linkedMapOf()
            }
            "APP_LOTW_EOF" -> {
                require(values.isEmpty())
                val size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                require(size >= 0 && size <= content.length - index)
                index += size
                require(content.substring(index).isBlank())
            }
            else -> {
                val size = parts.getOrNull(1)?.toIntOrNull() ?: error("Missing field length")
                require(size >= 0 && size <= content.length - index)
                values[name] = content.substring(index, index + size)
                index += size
            }
        }
    }
    require(header != null && values.isEmpty())
    return LoTWFields(header, records)
}
