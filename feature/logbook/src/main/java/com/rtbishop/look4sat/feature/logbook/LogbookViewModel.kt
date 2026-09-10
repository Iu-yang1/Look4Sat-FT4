/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.logbook.AdifImportResult
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.frequencyBand
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ILoTWRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWDownloadRequest
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWCertificate
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogbookEditor(
    val id: Long = 0L,
    val startUtcMillis: Long,
    val utcText: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(startUtcMillis)),
    val theirCallsign: String = "",
    val myCallsign: String = "",
    val theirGrid: String = "",
    val myGrid: String = "",
    val sentReport: String = "",
    val receivedReport: String = "",
    val txFrequencyHz: String = "",
    val rxFrequencyHz: String = "",
    val mode: String = "SSB",
    val submode: String = "",
    val satelliteName: String = "",
    val transponderName: String = "",
    val satelliteMode: String = "",
    val automatic: Boolean = false,
    val status: QsoStatus = QsoStatus.COMPLETE,
    val comment: String = "",
    val source: QsoRecord? = null
) {
    fun toRecord(): QsoRecord {
        val call = theirCallsign.trim().uppercase(Locale.US)
        require(call.isNotBlank()) { "Callsign is required" }
        require(call.matches(Regex("[A-Z0-9]+(/[A-Z0-9]+)*")) && call.any(Char::isLetter) && call.any(Char::isDigit)) {
            "Invalid callsign"
        }
        require(mode.isNotBlank()) { "Mode is required" }
        fun frequency(value: String): Long? {
            if (value.isBlank()) return null
            return value.toLongOrNull()?.takeIf { it > 0 } ?: error("Invalid frequency (Hz)")
        }
        val tx = frequency(txFrequencyHz)
        val rx = frequency(rxFrequencyHz)
        require(utcText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))) { "Invalid UTC date/time" }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val started = if (utcText == formatter.format(Date(startUtcMillis))) startUtcMillis
        else formatter.parse(utcText)?.time ?: error("Invalid UTC date/time")
        return (source ?: QsoRecord(startUtcMillis = startUtcMillis, theirCallsign = call, myCallsign = "")).copy(
            id = id,
            startUtcMillis = started,
            endUtcMillis = if (status == QsoStatus.COMPLETE) source?.endUtcMillis?.takeIf { it >= started } ?: started else null,
            theirCallsign = call,
            myCallsign = myCallsign.trim().uppercase(Locale.US),
            theirGrid = theirGrid.trim().uppercase(Locale.US),
            myGrid = myGrid.trim().uppercase(Locale.US),
            sentReport = sentReport.trim(),
            receivedReport = receivedReport.trim(),
            txFrequencyHz = tx,
            rxFrequencyHz = rx,
            band = frequencyBand(tx).ifBlank { source?.band.orEmpty() },
            rxBand = frequencyBand(rx).ifBlank { source?.rxBand.orEmpty() },
            mode = mode.trim().uppercase(Locale.US),
            submode = submode.trim().uppercase(Locale.US),
            satelliteName = satelliteName.trim(),
            transponderName = transponderName.trim(),
            satelliteMode = satelliteMode.trim(),
            automatic = automatic,
            status = status,
            propagationMode = when {
                satelliteName.isNotBlank() -> "SAT"
                source?.satelliteName?.isNotBlank() == true -> ""
                else -> source?.propagationMode.orEmpty()
            },
            comment = comment.trim()
        )
    }
}

enum class LogbookFilter { ALL, CONFIRMED, UNCONFIRMED, DRAFTS }

