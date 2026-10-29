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
import com.rtbishop.look4sat.core.domain.logbook.isSatellite
import com.rtbishop.look4sat.core.domain.logbook.operatorCallsign
import com.rtbishop.look4sat.core.domain.logbook.operatorGrids
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.model.LogbookSyncProvider
import com.rtbishop.look4sat.core.domain.repository.ILoTWRepository
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.LoTWCertificate
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWProgress
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode
import com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo
import com.rtbishop.look4sat.core.domain.repository.IWavelogSyncRepository
import com.rtbishop.look4sat.core.domain.repository.IWavelogUploadRepository
import com.rtbishop.look4sat.core.domain.repository.applyWavelogSyncResult
import com.rtbishop.look4sat.core.domain.repository.resolveWavelogSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldPromoteWavelogFullSync
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadAudit
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwCursorApi
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
        if (call.isBlank()) throw LogbookValidationException(LogbookError.CALLSIGN_REQUIRED)
        if (!call.matches(Regex("[A-Z0-9]+(/[A-Z0-9]+)*")) ||
            call.none(Char::isLetter) || call.none(Char::isDigit)
        ) {
            throw LogbookValidationException(LogbookError.CALLSIGN_INVALID)
        }
        if (mode.isBlank()) throw LogbookValidationException(LogbookError.MODE_REQUIRED)
        fun frequency(value: String): Long? {
            if (value.isBlank()) return null
            return value.toLongOrNull()?.takeIf { it > 0 }
                ?: throw LogbookValidationException(LogbookError.FREQUENCY_INVALID)
        }
        val tx = frequency(txFrequencyHz)
        val rx = frequency(rxFrequencyHz)
        if (!utcText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))) {
            throw LogbookValidationException(LogbookError.UTC_INVALID)
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val started = if (utcText == formatter.format(Date(startUtcMillis))) startUtcMillis
        else runCatching { formatter.parse(utcText)?.time }.getOrNull()
            ?: throw LogbookValidationException(LogbookError.UTC_INVALID)
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

typealias LogbookFilter = com.rtbishop.look4sat.core.domain.logbook.LogbookFilter

internal const val SATELLITE_MODE_FILTER = "__SATELLITE__"

internal fun newLogbookEditor(
    startUtcMillis: Long,
    defaultCallsign: String,
    defaultGrid: String,
    certificateCallsign: String,
    lotwStationGrid: String,
    wavelogSettings: WavelogUploadSettings
): LogbookEditor = LogbookEditor(
    startUtcMillis = startUtcMillis,
    myCallsign = operatorCallsign(certificateCallsign, wavelogSettings.stationCallsign).ifBlank { defaultCallsign },
    myGrid = operatorGrids(lotwStationGrid, wavelogSettings.stationGrid).firstOrNull().orEmpty().ifBlank { defaultGrid }
)

enum class LogbookError {
    CALLSIGN_REQUIRED,
    CALLSIGN_INVALID,
    MODE_REQUIRED,
    FREQUENCY_INVALID,
    UTC_INVALID,
    IMPORT_READ,
    EXPORT_WRITE,
    EXPORT_PREPARE,
    SAVE,
    DELETE,
    CLEAR_ALL,
    IMPORT
}

sealed interface LoTWDownloadError {
    data object NotConfigured : LoTWDownloadError
    data object BadCredentials : LoTWDownloadError
    data object RateLimited : LoTWDownloadError
    data object Timeout : LoTWDownloadError
    data object Storage : LoTWDownloadError
    data class Network(val detail: String) : LoTWDownloadError
}

data class LoTWDownloadSummary(
    val downloaded: Int,
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val confirmedGrids: Int
)

data class LogbookState(
    val records: List<QsoRecord> = emptyList(),
    val editor: LogbookEditor? = null,
    val deleteCandidate: QsoRecord? = null,
    val importResult: AdifImportResult? = null,
    val error: LogbookError? = null,
    val query: String = "",
    val modeFilter: String = SATELLITE_MODE_FILTER,
    val confirmationFilter: LogbookFilter = LogbookFilter.ALL,
    val groupByCallsign: Boolean = false,
    val isBusy: Boolean = false,
    val showLoTW: Boolean = false,
    val lotwSyncing: Boolean = false,
    val lotwSettings: LoTWSettings = LoTWSettings(),
    val syncProvider: LogbookSyncProvider = LogbookSyncProvider.LOTW,
    val logbookSourceLock: LogbookSyncProvider? = null,
    val providerSwitchConflict: LogbookSyncProvider? = null,
    val wavelogSettings: WavelogUploadSettings = WavelogUploadSettings(),
    val wavelogStations: List<WavelogStationInfo> = emptyList(),
    val wavelogRights: String = "",
    val wavelogProbeBusy: Boolean = false,
    val wavelogSyncing: Boolean = false,
    val wavelogLastSyncEpochMs: Long = 0L,
    val wavelogMessage: String? = null,
    val lotwSyncMode: LoTWSyncMode? = null,
    val lotwProgress: LoTWProgress? = null,
    val lotwDownloadError: LoTWDownloadError? = null,
    val lotwDownloadSummary: LoTWDownloadSummary? = null,
    val workedGridsCount: Int = 0,
    val lotwCertificate: LoTWCertificate? = null,
    val lotwPreview: LoTWUploadPreview? = null,
    val lotwUploadResult: LoTWUploadResult? = null,
    val lotwProblem: LoTWOperationException? = null,
    val lotwDefaultGrid: String = "",
    val lotwStation: LoTWStation? = null,
    val lotwAudit: LoTWUploadAudit? = null,
    val lotwAuditing: Boolean = false,
    val lotwProfileSaved: Boolean = false,
    val showStation: Boolean = false,
    val stationCallsign: String = "",
    val showClearAllConfirmation: Boolean = false
) {
    val filteredRecords: List<QsoRecord> get() = com.rtbishop.look4sat.core.domain.logbook.filterRecords(
        records = if (modeFilter == SATELLITE_MODE_FILTER) records.filter { it.isSatellite } else records,
        query = query,
        modeFilter = modeFilter.takeUnless { it == SATELLITE_MODE_FILTER }.orEmpty(),
        confirmation = confirmationFilter
    )
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
    data object RequestClearAll : LogbookAction
    data object ConfirmClearAll : LogbookAction
    data object DismissClearAll : LogbookAction
    data class Import(val content: String) : LogbookAction
    data object ClearNotice : LogbookAction
    data class Search(val value: String) : LogbookAction
    data class ModeFilter(val value: String) : LogbookAction
    data class ConfirmationFilter(val value: LogbookFilter) : LogbookAction
    data object ToggleGrouping : LogbookAction
    data object ShowLoTW : LogbookAction
    data object DismissLoTW : LogbookAction
    data object DismissLoTWAfterBackgroundStart : LogbookAction
    data class SelectSyncProvider(val provider: LogbookSyncProvider) : LogbookAction
    data object DismissProviderSwitchConflict : LogbookAction
    data class SaveWavelogSettings(val settings: WavelogUploadSettings) : LogbookAction
    data class FetchWavelogStations(val url: String, val apiKey: String) : LogbookAction
    data class SelectWavelogStation(val station: WavelogStationInfo) : LogbookAction
    data class SyncWavelog(val mode: WavelogSyncMode) : LogbookAction
    data object ClearWavelogConfig : LogbookAction
    data class SyncLoTW(val settings: LoTWSettings, val mode: LoTWSyncMode) : LogbookAction
    data class SaveLoTWProfile(
        val certificate: ByteArray?,
        val password: CharArray?,
        val station: LoTWStation
    ) : LogbookAction
    data object RemoveLoTWCertificate : LogbookAction
    data object RefreshLoTWUpload : LogbookAction
    data class PrepareLoTWUpload(val resubmit: Boolean) : LogbookAction
    data object DismissLoTWPreview : LogbookAction
    data object ConfirmLoTWUpload : LogbookAction
    data object CancelLoTW : LogbookAction
    data class Error(val error: LogbookError) : LogbookAction
    data object ShowStation : LogbookAction
    data object DismissStation : LogbookAction
    data class StationCallsign(val value: String) : LogbookAction
    data object SaveStation : LogbookAction
}

private fun getExistingLogbookSourceLock(settingsRepo: ISettingsRepo): LogbookSyncProvider? {
    settingsRepo.getLogbookSourceLock()?.let { return it }
    val lotwWasSynced = settingsRepo.getLastLotwSyncDate().isNotBlank() ||
        settingsRepo.getLastLotwSyncCallsign().isNotBlank()
    val wavelogWasSynced = settingsRepo.getLastWavelogSyncEpochMs() > 0L ||
        settingsRepo.getWavelogSyncCursors().isNotEmpty()
    return when {
        lotwWasSynced && !wavelogWasSynced -> LogbookSyncProvider.LOTW
        wavelogWasSynced && !lotwWasSynced -> LogbookSyncProvider.WAVELOG
        lotwWasSynced && wavelogWasSynced -> settingsRepo.logbookSyncProvider.value
        else -> null
    }
}

class LogbookViewModel(
    private val repository: IQsoRepository,
    private val nowMillis: () -> Long,
    private val defaultCallsign: () -> String,
    private val defaultGrid: () -> String,
    private val settingsRepo: ISettingsRepo,
    private val lotwRepository: ILoTWRepository,
    private val lotwUploadRepository: ILoTWUploadRepository,
    private val wavelogSyncRepository: IWavelogSyncRepository,
    private val wavelogUploadRepository: IWavelogUploadRepository,
    private val updateDefaultCallsign: (String) -> Unit
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        LogbookState(
            lotwSettings = settingsRepo.lotwSettings.value,
            syncProvider = getExistingLogbookSourceLock(settingsRepo) ?: settingsRepo.logbookSyncProvider.value,
            logbookSourceLock = getExistingLogbookSourceLock(settingsRepo),
            wavelogSettings = settingsRepo.wavelogUploadSettings.value,
            wavelogStations = settingsRepo.getWavelogStations(),
            wavelogLastSyncEpochMs = settingsRepo.getLastWavelogSyncEpochMs(),
            workedGridsCount = settingsRepo.getWorkedGrids().size
        )
    )
    val state: StateFlow<LogbookState> = mutableState
    private var syncJob: Job? = null
    private var auditJob: Job? = null
    private var auditGeneration = 0

    init {
        mutableState.value.logbookSourceLock?.let { lock ->
            if (settingsRepo.getLogbookSourceLock() == null) {
                settingsRepo.updateLogbookSourceLock(lock)
            }
            if (settingsRepo.logbookSyncProvider.value != lock) {
                settingsRepo.updateLogbookSyncProvider(lock)
            }
        }
        viewModelScope.launch {
            settingsRepo.logbookSourceLockFlow.collect { lock ->
                val resolved = lock ?: getExistingLogbookSourceLock(settingsRepo)
                if (lock == null && resolved != null && settingsRepo.getLogbookSourceLock() == null) {
                    settingsRepo.updateLogbookSourceLock(resolved)
                }
                mutableState.update { it.copy(logbookSourceLock = resolved) }
            }
        }
        viewModelScope.launch {
            repository.records.collect { records ->
                mutableState.update { it.copy(records = records) }
                if (mutableState.value.showLoTW && settingsRepo.logbookSyncProvider.value == LogbookSyncProvider.LOTW) {
                    refreshLoTWAudit()
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.lotwSettings.collect { settings ->
                mutableState.update { it.copy(lotwSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.logbookSyncProvider.collect { provider ->
                mutableState.update { it.copy(syncProvider = provider) }
            }
        }
        viewModelScope.launch {
            settingsRepo.wavelogUploadSettings.collect { settings ->
                mutableState.update { it.copy(wavelogSettings = settings) }
            }
        }
        viewModelScope.launch {
            LogbookBackgroundSyncController.status.collect { background ->
                val provider = background.provider ?: return@collect
                if (background.isRunning && provider == LogbookSyncProvider.LOTW) {
                    auditJob?.cancel()
                }
                mutableState.update { current ->
                    val isLotw = provider == LogbookSyncProvider.LOTW
                    val isWavelog = provider == LogbookSyncProvider.WAVELOG
                    current.copy(
                        lotwSyncing = if (isLotw) background.isRunning else current.lotwSyncing,
                        lotwSyncMode = if (isLotw) background.lotwMode.takeIf { background.isRunning } else current.lotwSyncMode,
                        lotwProgress = if (isLotw) background.progress.takeIf { background.isRunning } else current.lotwProgress,
                        lotwDownloadError = if (isLotw) background.lotwDownloadError else current.lotwDownloadError,
                        lotwDownloadSummary = when {
                            isLotw && background.isRunning -> null
                            isLotw && background.succeeded == true -> background.lotwDownloadSummary ?: current.lotwDownloadSummary
                            else -> current.lotwDownloadSummary
                        },
                        lotwAudit = if (isLotw && background.isRunning) null else current.lotwAudit,
                        lotwAuditing = if (isLotw && background.isRunning) false else current.lotwAuditing,
                        wavelogSyncing = if (isWavelog) background.isRunning else current.wavelogSyncing,
                        wavelogMessage = when {
                            isWavelog && background.isRunning -> null
                            isWavelog && background.succeeded != null -> background.message
                            else -> current.wavelogMessage
                        },
                        workedGridsCount = background.workedGridsCount ?: current.workedGridsCount
                    )
                }
            }
        }
    }

    fun onAction(action: LogbookAction) {
        when (action) {
            LogbookAction.Add -> addEditor()
            is LogbookAction.Edit -> mutableState.update { it.copy(editor = action.record.toEditor(), error = null) }
            is LogbookAction.Update -> mutableState.update { it.copy(editor = action.editor) }
            LogbookAction.Save -> saveEditor()
            LogbookAction.DismissEditor -> if (!mutableState.value.isBusy) {
                mutableState.update { it.copy(editor = null, error = null) }
            }
            is LogbookAction.RequestDelete -> mutableState.update { it.copy(deleteCandidate = action.record) }
            LogbookAction.ConfirmDelete -> deleteCandidate()
            LogbookAction.DismissDelete -> mutableState.update { it.copy(deleteCandidate = null) }
            LogbookAction.RequestClearAll -> if (mutableState.value.records.isNotEmpty() && !mutableState.value.isBusy) {
                mutableState.update { it.copy(showClearAllConfirmation = true) }
            }
            LogbookAction.ConfirmClearAll -> clearAllRecords()
            LogbookAction.DismissClearAll -> mutableState.update { it.copy(showClearAllConfirmation = false) }
            is LogbookAction.Import -> importAdi(action.content)
            LogbookAction.ClearNotice -> mutableState.update {
                it.copy(importResult = null, error = null)
            }
            is LogbookAction.Search -> mutableState.update { it.copy(query = action.value) }
            is LogbookAction.ModeFilter -> mutableState.update { it.copy(modeFilter = action.value) }
            is LogbookAction.ConfirmationFilter -> mutableState.update { it.copy(confirmationFilter = action.value) }
            LogbookAction.ToggleGrouping -> mutableState.update { it.copy(groupByCallsign = !it.groupByCallsign) }
            LogbookAction.ShowLoTW -> {
                mutableState.update {
                    it.copy(
                        showLoTW = true,
                        wavelogStations = settingsRepo.getWavelogStations(),
                        wavelogLastSyncEpochMs = settingsRepo.getLastWavelogSyncEpochMs(),
                        lotwProblem = null,
                        lotwDownloadError = null,
                        workedGridsCount = settingsRepo.getWorkedGrids().size,
                        lotwDefaultGrid = defaultGrid(),
                        lotwProfileSaved = false
                    )
                }
                if (settingsRepo.logbookSyncProvider.value == LogbookSyncProvider.LOTW) {
                    viewModelScope.launch {
                        try {
                            val certificate = lotwUploadRepository.certificate()
                            val station = lotwUploadRepository.station()
                            mutableState.update { it.copy(lotwCertificate = certificate, lotwStation = station) }
                            refreshLoTWAudit()
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { mutableState.update { it.copy(lotwProblem = LoTWOperationException(LoTWProblem.STORAGE)) } }
                    }
                }
            }
            LogbookAction.DismissLoTW,
            LogbookAction.DismissLoTWAfterBackgroundStart -> {
                lotwUploadRepository.discardPreview()
                auditJob?.cancel()
                mutableState.update { it.copy(showLoTW = false, lotwAudit = null, lotwAuditing = false) }
            }
            is LogbookAction.SelectSyncProvider -> {
                val state = mutableState.value
                if (!state.lotwSyncing && !state.lotwAuditing &&
                    !state.wavelogSyncing && !state.wavelogProbeBusy
                ) {
                    val lock = state.logbookSourceLock ?: settingsRepo.getLogbookSourceLock()
                    if (lock != null && action.provider != lock) {
                        mutableState.update { it.copy(providerSwitchConflict = action.provider) }
                    } else {
                        settingsRepo.updateLogbookSyncProvider(action.provider)
                        mutableState.update {
                            it.copy(
                                providerSwitchConflict = null,
                                wavelogMessage = null,
                                lotwDownloadError = null
                            )
                        }
                    }
                }
            }
            LogbookAction.DismissProviderSwitchConflict -> mutableState.update {
                it.copy(providerSwitchConflict = null)
            }
            is LogbookAction.SaveWavelogSettings -> {
                settingsRepo.updateWavelogUploadSettings(action.settings.copy(
                    url = action.settings.url.trim(), apiKey = action.settings.apiKey.trim()
                ))
                mutableState.update { it.copy(wavelogMessage = null) }
            }
            is LogbookAction.FetchWavelogStations -> fetchWavelogStations(action.url, action.apiKey)
            is LogbookAction.SelectWavelogStation -> {
                val current = settingsRepo.wavelogUploadSettings.value
                settingsRepo.updateWavelogUploadSettings(current.copy(
                    stationId = action.station.id,
                    stationName = action.station.name,
                    stationCallsign = action.station.callsign,
                    stationGrid = action.station.grid
                ))
                mutableState.update { it.copy(wavelogMessage = null) }
            }
            is LogbookAction.SyncWavelog -> syncWavelog(action.mode)
            LogbookAction.ClearWavelogConfig -> {
                settingsRepo.updateWavelogUploadSettings(WavelogUploadSettings())
                settingsRepo.setWavelogStations(emptyList())
                mutableState.update { it.copy(wavelogStations = emptyList(), wavelogRights = "", wavelogMessage = null) }
            }
            is LogbookAction.SyncLoTW -> syncLoTW(action.settings, action.mode)
            is LogbookAction.SaveLoTWProfile -> lotwOperation {
                val certificate = when {
                    action.certificate != null -> lotwUploadRepository.importCertificate(
                        action.certificate,
                        action.password ?: throw LoTWOperationException(LoTWProblem.CERTIFICATE_PASSWORD)
                    )
                    action.password != null -> lotwUploadRepository.saveCertificatePassword(action.password)
                    else -> mutableState.value.lotwCertificate
                        ?: throw LoTWOperationException(LoTWProblem.CERTIFICATE_MISSING)
                }
                mutableState.update { it.copy(lotwCertificate = certificate) }
                val station = lotwUploadRepository.saveStation(action.station)
                mutableState.update { it.copy(lotwStation = station, lotwProfileSaved = true) }
                refreshLoTWAudit()
            }
            LogbookAction.RemoveLoTWCertificate -> lotwOperation {
                lotwUploadRepository.removeCertificate()
                auditJob?.cancel()
                mutableState.update { it.copy(lotwCertificate = null, lotwPreview = null, lotwAudit = null, lotwAuditing = false) }
            }
            LogbookAction.RefreshLoTWUpload -> refreshLoTWAudit()
            is LogbookAction.PrepareLoTWUpload -> lotwOperation {
                val preview = lotwUploadRepository.prepare(mutableState.value.filteredRecords, action.resubmit)
                mutableState.update { it.copy(lotwPreview = preview) }
            }
            LogbookAction.DismissLoTWPreview -> if (!mutableState.value.lotwSyncing) {
                lotwUploadRepository.discardPreview()
                mutableState.update { it.copy(lotwPreview = null) }
            }
            LogbookAction.ConfirmLoTWUpload -> {
                val preview = mutableState.value.lotwPreview
                if (preview != null) lotwOperation {
                    mutableState.update { it.copy(lotwPreview = null, lotwUploadResult = LoTWUploadResult.Unknown()) }
                    val result = lotwUploadRepository.upload(preview.id)
                    mutableState.update { it.copy(lotwUploadResult = result) }
                    refreshLoTWAudit()
                }
            }
            LogbookAction.CancelLoTW -> syncJob?.cancel()
            is LogbookAction.Error -> mutableState.update { it.copy(error = action.error) }
            LogbookAction.ShowStation -> mutableState.update {
                it.copy(showStation = true, stationCallsign = defaultCallsign(), error = null)
            }
            LogbookAction.DismissStation -> mutableState.update { it.copy(showStation = false) }
            is LogbookAction.StationCallsign -> mutableState.update { it.copy(stationCallsign = action.value) }
            LogbookAction.SaveStation -> {
                val call = mutableState.value.stationCallsign.trim().uppercase(Locale.US)
                if (com.rtbishop.look4sat.core.domain.logbook.quickLogValidation(call, "59", "59", "FM") == null) {
                    updateDefaultCallsign(call)
                    mutableState.update { it.copy(showStation = false, error = null) }
                } else {
                    mutableState.update {
                        it.copy(
                            error = if (call.isBlank()) LogbookError.CALLSIGN_REQUIRED
                            else LogbookError.CALLSIGN_INVALID
                        )
                    }
                }
            }
        }
    }

    suspend fun exportAdi(includeIncomplete: Boolean = false): String = repository.exportAdi(
        ids = mutableState.value.filteredRecords.mapTo(mutableSetOf()) { it.id },
        includeIncomplete = includeIncomplete
    )

    private fun fetchWavelogStations(url: String, apiKey: String) {
        if (mutableState.value.wavelogProbeBusy || mutableState.value.wavelogSyncing) return
        val cleanUrl = url.trim()
        val cleanKey = apiKey.trim()
        mutableState.update { it.copy(wavelogProbeBusy = true, wavelogMessage = null) }
        viewModelScope.launch {
            try {
                val probe = wavelogUploadRepository.probe(cleanUrl, cleanKey)
                if (probe == null) {
                    mutableState.update { it.copy(wavelogProbeBusy = false, wavelogMessage = "Wavelog probe failed — check URL, API key and network") }
                    return@launch
                }
                if (probe.stations.isEmpty()) {
                    settingsRepo.setWavelogStations(emptyList())
                    mutableState.update {
                        it.copy(
                            wavelogProbeBusy = false,
                            wavelogStations = emptyList(),
                            wavelogRights = probe.rights,
                            wavelogMessage = "No station profiles returned by Wavelog"
                        )
                    }
                    return@launch
                }
                settingsRepo.setWavelogStations(probe.stations)
                val current = settingsRepo.wavelogUploadSettings.value
                val savedStillPresent = current.stationId.isNotBlank() && probe.stations.any { it.id == current.stationId }
                val updatedSettings = when {
                    savedStillPresent -> current.copy(url = cleanUrl, apiKey = cleanKey)
                    probe.stations.size == 1 -> current.copy(
                        url = cleanUrl, apiKey = cleanKey,
                        stationId = probe.stations.single().id,
                        stationName = probe.stations.single().name,
                        stationCallsign = probe.stations.single().callsign,
                        stationGrid = probe.stations.single().grid
                    )
                    else -> current.copy(url = cleanUrl, apiKey = cleanKey)
                }
                settingsRepo.updateWavelogUploadSettings(updatedSettings)
                mutableState.update {
                    it.copy(
                        wavelogProbeBusy = false,
                        wavelogStations = probe.stations,
                        wavelogRights = probe.rights,
                        wavelogMessage = if (probe.stations.isEmpty()) "No station profiles returned by Wavelog" else null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(wavelogProbeBusy = false, wavelogMessage = "Wavelog probe failed — check URL, API key and network") }
            }
        }
    }

    private fun syncWavelog(requested: WavelogSyncMode) {
        if (settingsRepo.logbookSyncProvider.value != LogbookSyncProvider.WAVELOG) return
        if (settingsRepo.getLogbookSourceLock()?.let { it != LogbookSyncProvider.WAVELOG } == true) return
        if (mutableState.value.wavelogSyncing || mutableState.value.lotwSyncing) return
        val settings = settingsRepo.wavelogUploadSettings.value
        if (!settings.isConfigured) {
            mutableState.update { it.copy(wavelogMessage = "Configure Wavelog URL and API key first") }
            return
        }
        val stations = settingsRepo.getWavelogStations().ifEmpty { mutableState.value.wavelogStations }
        if (stations.isEmpty()) {
            mutableState.update { it.copy(wavelogMessage = "Fetch station profiles before syncing") }
            return
        }
        val mode = resolveWavelogSyncMode(settingsRepo.getWavelogSyncUrl(), settings.url, requested)
        val promoted = mode == WavelogSyncMode.Incremental && shouldPromoteWavelogFullSync(settingsRepo.getLastWavelogFullSyncEpochMs())
        val actualMode = if (promoted) WavelogSyncMode.Full else mode
        mutableState.update { it.copy(wavelogSyncing = true, wavelogMessage = null, lotwDownloadError = null) }
        viewModelScope.launch {
            try {
                val fetch = wavelogSyncRepository.fetchNewRecords(
                    settings.url, settings.apiKey, stations, settingsRepo.getWavelogSyncCursors(),
                    full = actualMode == WavelogSyncMode.Full
                )
                if (fetch == null) {
                    mutableState.update { it.copy(wavelogSyncing = false, wavelogMessage = "Wavelog sync failed — check URL, API key and network") }
                    return@launch
                }
                val merge = repository.mergeWavelog(fetch.records,settings.url,
                    actualMode==WavelogSyncMode.Full && fetch.failedStations.isEmpty())
                val cursors = settingsRepo.getWavelogSyncCursors() + fetch.cursors
                applyWavelogSyncResult(
                    settingsRepo = settingsRepo,
                    records = fetch.records,
                    cursors = cursors,
                    url = settings.url,
                    mode = actualMode,
                    failedStations = fetch.failedStations.toSet()
                )
                lockLogbookSource(LogbookSyncProvider.WAVELOG)
                val head = when {
                    promoted -> "Full Wavelog sync (automatic re-scan)"
                    actualMode == WavelogSyncMode.Full -> "Full Wavelog sync"
                    else -> "Incremental Wavelog sync"
                }
                val base = "$head — ${merge.imported} new, ${merge.skipped} already present"
                val message = if (fetch.failedStations.isEmpty()) base else "$base · ${fetch.failedStations.size}/${stations.size} station(s) failed"
                mutableState.update {
                    it.copy(
                        wavelogSyncing = false,
                        wavelogLastSyncEpochMs = settingsRepo.getLastWavelogSyncEpochMs(),
                        workedGridsCount = settingsRepo.getWorkedGrids().size,
                        wavelogMessage = message
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(wavelogSyncing = false, wavelogMessage = "Wavelog sync failed — check URL, API key and network") }
            }
        }
    }

    private fun lockLogbookSource(provider: LogbookSyncProvider) {
        settingsRepo.updateLogbookSourceLock(provider)
        mutableState.update { it.copy(logbookSourceLock = provider, providerSwitchConflict = null) }
    }

    private fun syncLoTW(settings: LoTWSettings, requestedMode: LoTWSyncMode) {
        if (settingsRepo.logbookSyncProvider.value != LogbookSyncProvider.LOTW) return
        if (settingsRepo.getLogbookSourceLock()?.let { it != LogbookSyncProvider.LOTW } == true) return
        if (syncJob?.isActive == true) return
        if (!settings.isConfigured) {
            mutableState.update {
                it.copy(
                    lotwDownloadError = LoTWDownloadError.NotConfigured,
                    lotwDownloadSummary = null
                )
            }
            return
        }
        val callsign = settings.callsign.trim().uppercase(Locale.US)
        mutableState.update {
            it.copy(
                lotwSyncing = true,
                lotwSyncMode = null,
                lotwProgress = null,
                lotwDownloadError = null,
                lotwDownloadSummary = null
            )
        }
        // Run the complete sync pipeline away from the UI thread. StateFlow updates
        // are thread-safe, so progress and completion can be published from IO.
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepo.updateLoTWSettings(settings)
                val mode = resolveLoTWSyncMode(
                    settingsRepo.getLastLotwSyncCallsign(), callsign, requestedMode
                )
                val since = if (mode == LoTWSyncMode.Incremental) {
                    lotwCursorApi(settingsRepo.getLastLotwSyncDate())
                } else ""
                mutableState.update { it.copy(lotwSyncMode = mode) }
                when (val result = lotwRepository.fetchQsos(
                    callsign,
                    settings.password,
                    mode,
                    since
                ) { progress -> mutableState.update { it.copy(lotwProgress = progress) } }) {
                    is LoTWResult.Success -> {
                        val logbook = repository.mergeLoTW(result.records,callsign,mode==LoTWSyncMode.Full)
                        val grids = applyLoTWGridResult(settingsRepo, result, mode, callsign)
                        lockLogbookSource(LogbookSyncProvider.LOTW)
                        mutableState.update {
                            it.copy(
                                workedGridsCount = grids,
                                lotwDownloadSummary = LoTWDownloadSummary(
                                    downloaded = result.downloaded,
                                    imported = logbook.imported,
                                    updated = logbook.updated,
                                    skipped = logbook.skipped,
                                    confirmedGrids = grids
                                )
                            )
                        }
                    }
                    LoTWResult.BadCredentials -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.BadCredentials)
                    }
                    LoTWResult.RateLimited -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.RateLimited)
                    }
                    LoTWResult.Timeout -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.Timeout)
                    }
                    is LoTWResult.NetworkError -> mutableState.update {
                        it.copy(lotwDownloadError = LoTWDownloadError.Network(result.detail))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(lotwDownloadError = LoTWDownloadError.Storage) }
            } finally {
                mutableState.update {
                    it.copy(lotwSyncing = false, lotwSyncMode = null, lotwProgress = null)
                }
            }
        }
    }

    private fun lotwOperation(block: suspend () -> Unit) {
        if (syncJob?.isActive == true) return
        mutableState.update { it.copy(lotwSyncing = true, lotwProblem = null, lotwUploadResult = null, lotwProfileSaved = false) }
        syncJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LoTWOperationException) { mutableState.update { it.copy(lotwProblem = error) } }
            catch (_: Exception) { mutableState.update { it.copy(lotwProblem = LoTWOperationException(LoTWProblem.STORAGE)) } }
            finally { mutableState.update { it.copy(lotwSyncing = false) } }
        }
    }

    private fun refreshLoTWAudit() {
        if (settingsRepo.logbookSyncProvider.value != LogbookSyncProvider.LOTW) {
            auditJob?.cancel()
            mutableState.update { it.copy(lotwAudit = null, lotwAuditing = false) }
            return
        }
        auditJob?.cancel()
        val generation = ++auditGeneration
        val current = mutableState.value
        if (!current.showLoTW || current.lotwSyncing || current.lotwCertificate?.passwordSaved != true || current.lotwStation == null) {
            mutableState.update { it.copy(lotwAudit = null, lotwAuditing = false) }
            return
        }
        val records = current.filteredRecords
        mutableState.update { it.copy(lotwAudit = null, lotwAuditing = true) }
        auditJob = viewModelScope.launch {
            try {
                val audit = lotwUploadRepository.audit(records)
                mutableState.update { it.copy(lotwAudit = audit, lotwProblem = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LoTWOperationException) {
                mutableState.update { it.copy(lotwAudit = null, lotwProblem = error) }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(lotwAudit = null, lotwProblem = LoTWOperationException(LoTWProblem.STORAGE))
                }
            } finally {
                if (generation == auditGeneration) mutableState.update { it.copy(lotwAuditing = false) }
            }
        }
    }

    override fun onCleared() {
        lotwUploadRepository.discardPreview()
        super.onCleared()
    }

    private fun addEditor() = viewModelScope.launch {
        try {
            val editor = newLogbookEditor(
                startUtcMillis = nowMillis(),
                defaultCallsign = defaultCallsign(),
                defaultGrid = defaultGrid(),
                certificateCallsign = lotwUploadRepository.certificate()?.callsign.orEmpty(),
                lotwStationGrid = lotwUploadRepository.station()?.grid.orEmpty(),
                wavelogSettings = settingsRepo.wavelogUploadSettings.value
            )
            mutableState.update { it.copy(editor = editor, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(error = LogbookError.SAVE) }
        }
    }

    private fun saveEditor() = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        val editor = mutableState.value.editor ?: return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            repository.save(editor.toRecord())
            mutableState.update { it.copy(editor = null, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LogbookValidationException) {
            mutableState.update { it.copy(error = error.error) }
        } catch (_: Exception) {
            mutableState.update { it.copy(error = LogbookError.SAVE) }
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
            mutableState.update { it.copy(deleteCandidate = null, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(error = LogbookError.DELETE) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    private fun clearAllRecords() = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            repository.deleteAll()

            // The map's award and VUCC layers are cached separately from the logbook
            // in settings preferences. Clear every QSO-derived map cache as part of
            // the same reset so old worked-grid counts/details cannot survive.
            settingsRepo.setWorkedGrids(emptySet())
            settingsRepo.setWorkedGridQsos(emptyMap())
            settingsRepo.setRoamedGrids(emptySet())

            // These cursors describe the data represented by the caches above. Reset
            // them as well so the next LoTW/Wavelog sync performs a full rebuild rather
            // than merging only new confirmations into an intentionally empty cache.
            settingsRepo.setLastLotwSyncDate("")
            settingsRepo.setLastLotwSyncCallsign("")
            settingsRepo.setWavelogSyncCursors(emptyMap())
            settingsRepo.setWavelogSyncUrl("")
            settingsRepo.setLastWavelogSyncEpochMs(0L)
            settingsRepo.setLastWavelogFullSyncEpochMs(0L)
            settingsRepo.updateLogbookSourceLock(null)

            mutableState.update {
                it.copy(
                    showClearAllConfirmation = false,
                    error = null,
                    importResult = null,
                    lotwDownloadSummary = null,
                    lotwDownloadError = null,
                    logbookSourceLock = null,
                    providerSwitchConflict = null,
                    workedGridsCount = 0,
                    query = "",
                    modeFilter = SATELLITE_MODE_FILTER,
                    confirmationFilter = LogbookFilter.ALL,
                    groupByCallsign = false
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(showClearAllConfirmation = false, error = LogbookError.CLEAR_ALL) }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    private fun importAdi(content: String) = viewModelScope.launch {
        if (mutableState.value.isBusy) return@launch
        mutableState.update { it.copy(isBusy = true) }
        try {
            val result = repository.importAdi(content)
            mutableState.update { it.copy(importResult = result, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(error = LogbookError.IMPORT) }
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
                    settingsRepo = container.settingsRepo,
                    lotwRepository = container.lotwRepo,
                    lotwUploadRepository = container.lotwUploadRepository,
                    wavelogSyncRepository = container.wavelogSyncRepository,
                    wavelogUploadRepository = container.wavelogUploadRepository,
                    updateDefaultCallsign = { call -> container.settingsRepo.updateFt4Settings { it.copy(operatorCallsign = call) } }
                )
            }
        }
    }
}

private class LogbookValidationException(val error: LogbookError) : IllegalArgumentException()

private fun QsoRecord.toEditor() = LogbookEditor(
    id = id,
    startUtcMillis = startUtcMillis,
    theirCallsign = theirCallsign,
    myCallsign = myCallsign,
    theirGrid = theirGrid.ifBlank { theirVuccGrids.firstOrNull().orEmpty() },
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
