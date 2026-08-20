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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.ft4.Ft4DecodeResult
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState
import com.rtbishop.look4sat.core.presentation.CardButton
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
        DecodeTable(state, onAction)
        CallingCard(state, onAction)
        Ft4AutomationSection(state, onAction, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DecodeTable(state: Ft4State, onAction: (Ft4Action) -> Unit) {
    val durationMillis = (state.engineState as? com.rtbishop.look4sat.core.domain.ft4.Ft4EngineState.Receiving)
        ?.lastDecodeDurationMillis
    val listState = rememberLazyListState()
    LaunchedEffect(state.decodeResults.firstOrNull()?.stableId) {
        if (state.decodeResults.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem(0)
        }
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.ft4_decode_count, state.decodeResults.size),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = durationMillis?.let { stringResource(R.string.ft4_decode_duration, it) }
                            ?: stringResource(R.string.ft4_decode_duration_pending),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    TextButton(onClick = { onAction(Ft4Action.ClearDecodes) }) {
                        Text(stringResource(R.string.ft4_clear_decodes))
                    }
                }
            }
            HorizontalDivider()
            DecodeTableHeader()
            if (state.decodeResults.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
                ) {
                    Text(
                        stringResource(R.string.ft4_decode_empty),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp)
                ) {
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
    }
}

@Composable
private fun DecodeTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        TableCell(stringResource(R.string.ft4_decode_utc), 66.dp)
        TableCell(stringResource(R.string.ft4_decode_snr), 42.dp)
        TableCell(stringResource(R.string.ft4_decode_dt), 46.dp)
        TableCell(stringResource(R.string.ft4_decode_hz), 54.dp)
        Text(stringResource(R.string.ft4_decode_message), fontSize = 10.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DecodeRow(result: Ft4DecodeResult, highlighted: Boolean, onClick: () -> Unit) {
    val evenSlot = Math.floorMod(Math.floorDiv(result.slotUtcMillis, FT4_SLOT_MILLIS), 2L) == 0L
    val slotColor = if (evenSlot) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val background = slotColor.copy(
        alpha = when {
            highlighted -> 0.85f
            result.text.startsWith("CQ ") -> 0.55f
            else -> 0.28f
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .pointerInput(result.stableId) {
                val threshold = 48.dp.toPx()
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(distance) >= threshold) onClick()
                        distance = 0f
                    },
                    onDragCancel = { distance = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        distance += amount
                    }
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        TableCell(formatDecodeUtc(result.slotUtcMillis), 66.dp)
        TableCell(String.format(Locale.US, "%+d", result.snr), 42.dp)
        TableCell(String.format(Locale.US, "%.1f", result.dtSeconds), 46.dp)
        TableCell(String.format(Locale.US, "%.0f", result.frequencyHz), 54.dp)
        Text(result.text, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun TableCell(value: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        value,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width)
    )
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
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SectionHeader(R.drawable.ic_radio_tower, stringResource(R.string.ft4_calling_controls))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.ft4_tx_audio),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    stringResource(R.string.ft4_selected_frequency, state.selectedAudioFrequencyHz),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = state.selectedAudioFrequencyHz,
                onValueChange = { onAction(Ft4Action.SelectAudioFrequency(it)) },
                valueRange = 200f..2_800f,
                steps = 25
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.txSlotParity == 0,
                    onClick = { onAction(Ft4Action.SetTxSlotParity(0)) },
                    label = { Text(stringResource(R.string.ft4_even_slot)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = state.txSlotParity == 1,
                    onClick = { onAction(Ft4Action.SetTxSlotParity(1)) },
                    label = { Text(stringResource(R.string.ft4_odd_slot)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                stringResource(R.string.ft4_message_preview, preview),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            )
            if (state.manualTimeWarning) {
                Text(
                    stringResource(R.string.ft4_manual_time_warning),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = { onAction(Ft4Action.ManualTransmit) },
                    text = stringResource(R.string.ft4_transmit_next_slot),
                    enabled = !transmitting && state.settings.decodeEnabled && state.capability.transmitAvailable,
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = { onAction(Ft4Action.StopTransmit) },
                    text = stringResource(R.string.ft4_stop_transmit),
                    enabled = transmitting,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionHeader(iconRes: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}

private fun formatDecodeUtc(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))

private const val FT4_SLOT_MILLIS = 7_500L
