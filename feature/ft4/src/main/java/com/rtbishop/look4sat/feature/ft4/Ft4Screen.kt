/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.feature.ft4

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.*
import com.rtbishop.look4sat.core.presentation.R

@Composable
fun Ft4ShellDestination(navigateUp: () -> Unit, navigateToLogbook: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    var digitalModeReady by remember { mutableStateOf(false) }
    LaunchedEffect(container) {
        try {
            container.jttyTransmitter.stop()
            container.jttyService.stopReceiving()
            digitalModeReady = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { digitalModeReady = false }
    }
    val model: Ft4ViewModel = viewModel(factory = Ft4ViewModel.factory(container))
    val state by model.uiState.collectAsStateWithLifecycle()
    val timing by model.timingState.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var microphoneAttempted by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permanentlyDenied = !granted && microphoneAttempted &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        model.onAction(Ft4Action.MicrophonePermissionChanged(granted))
    }
    val radioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.onAction(Ft4Action.ConnectRadios)
    }
    DisposableEffect(lifecycleOwner) {
        model.setRadarForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; model.setRadarForeground(true) }
            if (event == Lifecycle.Event.ON_PAUSE) { resumed = false; model.setRadarForeground(false) }
            if (event == Lifecycle.Event.ON_STOP) model.onAction(Ft4Action.Leave)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            model.setRadarForeground(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.onAction(Ft4Action.Leave)
        }
    }
    LaunchedEffect(digitalModeReady, resumed, state.settings.decodeEnabled, state.capability.receiveAvailable, state.usesNetworkAudio) {
        if (!resumed || !digitalModeReady) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted || state.usesNetworkAudio) {
            permanentlyDenied = false
            model.onAction(Ft4Action.MicrophonePermissionChanged(true))
        } else if (state.settings.decodeEnabled && state.capability.receiveAvailable) {
            model.onAction(Ft4Action.MicrophonePermissionChanged(false))
            if (!microphoneAttempted) { microphoneAttempted = true; microphone.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
    var audioDialog by rememberSaveable { mutableStateOf(false) }
    val radioSettings by container.settingsRepo.radioControlSettings.collectAsStateWithLifecycle()
    // The renderer stays at session scope, so tabs do not restart spectrum collection.
    val renderState = remember { SpectrumRenderState() }
    LaunchedEffect(model) { model.spectrumFrames.collect { renderState.update(it) } }
    Ft4Workbench(
        state, timing, renderState, model::onAction, navigateUp, navigateToLogbook,
        requestMicrophone = {
            if (permanentlyDenied && activity != null) activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            ) else { microphoneAttempted = true; microphone.launch(Manifest.permission.RECORD_AUDIO) }
        },
        connectRadios = {
            val permission = requiredRadioPermission(state.radioTransport)
            if (permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                model.onAction(Ft4Action.ConnectRadios)
            } else radioPermission.launch(permission)
        },
        onAudio = { audioDialog = true }
    )
    if (audioDialog) RadioAudioDialog(
        radioSettings.radioAudio, radioSettings.radioModel, container.radioAudioSession,
        onDismiss = { audioDialog = false }, onSave = { model.onAction(Ft4Action.AudioSettings(it)) }
    )
}

