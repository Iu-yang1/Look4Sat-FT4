// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.feature.jtty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.rtbishop.look4sat.core.presentation.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    var page by rememberSaveable { mutableIntStateOf(1) }
    var large by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var template by rememberSaveable { mutableStateOf("") }
    var frequency by rememberSaveable { mutableStateOf("1500") }
    val renderState=remember { SpectrumRenderState() }
    LaunchedEffect(state.receive.spectrum?.sequence) { state.receive.spectrum?.let(renderState::update) }
    Box(Modifier.fillMaxSize()) {
    Scaffold(topBar={
        Column(Modifier.statusBarsPadding().padding(horizontal=6.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if(state.audioReady) R.string.radio_audio_ready else R.string.radio_audio_waiting),style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                IconCard(action=back,resId=R.drawable.ic_back)
                ElevatedCard(Modifier.weight(1f)) {
                    Text("JTTY",fontWeight=FontWeight.Bold,maxLines=1,modifier=Modifier.padding(horizontal=12.dp,vertical=10.dp))
                }
                IconCard(action=logbook,resId=R.drawable.ic_logbook)
            }
        }
    },bottomBar={
        NavigationBar(Modifier.navigationBarsPadding().height(58.dp),windowInsets=WindowInsets(0,0,0,0)) {
            listOf(1,0,2).forEach { index ->
                val label=when(index) {1->R.string.ft4_page_spectrum;0->R.string.jtty_page_radio;else->R.string.nav_logbook}
                NavigationBarItem(selected=page==index,onClick={page=index},icon={
                    Icon(painterResource(if(index==2) R.drawable.ic_logbook else if(index==1) R.drawable.ic_spectrum_ft8cn else R.drawable.ic_radios),null)
                },label={Text(stringResource(label),maxLines=1,fontSize=10.sp)})
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState()).padding(horizontal=6.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            DigitalRadioStatus(
                DigitalRadioPanelState(state.radio,state.pass,state.position,state.track,0f to 0f,false,false),
                {action(JttyAction.Disconnect)}, {action(JttyAction.Track)}, {action(JttyAction.EmergencyStop)},
                connect,back,large,{large=it},expanded,{expanded=it},Modifier.fillMaxWidth()
            )
            (state.error ?: state.receive.error ?: state.radio.lastCommandError)?.let { error ->
                TextButton(onClick={action(JttyAction.DismissError)}) { Text(error,color=MaterialTheme.colorScheme.error) }
            }
            if(page==1) {
                ElevatedCard(Modifier.fillMaxWidth().height(380.dp)) {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        DigitalSpectrumWaterfall(renderState,state.receive.selectedRxHz,
                            {action(JttyAction.RxFrequency(it))},Modifier.fillMaxSize().padding(8.dp),stringResource(R.string.jtty_spectrum_description))
                        if(state.receive.spectrum==null) Text(stringResource(R.string.jtty_waiting),modifier=Modifier.padding(24.dp))
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.ft4_spectrum_range),fontSize=11.sp)
                            Text(stringResource(R.string.ft4_input_level,state.receive.spectrum?.inputLevelDb ?: -120f),fontSize=11.sp)
                            Text(stringResource(R.string.ft4_selected_frequency,state.receive.selectedRxHz),fontSize=11.sp)
                        }
                        Button(onClick=receive,modifier=Modifier.fillMaxWidth()) {
                            Text(stringResource(if(state.receive.receiving) R.string.jtty_stop_receive else R.string.jtty_start_receive))
                        }
                    }
                }
                if(state.receive.carriers.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(stringResource(R.string.jtty_rx_carriers),fontWeight=FontWeight.Bold)
                        FlowRow { state.receive.carriers.forEach { carrier ->
                            TextButton(onClick={action(JttyAction.RxFrequency(carrier.frequencyHz))}) {
                                Text("${carrier.frequencyHz.toInt()} Hz · ${carrier.strengthDb.toInt()} dB"+(if(carrier.confirmed) " ✓" else ""))
                            }
                        } }
                    }
                }
            }
            if(state.transponders.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
                var choosing by remember {mutableStateOf(false)}
                TextButton(onClick={choosing=true},enabled=!state.busy) {Text(stringResource(R.string.jtty_transponder))}
                DropdownMenu(expanded=choosing,onDismissRequest={choosing=false}) {
                    state.transponders.forEach { item -> DropdownMenuItem(text={Text(item.info)},onClick={action(JttyAction.Transponder(item));choosing=false}) }
                }
            }
            if(page==0) JttyDecodeTable(state,action)
            if(page!=1) ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {

                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_radio_tower),null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(20.dp))
                    Text(stringResource(R.string.ft4_calling_controls),fontWeight=FontWeight.Bold)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(state.myCall,{action(JttyAction.MyCall(it))},label={Text(stringResource(R.string.jtty_my_call))},singleLine=true,modifier=Modifier.weight(1f))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ft4_grid),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.grid,color=MaterialTheme.colorScheme.primary,fontSize=18.sp,fontWeight=FontWeight.Bold)
                    }
                }
                OutlinedTextField(state.theirCall,{action(JttyAction.TheirCall(it))},label={Text(stringResource(R.string.jtty_their_call))},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(page==0) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.ft4_tx_audio),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.ft4_selected_frequency,state.txHz),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                    }
                    Slider(value=state.txHz.coerceIn(100f,2800f),onValueChange={frequency=it.toInt().toString();action(JttyAction.TxFrequency(it))},
                        valueRange=100f..2800f,steps=26,enabled=!state.busy)
                OutlinedTextField(frequency,{frequency=it;it.toFloatOrNull()?.let { hz -> action(JttyAction.TxFrequency(hz)) }},
                    enabled=!state.busy,label={Text(stringResource(R.string.jtty_tx_frequency))},singleLine=true,
                    isError=frequency.toFloatOrNull()?.let { it.isFinite() && it in 100f..2800f }!=true,modifier=Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.sentReport,{action(JttyAction.SentReport(it))},label={Text(stringResource(R.string.jtty_sent))},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(state.receivedReport,{action(JttyAction.ReceivedReport(it))},label={Text(stringResource(R.string.jtty_received))},singleLine=true,modifier=Modifier.weight(1f))
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
                Text(stringResource(R.string.jtty_duration,state.frames ?: 0,(state.frames ?: 0)*1.888),style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    CardButton(onClick={action(JttyAction.Send)},text=stringResource(R.string.jtty_send),
                        enabled=state.transmitReady && frequency.toFloatOrNull()?.let {it.isFinite() && it in 100f..2800f}==true,modifier=Modifier.weight(1f))
                    CardButton(onClick={action(JttyAction.Stop)},text=stringResource(R.string.jtty_stop_tx),enabled=state.busy,modifier=Modifier.weight(1f))
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
private fun JttyDecodeTable(state: JttyState, action: (JttyAction)->Unit) {
    val utc=remember { SimpleDateFormat("HH:mm:ss",Locale.US).apply {timeZone=TimeZone.getTimeZone("UTC")} }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.ft4_decode_count,state.receive.messages.size),fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            TextButton(onClick={action(JttyAction.Clear)}) {Text(stringResource(R.string.ft4_clear_decodes))}
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(stringResource(R.string.ft4_decode_utc),fontSize=10.sp,modifier=Modifier.width(66.dp))
            Text(stringResource(R.string.ft4_decode_snr),fontSize=10.sp,modifier=Modifier.width(42.dp))
            Text(stringResource(R.string.ft4_decode_hz),fontSize=10.sp,modifier=Modifier.width(54.dp))
            Text(stringResource(R.string.ft4_decode_message),fontSize=10.sp)
        }
        if(state.receive.messages.isEmpty()) Box(Modifier.fillMaxWidth().heightIn(min=220.dp),contentAlignment=Alignment.Center) {
            Text(stringResource(R.string.jtty_waiting),color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(16.dp))
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(min=220.dp,max=420.dp)) {
            items(state.receive.messages.asReversed(),key={it.id}) { message ->
                val highlighted=state.theirCall.isNotBlank() && state.theirCall in message.calls
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha=if(highlighted) 0.85f else if(message.text.startsWith("CQ ")) 0.55f else 0.28f))
                    .clickable {message.calls.firstOrNull {it!=state.myCall}?.let {action(JttyAction.TheirCall(it))}}
                    .padding(horizontal=8.dp,vertical=6.dp)) {
                    Text(utc.format(Date(message.utcMillis)),fontSize=10.sp,modifier=Modifier.width(66.dp))
                    Text(String.format(Locale.US,"%+d",message.snrDb.toInt()),fontSize=10.sp,modifier=Modifier.width(42.dp))
                    Text(message.frequencyHz.toInt().toString(),fontSize=10.sp,modifier=Modifier.width(54.dp))
                    Text(message.text+if(message.ended && !message.complete) " …" else "",fontSize=11.sp,maxLines=2,modifier=Modifier.weight(1f))
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.45f))
            }
        }
    }
}
