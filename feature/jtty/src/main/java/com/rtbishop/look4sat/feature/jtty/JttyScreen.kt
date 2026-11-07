// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.feature.jtty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.*
import com.rtbishop.look4sat.core.presentation.R
import java.util.Locale

@Composable
fun JttyDestination(navigateUp: () -> Unit, navigateToLogbook: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val model: JttyViewModel = viewModel(factory = JttyViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    val config by container.settingsRepo.radioControlSettings.collectAsStateWithLifecycle()
    var audioDialog by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.onAction(JttyAction.Receive)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.onAction(JttyAction.Connect)
    }
    LaunchedEffect(Unit) { model.enter() }
    DisposableEffect(lifecycle) {
        model.setRadarForeground(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.setRadarForeground(true)
            if (event == Lifecycle.Event.ON_PAUSE) model.setRadarForeground(false)
            if (event == Lifecycle.Event.ON_STOP) model.leave()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { model.setRadarForeground(false); lifecycle.lifecycle.removeObserver(observer); model.leave() }
    }
    BackHandler { model.leave(); navigateUp() }
    JttyScreen(state, model::onAction, { model.leave(); navigateUp() }, navigateToLogbook,
        receive = {
            if (state.receive.receiving || state.networkAudio ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) model.onAction(JttyAction.Receive) else microphone.launch(Manifest.permission.RECORD_AUDIO)
        }, connect = {
            val required = when {
                config.catTransport == RadioControlSettings.TRANSPORT_BLUETOOTH && !config.radioAudio.networkCat &&
                    Build.VERSION.SDK_INT >= 31 -> Manifest.permission.BLUETOOTH_CONNECT
                (config.catTransport == RadioControlSettings.TRANSPORT_TCP || config.radioAudio.networkCat) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN -> Manifest.permission.ACCESS_LOCAL_NETWORK
                else -> null
            }
            if (required == null || ContextCompat.checkSelfPermission(context, required) == PackageManager.PERMISSION_GRANTED)
                model.onAction(JttyAction.Connect) else permission.launch(required)
        }, onAudio = { audioDialog = true })
    if (audioDialog) RadioAudioDialog(config.radioAudio, config.radioModel, container.radioAudioSession,
        onDismiss = { audioDialog = false }, onSave = { model.onAction(JttyAction.AudioSettings(it)) })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JttyScreen(
    state: JttyState, action: (JttyAction) -> Unit, back: () -> Unit, logbook: () -> Unit,
    receive: () -> Unit, connect: () -> Unit, onAudio: () -> Unit = {}
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var spectrumExpanded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var template by rememberSaveable { mutableStateOf("") }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val page = DigitalPage.entries.getOrElse(pageIndex) { DigitalPage.SPECTRUM }
    // The spectrum host remains attached for the whole page session.
    val renderState = remember { SpectrumRenderState() }
    LaunchedEffect(state.receive.spectrum?.sequence) { state.receive.spectrum?.let(renderState::update) }
    val panel = DigitalRadioPanelState(state.radio, state.pass, state.position, state.track,
        state.orientationValues, state.shouldShowSweep, state.shouldUseCompass)
    val status = stringResource(R.string.digital_connection_state,
        stringResource(if (state.radio.txConnected || state.radio.rxConnected) R.string.digital_control_connected
        else R.string.digital_control_disconnected),
        stringResource(if (state.audioReady || state.receive.receiving) R.string.radio_audio_ready else R.string.radio_audio_waiting))
    Box(Modifier.fillMaxSize()) {
        DigitalWorkbench(
            title = stringResource(R.string.digital_jtty_title), panel = panel, page = page,
            onPageChange = { pageIndex = it.ordinal }, status = status, transmitting = state.busy,
            onBack = back, onConnect = connect, onDisconnect = { action(JttyAction.Disconnect) },
            onTrack = { action(JttyAction.Track) }, onStopTransmit = { action(JttyAction.EmergencyStop) },
            onSetBases = { action(JttyAction.SetBases(it)) }, onAudio = onAudio,
            parameterContent = {
                Text("${state.myCall} · ${state.grid}", color = MaterialTheme.colorScheme.primary)
                if (state.transponders.isNotEmpty()) TextButton(onClick = { choosing = !choosing }, enabled = !state.busy) {
                    Text(stringResource(R.string.jtty_transponder))
                }
                if (choosing) state.transponders.forEach { item ->
                    TextButton(onClick = { action(JttyAction.Transponder(item)); choosing = false }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(item.info) }
                }
                LogIdentityHint(state.myCall, state.grid)
            },
            timingContent = {
                Text(stringResource(R.string.digital_search_status, state.receive.carriers.size),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
            }
        ) {
            (state.error ?: state.receive.error ?: state.radio.lastCommandError)?.let { error ->
                TextButton(onClick = { action(JttyAction.DismissError) }) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            when (page) {
                DigitalPage.SPECTRUM -> {
                    DigitalSpectrumPane(renderState, state.receive.selectedRxHz,
                        { action(JttyAction.RxFrequency(it)) },
                        stringResource(R.string.digital_rx_frequency, state.receive.selectedRxHz.toInt()),
                        state.receive.receiving, onAudio, spectrumExpanded, { spectrumExpanded = it },
                        description = stringResource(R.string.jtty_spectrum_description), txHz = state.txHz
                    ) {
                        Text(stringResource(R.string.ft4_spectrum_range))
                        Text(stringResource(R.string.ft4_input_level, state.receive.spectrum?.inputLevelDb ?: -120f))
                        Text(stringResource(R.string.digital_tx_frequency, state.txHz.toInt()))
                    }
                    if (state.receive.carriers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.receive.carriers.forEach { carrier ->
                            FilterChip(
                                selected = kotlin.math.abs(carrier.frequencyHz - state.receive.selectedRxHz) < 10f,
                                onClick = { action(JttyAction.RxFrequency(carrier.frequencyHz)) },
                                label = { Text("${carrier.frequencyHz.toInt()} Hz · ${carrier.strengthDb.toInt()} dB" +
                                    if (carrier.confirmed) " ✓" else "") })
                        }
                    }
                    JttyDecodeTable(state, action)
                    DigitalReceiveActions(state.receive.receiving, receive, { pageIndex = DigitalPage.CONTACT.ordinal })
                }
                DigitalPage.CONTACT -> {
                    JttyDecodeTable(state, action)
                    JttyContactFields(state, action)
                    DigitalAudioFrequencyControl(state.txHz, 100f, 2800f, !state.busy) {
                        action(JttyAction.TxFrequency(it))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.presets.take(4).forEachIndexed { index, preset ->
                            OutlinedCard(Modifier.weight(1f).heightIn(min = 64.dp).combinedClickable(
                                enabled = !state.busy, onClick = { action(JttyAction.Preset(index)) },
                                onLongClick = { editing = index; template = preset })) {
                                Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("F${index + 1}", color = MaterialTheme.colorScheme.primary)
                                    Text(preset, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    OutlinedTextField(state.draft, { action(JttyAction.Draft(it)) }, enabled = !state.busy,
                        label = { Text(stringResource(R.string.jtty_message)) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    state.draftError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(stringResource(R.string.jtty_duration, state.frames ?: 0, (state.frames ?: 0) * 1.888),
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CardButton({ action(JttyAction.Send) }, stringResource(R.string.jtty_send),
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp), enabled = state.transmitReady)
                        CardButton({ action(JttyAction.Stop) }, stringResource(R.string.jtty_stop_tx),
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp), enabled = state.busy)
                    }
                }
                DigitalPage.LOG -> {
                    JttyContactFields(state, action)
                    LogIdentityHint(state.myCall, state.grid)
                    Button(onClick = { action(JttyAction.SaveLog) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.jtty_save_log)) }
                    if (state.saved) Text(stringResource(R.string.jtty_saved), color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.jtty_local_log), style = MaterialTheme.typography.bodySmall)
                    DigitalLogList(state.records, state.pass?.name, logbook)
                }
            }
        }
        if (editing >= 0) {
            BackHandler { editing = -1 }
            // Same-window bottom overlay avoids replacing the active native audio host.
            BoxWithConstraints(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .64f))
                    .clickable { editing = -1 }.safeDrawingPadding().imePadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(onClick = {}, shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * .55f)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("F${editing + 1}", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(template, { template = it.take(240) },
                            label = { Text(stringResource(R.string.jtty_template)) }, maxLines = 6,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("jttyPresetText"))
                        FlowRow {
                            listOf("CALL", "MYCALL", "RST", "GRID").forEach { variable ->
                                TextButton(onClick = { template = (template + " {$variable}").trim().take(240) }, enabled = !state.busy) {
                                    Text("{$variable}")
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editing = -1 }) { Text(stringResource(R.string.jtty_cancel)) }
                            TextButton(onClick = { action(JttyAction.SavePreset(editing, template)); editing = -1 }, enabled = !state.busy) {
                                Text(stringResource(R.string.jtty_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JttyContactFields(state: JttyState, action: (JttyAction) -> Unit) {
    Text("${state.myCall} · ${state.grid}", color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(state.theirCall, { action(JttyAction.TheirCall(it)) },
        label = { Text(stringResource(R.string.jtty_their_call)) }, singleLine = true,
        enabled = !state.busy, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state.sentReport, { action(JttyAction.SentReport(it)) },
            label = { Text(stringResource(R.string.jtty_sent)) }, singleLine = true,
            enabled = !state.busy, modifier = Modifier.weight(1f))
        OutlinedTextField(state.receivedReport, { action(JttyAction.ReceivedReport(it)) },
            label = { Text(stringResource(R.string.jtty_received)) }, singleLine = true,
            enabled = !state.busy, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun JttyDecodeTable(state: JttyState, action: (JttyAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ft4_decode_count, state.receive.messages.size), modifier = Modifier.weight(1f))
        TextButton(onClick = { action(JttyAction.Clear) }) { Text(stringResource(R.string.ft4_clear_decodes)) }
    }
    if (state.receive.messages.isEmpty()) Text(stringResource(R.string.jtty_waiting),
        modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
        items(state.receive.messages.asReversed(), key = { it.id }) { message ->
            val highlighted = state.theirCall.isNotBlank() && state.theirCall in message.calls
            Column(Modifier.fillMaxWidth().background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable { message.calls.firstOrNull { it != state.myCall }?.let { action(JttyAction.TheirCall(it)) } }
                .heightIn(min = 48.dp).padding(8.dp)) {
                DigitalMessageContent(message.text + if (message.ended && !message.complete) " …" else "",
                    String.format(Locale.US, "%s UTC · %+d dB · %.0f Hz",
                        digitalUtc(message.utcMillis), message.snrDb.toInt(), message.frequencyHz))
            }
        }
    }
}
