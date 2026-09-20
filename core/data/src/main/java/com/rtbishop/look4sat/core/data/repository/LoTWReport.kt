/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.logbook.AdifCodec
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import java.util.Locale

internal data class ParsedLoTWReport(
    val records: List<QsoRecord>,
    val downloaded: Int
)

/** Length-aware parsing rejects truncated LoTW reports before they reach the logbook. */
internal fun parseLoTWRecords(
    body: String,
    defaultCallsign: String,
    assumeConfirmed: Boolean
): ParsedLoTWReport? = runCatching {
    if (!body.contains("<eoh>", ignoreCase = true)) return null
    val report = parseLoTWFields(body)
    report.header["APP_LOTW_NUMREC"]?.let { count ->
        require(count.trim().toIntOrNull() == report.records.size)
    }
    val normalized = report.records.map { fields ->
        fields.toMutableMap().apply {
            put("LOTW_QSL_SENT", "Y")
            put(
                "LOTW_QSL_RCVD",
                fields["QSL_RCVD"] ?: fields["LOTW_QSL_RCVD"]
                    ?: if (assumeConfirmed) "Y" else "N"
            )
            put("LOTW_QSLRDATE", fields["QSLRDATE"] ?: fields["LOTW_QSLRDATE"].orEmpty())
            if (get("MODE").isNullOrBlank()) put("MODE", fields["APP_LOTW_MODE"].orEmpty())
            if (get("STATION_CALLSIGN").isNullOrBlank()) {
                put(
                    "STATION_CALLSIGN",
                    fields["APP_LOTW_OWNCALL"].orEmpty().ifBlank { defaultCallsign }
                        .trim().uppercase(Locale.US)
                )
            }
        }
    }
    val records = AdifCodec.decodeRecords(normalized)
    require(records.size == report.records.size)
    ParsedLoTWReport(records, report.records.size)
}.getOrNull()

internal data class LoTWFields(
    val header: Map<String, String>,
    val records: List<Map<String, String>>
)

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
    return LoTWFields(requireNotNull(header), records).also { require(values.isEmpty()) }
}
