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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.rtbishop.look4sat.core.domain.logbook.satelliteIdentity
import com.rtbishop.look4sat.core.domain.logbook.unavailableUploadSummary
import com.rtbishop.look4sat.core.presentation.SwipeController
import com.rtbishop.look4sat.core.presentation.SwipeRevealRow
import com.rtbishop.look4sat.core.presentation.rememberSwipeController
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.downlinkHz
import com.rtbishop.look4sat.core.domain.utility.uplinkHz
import com.rtbishop.look4sat.core.domain.utility.voiceRepeater
import com.rtbishop.look4sat.core.presentation.EmptyListCard
import com.rtbishop.look4sat.core.presentation.QsoEditDialog
import com.rtbishop.look4sat.core.presentation.SheetDialogTitle
import com.rtbishop.look4sat.core.presentation.sheetDialogContainerColor
import com.rtbishop.look4sat.core.presentation.sheetDialogShape

@Composable
fun LogPage(
    uiState: RadarState,
    logViewModel: LogViewModel,
    modifier: Modifier = Modifier
) {
    val logUiState by logViewModel.uiState.collectAsStateWithLifecycle()
    val records by logViewModel.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val swipeController = rememberSwipeController()
    // Record currently open in the edit dialog (null when closed).
    var editTarget by remember { mutableStateOf<QsoRecord?>(null) }

    val selectedRadio = remember(uiState.transceivers.transmitters, uiState.transceivers.selectedUuid) {
        uiState.transceivers.transmitters.firstOrNull { it.uuid == uiState.transceivers.selectedUuid }
    }
    // Linear if the satellite carries ANY named linear transponder — same test
    // that gates the Calculator tab (RadarScreen), so the mode selector and the
    // calculator always appear together regardless of which entry is selected.
    val isLinear = uiState.transceivers.transmitters.any(DopplerFrequencyCalculator::isNamedLinearTransponder)
    val catnum = uiState.currentPass?.catNum ?: selectedRadio?.catnum ?: 0
    val satName = uiState.currentPass?.name?.trim().orEmpty()
    // The satellite's FM voice repeater — the single-frequency transceiver the
    // contact is actually made through. SatNOGS publishes its nominal pair, so a
    // contact can never be logged without a frequency (which LoTW rejects as a
    // missing BAND).
    val voiceRepeater = remember(uiState.transceivers.transmitters) {
        uiState.transceivers.transmitters.voiceRepeater()
    }
    // AMSAT Live FM satellites are always logged on that fixed nominal pair,
    // even when the calculator happens to be tuned to something else.
    val useRepeater = catnum in Sources.amSatFmCatnums

    LaunchedEffect(catnum) {
        if (catnum != 0) logViewModel.selectSatellite(catnum)
    }

    val selectedTxHz = remember(selectedRadio) { selectedRadio?.uplinkHz() }
    val selectedRxHz = remember(selectedRadio) { selectedRadio?.downlinkHz() }
    val repeaterTxHz = voiceRepeater?.uplinkHz()
    val repeaterRxHz = voiceRepeater?.downlinkHz()
    val txHz = if (useRepeater) {
        repeaterTxHz ?: uiState.calculatorTxHz ?: selectedTxHz
    } else {
        uiState.calculatorTxHz ?: repeaterTxHz ?: selectedTxHz
    }
    val rxHz = if (useRepeater) {
        repeaterRxHz ?: uiState.calculatorRxHz ?: selectedRxHz
    } else {
        uiState.calculatorRxHz ?: repeaterRxHz ?: selectedRxHz
    }
    val mode = if (isLinear) logUiState.selectedMode.ifBlank { "CW" } else "FM"
    val satIdentity = remember(satName) { satelliteIdentity(satName) }
    val passWindow = remember(uiState.currentPass) {
        uiState.currentPass?.let { it.aosTime..it.losTime }
    }
    val recent = records
        .filter { satelliteIdentity(it.satelliteName) == satIdentity }
        // Only contacts inside the current pass window: the log page is the "this pass" sheet.
        .filter { passWindow?.contains(it.startUtcMillis) == true }
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
                    LogRecordRow(
                        record = record,
                        swipeController = swipeController,
                        onDelete = { logViewModel.delete(record.id) },
                        onClick = { editTarget = record }
                    )
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
            shape = sheetDialogShape(),
            containerColor = sheetDialogContainerColor(),
            title = { SheetDialogTitle("LoTW Upload") },
            text = { Text(logUiState.message) },
            confirmButton = {
                TextButton(onClick = logViewModel::clearMessage) { Text("OK") }
            }
        )
    }

    editTarget?.let { target ->
        QsoEditDialog(
            record = target,
            satelliteCandidates = logUiState.satelliteCatalog,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                logViewModel.updateRecord(updated)
                editTarget = null
            }
        )
    }
}

@Composable
private fun LogRecordRow(
    record: QsoRecord,
    swipeController: SwipeController,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
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
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "$time ", fontSize = 14.sp, maxLines = 1)
                Text(
                    text = record.theirCallsign,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Text(text = "  ${record.displayMode}", fontSize = 14.sp, maxLines = 1)
            }
            when {
                record.lotwConfirmed -> Text("QSL", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                record.lotwUploaded -> Text("UP", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
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
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle("通联记录") },
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
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle("上传到 LoTW") },
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
                if (preview.skipped > 0 || preview.unknownSkipped > 0 || preview.unavailableSkipped > 0) {
                    val parts = buildList {
                        // skipped counts every record left out of this batch: previously-uploaded
                        // ones, batch duplicates and unknown-outcome ones. Show them separately so
                        // "10 already uploaded" (historical records) is not read as this upload
                        // being rejected as a duplicate.
                        val alreadyUploaded = preview.skipped - preview.duplicateSkipped - preview.unknownSkipped
                        if (alreadyUploaded > 0) add("$alreadyUploaded already uploaded")
                        if (preview.duplicateSkipped > 0) add("${preview.duplicateSkipped} duplicate")
                        if (preview.unknownSkipped > 0) add("${preview.unknownSkipped} unknown result")
                        if (preview.unavailableSkipped > 0) add(
                            unavailableUploadSummary(
                                preview.unavailableSkipped,
                                preview.unavailableReasons,
                                duplicates = preview.duplicateSkipped
                            )
                        )
                    }
                    Text(parts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
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
