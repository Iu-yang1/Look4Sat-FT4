/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.ft4

import com.rtbishop.look4sat.core.presentation.SpectrumRenderState
import com.rtbishop.look4sat.core.presentation.DigitalSpectrumWaterfall
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.audio.AudioSampleFormat
import com.rtbishop.look4sat.core.domain.ft4.Ft4EngineState
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun Ft4SpectrumPage(
    state: Ft4State,
    viewModel: Ft4ViewModel,
    onAction: (Ft4Action) -> Unit,
    requestMicrophone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val renderState = remember { SpectrumRenderState() }
    val shouldCollectSpectrum = state.settings.decodeEnabled &&
        state.hasMicrophonePermission &&
        state.capability.receiveAvailable &&
        state.isReceiving
    LaunchedEffect(viewModel, shouldCollectSpectrum) {
        if (shouldCollectSpectrum) {
            viewModel.spectrumFrames.collectLatest(renderState::update)
        }
    }
    val frame = renderState.frame()
    val inputLevel = frame?.inputLevelDb ?: -120f
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth().height(380.dp)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DigitalSpectrumWaterfall(
                    renderState = renderState,
                    selectedFrequencyHz = state.selectedAudioFrequencyHz,
                    onFrequencySelected = { onAction(Ft4Action.SelectAudioFrequency(it)) },
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
                spectrumBlockingMessage(state, frame == null)?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ft4_spectrum_range), fontSize = 11.sp)
                    Text(stringResource(R.string.ft4_input_level, inputLevel), fontSize = 11.sp)
                    Text(stringResource(R.string.ft4_selected_frequency, state.selectedAudioFrequencyHz), fontSize = 11.sp)
                }
                Text(
                    audioOwnerText(state.audioHub),
                    fontSize = 11.sp,
                    color = if (state.audioHub.isUnavailable()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                ReceiveDiagnostics(state.engineState)
                if (!state.hasMicrophonePermission) {
                    Text(stringResource(R.string.ft4_microphone_required), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        if (state.hasMicrophonePermission) onAction(Ft4Action.ToggleReceiving)
                        else requestMicrophone()
                    },
                    enabled = state.settings.decodeEnabled && state.capability.receiveAvailable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            when {
                                !state.hasMicrophonePermission -> R.string.ft4_grant_microphone
                                state.isReceiving -> R.string.ft4_stop_receive
                                else -> R.string.ft4_start_receive
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun spectrumBlockingMessage(state: Ft4State, hasNoFrame: Boolean): String? = when {
    !state.settings.decodeEnabled -> stringResource(R.string.ft4_status_disabled)
    !state.capability.receiveAvailable -> stringResource(R.string.ft4_status_unavailable)
    !state.hasMicrophonePermission -> stringResource(R.string.ft4_microphone_required)
    state.audioHub is AudioHubState.Failed -> stringResource(R.string.ft4_error_audio_input)
    hasNoFrame -> stringResource(R.string.ft4_waiting_audio)
    else -> null
}

@Composable
internal fun clockSourceLabel(source: com.rtbishop.look4sat.core.domain.time.ClockSource): String =
    stringResource(
        when (source) {
            com.rtbishop.look4sat.core.domain.time.ClockSource.SYSTEM -> R.string.ft4_clock_system
            com.rtbishop.look4sat.core.domain.time.ClockSource.NTP -> R.string.ft4_clock_ntp
            com.rtbishop.look4sat.core.domain.time.ClockSource.GNSS -> R.string.ft4_clock_gnss
            com.rtbishop.look4sat.core.domain.time.ClockSource.HOLDOVER -> R.string.ft4_clock_holdover
        }
    )

@Composable
private fun audioOwnerText(state: AudioHubState): String = when (state) {
    AudioHubState.Idle -> stringResource(R.string.ft4_audio_owner_idle)
    is AudioHubState.Capturing -> if (state.systemSilenced) {
        stringResource(R.string.ft4_audio_system_silenced)
    } else {
        stringResource(
            R.string.ft4_audio_owner,
            state.deviceName.ifBlank { stringResource(R.string.ft4_audio_device_unknown) },
            state.sampleRate,
            stringResource(
                when (state.format) {
                    AudioSampleFormat.PCM_FLOAT -> R.string.ft4_audio_pcm_float
                    AudioSampleFormat.PCM_16 -> R.string.ft4_audio_pcm_16
                }
            )
        )
    }
    is AudioHubState.Failed -> stringResource(R.string.ft4_error_audio_input)
}

private fun AudioHubState.isUnavailable(): Boolean =
    this is AudioHubState.Failed || (this is AudioHubState.Capturing && systemSilenced)

@Composable
private fun ReceiveDiagnostics(engineState: Ft4EngineState) {
    val receiving = engineState as? Ft4EngineState.Receiving ?: return
    Text(
        text = receiving.lastDecodeDurationMillis?.let {
            stringResource(R.string.ft4_decode_diagnostics, it, receiving.lastDecodeResultCount)
        } ?: stringResource(R.string.ft4_decode_diagnostics_pending),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp
    )
}
