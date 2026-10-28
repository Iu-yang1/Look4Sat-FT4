package com.rtbishop.look4sat.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlinx.coroutines.launch

@Composable
internal fun RadioAudioDialog(
    initial: RadioAudioSettings,model: String,session: IRadioAudioSession,
    onDismiss: ()->Unit,onSave: (RadioAudioSettings)->Unit
) {
    var backend by remember { mutableStateOf(initial.backend) }
    var input by remember { mutableStateOf(initial.rxDeviceKey) }
    var output by remember { mutableStateOf(initial.txDeviceKey) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.controlPort.toString()) }
    var networkCat by remember { mutableStateOf(initial.networkCat) }
    var txLatency by remember { mutableStateOf(initial.txLatencyMillis.toString()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf(emptyList<String>()) }
    val scope=rememberCoroutineScope()
    val devices=remember { session.devices() }
    SharedDialog(title=stringResource(R.string.radio_audio_title),onCancel=onDismiss,onAccept={
        try {
            val correction=txLatency.toLongOrNull()
            require(correction!=null && correction in -500..1000) { "Audio timing correction must be between -500 and 1000 ms" }
            val value=RadioAudioSettings(backend,input,output,host.trim(),port.toIntOrNull() ?: 0,networkCat && backend==RadioAudioBackend.ICOM_WLAN,correction)
            when(backend) {
                RadioAudioBackend.ANDROID_DEVICE -> require(input.isNotBlank() && output.isNotBlank()) { "Select RX and TX audio devices" }
                RadioAudioBackend.ICOM_WLAN -> {
                    require(value.host.isNotBlank() && value.controlPort in 1..65535) { "Enter a radio address and valid port" }
                    if(username.isNotBlank() || password.isNotBlank()) session.saveCredentials(username,password,model,value)
                }
                else -> Unit
            }
            onSave(value);onDismiss()
        } catch(e: Exception) { error=e.message }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(RadioAudioBackend.UNCONFIGURED,RadioAudioBackend.ANDROID_DEVICE).plus(
                if(model==RadioControlSettings.MODEL_ICOM_IC705) listOf(RadioAudioBackend.ICOM_WLAN) else emptyList()).forEach { option ->
                FilterChip(selected=backend==option,onClick={ backend=option },label={ Text(stringResource(when(option) {
                    RadioAudioBackend.UNCONFIGURED -> R.string.radio_audio_none
                    RadioAudioBackend.ANDROID_DEVICE -> R.string.radio_audio_usb
                    RadioAudioBackend.ICOM_WLAN -> R.string.radio_audio_wlan
                })) })
            }
            if(backend==RadioAudioBackend.ANDROID_DEVICE) {
                Text(stringResource(R.string.radio_audio_rx))
                devices.filter { it.input }.forEach { device ->
                    FilterChip(selected=input==device.key,onClick={ input=device.key },label={ Text(device.name) })
                }
                Text(stringResource(R.string.radio_audio_tx))
                devices.filterNot { it.input }.forEach { device ->
                    FilterChip(selected=output==device.key,onClick={ output=device.key },label={ Text(device.name) })
                }
                if(devices.isEmpty()) Text(stringResource(R.string.radio_audio_no_device))
            }
            if(backend==RadioAudioBackend.ICOM_WLAN) {
                OutlinedTextField(host,{ host=it },label={ Text(stringResource(R.string.radio_audio_host)) },singleLine=true)
                OutlinedTextField(port,{ port=it },label={ Text(stringResource(R.string.radio_audio_port)) },singleLine=true)
                OutlinedTextField(username,{ username=it },label={ Text(stringResource(R.string.radio_audio_user)) },singleLine=true)
                OutlinedTextField(password,{ password=it },label={ Text(stringResource(R.string.radio_audio_password)) },
                    visualTransformation=PasswordVisualTransformation(),singleLine=true)
                Row { Checkbox(networkCat,{ networkCat=it });Text(stringResource(R.string.radio_audio_network_cat)) }
                Text(stringResource(R.string.radio_audio_wireless_manual),style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!discovering,onClick={ scope.launch {
                    discovering=true;error=null
                    try { discovered=session.discover() } catch(e:Exception) { error=e.message } finally { discovering=false }
                } }) { Text(stringResource(R.string.radio_audio_discover)) }
                discovered.forEach { address -> TextButton(onClick={ host=address }) { Text(address) } }
            }
            if(backend!=RadioAudioBackend.UNCONFIGURED) OutlinedTextField(txLatency,{ txLatency=it },
                label={ Text(stringResource(R.string.radio_audio_tx_latency)) },singleLine=true,
                supportingText={ Text(stringResource(R.string.radio_audio_tx_latency_hint)) })
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    }
}
