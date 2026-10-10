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
    /** Every 4-char grid the OPPOSITE station logged this QSO under (ADIF
     *  <GRIDSQUARE> + <VUCC_GRIDS>; 1–4 grids). [theirGrid] keeps the first;
     *  the logbook's QSL slot shows the abbreviated set. */
    val theirVuccGrids: List<String> = emptyList(),
    val myGrid: String = "",
    val sentReport: String = "",
    val receivedReport: String = "",
    val txFrequencyHz: Long? = null,
    val rxFrequencyHz: Long? = null,
    val band: String = "",
    val rxBand: String = "",
    val mode: String = "FM",
    val submode: String = "",
    val satelliteName: String = "",
    val transponderName: String = "",
    val satelliteMode: String = "",
    val passAosUtcMillis: Long? = null,
    val automatic: Boolean = false,
    val status: QsoStatus = QsoStatus.DRAFT,
    val propagationMode: String = if (satelliteName.isNotBlank()) "SAT" else "",
    val lotwConfirmed: Boolean = false,
    val lotwUploaded: Boolean = false,
    /** Internal: set after a successful Wavelog upload; keeps the pending filter small. Not shown in any list. */
    val wavelogUploaded: Boolean = false,
    /** Wavelog station profile (台址) this QSO belongs to: the profile it was uploaded
     *  through, or the profile a sync pulled it from. Empty for records the app never
     *  involved with Wavelog — the logbook's 台址 selector shows those under "All" only. */
    val wavelogStation: String = "",
    val lotwReceived: Boolean = false,
    val lotwQslDate: String = "",
    /** Paper QSL received (ADIF QSL_RCVD=Y) — one of the routes Wavelog counts as
     *  "confirmed". Sync-only: the logbook stores no paper-QSL column, so this survives
     *  only inside the record a Wavelog pull produced (the grid rule reads it there). */
    val qslConfirmed: Boolean = false,
    /** eQSL confirmation received (ADIF EQSL_QSL_RCVD=Y). Sync-only, see [qslConfirmed]. */
    val eqslConfirmed: Boolean = false,
    /** Own operated grid set this QSO went out under — uploaded as ADIF MY_VUCC_GRIDS
     *  (never VUCC_GRIDS, which belongs to the opposite station). Stamped at upload. */
    val vuccGrids: List<String> = emptyList(),
    val dxcc: Int? = null,
    val country: String = "",
    val cqZone: Int? = null,
    val region: String = "",
    val comment: String = ""
)

data class AdifImportResult(val imported: Int, val skipped: Int, val updated: Int = 0)
