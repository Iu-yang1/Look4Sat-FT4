/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.model.WavelogSettings
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISensorsRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.IUpdateRepository
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwCursorApi
import com.rtbishop.look4sat.core.domain.repository.lotwCursorEpochMs
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.IWavelogRepository
import com.rtbishop.look4sat.core.domain.usecase.IShowToast
import com.rtbishop.look4sat.core.domain.utility.VersionComparator
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(
    private val databaseRepo: IDatabaseRepo,
    private val settingsRepo: ISettingsRepo,
    private val updateRepo: IUpdateRepository,
    private val wavelogRepo: IWavelogRepository,
    private val lotwRepo: com.rtbishop.look4sat.core.domain.repository.ILoTWRepository,
    private val qsoRepository: com.rtbishop.look4sat.core.domain.logbook.IQsoRepository,
    private val lotwUploadRepository: com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository,
    private val sensorsRepo: ISensorsRepo,
    private val apkFile: File,
    private val showToast: IShowToast
) : ViewModel() {

    private val defaultPosSettings = PositionSettings(false, settingsRepo.stationPosition.value, 0)
    private val defaultDataSettings = DataSettings(false, 0, 0, 0L)
    /** In-flight LoTW sync job, cancelled by CancelLoTWSync. */
    private var lotwSyncJob: kotlinx.coroutines.Job? = null
    /** 指南针校准: 传感器原始方位(未加磁偏角/偏置), 校准对话框实时显示校正后航向. */
    private var rawCompassHeading = sensorsRepo.sensorData.value.first
    private val _uiState = MutableStateFlow(
        SettingsState(
            appVersionName = settingsRepo.appVersionName,
            positionSettings = defaultPosSettings,
            dataSettings = defaultDataSettings,
            otherSettings = settingsRepo.otherSettings.value,
            rcSettings = settingsRepo.rcSettings.value,
            radioControlSettings = settingsRepo.radioControlSettings.value,
            dataSourcesSettings = settingsRepo.dataSourcesSettings.value,
            dataSourcesStatus = settingsRepo.dataSourcesStatus.value,
            wavelogSettings = settingsRepo.wavelogSettings.value,
            workedGridsCount = settingsRepo.getWorkedGrids().size,
            compassAccuracy = sensorsRepo.compassAccuracy.value,
            compassHeadingDegrees = correctedCompassHeading(),
            lotwLastSyncEpochMs = lotwCursorEpochMs(settingsRepo.getLastLotwSyncDate())
        )
    )

    val uiState: StateFlow<SettingsState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepo.stationPosition.collect { geoPos ->
                _uiState.update {
                    it.copy(
                        positionSettings = it.positionSettings.copy(isUpdating = false, stationPos = geoPos),
                        compassHeadingDegrees = correctedCompassHeading()
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.databaseState.collect { state ->
                _uiState.update {
                    it.copy(
                        dataSettings = it.dataSettings.copy(
                            isUpdating = false,
                            entriesTotal = state.numberOfSatellites,
                            radiosTotal = state.numberOfRadios,
                            timestamp = state.updateTimestamp
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.rcSettings.collect { settings ->
                _uiState.update { it.copy(rcSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.otherSettings.collect { settings ->
                _uiState.update {
                    it.copy(otherSettings = settings, compassHeadingDegrees = correctedCompassHeading())
                }
            }
        }
        viewModelScope.launch {
            sensorsRepo.compassAccuracy.collect { accuracy ->
                _uiState.update { it.copy(compassAccuracy = accuracy) }
            }
        }
        viewModelScope.launch {
            sensorsRepo.sensorData.collect { orientation ->
                rawCompassHeading = orientation.first
                _uiState.update { it.copy(compassHeadingDegrees = correctedCompassHeading()) }
            }
        }
        viewModelScope.launch {
            settingsRepo.dataSourcesSettings.collect { settings ->
                _uiState.update { it.copy(dataSourcesSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.dataSourcesStatus.collect { status ->
                _uiState.update { it.copy(dataSourcesStatus = status) }
            }
        }
        viewModelScope.launch {
            settingsRepo.radioControlSettings.collect { settings ->
                _uiState.update { it.copy(radioControlSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.wavelogSettings.collect { settings ->
                _uiState.update { it.copy(wavelogSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.lotwSettings.collect { settings ->
                _uiState.update { it.copy(lotwSettings = settings) }
            }
        }
        viewModelScope.launch {
            qsoRepository.records.collect { records ->
                _uiState.update { it.copy(logbookRecords = records) }
            }
        }
    }


    fun onAction(action: SettingsAction) {
        when (action) {
            // Position
            SettingsAction.SetGpsPosition -> setGpsPosition()
            is SettingsAction.SetGeoPosition -> setGeoPosition(action.latitude, action.longitude)
            is SettingsAction.SetQthPosition -> setQthPosition(action.locator)
            SettingsAction.DismissPosMessages -> dismissPosMessage()
            // Data
            SettingsAction.UpdateFromWeb -> runDataUpdate { databaseRepo.updateFromRemote() }
            is SettingsAction.UpdateTLEFromFile -> runManualImport(action.invalidFileMessage) {
                databaseRepo.updateTLEFromFile(action.uri)
            }
            is SettingsAction.UpdateTransceiversFromFile -> runManualImport(action.invalidFileMessage) {
                databaseRepo.updateTransceiversFromFile(action.uri)
            }
            SettingsAction.ClearAllData -> viewModelScope.launch { databaseRepo.clearAllData() }
            // Toggles
            is SettingsAction.ToggleUtc -> settingsRepo.updateOtherSettings { it.copy(stateOfUtc = action.value) }
            is SettingsAction.ToggleUpdate -> settingsRepo.updateOtherSettings { it.copy(stateOfAutoUpdate = action.value) }
            is SettingsAction.ToggleAutoLotwSync -> settingsRepo.updateOtherSettings { it.copy(stateOfAutoLotwSync = action.value) }
            is SettingsAction.ToggleSweep -> settingsRepo.updateOtherSettings { it.copy(stateOfSweep = action.value) }
            is SettingsAction.ToggleSensor -> settingsRepo.updateOtherSettings { it.copy(stateOfSensors = action.value) }
            SettingsAction.StartCompassCalibration -> sensorsRepo.enableSensor()
            SettingsAction.StopCompassCalibration -> sensorsRepo.disableSensor()
            is SettingsAction.SetCompassOffset -> settingsRepo.updateOtherSettings {
                it.copy(compassOffsetDegrees = action.degrees.coerceIn(-180f, 180f))
            }
            is SettingsAction.ToggleLightTheme -> settingsRepo.updateOtherSettings { it.copy(stateOfLightTheme = action.value) }
            is SettingsAction.ToggleNightMode -> settingsRepo.updateOtherSettings { it.copy(stateOfNightMode = action.value) }
            is SettingsAction.UpdateMapSettings -> settingsRepo.updateOtherSettings {
                it.copy(mapSource = action.mapSource, tiandituKey = action.tiandituKey)
            }
            // Remote control & data sources
            is SettingsAction.UpdateRC -> settingsRepo.updateRCSettings(action.settings)
            is SettingsAction.UpdateRadioControl -> settingsRepo.updateRadioControlSettings(action.settings)
            is SettingsAction.UpdateDataSources -> settingsRepo.updateDataSourcesSettings(action.settings)
            // Wavelog worked grids
            is SettingsAction.UpdateWavelog -> settingsRepo.updateWavelogSettings(action.settings)
            is SettingsAction.SyncWorkedGrids -> syncWorkedGrids(action.settings)
            // LoTW confirmed grids
            is SettingsAction.UpdateLoTW -> settingsRepo.updateLoTWSettings(action.settings)
            is SettingsAction.SyncLoTWGrids -> syncLoTWGrids(action.settings, action.mode)
            SettingsAction.CancelLoTWSync -> cancelLoTWSync()
            // Logbook
            SettingsAction.RefreshLogbook -> refreshLogbook()
            is SettingsAction.DeleteLogbookRecord -> viewModelScope.launch { qsoRepository.delete(action.id) }
            SettingsAction.PrepareLogbookUpload -> prepareLogbookUpload()
            SettingsAction.ConfirmLogbookUpload -> confirmLogbookUpload()
            SettingsAction.DismissLogbookPreview -> dismissLogbookPreview()
            SettingsAction.ClearLogbookMessage -> clearLogbookMessage()
            // LoTW upload configuration
            SettingsAction.LoadLoTWUploadStatus -> loadLoTWUploadStatus()
            is SettingsAction.ImportLoTWCertificate -> importLoTWCertificate(action.bytes, action.password)
            SettingsAction.RemoveLoTWCertificate -> removeLoTWCertificate()
            is SettingsAction.SaveLoTWStation -> saveLoTWStation(action.station)
            // Update checker
            SettingsAction.CheckForUpdate -> checkForUpdate()
            SettingsAction.DownloadUpdate -> downloadUpdate()
            SettingsAction.ConsumeDownloadedApk -> consumeDownloadedApk()
            // System
            is SettingsAction.ShowToast -> showToast(action.message)
        }
    }

    // region Wavelog worked grids

    private fun syncWorkedGrids(settings: WavelogSettings) {
        if (!settings.isConfigured) {
            _uiState.update { it.copy(wavelogMessage = "Wavelog URL/token not configured") }
            return
        }
        // Persist the credentials first, then sync with the freshly-typed values.
        settingsRepo.updateWavelogSettings(settings)
        _uiState.update { it.copy(wavelogSyncing = true, wavelogMessage = null) }
        viewModelScope.launch {
            val grids = wavelogRepo.fetchWorkedGrids(settings.url, settings.token)
            _uiState.update {
                if (grids != null) {
                    settingsRepo.setWorkedGrids(grids)
                    it.copy(wavelogSyncing = false, workedGridsCount = grids.size, wavelogMessage = null)
                } else {
                    it.copy(wavelogSyncing = false, wavelogMessage = "Sync failed — check URL/token/network")
                }
            }
        }
    }

    private fun syncLoTWGrids(
        settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings,
        mode: LoTWSyncMode
    ) {
        if (!settings.isConfigured) {
            _uiState.update { it.copy(lotwError = LoTWError.NotConfigured) }
            return
        }
        // Persist the credentials first, then sync with the freshly-typed values.
        settingsRepo.updateLoTWSettings(settings)
        // Incremental only makes sense for the same callsign as the last sync;
        // a first sync, an unknown/empty record or a callsign change must fall
        // back to a full report so no stale grids from another account linger.
        val callsign = settings.callsign.trim().uppercase()
        val effectiveMode = resolveLoTWSyncMode(settingsRepo.getLastLotwSyncCallsign(), callsign, mode)
        val since = if (effectiveMode == LoTWSyncMode.Incremental) {
            lotwCursorApi(settingsRepo.getLastLotwSyncDate())
        } else ""
        _uiState.update {
            it.copy(lotwSyncing = true, lotwSyncMode = effectiveMode, lotwProgress = null, lotwError = null)
        }
        lotwSyncJob = viewModelScope.launch {
            val result = lotwRepo.fetchConfirmedGridQsos(callsign, settings.password, since) { progress ->
                _uiState.update { it.copy(lotwProgress = progress) }
            }
            when (result) {
                is LoTWResult.Success -> {
                    // Incremental: merge new grids/QSOs into the stored set (dedup
                    // by call + QSO time); full: the fresh report replaces it all.
                    // Shared with the automatic sync on app start (MainApplication).
                    val mergedGridsCount = applyLoTWGridResult(settingsRepo, result, effectiveMode, callsign)
                    // Feed the logbook: imported confirmations show up in 日志本 and
                    // match local uploads (sameConfirmedContact) to mark them confirmed.
                    viewModelScope.launch {
                        val confirmed = result.qsos.values.flatten()
                            .map { it.toConfirmedRecord(callsign) }
                        qsoRepository.mergeLoTW(confirmed)
                    }
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            workedGridsCount = mergedGridsCount, lotwError = null,
                            lotwLastSyncEpochMs = lotwCursorEpochMs(settingsRepo.getLastLotwSyncDate())
                        )
                    }
                }
                is LoTWResult.BadCredentials ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.BadCredentials
                        )
                    }
                is LoTWResult.RateLimited ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.RateLimited
                        )
                    }
                is LoTWResult.Timeout ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.Timeout
                        )
                    }
                is LoTWResult.NetworkError ->
                    _uiState.update { state ->
                        state.copy(
                            lotwSyncing = false, lotwSyncMode = null, lotwProgress = null,
                            lotwError = LoTWError.Network(result.detail)
                        )
                    }
            }
        }
    }

    /** Abort the in-flight LoTW sync job and reset its progress state. */
    private fun cancelLoTWSync() {
        lotwSyncJob?.cancel()
        lotwSyncJob = null
        _uiState.update { it.copy(lotwSyncing = false, lotwSyncMode = null, lotwProgress = null) }
    }

    // endregion

    // region Logbook + LoTW upload configuration

    private fun refreshLogbook() {
        viewModelScope.launch {
            _uiState.update { it.copy(logbookRecords = qsoRepository.records.first()) }
        }
    }

    // Record ids submitted in the most recent logbook prepare → upload cycle, so a
    // successful POST can mark them "uploaded" (distinct from "confirmed").
    private var lastLogbookUploadIds: List<Long> = emptyList()

    private fun prepareLogbookUpload() {
        if (_uiState.value.logbookUploadBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(logbookUploadBusy = true, logbookUploadMessage = "") }
            try {
                val all = qsoRepository.records.first()
                // Only local (non-confirmed) records are candidates for upload;
                // LoTW-imported confirmations are the feedback side.
                val pending = all.filter { !it.lotwConfirmed && it.status == com.rtbishop.look4sat.core.domain.logbook.QsoStatus.COMPLETE }
                val audit = lotwUploadRepository.audit(pending)
                if (audit.pending == 0) {
                    _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "No pending QSOs to upload") }
                    return@launch
                }
                val preview = lotwUploadRepository.prepare(pending, false)
                lastLogbookUploadIds = pending.map { it.id }
                _uiState.update { it.copy(logbookUploadBusy = false, logbookPreview = preview) }
            } catch (e: com.rtbishop.look4sat.core.domain.repository.LoTWOperationException) {
                _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "Upload unavailable: ${e.reason}") }
            } catch (_: Exception) {
                _uiState.update { it.copy(logbookUploadBusy = false, logbookUploadMessage = "Upload failed") }
            }
        }
    }

    private fun confirmLogbookUpload() {
        val preview = _uiState.value.logbookPreview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(logbookUploadBusy = true) }
            val result = lotwUploadRepository.upload(preview.id)
            if (result is com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Accepted && lastLogbookUploadIds.isNotEmpty()) {
                qsoRepository.markUploaded(lastLogbookUploadIds)
                lastLogbookUploadIds = emptyList()
            }
            val msg = when (result) {
                is com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Accepted -> "Uploaded ${result.count} QSO(s) — accepted by LoTW"
                is com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Rejected -> "Rejected: ${result.message}"
                com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.Unknown -> "Unknown result — will not auto-retry"
                com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult.ExpiredPreview -> "Preview expired — tap upload again"
            }
            _uiState.update { it.copy(logbookUploadBusy = false, logbookPreview = null, logbookUploadMessage = msg) }
        }
    }

    private fun dismissLogbookPreview() = _uiState.update { it.copy(logbookPreview = null) }

    private fun clearLogbookMessage() = _uiState.update { it.copy(logbookUploadMessage = "") }

    private fun loadLoTWUploadStatus() {
        viewModelScope.launch {
            val cert = lotwUploadRepository.certificate()
            val station = lotwUploadRepository.station()
            // Config parsing failure must never crash the dialog — degrade to no meta.
            val meta = cert?.let { runCatching { lotwUploadRepository.stationMeta(it.dxcc) }.getOrNull() }
            _uiState.update { it.copy(lotwCertificate = cert, lotwStation = station, lotwStationMeta = meta, lotwUploadBusy = false) }
        }
    }

    private fun importLoTWCertificate(bytes: ByteArray, password: CharArray) {
        viewModelScope.launch {
            _uiState.update { it.copy(lotwUploadBusy = true, lotwUploadError = null) }
            try {
                val cert = lotwUploadRepository.importCertificate(bytes, password)
                _uiState.update { it.copy(lotwCertificate = cert, lotwUploadBusy = false, lotwUploadError = null) }
            } catch (e: com.rtbishop.look4sat.core.domain.repository.LoTWOperationException) {
                val error = when (e.reason) {
                    com.rtbishop.look4sat.core.domain.repository.LoTWProblem.CERTIFICATE_PASSWORD -> LoTWUploadError.PASSWORD
                    com.rtbishop.look4sat.core.domain.repository.LoTWProblem.CERTIFICATE_EXPIRED -> LoTWUploadError.EXPIRED
                    com.rtbishop.look4sat.core.domain.repository.LoTWProblem.CERTIFICATE_FORMAT -> LoTWUploadError.FORMAT
                    else -> LoTWUploadError.INVALID_FILE
                }
                _uiState.update { it.copy(lotwUploadBusy = false, lotwUploadError = error, lotwUploadErrorDetail = e.detail) }
            } catch (_: Exception) {
                _uiState.update { it.copy(lotwUploadBusy = false, lotwUploadError = LoTWUploadError.UNKNOWN, lotwUploadErrorDetail = "") }
            }
        }
    }

    private fun removeLoTWCertificate() {
        viewModelScope.launch {
            lotwUploadRepository.removeCertificate()
            _uiState.update { it.copy(lotwCertificate = null, lotwStation = null, lotwStationMeta = null) }
        }
    }

    private fun saveLoTWStation(station: com.rtbishop.look4sat.core.domain.repository.LoTWStation) {
        viewModelScope.launch {
            _uiState.update { it.copy(lotwUploadBusy = true) }
            try {
                val saved = lotwUploadRepository.saveStation(station)
                _uiState.update { it.copy(lotwStation = saved, lotwUploadBusy = false) }
            } catch (_: Exception) {
                _uiState.update { it.copy(lotwUploadBusy = false) }
            }
        }
    }

    // endregion

    // region Update checker

    private fun checkForUpdate() {
        _uiState.update { it.copy(updateChecker = it.updateChecker.copy(isChecking = true, errorResId = null)) }
        viewModelScope.launch {
            val release = updateRepo.getLatestRelease()
            val hasUpdate = release != null &&
                VersionComparator.isNewer(release.versionTag, settingsRepo.appVersionName)
            _uiState.update {
                it.copy(
                    updateChecker = it.updateChecker.copy(
                        isChecking = false,
                        release = release,
                        hasUpdate = hasUpdate,
                        errorResId = if (release == null) R.string.update_check_error else null
                    )
                )
            }
        }
    }

    private fun downloadUpdate() {
        val url = _uiState.value.updateChecker.release?.apkUrl ?: return
        _uiState.update { it.copy(updateChecker = it.updateChecker.copy(isDownloading = true, errorResId = null)) }
        viewModelScope.launch {
            val success = updateRepo.downloadApk(url, apkFile)
            _uiState.update {
                it.copy(
                    updateChecker = it.updateChecker.copy(
                        isDownloading = false,
                        apkFile = if (success) apkFile else null,
                        errorResId = if (success) null else R.string.update_check_download_error
                    )
                )
            }
        }
    }

    private fun consumeDownloadedApk() {
        _uiState.update { it.copy(updateChecker = it.updateChecker.copy(apkFile = null)) }
    }

    // endregion

    // region Position helpers — consolidated from 3 near-identical functions

    private fun setGpsPosition() {
        updatePosition(R.string.prefs_loc_gps_error) { settingsRepo.setStationPosition() }
    }

    private fun setGeoPosition(latitude: Double, longitude: Double) {
        updatePosition(R.string.prefs_loc_input_error) { settingsRepo.setStationPosition(latitude, longitude, 0.0) }
    }

    private fun setQthPosition(locator: String) {
        updatePosition(R.string.prefs_loc_qth_error) { settingsRepo.setStationPosition(locator) }
    }

    /**
     * Common position update logic. Calls [action], emits success or error message.
     */
    private inline fun updatePosition(errorResId: Int, action: () -> Boolean) {
        val success = action()
        val resId = if (success) R.string.prefs_loc_success else errorResId
        val isUpdating = success // GPS update is async; geo/qth are immediate
        _uiState.update {
            it.copy(positionSettings = it.positionSettings.copy(isUpdating = isUpdating, messageResId = resId))
        }
    }

    private fun dismissPosMessage() {
        _uiState.update {
            it.copy(positionSettings = it.positionSettings.copy(isUpdating = false, messageResId = 0))
        }
    }

    /** 校正后航向 = 传感器原始方位 + 磁偏角 + 手动偏置. */
    private fun correctedCompassHeading(): Float {
        val declination = sensorsRepo.getMagDeclination(settingsRepo.stationPosition.value)
        val offset = settingsRepo.otherSettings.value.compassOffsetDegrees
        return ((rawCompassHeading + declination + offset) % 360f + 360f) % 360f
    }

    override fun onCleared() {
        sensorsRepo.disableSensor()
        super.onCleared()
    }

    // endregion

    // region Data update helpers — consolidated from 3 near-identical functions

    /**
     * Common data update logic. Sets isUpdating=true, runs the suspending [block],
     * and resets isUpdating=false on failure.
     */
    private fun runDataUpdate(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            _uiState.update {
                it.copy(dataSettings = it.dataSettings.copy(isUpdating = true))
            }
            block()
        } catch (exception: Exception) {
            _uiState.update {
                it.copy(dataSettings = it.dataSettings.copy(isUpdating = false))
            }
            println(exception)
        }
    }

    private fun runManualImport(importErrorMessage: String, block: suspend () -> Int) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(dataSettings = it.dataSettings.copy(isUpdating = true)) }
                if (block() == 0) showToast(importErrorMessage)
            } catch (exception: Exception) {
                _uiState.update { it.copy(dataSettings = it.dataSettings.copy(isUpdating = false)) }
                println(exception)
            }
        }
    }


    // endregion

    companion object {

        fun factory(container: IMainContainer, context: Context) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    databaseRepo = container.databaseRepo,
                    settingsRepo = container.settingsRepo,
                    updateRepo = container.updateRepo,
                    wavelogRepo = container.wavelogRepo,
                    lotwRepo = container.lotwRepo,
                    qsoRepository = container.qsoRepository,
                    lotwUploadRepository = container.lotwUploadRepository,
                    sensorsRepo = container.provideSensorsRepo(),
                    apkFile = File(context.cacheDir, "look4sat-update.apk"),
                    showToast = container.provideShowToast()
                )
            }
        }
    }
}
