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
    val showStation: Boolean = false,
    val stationCallsign: String = ""
) {
    val filteredRecords: List<QsoRecord> get() = records.filter { record ->
        (query.isBlank() || listOf(record.theirCallsign, record.satelliteName, record.theirGrid, record.comment)
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
    data class SyncLoTW(val password: String) : LogbookAction
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
            LogbookAction.ShowLoTW -> mutableState.update {
                it.copy(showLoTW = true, lotwError = null, lotwCallsign = it.lotwCallsign.ifBlank { defaultCallsign() })
            }
            LogbookAction.DismissLoTW -> if (!mutableState.value.lotwSyncing) {
                mutableState.update { it.copy(showLoTW = false) }
            }
            is LogbookAction.LoTWCallsign -> mutableState.update { it.copy(lotwCallsign = action.value) }
            is LogbookAction.SyncLoTW -> syncLoTW(action.password)
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

    private fun syncLoTW(password: String) {
        if (syncJob?.isActive == true) return
        val callsign = mutableState.value.lotwCallsign
        mutableState.update { it.copy(lotwSyncing = true, lotwError = null, lotwResult = null) }
        syncJob = viewModelScope.launch {
            try {
                when (val result = lotwRepository.fetchConfirmedQsos(callsign, password)) {
                    is LoTWResult.Success -> {
                        val imported = repository.mergeConfirmed(result.records)
                        mutableState.update { it.copy(lotwResult = imported, showLoTW = false) }
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
