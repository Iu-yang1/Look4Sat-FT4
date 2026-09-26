/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.radar

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.presentation.SwipeController
import com.rtbishop.look4sat.core.presentation.SwipeRevealRow
import com.rtbishop.look4sat.core.presentation.rememberSwipeController
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.presentation.EmptyListCard

private const val LOG_WINDOW_MS = 24 * 3_600_000L

@Composable
fun LogPage(
    uiState: RadarState,
    logViewModel: LogViewModel,
    modifier: Modifier = Modifier
) {
    val logUiState by logViewModel.uiState.collectAsStateWithLifecycle()
    val records by logViewModel.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val swipeController = rememberSwipeController()

    val selectedRadio = remember(uiState.transceivers.transmitters, uiState.transceivers.selectedUuid) {
        uiState.transceivers.transmitters.firstOrNull { it.uuid == uiState.transceivers.selectedUuid }
    }
    // Linear if the satellite carries ANY named linear transponder — same test
    // that gates the Calculator tab (RadarScreen), so the mode selector and the
    // calculator always appear together regardless of which entry is selected.
    val isLinear = uiState.transceivers.transmitters.any(DopplerFrequencyCalculator::isNamedLinearTransponder)
    val catnum = uiState.currentPass?.catNum ?: selectedRadio?.catnum ?: 0
    val satName = uiState.currentPass?.name?.trim().orEmpty()

    LaunchedEffect(catnum) {
        if (catnum != 0) logViewModel.selectSatellite(catnum)
    }

    val txHz = uiState.calculatorTxHz ?: remember(selectedRadio) {
        selectedRadio?.uplinkLow?.let { low ->
            selectedRadio.uplinkHigh?.let { high -> (low + high) / 2 } ?: low
        }
    }
    val rxHz = uiState.calculatorRxHz ?: remember(selectedRadio) {
        selectedRadio?.downlinkLow?.let { low ->
            selectedRadio.downlinkHigh?.let { high -> (low + high) / 2 } ?: low
        }
    }
    val mode = if (isLinear) logUiState.selectedMode.ifBlank { "CW" } else "FM"
    val now = System.currentTimeMillis()
    val recent = records
        .filter { it.satelliteName.trim().equals(satName, true) && it.startUtcMillis > now - LOG_WINDOW_MS }
        .sortedByDescending { it.startUtcMillis }
    val maxElev = uiState.currentPass?.maxElevation ?: 0.0

    Column(modifier = modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = logUiState.callsignInput,
                onValueChange = logViewModel::updateCallsign,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Callsign", fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    logViewModel.record(satName, mode, txHz, rxHz, logUiState.stationGrid)
                })
            )
            Button(
                onClick = { logViewModel.record(satName, mode, txHz, rxHz, logUiState.stationGrid) },
                enabled = logUiState.callsignInput.isNotBlank() && satName.isNotBlank()
            ) { Text("Log") }
        }
        if (isLinear) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("CW", "SSB", "FT4").forEach { candidate ->
                    FilterChip(
                        selected = mode == candidate,
                        onClick = { logViewModel.selectMode(catnum, candidate) },
                        label = { Text(candidate, fontSize = 13.sp) }
                    )
                }
            }
        } else {
            Text("FM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (recent.isEmpty()) {
            EmptyListCard(message = "No QSOs yet — type a callsign and tap Log")
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(recent, key = { it.id }) { record ->
                    LogRecordRow(record, swipeController, onDelete = { logViewModel.delete(record.id) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { logViewModel.generatePost(satName, maxElev) },
                enabled = recent.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("生成通联记录", fontSize = 13.sp) }
            OutlinedButton(
                onClick = logViewModel::prepareUpload,
                enabled = !logUiState.busy,
                modifier = Modifier.weight(1f)
            ) { Text("上传 LoTW", fontSize = 13.sp) }
        }
        if (logUiState.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                Text("Working…", fontSize = 13.sp)
            }
        }
    }

    logUiState.postText?.let { text ->
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        PostDialog(
            text = text,
            onDismiss = logViewModel::dismissPost,
            onCopy = {
                clipboard.setText(AnnotatedString(text))
                logViewModel.dismissPost()
            },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Share QSO log"))
                logViewModel.dismissPost()
            }
        )
    }

    logUiState.preview?.let { preview ->
        UploadPreviewDialog(
            preview = preview,
            busy = logUiState.busy,
            onConfirm = logViewModel::confirmUpload,
            onDismiss = logViewModel::dismissPreview
        )
    }

    if (logUiState.message.isNotBlank()) {
        AlertDialog(
            onDismissRequest = logViewModel::clearMessage,
            title = { Text("LoTW Upload") },
            text = { Text(logUiState.message) },
            confirmButton = {
                TextButton(onClick = logViewModel::clearMessage) { Text("OK") }
            }
        )
    }
}

@Composable
private fun LogRecordRow(record: QsoRecord, swipeController: SwipeController, onDelete: () -> Unit) {
    val time = remember(record.startUtcMillis) {
        java.text.SimpleDateFormat("HH:mm'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(record.startUtcMillis))
    }
    // 右划露出删除按钮（短信式）；整行点击不再删除。
    SwipeRevealRow(
        key = record.id.toString(),
        controller = swipeController,
        revealAction = onDelete,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "$time ", fontSize = 14.sp, maxLines = 1)
                Text(
                    text = record.theirCallsign,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFFFE082),
                    maxLines = 1
                )
                Text(text = "  ${record.displayMode}", fontSize = 14.sp, maxLines = 1)
            }
            when {
                record.lotwConfirmed -> Text("QSL", fontSize = 12.sp, color = Color(0xFFFFE082), fontFamily = FontFamily.Monospace)
                record.lotwUploaded -> Text("UP", fontSize = 12.sp, color = Color(0xFFFFE082), fontFamily = FontFamily.Monospace)
                else -> Text("", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PostDialog(
    text: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通联记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onShare) { Text("Share") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun UploadPreviewDialog(
    preview: com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传到 LoTW") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${preview.callsign}  DXCC ${preview.dxcc}  Grid ${preview.grid}", fontSize = 13.sp)
                Text("${preview.count} QSO(s) · ${
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date(preview.firstUtc.let {
                        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.parse(it).time }.getOrDefault(System.currentTimeMillis())
                    }))
                } – ${
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date(preview.lastUtc.let {
                        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.parse(it).time }.getOrDefault(System.currentTimeMillis())
                    }))
                }Z", fontSize = 13.sp)
                Text(preview.contacts.joinToString("\n") { it }, fontSize = 12.sp, maxLines = 8)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text("确认上传") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