data class LogbookState(
    val records: List<QsoRecord> = emptyList(),
    val editor: LogbookEditor? = null,
    val deleteCandidate: QsoRecord? = null,
    val importResult: AdifImportResult? = null,
    val error: String = "",
    val query: String = "",
    val modeFilter: String = "",
    val confirmationFilter: LogbookFilter = LogbookFilter.ALL,
    val groupByCallsign: Boolean = false,
    val isBusy: Boolean = false,
    val showLoTW: Boolean = false,
    val lotwCallsign: String = "",
    val lotwSyncing: Boolean = false,
    val lotwError: LoTWResult? = null,
    val lotwResult: AdifImportResult? = null,
    val lotwDownloaded: Int = 0,
    val lotwMatched: Int = 0,
    val lotwUploadTab: Boolean = false,
    val lotwCertificate: LoTWCertificate? = null,
    val lotwPreview: LoTWUploadPreview? = null,
    val lotwUploadResult: LoTWUploadResult? = null,
    val lotwProblem: LoTWOperationException? = null,
    val lotwDefaultGrid: String = "",
    val lotwStation: LoTWStation? = null,
    val showStation: Boolean = false,
    val stationCallsign: String = ""
) {
    val filteredRecords: List<QsoRecord> get() = records.filter { record ->
        (query.isBlank() || listOf(record.theirCallsign, record.myCallsign, record.satelliteName, record.theirGrid, record.myGrid, record.comment)
            .any { it.contains(query.trim(), true) }) &&
            (modeFilter.isBlank() || record.displayMode == modeFilter) &&
            when (confirmationFilter) {
                LogbookFilter.ALL -> true
                LogbookFilter.CONFIRMED -> record.lotwConfirmed
                LogbookFilter.UNCONFIRMED -> record.status == QsoStatus.COMPLETE && !record.lotwConfirmed
                LogbookFilter.DRAFTS -> record.status != QsoStatus.COMPLETE
            }
    }
}

sealed interface LogbookAction {
    data object Add : LogbookAction
    data class Edit(val record: QsoRecord) : LogbookAction
    data class Update(val editor: LogbookEditor) : LogbookAction
    data object Save : LogbookAction
    data object DismissEditor : LogbookAction
    data class RequestDelete(val record: QsoRecord) : LogbookAction
    data object ConfirmDelete : LogbookAction
    data object DismissDelete : LogbookAction
    data class Import(val content: String) : LogbookAction
    data object ClearNotice : LogbookAction
    data class Search(val value: String) : LogbookAction
    data class ModeFilter(val value: String) : LogbookAction
    data class ConfirmationFilter(val value: LogbookFilter) : LogbookAction
    data object ToggleGrouping : LogbookAction
    data object ShowLoTW : LogbookAction
    data object DismissLoTW : LogbookAction
    data class LoTWCallsign(val value: String) : LogbookAction
    data class SyncLoTW(val password: String, val confirmedOnly: Boolean = false, val satellitesOnly: Boolean = false,
        val since: String = "1900-01-01", val stationCallsign: String = "") : LogbookAction
    data class LoTWTab(val upload: Boolean) : LogbookAction
    data class ImportLoTWCertificate(val data: ByteArray, val password: CharArray) : LogbookAction
    data object RemoveLoTWCertificate : LogbookAction
    data class PrepareLoTWUpload(val station: LoTWStation, val password: CharArray, val resubmit: Boolean) : LogbookAction
    data object DismissLoTWPreview : LogbookAction
    data object ConfirmLoTWUpload : LogbookAction
    data object CancelLoTW : LogbookAction
    data class Error(val message: String) : LogbookAction
    data object ShowStation : LogbookAction
    data object DismissStation : LogbookAction
    data class StationCallsign(val value: String) : LogbookAction
    data object SaveStation : LogbookAction
}

