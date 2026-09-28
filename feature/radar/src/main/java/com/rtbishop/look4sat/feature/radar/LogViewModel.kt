/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.frequencyBand
import com.rtbishop.look4sat.core.domain.logbook.officialSatelliteName
import com.rtbishop.look4sat.core.domain.logbook.satelliteIdentity
import com.rtbishop.look4sat.core.domain.logbook.unavailableUploadSummary
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class LogUiState(
    val callsignInput: String = "",
    /** Current logbook mode for the selected satellite (FM/CW/SSB/FT4). */
    val selectedMode: String = "",
    /** Station callsign from the imported LoTW certificate ("" when none). */
    val certificateCallsign: String = "",
    /** Primary grid of the LoTW upload station location ("" when none). */
    val stationGrid: String = "",
    /** ARRL satellite names (config.tq6) — the only names a record may be signed with. */
    val satelliteCatalog: List<String> = emptyList(),
    val preview: LoTWUploadPreview? = null,
    /** User-facing upload / record message ("" when none). */
    val message: String = "",
    val postText: String? = null,
    val busy: Boolean = false
)

class LogViewModel(
    private val qsoRepository: IQsoRepository,
    private val lotwUploadRepository: ILoTWUploadRepository,
    private val settingsRepo: ISettingsRepo
) : ViewModel() {

    /** Records shown on the radar log page: all records of the current pass window, including
     *  already-uploaded (UP) and confirmed (QSL) ones. Window filtering happens in LogPage. */
    val records: Flow<List<QsoRecord>> = qsoRepository.records

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState

    // Record ids submitted in the most recent prepare → upload cycle, so a
    // successful POST can mark them "uploaded" (distinct from "confirmed").
    private var lastUploadedIds: List<Long> = emptyList()

    init {
        viewModelScope.launch {
            val cert = lotwUploadRepository.certificate()
            val station = lotwUploadRepository.station()
            val catalog = runCatching { lotwUploadRepository.satelliteCatalog() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    certificateCallsign = cert?.callsign.orEmpty(),
                    stationGrid = station?.grid.orEmpty(),
                    satelliteCatalog = catalog
                )
            }
        }
    }

    fun updateCallsign(value: String) =
        _uiState.update { it.copy(callsignInput = value.uppercase(Locale.US)) }

    /** Load the per-satellite mode preset when the selected satellite changes. */
    fun selectSatellite(catnum: Int) {
        val preset = settingsRepo.getSatelliteMode(catnum)
        _uiState.update { it.copy(selectedMode = preset) }
    }

    fun selectMode(catnum: Int, mode: String) {
        _uiState.update { it.copy(selectedMode = mode) }
        settingsRepo.setSatelliteMode(catnum, mode)
    }

    fun record(satName: String, mode: String, txHz: Long?, rxHz: Long?, myGrid: String) {
        val call = _uiState.value.callsignInput.trim()
        if (call.isEmpty()) return
        val normalizedMode = mode.ifBlank { "FM" }.uppercase(Locale.US)
        // Whole minutes (seconds zeroed): matches the edit dialog's HH:mm granularity and the
        // minute-resolution of LoTW reports, so re-saving an unchanged record never looks edited.
        val now = System.currentTimeMillis() / 60_000L * 60_000L
        // kHz granularity (3 decimals in MHz) — enough for operating and for the ADIF export.
        val tx = txHz?.let { (it + 500L) / 1000L * 1000L }
        val rx = rxHz?.let { (it + 500L) / 1000L * 1000L }
        val record = QsoRecord(
            startUtcMillis = now,
            endUtcMillis = now,
            theirCallsign = call,
            myCallsign = _uiState.value.certificateCallsign,
            myGrid = myGrid.take(6).uppercase(Locale.US),
            txFrequencyHz = tx,
            rxFrequencyHz = rx,
            band = frequencyBand(tx),
            rxBand = frequencyBand(rx),
            mode = if (normalizedMode == "FT4") "MFSK" else normalizedMode,
            submode = normalizedMode.takeIf { it == "FT4" }.orEmpty(),
            // Stored under the ARRL name (the tracker's "SAUDISAT 1C" is logged as "SO-50"):
            // one name for the logbook, the ADIF export and the signed record.
            satelliteName = officialSatelliteName(satName, _uiState.value.satelliteCatalog),
            satelliteMode = normalizedMode,
            status = QsoStatus.COMPLETE,
            propagationMode = "SAT"
        )
        viewModelScope.launch { qsoRepository.save(record) }
        _uiState.update { it.copy(callsignInput = "") }
    }

    fun delete(id: Long) = viewModelScope.launch { qsoRepository.delete(id) }

    /**
     * Persist an edited record (frequency/callsign/time/…). The repository keeps
     * the LoTW confirmation state consistent when the contact identity changes.
     */
    fun updateRecord(record: QsoRecord) = viewModelScope.launch { qsoRepository.save(record) }

    /** English social post from the recorded QSOs of the current satellite (last 24h). */
    fun generatePost(satName: String, maxElev: Double) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Records carry the ARRL name while the pass carries the tracker's name, so the
            // operator's own contacts are collected by identity, not by the raw name.
            val identity = satelliteIdentity(satName)
            val list = qsoRepository.records.first()
                .filter { satelliteIdentity(it.satelliteName) == identity }
                .filter { it.startUtcMillis > now - 24 * 3_600_000L }
                .sortedBy { it.startUtcMillis }
            if (list.isEmpty()) return@launch
            val shortName = officialSatelliteName(satName).substringBefore('(').trim().uppercase(Locale.US)
            val utc = SimpleDateFormat("yyyyMMdd|HH:mm'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val firstLine = "🛰️ $shortName | ${utc.format(Date(list.first().startUtcMillis))}"
            val elLine = "🔺 EL ${String.format(Locale.US, "%.1f", maxElev)}° | ${list.size} QSOs"
            val modeLines = list.groupBy { it.displayMode }
                .map { (mode, qsos) -> "📡 $mode: ${qsos.joinToString(" ") { it.theirCallsign }}" }
                .joinToString("\n")
            val currentGrid = settingsRepo.stationPosition.value.qthLocator.take(6)
            val logLine = "📍 Log: ${_uiState.value.stationGrid.take(4)} | Current: $currentGrid"
            val viaLine = "via Look4Sat-BA7OPF — TNX de ${_uiState.value.certificateCallsign}"
            val tag = "#HamRadio #SatelliteQSO #" + shortName.filter { it.isLetterOrDigit() }
            val text = listOf(firstLine, elLine, modeLines, "", logLine, viaLine, tag).joinToString("\n")
            _uiState.update { it.copy(postText = text) }
        }
    }

    fun dismissPost() = _uiState.update { it.copy(postText = null) }

    fun prepareUpload() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "") }
            try {
                val all = qsoRepository.records.first()
                // Only local (non-confirmed) records are candidates for upload;
                // LoTW-imported confirmations are the feedback side.
                val pending = all.filter { !it.lotwConfirmed && !it.lotwUploaded && it.status == QsoStatus.COMPLETE }
                val audit = lotwUploadRepository.audit(pending)
                if (audit.pending == 0) {
                    val msg = when {
                        audit.unavailable > 0 -> unavailableUploadSummary(
                            audit.unavailable, audit.reasons, audit.details, audit.duplicates, audit.incomplete
                        )

                        audit.unknown > 0 -> "${audit.unknown} QSO(s) had an unknown upload result — not retried automatically"
                        else -> "No pending QSOs to upload"
                    }
                    _uiState.update { it.copy(busy = false, message = msg) }
                    return@launch
                }
                val preview = lotwUploadRepository.prepare(pending, false)
                // Only the records that actually made it into the TQ8 may be
                // marked uploaded later — never the whole candidate list.
                lastUploadedIds = preview.submittedIds
                _uiState.update { it.copy(busy = false, preview = preview) }
            } catch (e: LoTWOperationException) {
                _uiState.update { it.copy(busy = false, message = "Upload unavailable: ${e.reason}") }
            } catch (_: Exception) {
                _uiState.update { it.copy(busy = false, message = "Upload failed") }
            }
        }
    }

    fun confirmUpload() {
        val preview = _uiState.value.preview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = lotwUploadRepository.upload(preview.id)
            if (result is LoTWUploadResult.Accepted && lastUploadedIds.isNotEmpty()) {
                // Mark the submitted QSOs as uploaded (distinct from confirmed).
                qsoRepository.markUploaded(lastUploadedIds)
                lastUploadedIds = emptyList()
            }
            val msg = when (result) {
                is LoTWUploadResult.Accepted -> "Uploaded ${result.count} QSO(s) — accepted by LoTW"
                is LoTWUploadResult.Rejected -> "Rejected: ${result.message}"
                LoTWUploadResult.Unknown -> "Unknown result — will not auto-retry"
                LoTWUploadResult.ExpiredPreview -> "Preview expired — tap upload again"
            }
            _uiState.update { it.copy(busy = false, preview = null, message = msg) }
        }
    }

    fun dismissPreview() = _uiState.update { it.copy(preview = null) }

    fun clearMessage() = _uiState.update { it.copy(message = "") }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                LogViewModel(container.qsoRepository, container.lotwUploadRepository, container.settingsRepo)
            }
        }
    }
}
