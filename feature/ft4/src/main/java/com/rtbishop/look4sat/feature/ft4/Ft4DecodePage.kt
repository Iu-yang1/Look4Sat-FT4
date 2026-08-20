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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecodeResult
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState
import com.rtbishop.look4sat.core.presentation.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun Ft4DecodePage(
    state: Ft4State,
    onAction: (Ft4Action) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp)) {
            if (state.decodeResults.isEmpty()) {
                Text(
                    stringResource(R.string.ft4_decode_empty),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.decodeResults, key = Ft4DecodeResult::stableId) { result ->
                        DecodeRow(
                            result = result,
                            highlighted = result.targetCall.equals(
                                state.settings.operatorCallsign,
                                ignoreCase = true
                            ),
                            onClick = { onAction(Ft4Action.SelectDecode(result)) }
                        )
                    }
                }
            }
        }
        CallingCard(state, onAction)
    }
}

@Composable
private fun DecodeRow(result: Ft4DecodeResult, highlighted: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).clickable(onClick = onClick)
    ) {
        Text(
            text = stringResource(
                R.string.ft4_decode_row,
                formatDecodeUtc(result.slotUtcMillis),
                result.snr,
                result.dtSeconds,
                result.frequencyHz,
                result.text
            ),
            modifier = Modifier.padding(8.dp),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun CallingCard(state: Ft4State, onAction: (Ft4Action) -> Unit) {
    val myCall = state.settings.operatorCallsign
    val notSet = stringResource(R.string.ft4_not_set)
    val preview = if (state.targetCall.isBlank()) {
        "CQ $myCall ${state.grid4}"
    } else {
        "${state.targetCall.trim()} $myCall ${state.grid4}"
    }
    val transmitting = state.transmitState !is Ft4TransmitState.Idle &&
        state.transmitState !is Ft4TransmitState.Failed
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledValue(stringResource(R.string.ft4_operator_call), myCall.ifBlank { notSet }, Modifier.weight(1f))
                LabeledValue(stringResource(R.string.ft4_grid), state.grid4.ifBlank { notSet }, Modifier.weight(1f))
            }
            OutlinedTextField(
                value = state.targetCall,
                onValueChange = { onAction(Ft4Action.SetTargetCall(it)) },
                label = { Text(stringResource(R.string.ft4_target_call)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.ft4_selected_frequency, state.selectedAudioFrequencyHz), fontSize = 12.sp)
            Slider(
                value = state.selectedAudioFrequencyHz,
                onValueChange = { onAction(Ft4Action.SelectAudioFrequency(it)) },
                valueRange = 200f..2_800f,
                steps = 25
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.txSlotParity == 0,
                    onClick = { onAction(Ft4Action.SetTxSlotParity(0)) },
                    label = { Text(stringResource(R.string.ft4_even_slot)) }
                )
                FilterChip(
                    selected = state.txSlotParity == 1,
                    onClick = { onAction(Ft4Action.SetTxSlotParity(1)) },
                    label = { Text(stringResource(R.string.ft4_odd_slot)) }
                )
                Button(onClick = { onAction(Ft4Action.ClearDecodes) }) {
                    Text(stringResource(R.string.ft4_clear_decodes))
                }
            }
            Text(
                stringResource(R.string.ft4_message_preview, preview),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            if (state.manualTimeWarning) {
                Text(
                    stringResource(R.string.ft4_manual_time_warning),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { onAction(Ft4Action.ManualTransmit) },
                    enabled = !transmitting && state.settings.decodeEnabled && state.capability.transmitAvailable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.ft4_transmit_next_slot)) }
                Button(
                    onClick = { onAction(Ft4Action.StopTransmit) },
                    enabled = transmitting,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.ft4_stop_transmit)) }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatDecodeUtc(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))
