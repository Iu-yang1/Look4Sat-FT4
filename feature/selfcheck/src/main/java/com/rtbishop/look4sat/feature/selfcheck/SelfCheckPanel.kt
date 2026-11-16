package com.rtbishop.look4sat.feature.selfcheck

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.maintenance.*
import com.rtbishop.look4sat.core.domain.audio.RadioAudioBackend
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.presentation.*
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SelfCheckPanel(container: IMainContainer, onDismiss: () -> Unit) {
    val model: SelfCheckViewModel = viewModel(factory = SelfCheckViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    val ui by model.uiState.collectAsStateWithLifecycle()
    val config by container.settingsRepo.radioControlSettings.collectAsStateWithLifecycle()
    val radio by container.radioTrackingService.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val close = {
        container.appScope.launch { container.radioSelfCheck.cancel() }
        onDismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.onAction(SelfCheckAction.Start)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use {
                    it.write(container.controlDiagnostics.exportText())
                }
            }.onFailure { container.provideShowToast()(context.getString(R.string.maintenance_export_failed)) }
        }
    }
    val back = {
        if (ui.page != "summary") {
            container.appScope.launch { container.radioSelfCheck.stopTransmit() }
            model.onAction(SelfCheckAction.Page("summary"))
        } else close()
    }
    BackHandler(onBack = back)
    SharedDialog(
        title = stringResource(if (ui.page == "test") R.string.selfcheck_test else R.string.selfcheck_title),
        onDismissRequest = close,
        onCancel = back,
        cancelText = stringResource(if (ui.page == "summary") R.string.maintenance_close else R.string.maintenance_back)
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .65f)
                .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = padding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(config.radioModel, style = MaterialTheme.typography.titleMedium)
            Text("${if (config.catTransport == RadioControlSettings.TRANSPORT_BLUETOOTH) "Bluetooth" else config.catTransport} · " +
                stringResource(if (radio.txConnected || radio.rxConnected) R.string.ft4_connected else R.string.ft4_disconnected),
                style = MaterialTheme.typography.bodySmall)
            Text(if (config.radioModel == RadioControlSettings.MODEL_VERO_N76 && config.n76BluetoothAudio) "N76"
                else stringResource(when (config.radioAudio.backend) {
                    RadioAudioBackend.UNCONFIGURED -> R.string.radio_audio_none
                    RadioAudioBackend.ANDROID_DEVICE -> R.string.radio_audio_usb
                    RadioAudioBackend.ICOM_WLAN -> R.string.radio_audio_wlan
                }), style = MaterialTheme.typography.bodySmall)
            if (state.preparing || state.transmitting || state.stopRequired) {
                Button(onClick = { model.onAction(SelfCheckAction.Stop) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.selfcheck_stop_tx))
                }
            }
            if (ui.page == "audio") {
                RadioAudioSettingsContent(config.radioAudio, config.radioModel, container.radioAudioSession, { audio ->
                    scope.launch {
                        if (container.radioTrackingService.applyRadioControlSettings(config.copy(radioAudio = audio)))
                            model.onAction(SelfCheckAction.Page("summary"))
                    }
                })
            } else if (ui.page == "test") {
                Text(stringResource(R.string.selfcheck_test_frequency,
                    (state.testFrequencyHz ?: radio.txFrequencyHz)?.toString() ?: "—", state.testMode ?: radio.txMode ?: "—"))
                Text(stringResource(R.string.selfcheck_test_notice), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !state.preparing && !state.transmitting) {
                    model.onAction(SelfCheckAction.Audio(!ui.withAudio))
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ui.withAudio, { model.onAction(SelfCheckAction.Audio(it)) },
                        enabled = !state.preparing && !state.transmitting)
                    Text(stringResource(R.string.selfcheck_include_tone))
                }
                Text(stringResource(statusLabel(state.testResult)), color = statusColor(state.testResult))
                Button(onClick = { model.onAction(SelfCheckAction.Transmit) },
                    enabled = !state.running && !state.preparing && !state.transmitting && !state.stopRequired,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.selfcheck_start_test))
                }
            } else {
                if (state.stale) Text(stringResource(R.string.selfcheck_stale), color = MaterialTheme.colorScheme.primary)
                if (state.checkedAtMillis != 0L)
                    Text(DateFormat.getDateTimeInstance().format(Date(state.checkedAtMillis)),
                        style = MaterialTheme.typography.bodySmall)
                val results = if (ui.page == "detail") state.results.filter { it.item == ui.detail } else state.results
                results.forEach { result ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = ui.page == "summary") { model.onAction(SelfCheckAction.Detail(result.item)) }
                        .padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(itemLabel(result.item)), Modifier.weight(1f))
                            Icon(painterResource(when (result.status) {
                                CheckStatus.PASSED -> R.drawable.ic_done
                                CheckStatus.FAILED -> R.drawable.ic_close
                                CheckStatus.ATTENTION -> R.drawable.ic_policy
                                CheckStatus.RUNNING -> R.drawable.ic_clock
                                else -> R.drawable.ic_more
                            }), contentDescription = null, tint = statusColor(result.status))
                            Text(stringResource(statusLabel(result.status)), color = statusColor(result.status),
                                style = MaterialTheme.typography.labelLarge)
                        }
                        if (result.item == CheckItem.OUTPUT && result.status == CheckStatus.ATTENTION)
                            Text(stringResource(R.string.selfcheck_output_unverified), style = MaterialTheme.typography.bodySmall)
                        if (result.item == CheckItem.RECEIVE && state.receive.samples > 0) {
                            val meter = state.receive
                            LinearProgressIndicator(
                                progress = { ((meter.rmsDbfs + 60) / 60).coerceIn(0.0, 1.0).toFloat() },
                                modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.selfcheck_meter, meter.source, meter.sampleRate,
                                String.format(Locale.getDefault(), "%.1f", meter.rmsDbfs), meter.gaps, meter.clippedSamples),
                                style = MaterialTheme.typography.bodySmall)
                            if (meter.peakDbfs < -90) Text(stringResource(R.string.selfcheck_no_signal))
                        }
                        if (result.item == CheckItem.CALIBRATION && result.status == CheckStatus.NOT_CHECKED)
                            Text(stringResource(R.string.selfcheck_uncalibrated), style = MaterialTheme.typography.bodySmall)
                        if (ui.page == "detail") {
                            if (result.detail.isNotBlank()) Text(result.detail, style = MaterialTheme.typography.bodySmall)
                            if (result.action != CheckAction.NONE) TextButton(onClick = {
                                when (result.action) {
                                    CheckAction.CONNECT -> model.onAction(SelfCheckAction.Connect)
                                    CheckAction.AUDIO -> model.onAction(SelfCheckAction.Page("audio"))
                                    CheckAction.PERMISSION -> permission.launch(Manifest.permission.RECORD_AUDIO)
                                    CheckAction.CLOCK -> context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                                    else -> Unit
                                }
                            }) { Text(stringResource(actionLabel(result.action))) }
                        }
                    }
                }
                if (ui.page == "summary") {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.onAction(if (state.running) SelfCheckAction.Cancel else SelfCheckAction.Start) },
                            enabled = !state.preparing && !state.transmitting && !state.stopRequired,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (state.running) R.string.selfcheck_cancel else R.string.selfcheck_start))
                        }
                        OutlinedButton(onClick = { model.onAction(SelfCheckAction.Page("test")) },
                            enabled = !state.running, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.selfcheck_test))
                        }
                    }
                    TextButton(onClick = { export.launch("Look4Sat-BA7OPF-Orekit-selfcheck-${System.currentTimeMillis()}.txt") }) {
                        Text(stringResource(R.string.selfcheck_export))
                    }
                }
            }
            (state.error ?: ui.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun itemLabel(item: CheckItem) = when (item) {
    CheckItem.CAT -> R.string.selfcheck_cat
    CheckItem.FREQUENCY -> R.string.selfcheck_frequency
    CheckItem.PTT -> R.string.selfcheck_ptt
    CheckItem.RECEIVE -> R.string.selfcheck_receive
    CheckItem.OUTPUT -> R.string.selfcheck_output
    CheckItem.CLOCK -> R.string.selfcheck_clock
    CheckItem.CALIBRATION -> R.string.selfcheck_calibration
}

internal fun statusLabel(status: CheckStatus) = when (status) {
    CheckStatus.NOT_CHECKED -> R.string.selfcheck_not_checked
    CheckStatus.RUNNING -> R.string.selfcheck_running
    CheckStatus.PASSED -> R.string.selfcheck_passed
    CheckStatus.ATTENTION -> R.string.selfcheck_attention
    CheckStatus.FAILED -> R.string.selfcheck_failed
    CheckStatus.UNSUPPORTED -> R.string.selfcheck_unsupported
}

@Composable
private fun statusColor(status: CheckStatus) = when (status) {
    CheckStatus.FAILED -> MaterialTheme.colorScheme.error
    CheckStatus.ATTENTION -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun actionLabel(action: CheckAction) = when (action) {
    CheckAction.CONNECT -> R.string.selfcheck_connect
    CheckAction.PERMISSION -> R.string.selfcheck_permission
    CheckAction.CLOCK -> R.string.selfcheck_clock_settings
    else -> R.string.radio_audio_title
}
