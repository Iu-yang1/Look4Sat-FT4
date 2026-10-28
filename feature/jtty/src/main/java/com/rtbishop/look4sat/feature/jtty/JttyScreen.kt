// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.feature.jtty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.presentation.DigitalRadarSizeButton
import com.rtbishop.look4sat.core.presentation.DigitalSatellitePanel
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.formatFrequency

@Composable
fun JttyDestination(navigateUp: () -> Unit, navigateToLogbook: () -> Unit) {
    val context=LocalContext.current
    val container=(context.applicationContext as IContainerProvider).getMainContainer()
    val model: JttyViewModel=viewModel(factory=JttyViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) model.onAction(JttyAction.Receive)
    }
    val bluetooth=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) model.onAction(JttyAction.Connect)
    }
    LaunchedEffect(Unit) { model.enter() }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) model.leave() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);model.leave() }
    }
    BackHandler { model.leave();navigateUp() }
    JttyScreen(state,model::onAction,{model.leave();navigateUp()},navigateToLogbook,
        receive={
            if(state.receive.receiving || state.networkAudio || ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                model.onAction(JttyAction.Receive)
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        },connect={
            val config=container.settingsRepo.radioControlSettings.value
            val needsBluetooth=config.catTransport==RadioControlSettings.TRANSPORT_BLUETOOTH && !config.radioAudio.networkCat
            if(needsBluetooth && Build.VERSION.SDK_INT>=31 && ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
                bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else model.onAction(JttyAction.Connect)
        })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JttyScreen(
    state: JttyState, action: (JttyAction)->Unit, back: ()->Unit, logbook: ()->Unit,
    receive: ()->Unit, connect: ()->Unit
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var large by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var template by rememberSaveable { mutableStateOf("") }
    var frequency by rememberSaveable { mutableStateOf("1500") }
    Box(Modifier.fillMaxSize()) {
    Scaffold(topBar={
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(6.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back) { Icon(painterResource(R.drawable.ic_back),stringResource(R.string.jtty_back)) }
            Text("JTTY",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            DigitalRadarSizeButton(large,{large=it})
            IconButton(onClick=logbook) { Icon(painterResource(R.drawable.ic_logbook),stringResource(R.string.nav_logbook)) }
        }
    },bottomBar={
        NavigationBar(Modifier.navigationBarsPadding()) {
            listOf(R.string.jtty_page_radio,R.string.ft4_page_spectrum,R.string.nav_logbook).forEachIndexed { index,label ->
                NavigationBarItem(selected=page==index,onClick={page=index},icon={
                    Icon(painterResource(if(index==2) R.drawable.ic_logbook else if(index==1) R.drawable.ic_spectrum_ft8cn else R.drawable.ic_radios),null)
                },label={Text(stringResource(label),maxLines=1)})
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={expanded=!expanded},modifier=Modifier.weight(1f)) {
                    Text(state.pass?.name ?: stringResource(R.string.ft4_no_pass))
                }
                Text(stringResource(if(state.radio.txConnected || state.radio.rxConnected) R.string.jtty_control_connected else R.string.jtty_control_disconnected),style=MaterialTheme.typography.labelSmall)
            }
            if(expanded) {
                DigitalSatellitePanel(state.pass?.name.orEmpty(),state.radio.selectedTransponder?.info.orEmpty(),state.position,state.track,large) {
                    Text("RX "+(state.radio.rxFrequencyHz?.let(::formatFrequency) ?: "—"),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
                    Text("TX "+(state.radio.txFrequencyHz?.let(::formatFrequency) ?: "—"),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
                }
                if(state.transponders.isNotEmpty()) {
                    var choose by remember { mutableStateOf(false) }
                    TextButton(onClick={choose=true},enabled=!state.busy) { Text(stringResource(R.string.jtty_transponder)) }
                    DropdownMenu(expanded=choose,onDismissRequest={choose=false}) {
                        state.transponders.forEach { item -> DropdownMenuItem(text={Text(item.info)},onClick={action(JttyAction.Transponder(item));choose=false}) }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=connect,enabled=!state.busy,modifier=Modifier.weight(1f)) { Text(stringResource(R.string.ft4_connect_radios)) }
                    OutlinedButton(onClick={action(JttyAction.Track)},enabled=!state.busy,modifier=Modifier.weight(1f)) {
                        Text(stringResource(if(state.radio.isActive) R.string.ft4_stop_tracking else R.string.ft4_start_tracking))
                    }
                }
            }
            Text(stringResource(if(state.audioReady) R.string.radio_audio_ready else R.string.radio_audio_waiting),style=MaterialTheme.typography.bodySmall)
            (state.error ?: state.receive.error ?: state.radio.lastCommandError)?.let { error ->
                TextButton(onClick={action(JttyAction.DismissError)}) { Text(error,color=MaterialTheme.colorScheme.error) }
            }
            if(page!=2) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=receive,modifier=Modifier.weight(1f)) { Text(stringResource(if(state.receive.receiving) R.string.jtty_stop_receive else R.string.jtty_start_receive)) }
                    OutlinedButton(onClick={action(JttyAction.Clear)},modifier=Modifier.weight(1f)) { Text(stringResource(R.string.jtty_clear)) }
                }
                if(page==1) Spectrum(state)
                Text(stringResource(R.string.jtty_rx_carriers),style=MaterialTheme.typography.titleSmall)
                state.receive.carriers.forEach { carrier ->
                    TextButton(onClick={action(JttyAction.RxFrequency(carrier.frequencyHz))}) {
                        Text("${carrier.frequencyHz.toInt()} Hz · ${carrier.strengthDb.toInt()} dB"+(if(carrier.confirmed) " ✓" else ""))
                    }
                }
                if(state.receive.messages.isEmpty()) Text(stringResource(R.string.jtty_waiting),style=MaterialTheme.typography.bodyMedium)
                state.receive.messages.takeLast(30).forEach { message ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text("${message.frequencyHz.toInt()} Hz · ${message.snrDb.toInt()} dB"+
                                if(message.ended && !message.complete) " · "+stringResource(R.string.jtty_incomplete) else "",style=MaterialTheme.typography.labelSmall)
                            Text(message.text)
                            Row { message.calls.take(4).forEach { call -> TextButton(onClick={action(JttyAction.TheirCall(call))}) { Text(call) } } }
                        }
                    }
                }
            }
            if(page!=1) {
                HorizontalDivider()
                OutlinedTextField(state.theirCall,{action(JttyAction.TheirCall(it))},label={Text(stringResource(R.string.jtty_their_call))},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(state.myCall,{action(JttyAction.MyCall(it))},label={Text(stringResource(R.string.jtty_my_call))},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text(stringResource(R.string.jtty_grid,state.grid),style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.sentReport,{action(JttyAction.SentReport(it))},label={Text(stringResource(R.string.jtty_sent))},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(state.receivedReport,{action(JttyAction.ReceivedReport(it))},label={Text(stringResource(R.string.jtty_received))},singleLine=true,modifier=Modifier.weight(1f))
                }
            }
            if(page==0) {
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    state.presets.forEachIndexed { index,preset ->
                        OutlinedCard(Modifier.weight(1f).combinedClickable(enabled=!state.busy,
                            onClick={action(JttyAction.Preset(index))},onLongClick={editing=index;template=preset})) {
                            Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text("F${index+1}",color=MaterialTheme.colorScheme.primary)
                                Text(preset,maxLines=2,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                OutlinedTextField(state.draft,{action(JttyAction.Draft(it))},enabled=!state.busy,label={Text(stringResource(R.string.jtty_message))},maxLines=4,modifier=Modifier.fillMaxWidth())
                state.draftError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                OutlinedTextField(frequency,{frequency=it;it.toFloatOrNull()?.let { hz -> action(JttyAction.TxFrequency(hz)) }},
                    enabled=!state.busy,label={Text(stringResource(R.string.jtty_tx_frequency))},singleLine=true,
                    isError=frequency.toFloatOrNull()?.let { it.isFinite() && it in 100f..2800f }!=true,modifier=Modifier.fillMaxWidth())
                Text(stringResource(R.string.jtty_duration,state.frames ?: 0,(state.frames ?: 0)*1.888),style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={action(JttyAction.Send)},enabled=state.transmitReady &&
                        frequency.toFloatOrNull()?.let { it.isFinite() && it in 100f..2800f }==true,modifier=Modifier.weight(1f)) { Text(stringResource(R.string.jtty_send)) }
                    OutlinedButton(onClick={action(JttyAction.Stop)},modifier=Modifier.weight(1f)) { Text(stringResource(R.string.jtty_stop_tx)) }
                }
            }
            if(page==2) {
                Button(onClick={action(JttyAction.SaveLog)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.jtty_save_log)) }
                if(state.saved) Text(stringResource(R.string.jtty_saved),color=MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.jtty_local_log),style=MaterialTheme.typography.bodySmall)
                state.records.forEach { record ->
                    ListItem(headlineContent={Text(record.theirCallsign)},supportingContent={Text(record.satelliteName+" · "+record.sentReport+" / "+record.receivedReport)})
                }
                OutlinedButton(onClick=logbook,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.nav_logbook)) }
            }
        }
    }
    if(editing>=0) {
        BackHandler {editing=-1}
        // Keep the editor in the same window, as in the CW console. This also
        // preserves the active audio/spectrum host while editing a preset.
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha=0.64f))
            .clickable {editing=-1}.safeDrawingPadding().imePadding().padding(16.dp),contentAlignment=Alignment.Center) {
            Surface(onClick={},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,
                modifier=Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=maxHeight)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("F${editing+1}",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                    OutlinedTextField(template,{template=it.take(240)},label={Text(stringResource(R.string.jtty_template))},maxLines=6,
                        modifier=Modifier.fillMaxWidth().testTag("jttyPresetText"))
                    FlowRow {
                        listOf("CALL","MYCALL","RST","GRID").forEach { variable ->
                            TextButton(onClick={template=(template+" {$variable}").trim().take(240)}) {Text("{$variable}")}
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick={editing=-1}) {Text(stringResource(R.string.jtty_cancel))}
                        TextButton(onClick={action(JttyAction.SavePreset(editing,template));editing=-1}) {Text(stringResource(R.string.jtty_save))}
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Spectrum(state: JttyState) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val bins=state.receive.spectrum?.magnitudesDb ?: return@Canvas
        for(i in 1 until bins.size) {
            val y1=size.height*(1-(bins[i-1]+100).coerceIn(0f,100f)/100)
            val y2=size.height*(1-(bins[i]+100).coerceIn(0f,100f)/100)
            drawLine(color,Offset((i-1)*size.width/bins.size,y1),Offset(i*size.width/bins.size,y2))
        }
    }
}
