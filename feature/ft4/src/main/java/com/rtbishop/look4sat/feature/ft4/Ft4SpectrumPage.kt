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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.audio.AudioSampleFormat
import com.rtbishop.look4sat.core.domain.ft4.Ft4EngineState
import com.rtbishop.look4sat.core.presentation.R

@Composable
internal fun Ft4SpectrumPage(
    state: Ft4State,
    renderState: SpectrumRenderState,
    onAction: (Ft4Action) -> Unit,
    requestMicrophone: () -> Unit,
    onAudio: () -> Unit,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val frame = renderState.frame()
    val inputLevel = frame?.inputLevelDb ?: -120f
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        com.rtbishop.look4sat.core.presentation.DigitalSpectrumPane(
            renderState, state.selectedAudioFrequencyHz,
            { if (!state.radio.frequencyEditingLocked &&
                (state.transmitState is com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState.Idle ||
                    state.transmitState is com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState.Failed))
                onAction(Ft4Action.SelectAudioFrequency(it)) },
            stringResource(R.string.digital_tx_frequency, state.selectedAudioFrequencyHz.toInt()),
            state.isReceiving, onAudio, expanded, onExpanded
        ) {
            Text(stringResource(R.string.ft4_spectrum_range))
            Text(stringResource(R.string.ft4_input_level, inputLevel))
            Text(audioOwnerText(state.audioHub),
                color = if (state.audioHub.isUnavailable()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
            ReceiveDiagnostics(state.engineState)
        }
        spectrumBlockingMessage(state, frame == null)?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.hasMicrophonePermission) {
            androidx.compose.material3.TextButton(onClick = requestMicrophone) {
                Text(stringResource(R.string.ft4_grant_microphone))
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