@Composable
internal fun Ft4Workbench(
    state: Ft4State, timing: Ft4TimingState, renderState: SpectrumRenderState,
    onAction: (Ft4Action) -> Unit, navigateUp: () -> Unit, navigateToLogbook: () -> Unit,
    requestMicrophone: () -> Unit, connectRadios: () -> Unit, onAudio: () -> Unit
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var automatic by rememberSaveable { mutableStateOf(false) }
    var spectrumExpanded by rememberSaveable { mutableStateOf(false) }
    val page = DigitalPage.entries.getOrElse(pageIndex) { DigitalPage.SPECTRUM }
    val panel = DigitalRadioPanelState(state.radio, state.trackingPass, state.orbitalPosition,
        state.satelliteTrack, state.orientationValues, state.shouldShowSweep, state.shouldUseCompass)
    val transmitting = state.transmitState !is Ft4TransmitState.Idle && state.transmitState !is Ft4TransmitState.Failed
    val audioStatus = state.radioAudio.error ?: stringResource(
        if (state.usesNetworkAudio && state.radioAudio.transmittingReady && !state.radioAudio.linkSelfCheckPassed)
            R.string.radio_audio_selfcheck
        else if (state.radioAudio.transmittingReady) R.string.radio_audio_ready else R.string.radio_audio_waiting
    )
    val status = stringResource(R.string.digital_connection_state,
        stringResource(if (state.radio.txConnected || state.radio.rxConnected) R.string.digital_control_connected
        else R.string.digital_control_disconnected), audioStatus)
    Box(Modifier.fillMaxSize().keepScreenOn()) {
        DigitalWorkbench(
            title = stringResource(R.string.ft4_title), panel = panel, page = page,
            onPageChange = { pageIndex = it.ordinal }, status = status, transmitting = transmitting,
            onBack = navigateUp, onConnect = connectRadios,
            onDisconnect = { onAction(Ft4Action.DisconnectRadios) }, onTrack = { onAction(Ft4Action.ToggleTracking) },
            onStopTransmit = { onAction(Ft4Action.EmergencyStop) },
            onSetBases = { onAction(Ft4Action.SetBases(it)) }, onAudio = onAudio,
            radioDetails = {
                if (state.usesNetworkAudio && !state.radioAudio.endToEndTimingVerified)
                    Text(stringResource(R.string.radio_audio_timing_unverified), style = MaterialTheme.typography.bodySmall)
            },
            parameterContent = {
                Text(stringResource(R.string.prefs_ft4_decode_depth), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to R.string.prefs_ft4_decode_fast, 2 to R.string.prefs_ft4_decode_balanced,
                        3 to R.string.prefs_ft4_decode_deep).forEach { (depth, label) ->
                        FilterChip(selected = state.settings.decodeDepth == depth, enabled = !transmitting,
                            onClick = { onAction(Ft4Action.DecodeDepth(depth)) },
                            label = { Text(stringResource(label)) })
                    }
                }
                LogIdentityHint(state.settings.operatorCallsign, state.stationGrid)
                Text(stringResource(if (spectrumExpanded) R.string.digital_compact_spectrum else R.string.digital_expand_spectrum))
                Switch(checked = spectrumExpanded, onCheckedChange = { spectrumExpanded = it })
            },
            timingContent = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ft4_slot_progress, (timing.slotProgress * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.ft4_time_source, clockSourceLabel(timing.clock.source),
                        timing.clock.uncertaintyMillis), style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(progress = { timing.slotProgress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        ) {
            state.error?.let {
                TextButton(onClick = { onAction(Ft4Action.ClearError) }) {
                    Text(ft4ErrorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
            (state.transmitState as? Ft4TransmitState.Failed)?.let {
                Text(it.reason, color = MaterialTheme.colorScheme.error)
            }
            when (page) {
                DigitalPage.SPECTRUM -> {
                    Ft4SpectrumPage(state, renderState, onAction, requestMicrophone, onAudio,
                        spectrumExpanded, { spectrumExpanded = it })
                    Ft4DecodeList(state, onAction, compact = true)
                    DigitalReceiveActions(state.isReceiving, {
                        if (state.hasMicrophonePermission) onAction(Ft4Action.ToggleReceiving)
                        else requestMicrophone()
                    }, { pageIndex = DigitalPage.CONTACT.ordinal },
                        enabled = state.settings.decodeEnabled && state.capability.receiveAvailable)
                }
                DigitalPage.CONTACT -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !automatic, onClick = { automatic = false },
                            label = { Text(stringResource(R.string.digital_manual)) }, modifier = Modifier.weight(1f))
                        FilterChip(selected = automatic, onClick = { automatic = true },
                            label = { Text(stringResource(R.string.ft4_page_automatic)) }, modifier = Modifier.weight(1f))
                    }
                    Ft4DecodeList(state, onAction, compact = true)
                    if (automatic) Ft4AutomaticPage(state, onAction) else Ft4CallingControls(state, onAction)
                }
                DigitalPage.LOG -> DigitalLogList(state.records, state.trackingPass?.name, navigateToLogbook)
            }
        }
    }
}

private fun requiredRadioPermission(transport: String): String? = when {
    transport == RadioControlSettings.TRANSPORT_BLUETOOTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        Manifest.permission.BLUETOOTH_CONNECT
    transport == RadioControlSettings.TRANSPORT_TCP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN ->
        Manifest.permission.ACCESS_LOCAL_NETWORK
    else -> null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ft4ErrorText(error: Ft4UiError): String = stringResource(when (error) {
    Ft4UiError.DECODE_DISABLED -> R.string.ft4_status_disabled
    Ft4UiError.UNAVAILABLE -> R.string.ft4_error_unavailable
    Ft4UiError.MICROPHONE_PERMISSION -> R.string.ft4_microphone_required
    Ft4UiError.TIME_SYNCHRONIZATION -> R.string.ft4_error_time_sync
    Ft4UiError.RADIO_NOT_READY -> R.string.ft4_error_radio_not_ready
    Ft4UiError.PASS_TRANSPONDER_REQUIRED -> R.string.ft4_error_pass_transponder_required
    Ft4UiError.CALLSIGN_REQUIRED -> R.string.ft4_error_callsign_required
    Ft4UiError.GRID_REQUIRED -> R.string.ft4_error_grid_required
    Ft4UiError.TX_MODE_UNAVAILABLE -> R.string.ft4_error_tx_mode_unavailable
    Ft4UiError.SELECT_PASS -> R.string.ft4_error_select_pass
    Ft4UiError.SELECT_TRANSPONDER -> R.string.ft4_error_select_transponder
    Ft4UiError.INVALID_MESSAGE -> R.string.ft4_error_invalid_message
    Ft4UiError.OPERATION_FAILED -> R.string.ft4_error_operation_failed
})
