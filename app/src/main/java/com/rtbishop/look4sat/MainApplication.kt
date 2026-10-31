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
package com.rtbishop.look4sat

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.data.framework.matchesConfiguredL4sUsbDevice
import com.rtbishop.look4sat.core.domain.repository.PttState
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.rtbishop.look4sat.core.data.injection.MainContainer
import com.rtbishop.look4sat.core.domain.model.LogbookSyncProvider
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwIncrementalSince
import com.rtbishop.look4sat.core.domain.repository.lotwSyncToday
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldAutoSyncLoTW
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainApplication : Application(), IContainerProvider, com.rtbishop.look4sat.core.domain.audio.IRecordingForegroundLauncher {
    override fun startRecordingForeground(request: com.rtbishop.look4sat.core.domain.audio.RecordingRequest) {
        androidx.core.content.ContextCompat.startForegroundService(this,RecordingForegroundService.startIntent(this,request))
    }

    private lateinit var container: IMainContainer
    private var usbReconnectJob: Job? = null

    override fun getMainContainer(): IMainContainer = container

    override fun onCreate() {
        super.onCreate()
        container = MainContainer(this)
        observeL4sUsbAttachments()
        clearAmSatCacheOnAppBackground()
        // trigger automatic update every 48 hours
        container.appScope.launch { checkAutoUpdate() }
        // automatic LoTW grid sync on every app start (gated, see checkLoTWAutoSync)
        container.appScope.launch { checkLoTWAutoSync() }
        // repair logbooks split by the old name/band identity mismatch, independent of
        // whether a sync is due (cheap read + in-memory match on every start)
        container.appScope.launch { runCatching { container.qsoRepository.consolidateConfirmations() } }
        // load satellite data on every app start
        container.appScope.launch { container.satelliteRepo.initRepository() }
    }

    /** USB CAT reattachment is a device event, not an FT4 audio or CAT retry.
     * Android must enumerate the device and grant permission before reconnecting.
     * Only the configured L4S VID/PID is eligible; never open an unrelated USB
     * serial device or start tracking/transmitting automatically. */
    @Suppress("DEPRECATION")
    private fun observeL4sUsbAttachments() {
        val manager = getSystemService(UsbManager::class.java) ?: return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val event = if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) "attach" else "detach"
                container.controlDiagnostics.record(ControlDiagnosticSource.RADIO, "usb/hotplug",
                    "event=$event VID=${device?.vendorId} PID=${device?.productId} " +
                        "devices=${manager.deviceList.values.joinToString { "%04X:%04X".format(it.vendorId, it.productId) }}")
                usbReconnectJob?.cancel()
                if (event != "attach" || device == null) return
                usbReconnectJob = container.appScope.launch {
                    delay(450) // Android permission and descriptor discovery settle after attach.
                    val config = container.settingsRepo.radioControlSettings.value
                    if (!matchesConfiguredL4sUsbDevice(config, device.vendorId, device.productId)) return@launch
                    val matches = manager.deviceList.values.filter {
                        it.vendorId == device.vendorId && it.productId == device.productId
                    }
                    if (matches.size != 1 || matches.single().deviceId != device.deviceId) return@launch
                    if (!manager.hasPermission(device)) {
                        container.controlDiagnostics.record(ControlDiagnosticSource.RADIO, "usb/reconnect",
                            "permission required for %04X:%04X; select the radio in Settings".format(device.vendorId, device.productId))
                        return@launch
                    }
                    val tracking = container.radioTrackingService
                    val current = tracking.state.value
                    if (current.txConnected || current.rxConnected || current.isActive ||
                        current.pttState != PttState.OFF || current.catTransmitting) return@launch
                    container.controlDiagnostics.record(ControlDiagnosticSource.RADIO, "usb/reconnect",
                        "device rediscovered; reopening L4S CDC and HELLO/OPEN")
                    tracking.connectRadios()
                    container.controlDiagnostics.record(ControlDiagnosticSource.RADIO, "usb/reconnect",
                        "connected=${tracking.state.value.txConnected}")
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun clearAmSatCacheOnAppBackground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivityCount = 0

            override fun onActivityStarted(activity: Activity) {
                if (startedActivityCount == 0) {
                    container.appScope.launch { container.amSatRepo.prefetchStatus() }
                }
                startedActivityCount += 1
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) container.amSatRepo.clearStatusCache()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private suspend fun checkAutoUpdate(timeNow: Long = System.currentTimeMillis()) {
        if (container.settingsRepo.otherSettings.value.stateOfAutoUpdate) {
            val timeDelta = timeNow - container.settingsRepo.databaseState.value.updateTimestamp
            if (timeDelta > 172_800_000L) { // 48 hours in ms
                val sdf = SimpleDateFormat("d MMM yyyy - HH:mm:ss", Locale.getDefault())
                println("Started periodic data update on ${sdf.format(Date())}")
                container.databaseRepo.updateFromRemote()
            }
        }
    }

    /**
     * Automatic LoTW grid sync, mirroring [checkAutoUpdate]: checked on every
     * app start, but only pulls when due — LoTW credentials configured, the
     * LoTW auto-sync toggle on, at least one prior (manual) sync, and not
     * already synced today (ARRL limits report pulls, ~once a day per account).
     * Incremental when the callsign is unchanged; a callsign change falls back
     * to a full replace so no stale grids from another account linger.
     * Failures are silent and simply retried on the next app start — the manual
     * sync button still surfaces the explicit cause to the user.
     */
    private suspend fun checkLoTWAutoSync(timeNow: Long = System.currentTimeMillis()) {
        val settingsRepo = container.settingsRepo
        // Exactly one provider is active. A configured Wavelog account must not
        // silently override the user's explicit provider choice.
        if (settingsRepo.logbookSyncProvider.value != LogbookSyncProvider.LOTW) return
        val sourceLock = settingsRepo.getLogbookSourceLock() ?: run {
            val lotwWasSynced = settingsRepo.getLastLotwSyncDate().isNotBlank() ||
                settingsRepo.getLastLotwSyncCallsign().isNotBlank()
            val wavelogWasSynced = settingsRepo.getLastWavelogSyncEpochMs() > 0L ||
                settingsRepo.getWavelogSyncCursors().isNotEmpty()
            when {
                lotwWasSynced && !wavelogWasSynced -> LogbookSyncProvider.LOTW
                wavelogWasSynced && !lotwWasSynced -> LogbookSyncProvider.WAVELOG
                lotwWasSynced && wavelogWasSynced -> settingsRepo.logbookSyncProvider.value
                else -> null
            }
        }
        if (sourceLock != null && sourceLock != LogbookSyncProvider.LOTW) return
        val lotwSettings = settingsRepo.lotwSettings.value
        if (!shouldAutoSyncLoTW(
                isConfigured = lotwSettings.isConfigured,
                autoLotwSyncEnabled = settingsRepo.otherSettings.value.stateOfAutoLotwSync,
                lastSyncDate = settingsRepo.getLastLotwSyncDate(),
                today = lotwSyncToday(timeNow)
            )
        ) return
        val callsign = lotwSettings.callsign.trim().uppercase()
        val mode = resolveLoTWSyncMode(settingsRepo.getLastLotwSyncCallsign(), callsign, requested = null)
        val since = if (mode == LoTWSyncMode.Incremental) lotwIncrementalSince(settingsRepo) else ""
        println("Started periodic LoTW sync (${mode.name.lowercase()})")
        val result = container.lotwRepo.fetchQsos(callsign, lotwSettings.password, mode, since)
        if (result is LoTWResult.Success) {
            val logbook = container.qsoRepository.mergeLoTW(result.records,callsign,mode==LoTWSyncMode.Full)
            val count = applyLoTWGridResult(settingsRepo, result, mode, callsign, timeNow)
            settingsRepo.updateLogbookSourceLock(LogbookSyncProvider.LOTW)
            println(
                "Periodic LoTW sync finished: $count worked grids, " +
                    "${logbook.imported} logs added, ${logbook.updated} updated"
            )
        } else {
            println("Periodic LoTW sync skipped (${result::class.simpleName})")
        }
    }
}