class LogbookViewModel(
    private val repository: IQsoRepository,
    private val nowMillis: () -> Long,
    private val defaultCallsign: () -> String,
    private val defaultGrid: () -> String,
    private val lotwRepository: ILoTWRepository,
    private val lotwUploadRepository: ILoTWUploadRepository,
    private val updateDefaultCallsign: (String) -> Unit
) : ViewModel() {
    private val mutableState = MutableStateFlow(LogbookState())
    val state: StateFlow<LogbookState> = mutableState
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            repository.records.collect { records -> mutableState.update { it.copy(records = records) } }
        }
    }

    fun onAction(action: LogbookAction) {
        when (action) {
            LogbookAction.Add -> mutableState.update {
                it.copy(editor = LogbookEditor(startUtcMillis = nowMillis(), myCallsign = defaultCallsign(), myGrid = defaultGrid()), error = "")
            }
            is LogbookAction.Edit -> mutableState.update { it.copy(editor = action.record.toEditor(), error = "") }
            is LogbookAction.Update -> mutableState.update { it.copy(editor = action.editor) }
            LogbookAction.Save -> saveEditor()
            LogbookAction.DismissEditor -> if (!mutableState.value.isBusy) mutableState.update { it.copy(editor = null, error = "") }
            is LogbookAction.RequestDelete -> mutableState.update { it.copy(deleteCandidate = action.record) }
            LogbookAction.ConfirmDelete -> deleteCandidate()
            LogbookAction.DismissDelete -> mutableState.update { it.copy(deleteCandidate = null) }
            is LogbookAction.Import -> importAdi(action.content)
            LogbookAction.ClearNotice -> mutableState.update { it.copy(importResult = null, lotwResult = null, error = "") }
            is LogbookAction.Search -> mutableState.update { it.copy(query = action.value) }
            is LogbookAction.ModeFilter -> mutableState.update { it.copy(modeFilter = action.value) }
            is LogbookAction.ConfirmationFilter -> mutableState.update { it.copy(confirmationFilter = action.value) }
            LogbookAction.ToggleGrouping -> mutableState.update { it.copy(groupByCallsign = !it.groupByCallsign) }
            LogbookAction.ShowLoTW -> {
                mutableState.update {
                    it.copy(showLoTW = true, lotwError = null, lotwProblem = null, lotwDefaultGrid = defaultGrid(),
                        lotwCallsign = it.lotwCallsign.ifBlank { defaultCallsign() })
                }
                viewModelScope.launch {
                    try {
                        val certificate = lotwUploadRepository.certificate()
                        val station = lotwUploadRepository.station()
                        mutableState.update { it.copy(lotwCertificate = certificate, lotwStation = station) }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(lotwProblem = LoTWOperationException(LoTWProblem.STORAGE)) } }
                }
            }
            LogbookAction.DismissLoTW -> if (!mutableState.value.lotwSyncing) {
                lotwUploadRepository.discardPreview()
                mutableState.update { it.copy(showLoTW = false) }
            }
            is LogbookAction.LoTWCallsign -> mutableState.update { it.copy(lotwCallsign = action.value) }
            is LogbookAction.SyncLoTW -> syncLoTW(action)
            is LogbookAction.LoTWTab -> if (!mutableState.value.lotwSyncing) mutableState.update { it.copy(lotwUploadTab = action.upload) }
            is LogbookAction.ImportLoTWCertificate -> lotwOperation {
                val certificate = lotwUploadRepository.importCertificate(action.data, action.password)
                mutableState.update { it.copy(lotwCertificate = certificate) }
            }
            LogbookAction.RemoveLoTWCertificate -> lotwOperation {
                lotwUploadRepository.removeCertificate()
                mutableState.update { it.copy(lotwCertificate = null, lotwPreview = null) }
            }
            is LogbookAction.PrepareLoTWUpload -> lotwOperation {
                val preview = lotwUploadRepository.prepare(mutableState.value.filteredRecords, action.station, action.password, action.resubmit)
                mutableState.update { it.copy(lotwPreview = preview, lotwStation = action.station) }
            }
            LogbookAction.DismissLoTWPreview -> if (!mutableState.value.lotwSyncing) {
                lotwUploadRepository.discardPreview()
                mutableState.update { it.copy(lotwPreview = null) }
            }
            LogbookAction.ConfirmLoTWUpload -> {
                val preview = mutableState.value.lotwPreview
                if (preview != null) lotwOperation {
                    mutableState.update { it.copy(lotwPreview = null, lotwUploadResult = LoTWUploadResult.Unknown) }
                    val result = lotwUploadRepository.upload(preview.id)
                    mutableState.update { it.copy(lotwUploadResult = result) }
                }
            }
            LogbookAction.CancelLoTW -> syncJob?.cancel()
            is LogbookAction.Error -> mutableState.update { it.copy(error = action.message) }
            LogbookAction.ShowStation -> mutableState.update { it.copy(showStation = true, stationCallsign = defaultCallsign(), error = "") }
            LogbookAction.DismissStation -> mutableState.update { it.copy(showStation = false) }
            is LogbookAction.StationCallsign -> mutableState.update { it.copy(stationCallsign = action.value) }
            LogbookAction.SaveStation -> {
                val call = mutableState.value.stationCallsign.trim().uppercase(Locale.US)
                if (com.rtbishop.look4sat.core.domain.logbook.quickLogValidation(call, "59", "59", "FM") == null) {
                    updateDefaultCallsign(call)
                    mutableState.update { it.copy(showStation = false, error = "") }
                } else mutableState.update { it.copy(error = "Invalid callsign") }
            }
        }
    }

    suspend fun exportAdi(includeIncomplete: Boolean = false): String = repository.exportAdi(
        ids = mutableState.value.filteredRecords.mapTo(mutableSetOf()) { it.id },
        includeIncomplete = includeIncomplete
    )

    private fun syncLoTW(action: LogbookAction.SyncLoTW) {
        if (syncJob?.isActive == true) return
        val callsign = mutableState.value.lotwCallsign
        mutableState.update { it.copy(lotwSyncing = true, lotwError = null, lotwResult = null) }
        syncJob = viewModelScope.launch {
            try {
                when (val result = lotwRepository.download(LoTWDownloadRequest(callsign, action.password,
                    action.confirmedOnly, action.satellitesOnly, action.since, action.stationCallsign))) {
                    is LoTWResult.Success -> {
                        val imported = repository.mergeLoTW(result.records)
                        mutableState.update { it.copy(lotwResult = imported, lotwDownloaded = result.downloaded, lotwMatched = result.records.size) }
                    }
                    else -> mutableState.update { it.copy(lotwError = result) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(lotwError = LoTWResult.NetworkError) }
            } finally {
                mutableState.update { it.copy(lotwSyncing = false) }
            }
        }
    }

    private fun lotwOperation(block: suspend () -> Unit) {
        if (syncJob?.isActive == true) return
        mutableState.update { it.copy(lotwSyncing = true, lotwProblem = null, lotwUploadResult = null) }
        syncJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LoTWOperationException) { mutableState.update { it.copy(lotwProblem = error) } }
            catch (_: Exception) { mutableState.update { it.copy(lotwProblem = LoTWOperationException(LoTWProblem.STORAGE)) } }
            finally { mutableState.update { it.copy(lotwSyncing = false) } }
        }
    }

    override fun onCleared() {
        lotwUploadRepository.discardPreview()
        super.onCleared()
    }

    private fun saveEditor() = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        val editor = mutableState.value.editor ?: return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            repository.save(editor.toRecord())
            mutableState.update { it.copy(editor = null, error = "") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message.orEmpty()) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    private fun deleteCandidate() = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        val record = mutableState.value.deleteCandidate ?: return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            repository.delete(record.id)
            mutableState.update { it.copy(deleteCandidate = null, error = "") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message.orEmpty()) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    private fun importAdi(content: String) = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            val result = repository.importAdi(content)
            mutableState.update { it.copy(importResult = result, error = "") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message.orEmpty()) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                LogbookViewModel(
                    repository = container.qsoRepository,
                    nowMillis = container.disciplinedClock::nowMillis,
                    defaultCallsign = { container.settingsRepo.ft4Settings.value.operatorCallsign },
                    defaultGrid = { container.settingsRepo.stationPosition.value.qthLocator },
                    lotwRepository = container.lotwRepository,
                    lotwUploadRepository = container.lotwUploadRepository,
                    updateDefaultCallsign = { call -> container.settingsRepo.updateFt4Settings { it.copy(operatorCallsign = call) } }
                )
            }
        }
    }
}

private fun QsoRecord.toEditor() = LogbookEditor(
    id = id,
    startUtcMillis = startUtcMillis,
    theirCallsign = theirCallsign,
    myCallsign = myCallsign,
    theirGrid = theirGrid,
    myGrid = myGrid,
    sentReport = sentReport,
    receivedReport = receivedReport,
    txFrequencyHz = txFrequencyHz?.toString().orEmpty(),
    rxFrequencyHz = rxFrequencyHz?.toString().orEmpty(),
    mode = mode,
    submode = submode,
    satelliteName = satelliteName,
    transponderName = transponderName,
    satelliteMode = satelliteMode,
    automatic = automatic,
    status = status,
    comment = comment,
    source = this
)
