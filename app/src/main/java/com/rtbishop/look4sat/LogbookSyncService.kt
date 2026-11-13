/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rtbishop.look4sat.core.domain.model.LogbookSyncProvider
import com.rtbishop.look4sat.core.domain.repository.LoTWPhase
import com.rtbishop.look4sat.core.domain.repository.LoTWProgress
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.applyWavelogSyncResult
import com.rtbishop.look4sat.core.domain.repository.lotwCursorApi
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.resolveWavelogSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldPromoteWavelogFullSync
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.R as CoreR
import com.rtbishop.look4sat.feature.logbook.LogbookBackgroundSyncController
import com.rtbishop.look4sat.feature.logbook.LogbookBackgroundSyncStatus
import com.rtbishop.look4sat.feature.logbook.R as LogbookR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** User-visible, long-running logbook sync. The service outlives the dialog and Activity UI. */
class LogbookSyncService : Service() {
    private data class Request(
        val provider: LogbookSyncProvider,
        val mode: String,
        val callsign: String,
        val password: String
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var syncJob: Job? = null
    private var currentRequest: Request? = null
    private var lastNotificationAt = 0L
    private var lastNotificationPercent = -2
    private var lastNotificationQsoCount = -1L
    private var lastNotificationRemainingSeconds = -1L
    private var lastNotificationStageMessage: String? = null
    private var lastNotificationPhase: LoTWPhase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != LogbookBackgroundSyncController.ACTION_START) {
            if (currentRequest == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (currentRequest != null) return START_NOT_STICKY

        val provider = runCatching {
            LogbookSyncProvider.valueOf(intent.getStringExtra(LogbookBackgroundSyncController.EXTRA_PROVIDER).orEmpty())
        }.getOrNull() ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val mode = intent.getStringExtra(LogbookBackgroundSyncController.EXTRA_MODE).orEmpty()
        val request = Request(
            provider = provider,
            mode = mode,
            callsign = intent.getStringExtra(LogbookBackgroundSyncController.EXTRA_CALLSIGN).orEmpty(),
            password = intent.getStringExtra(LogbookBackgroundSyncController.EXTRA_PASSWORD).orEmpty()
        )
        currentRequest = request
        lastNotificationAt = 0L
        lastNotificationPercent = -2
        lastNotificationQsoCount = -1L
        lastNotificationRemainingSeconds = -1L
        lastNotificationStageMessage = null
        lastNotificationPhase = null
        createNotificationChannel()

        val initial = LogbookBackgroundSyncStatus(
            isRunning = true,
            provider = provider,
            lotwMode = if (provider == LogbookSyncProvider.LOTW) parseLoTWMode(mode) else null,
            wavelogMode = if (provider == LogbookSyncProvider.WAVELOG) parseWavelogMode(mode) else null,
            stageMessage = getString(LogbookR.string.logbook_sync_notification_preparing)
        )
        startForeground(NOTIFICATION_ID, buildNotification(initial))
        LogbookBackgroundSyncController.publish(initial)

        syncJob = serviceScope.launch {
            val finalStatus = try {
                when (request.provider) {
                    LogbookSyncProvider.LOTW -> syncLoTW(request)
                    LogbookSyncProvider.WAVELOG -> syncWavelog(request)
                }
            } catch (cancelled: CancellationException) {
                failedStatus(
                    request,
                    if (request.provider == LogbookSyncProvider.LOTW) getString(LogbookR.string.lotw_storage_error)
                    else getString(LogbookR.string.logbook_sync_notification_failed)
                ).copy(succeeded = false)
            } catch (_: Exception) {
                failedStatus(
                    request,
                    if (request.provider == LogbookSyncProvider.LOTW) getString(LogbookR.string.lotw_storage_error)
                    else getString(LogbookR.string.logbook_sync_notification_failed)
                )
            }
            mainHandler.post { finish(request, finalStatus) }
        }.also { job ->
            // A job cancelled before dispatch never reaches the body above.
            job.invokeOnCompletion { cause ->
                if (cause != null) mainHandler.post {
                    finish(request, failedStatus(request, getString(LogbookR.string.logbook_sync_interrupted)))
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun syncLoTW(request: Request): LogbookBackgroundSyncStatus {
        val container = (applicationContext as IContainerProvider).getMainContainer()
        val settingsRepo = container.settingsRepo
        val settings = settingsRepo.lotwSettings.value.copy(
            callsign = request.callsign.trim().uppercase(Locale.US),
            password = request.password
        )
        if (!settings.isConfigured) {
            return failedStatus(request, getString(CoreR.string.lotw_sync_error_not_configured))
                .copy(lotwDownloadError = com.rtbishop.look4sat.feature.logbook.LoTWDownloadError.NotConfigured)
        }
        settingsRepo.updateLoTWSettings(settings)
        val callsign = settings.callsign.trim().uppercase(Locale.US)
        val mode = resolveLoTWSyncMode(
            settingsRepo.getLastLotwSyncCallsign(),
            callsign,
            parseLoTWMode(request.mode)
        )
        val since = if (mode == LoTWSyncMode.Incremental) lotwCursorApi(settingsRepo.getLastLotwSyncDate()) else ""
        LogbookBackgroundSyncController.update {
            it.copy(lotwMode = mode, progress = null, stageMessage = getString(LogbookR.string.logbook_sync_notification_connecting))
        }
        showNotification(LogbookBackgroundSyncController.status.value, force = true)

        return when (val result = container.lotwRepo.fetchQsos(callsign, settings.password, mode, since) { progress ->
            LogbookBackgroundSyncController.update {
                it.copy(
                    progress = progress,
                    notificationQsoCount = progress.qsoCount,
                    stageMessage = null
                )
            }
            showNotification(LogbookBackgroundSyncController.status.value)
        }) {
            is LoTWResult.Success -> {
                LogbookBackgroundSyncController.update {
                    it.copy(progress = null, stageMessage = getString(LogbookR.string.logbook_sync_notification_saving))
                }
                showNotification(LogbookBackgroundSyncController.status.value, force = true)
                val merge = container.qsoRepository.mergeLoTW(result.records, callsign, mode == LoTWSyncMode.Full)
                val grids = applyLoTWGridResult(settingsRepo, result, mode, callsign)
                settingsRepo.updateLogbookSourceLock(LogbookSyncProvider.LOTW)
                val summary = com.rtbishop.look4sat.feature.logbook.LoTWDownloadSummary(
                    downloaded = result.downloaded,
                    imported = merge.imported,
                    updated = merge.updated,
                    skipped = merge.skipped,
                    confirmedGrids = grids
                )
                LogbookBackgroundSyncController.status.value.copy(
                    isRunning = false,
                    lotwMode = mode,
                    progress = null,
                    stageMessage = null,
                    message = getString(LogbookR.string.logbook_sync_result, merge.imported, merge.updated, merge.skipped),
                    succeeded = true,
                    lotwDownloadSummary = summary,
                    lotwDownloadError = null,
                    workedGridsCount = grids
                )
            }
            LoTWResult.BadCredentials -> failedStatus(request, getString(CoreR.string.lotw_sync_error_credentials))
                .copy(lotwDownloadError = com.rtbishop.look4sat.feature.logbook.LoTWDownloadError.BadCredentials)
            LoTWResult.RateLimited -> failedStatus(request, getString(CoreR.string.lotw_sync_error_rate_limited))
                .copy(lotwDownloadError = com.rtbishop.look4sat.feature.logbook.LoTWDownloadError.RateLimited)
            LoTWResult.Timeout -> failedStatus(request, getString(CoreR.string.lotw_sync_error_timeout))
                .copy(lotwDownloadError = com.rtbishop.look4sat.feature.logbook.LoTWDownloadError.Timeout)
            is LoTWResult.NetworkError -> failedStatus(request, getString(CoreR.string.lotw_sync_error_network, result.detail))
                .copy(lotwDownloadError = com.rtbishop.look4sat.feature.logbook.LoTWDownloadError.Network(result.detail))
        }
    }

    private suspend fun syncWavelog(request: Request): LogbookBackgroundSyncStatus {
        val container = (applicationContext as IContainerProvider).getMainContainer()
        val settingsRepo = container.settingsRepo
        val settings = settingsRepo.wavelogUploadSettings.value
        if (!settings.isConfigured) {
            return failedStatus(request, getString(LogbookR.string.logbook_sync_notification_failed))
        }
        val stations = settingsRepo.getWavelogStations()
        if (stations.isEmpty()) {
            return failedStatus(request, getString(CoreR.string.prefs_wavelog_upload_station_none))
        }
        val requested = parseWavelogMode(request.mode)
        val mode = resolveWavelogSyncMode(settingsRepo.getWavelogSyncUrl(), settings.url, requested)
        val promoted = mode == WavelogSyncMode.Incremental &&
            shouldPromoteWavelogFullSync(settingsRepo.getLastWavelogFullSyncEpochMs())
        val actualMode = if (promoted) WavelogSyncMode.Full else mode
        LogbookBackgroundSyncController.update {
            it.copy(wavelogMode = actualMode, progress = null, stageMessage = getString(LogbookR.string.logbook_sync_notification_wavelog))
        }
        showNotification(LogbookBackgroundSyncController.status.value, force = true)

        val fetch = container.wavelogSyncRepository.fetchNewRecords(
            settings.url,
            settings.apiKey,
            stations,
            settingsRepo.getWavelogSyncCursors(),
            full = actualMode == WavelogSyncMode.Full
        ) ?: return failedStatus(request, getString(LogbookR.string.logbook_sync_notification_failed))

        LogbookBackgroundSyncController.update {
            it.copy(
                notificationQsoCount = fetch.records.size.toLong(),
                progress = null,
                stageMessage = getString(LogbookR.string.logbook_sync_notification_saving)
            )
        }
        showNotification(LogbookBackgroundSyncController.status.value, force = true)

        val merge = container.qsoRepository.mergeWavelog(
            fetch.records, settings.url,
            actualMode == WavelogSyncMode.Full && fetch.failedStations.isEmpty()
        )
        val cursors = settingsRepo.getWavelogSyncCursors() + fetch.cursors
        applyWavelogSyncResult(
            settingsRepo = settingsRepo,
            records = fetch.records,
            cursors = cursors,
            url = settings.url,
            mode = actualMode,
            failedStations = fetch.failedStations.toSet()
        )
        settingsRepo.updateLogbookSourceLock(LogbookSyncProvider.WAVELOG)
        val head = when {
            promoted -> "Full Wavelog sync (automatic re-scan)"
            actualMode == WavelogSyncMode.Full -> "Full Wavelog sync"
            else -> "Incremental Wavelog sync"
        }
        val base = "$head · ${merge.imported} new, ${merge.skipped} already present"
        val message = if (fetch.failedStations.isEmpty()) base
            else "$base · ${fetch.failedStations.size}/${stations.size} station(s) failed"
        return LogbookBackgroundSyncController.status.value.copy(
            isRunning = false,
            wavelogMode = actualMode,
            progress = null,
            stageMessage = null,
            message = message,
            succeeded = fetch.failedStations.isEmpty(),
            workedGridsCount = settingsRepo.getWorkedGrids().size
        )
    }

    private fun failedStatus(request: Request, message: String): LogbookBackgroundSyncStatus =
        LogbookBackgroundSyncController.status.value.copy(
            isRunning = false,
            provider = request.provider,
            lotwMode = if (request.provider == LogbookSyncProvider.LOTW) parseLoTWMode(request.mode) else null,
            wavelogMode = if (request.provider == LogbookSyncProvider.WAVELOG) parseWavelogMode(request.mode) else null,
            progress = null,
            stageMessage = null,
            message = message,
            succeeded = false,
            lotwDownloadSummary = null,
            lotwDownloadError = if (request.provider == LogbookSyncProvider.LOTW)
                com.rtbishop.look4sat.feature.logbook.LoTWDownloadError.Storage else null
        )

    private fun parseLoTWMode(value: String): LoTWSyncMode =
        runCatching { LoTWSyncMode.valueOf(value) }.getOrDefault(LoTWSyncMode.Full)

    private fun parseWavelogMode(value: String): WavelogSyncMode =
        runCatching { WavelogSyncMode.valueOf(value) }.getOrDefault(WavelogSyncMode.Full)

    private fun notificationContent(status: LogbookBackgroundSyncStatus): String {
        if (!status.isRunning) return status.message.orEmpty().ifBlank {
            getString(if (status.succeeded == true) LogbookR.string.logbook_sync_notification_done else LogbookR.string.logbook_sync_notification_failed)
        }

        val count = status.notificationQsoCount
        val progress = status.progress
        if (status.stageMessage == getString(LogbookR.string.logbook_sync_notification_saving)) {
            return if (count > 0L) {
                getString(LogbookR.string.logbook_sync_notification_qso_saving, count)
            } else {
                getString(LogbookR.string.logbook_sync_notification_saving)
            }
        }
        if (status.stageMessage == getString(LogbookR.string.logbook_sync_notification_connecting)) {
            return getString(LogbookR.string.logbook_sync_notification_connecting_detail)
        }
        if (status.provider == LogbookSyncProvider.WAVELOG) {
            return if (count > 0L) {
                getString(LogbookR.string.logbook_sync_notification_qso_estimate, count)
            } else {
                getString(LogbookR.string.logbook_sync_notification_wavelog_progress)
            }
        }
        if (progress?.phase == LoTWPhase.Connecting) {
            return getString(LogbookR.string.logbook_sync_notification_connecting_detail)
        }
        if (count > 0L && progress != null && progress.remainingSeconds > 0L) {
            return getString(
                LogbookR.string.logbook_sync_notification_qso_progress,
                count,
                formatRemainingTime(progress.remainingSeconds)
            )
        }
        if (count > 0L) {
            return getString(LogbookR.string.logbook_sync_notification_qso_estimate, count)
        }
        status.stageMessage?.let { return it }
        return when {
            progress == null -> getString(LogbookR.string.logbook_sync_notification_connecting_detail)
            progress.fraction >= 1f -> getString(LogbookR.string.logbook_sync_notification_saving)
            else -> getString(LogbookR.string.logbook_sync_notification_downloading)
        }
    }

    private fun formatRemainingTime(seconds: Long): String {
        val value = seconds.coerceAtLeast(0L)
        return if (value < 60L) {
            getString(LogbookR.string.logbook_sync_notification_remaining_seconds, value)
        } else {
            getString(
                LogbookR.string.logbook_sync_notification_remaining_minutes,
                value / 60L,
                value % 60L
            )
        }
    }

    private fun buildNotification(status: LogbookBackgroundSyncStatus): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            7302,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            status.isRunning -> getString(LogbookR.string.logbook_sync_notification_title)
            status.succeeded == true -> getString(LogbookR.string.logbook_sync_notification_done)
            else -> getString(LogbookR.string.logbook_sync_notification_failed)
        }
        val progress = status.progress
        val saving = status.provider == LogbookSyncProvider.LOTW &&
            status.stageMessage == getString(LogbookR.string.logbook_sync_notification_saving)
        val indeterminate = status.isRunning && !saving && (
            status.provider != LogbookSyncProvider.LOTW || progress == null ||
                progress.phase == LoTWPhase.Connecting || progress.expectedBytes <= 0
            )
        val progressValue = if (progress != null && progress.expectedBytes > 0) {
            (progress.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
        } else 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_logbook)
            .setContentTitle(title)
            .setContentText(notificationContent(status))
            .setContentIntent(contentIntent)
            .setCategory(if (status.isRunning) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(status.isRunning)
            .setAutoCancel(!status.isRunning)
        when {
            status.isRunning && saving -> builder.setProgress(0, 0, false)
            status.isRunning -> builder.setProgress(100, progressValue, indeterminate)
            status.succeeded == true -> builder.setProgress(100, 100, false)
            else -> builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun showNotification(status: LogbookBackgroundSyncStatus, force: Boolean = false) {
        val progress = status.progress
        val percent = if (progress != null && progress.expectedBytes > 0) {
            (progress.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
        } else -1
        val qsoCount = status.notificationQsoCount
        val remainingSeconds = progress?.remainingSeconds ?: -1L
        val phase = progress?.phase
        val stageMessage = status.stageMessage
        val now = System.currentTimeMillis()
        val visibleContentChanged = percent != lastNotificationPercent ||
            qsoCount != lastNotificationQsoCount ||
            remainingSeconds != lastNotificationRemainingSeconds ||
            phase != lastNotificationPhase ||
            stageMessage != lastNotificationStageMessage
        // Avoid needless repeated system calls, while pushing changed count/ETA/phase immediately.
        if (!force && status.isRunning && now - lastNotificationAt < 250L && !visibleContentChanged) return
        lastNotificationAt = now
        lastNotificationPercent = percent
        lastNotificationQsoCount = qsoCount
        lastNotificationRemainingSeconds = remainingSeconds
        lastNotificationPhase = phase
        lastNotificationStageMessage = stageMessage
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(status))
        } catch (error: SecurityException) {
            android.util.Log.w("LogbookSync", "Notification permission is unavailable", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(LogbookR.string.logbook_sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(LogbookR.string.logbook_sync_notification_title)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        val request = currentRequest
        currentRequest = null
        serviceScope.cancel()
        if (request != null) {
            val finalStatus = failedStatus(request, getString(LogbookR.string.logbook_sync_interrupted))
            LogbookBackgroundSyncController.publish(finalStatus)
            showNotification(finalStatus, force = true)
        }
        super.onDestroy()
    }

    private fun finish(request: Request, finalStatus: LogbookBackgroundSyncStatus) {
        if (currentRequest !== request) return
        currentRequest = null
        syncJob = null
        LogbookBackgroundSyncController.publish(finalStatus)
        stopForeground(STOP_FOREGROUND_DETACH)
        showNotification(finalStatus, force = true)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val request = currentRequest
        syncJob?.cancel()
        if (request != null) {
            finish(request, failedStatus(request, getString(LogbookR.string.logbook_sync_interrupted)))
        } else stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "logbook_sync"
        private const val NOTIFICATION_ID = 7301
    }
}
