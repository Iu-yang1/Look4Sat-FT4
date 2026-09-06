/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

enum class QsoStatus { DRAFT, COMPLETE, ABORTED }

data class QsoRecord(
    val id: Long = 0L,
    val startUtcMillis: Long,
    val endUtcMillis: Long? = null,
    val theirCallsign: String,
    val myCallsign: String,
    val theirGrid: String = "",
    val myGrid: String = "",
    val sentReport: String = "",
    val receivedReport: String = "",
    val txFrequencyHz: Long? = null,
    val rxFrequencyHz: Long? = null,
    val band: String = "",
    val rxBand: String = "",
    val satelliteName: String = "",
    val transponderName: String = "",
    val satelliteMode: String = "",
    val passAosUtcMillis: Long? = null,
    val ft4AudioFrequencyHz: Int? = null,
    val automatic: Boolean = false,
    val status: QsoStatus = QsoStatus.DRAFT,
    val rawMessages: List<String> = emptyList()
)

data class AdifImportResult(val imported: Int, val skipped: Int)
