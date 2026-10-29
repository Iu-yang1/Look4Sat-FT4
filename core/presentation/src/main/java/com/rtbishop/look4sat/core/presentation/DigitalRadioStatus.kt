// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2019-2026 Arty Bishop and contributors.
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.repository.TrackingPhase

@Immutable
data class DigitalRadioPanelState(
    val radio: RadioTrackingState,
    val pass: OrbitalPass?,
    val orbitalPosition: OrbitalPos?,
    val satelliteTrack: List<OrbitalPos>,
    val orientationValues: Pair<Float, Float>,
    val shouldShowSweep: Boolean,
    val shouldUseCompass: Boolean
)

@Composable
fun DigitalRadioStatus(
    state: DigitalRadioPanelState,
    disconnectRadios: () -> Unit,
    toggleTracking: () -> Unit,
    emergencyStop: () -> Unit,
    connectRadios: () -> Unit,
    navigateToPasses: () -> Unit,
    largeRadar: Boolean,
    onRadarSizeChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val radio = state.radio
    val trackingInProgress = radio.isActive || radio.trackingPhase == TrackingPhase.INITIALIZING
    val pass = state.pass
    val transponder = radio.selectedTransponder
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ft4_satellite_radio), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (trackingInProgress) stringResource(R.string.ft4_tracking_on)
                    else stringResource(R.string.ft4_tracking_off),
                    color = if (trackingInProgress) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                com.rtbishop.look4sat.core.presentation.DigitalRadarSizeButton(largeRadar,onRadarSizeChange)
                Icon(
                    painterResource(if (expanded) R.drawable.ic_arrow else R.drawable.ic_add),
                    contentDescription = stringResource(
                        if (expanded) R.string.ft4_collapse_status else R.string.ft4_expand_status
                    )
                )
            }
            if (pass == null || transponder == null) {
                Text(stringResource(R.string.ft4_no_pass))
                Text(stringResource(R.string.ft4_select_pass_hint), fontSize = 12.sp)
                CardButton(
                    onClick = navigateToPasses,
                    text = stringResource(R.string.ft4_go_to_passes),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                if (expanded) {
                    com.rtbishop.look4sat.core.presentation.DigitalSatellitePanel(
                        pass.name,transponder.info,state.orbitalPosition,state.satelliteTrack,largeRadar,
                        state.orientationValues,state.shouldShowSweep,state.shouldUseCompass
                    ) { if(!largeRadar) FrequencyStatus(state,showNominal=false) }
                    if(largeRadar) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FrequencyStatus(state)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RadioControlStatus(radio)
                    radio.lastCommandError?.let {
                        Text(
                            stringResource(R.string.ft4_error_radio_command),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardButton(
                            onClick = if (radio.txConnected || radio.rxConnected) {
                                disconnectRadios
                            } else connectRadios,
                            text = stringResource(
                                if (radio.txConnected || radio.rxConnected) R.string.ft4_disconnect_radios
                                else R.string.ft4_connect_radios
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        CardButton(
                            onClick = toggleTracking,
                            text = stringResource(
                                if (trackingInProgress) R.string.ft4_stop_tracking else R.string.ft4_start_tracking
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    CardButton(
                        onClick = emergencyStop,
                        text = stringResource(R.string.ft4_emergency_stop),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.ft4_radar_summary,
                            pass.name,
                            state.orbitalPosition?.let { Math.toDegrees(it.azimuth) } ?: radio.azimuth,
                            state.orbitalPosition?.let { Math.toDegrees(it.elevation) } ?: radio.elevation
                        ),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FrequencyStatus(state: DigitalRadioPanelState, showNominal: Boolean = true) {
    val radio = state.radio
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Ft4FrequencyRow(
            label = stringResource(R.string.ft4_tx_label),
            frequencyHz = radio.txFrequencyHz ?: radio.nominalTxFrequencyHz,
            nominalHz = radio.nominalTxFrequencyHz.takeIf { showNominal },
            correctionHz = radio.txDopplerCorrectionHz,
            connected = radio.txConnected
        )
        Ft4FrequencyRow(
            label = stringResource(R.string.ft4_rx_label),
            frequencyHz = radio.rxFrequencyHz ?: radio.nominalRxFrequencyHz,
            nominalHz = radio.nominalRxFrequencyHz.takeIf { showNominal },
            correctionHz = radio.rxDopplerCorrectionHz,
            connected = radio.rxConnected
        )
    }
}

@Composable
private fun Ft4FrequencyRow(
    label: String,
    frequencyHz: Long?,
    nominalHz: Long?,
    correctionHz: Long?,
    connected: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ft4_label_value, label),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(
                text = frequencyHz?.let(::formatFrequency) ?: stringResource(R.string.radar_no_link),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (nominalHz != null) {
                Text(
                    text = stringResource(
                        R.string.ft4_frequency_detail,
                        formatFrequency(nominalHz),
                        correctionHz ?: 0L
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        ConnectionDot(connected, Modifier.width(30.dp))
    }
}

@Composable
private fun RadioControlStatus(radio: RadioTrackingState) {
    val notSet = stringResource(R.string.ft4_not_set)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_radios),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.ft4_radio_control_status),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        RadioConnectionRow(stringResource(R.string.ft4_tx_label), radio.txMode ?: notSet, radio.txConnected)
        RadioConnectionRow(stringResource(R.string.ft4_rx_label), radio.rxMode ?: notSet, radio.rxConnected)
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    when {
                        radio.satelliteMode -> R.string.ft4_satellite_mode
                        radio.splitMode -> R.string.ft4_split
                        else -> R.string.ft4_dual_radio
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Text(
                text = stringResource(R.string.ft4_ptt_status, radio.pttState.name),
                color = when (radio.pttState) {
                    PttState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                    PttState.ARMING -> MaterialTheme.colorScheme.primary
                    PttState.ON, PttState.ERROR -> MaterialTheme.colorScheme.error
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(if (radio.commandBusy) R.string.ft4_cat_busy else R.string.ft4_cat_ready),
                color = if (radio.commandBusy) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun RadioConnectionRow(label: String, mode: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ft4_label_value, label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.width(30.dp)
        )
        Text(text = mode, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(if (connected) R.string.ft4_connected else R.string.ft4_disconnected),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        ConnectionDot(connected)
    }
}

@Composable
private fun ConnectionDot(connected: Boolean, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
        )
    }
}
