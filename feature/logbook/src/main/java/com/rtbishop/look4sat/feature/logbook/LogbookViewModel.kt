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
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogbookEditor(
    val id: Long = 0L,
    val startUtcMillis: Long,
    val theirCallsign: String = "",
    val myCallsign: String = "",
    val theirGrid: String = "",
    val myGrid: String = "",
    val sentReport: String = "",
    val receivedReport: String = "",
    val txFrequencyHz: String = "",
    val rxFrequencyHz: String = "",
    val satelliteName: String = "",
    val transponderName: String = "",
    val satelliteMode: String = "",
    val automatic: Boolean = false,
    val status: QsoStatus = QsoStatus.DRAFT,
    val source: QsoRecord? = null
) {
    fun toRecord(): QsoRecord {
        val call = theirCallsign.trim().uppercase(Locale.US)
        require(call.isNotBlank()) { "Callsign is required" }
        return (source ?: QsoRecord(startUtcMillis = startUtcMillis, theirCallsign = call, myCallsign = "")).copy(
            id = id,
            startUtcMillis = startUtcMillis,
            endUtcMillis = if (status == QsoStatus.COMPLETE) source?.endUtcMillis ?: startUtcMillis else null,
            theirCallsign = call,
            myCallsign = myCallsign.trim().uppercase(Locale.US),
            theirGrid = theirGrid.trim().uppercase(Locale.US),
            myGrid = myGrid.trim().uppercase(Locale.US),
            sentReport = sentReport.trim(),
            receivedReport = receivedReport.trim(),
            txFrequencyHz = txFrequencyHz.toLongOrNull(),
            rxFrequencyHz = rxFrequencyHz.toLongOrNull(),
            satelliteName = satelliteName.trim(),
            transponderName = transponderName.trim(),
            satelliteMode = satelliteMode.trim(),
            automatic = automatic,
            status = status
        )
    }
}

data class LogbookState(
    val records: List<QsoRecord> = emptyList(),
    val editor: LogbookEditor? = null,
    val deleteCandidate: QsoRecord? = null,
    val importResult: AdifImportResult? = null,
    val error: String = ""
)

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
}

class LogbookViewModel(
    private val repository: IQsoRepository,
    private val nowMillis: () -> Long,
    private val defaultCallsign: () -> String,
    private val defaultGrid: () -> String
) : ViewModel() {
    private val mutableState = MutableStateFlow(LogbookState())
    val state: StateFlow<LogbookState> = mutableState

    init {
        viewModelScope.launch {
            repository.records.collect { records -> mutableState.update { it.copy(records = records) } }
        }
    }

    fun onAction(action: LogbookAction) {
        when (action) {
            LogbookAction.Add -> mutableState.update {
                it.copy(editor = LogbookEditor(startUtcMillis = nowMillis(), myCallsign = defaultCallsign(), myGrid = defaultGrid()))
            }
            is LogbookAction.Edit -> mutableState.update { it.copy(editor = action.record.toEditor()) }
            is LogbookAction.Update -> mutableState.update { it.copy(editor = action.editor) }
            LogbookAction.Save -> saveEditor()
            LogbookAction.DismissEditor -> mutableState.update { it.copy(editor = null) }
            is LogbookAction.RequestDelete -> mutableState.update { it.copy(deleteCandidate = action.record) }
            LogbookAction.ConfirmDelete -> deleteCandidate()
            LogbookAction.DismissDelete -> mutableState.update { it.copy(deleteCandidate = null) }
            is LogbookAction.Import -> importAdi(action.content)
            LogbookAction.ClearNotice -> mutableState.update { it.copy(importResult = null, error = "") }
        }
    }

    suspend fun exportAdi(): String = repository.exportAdi()

    private fun saveEditor() = viewModelScope.launch {
        val editor = mutableState.value.editor ?: return@launch
        runCatching { repository.save(editor.toRecord()) }
            .onSuccess { mutableState.update { it.copy(editor = null, error = "") } }
            .onFailure { error -> mutableState.update { it.copy(error = error.message.orEmpty()) } }
    }

    private fun deleteCandidate() = viewModelScope.launch {
        val record = mutableState.value.deleteCandidate ?: return@launch
        runCatching { repository.delete(record.id) }
            .onSuccess { mutableState.update { it.copy(deleteCandidate = null, error = "") } }
            .onFailure { error -> mutableState.update { it.copy(error = error.message.orEmpty()) } }
    }

    private fun importAdi(content: String) = viewModelScope.launch {
        runCatching { repository.importAdi(content) }
            .onSuccess { result -> mutableState.update { it.copy(importResult = result, error = "") } }
            .onFailure { error -> mutableState.update { it.copy(error = error.message.orEmpty()) } }
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                LogbookViewModel(
                    repository = container.qsoRepository,
                    nowMillis = container.disciplinedClock::nowMillis,
                    defaultCallsign = { container.settingsRepo.ft4Settings.value.operatorCallsign },
                    defaultGrid = { container.settingsRepo.stationPosition.value.qthLocator }
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
    satelliteName = satelliteName,
    transponderName = transponderName,
    satelliteMode = satelliteMode,
    automatic = automatic,
    status = status,
    source = this
)
