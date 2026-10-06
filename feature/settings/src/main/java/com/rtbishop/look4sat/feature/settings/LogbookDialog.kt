/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.frequencyBand
import com.rtbishop.look4sat.core.domain.logbook.unavailableUploadSummary
import com.rtbishop.look4sat.core.presentation.LocalSpacing
import com.rtbishop.look4sat.core.presentation.QsoEditDialog
import com.rtbishop.look4sat.core.presentation.SheetDialogTitle
import com.rtbishop.look4sat.core.presentation.sheetDialogContainerColor
import com.rtbishop.look4sat.core.presentation.sheetDialogShape
import com.rtbishop.look4sat.core.presentation.gridsLabel
import com.rtbishop.look4sat.core.presentation.LoTWPositionWarningDialog
import com.rtbishop.look4sat.core.presentation.WavelogUploadPreviewDialog
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.SwipeController
import com.rtbishop.look4sat.core.presentation.SwipeRevealRow
import com.rtbishop.look4sat.core.presentation.rememberSwipeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun LogbookCard(recordCount: Int, showLogbookDialog: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { showLogbookDialog() }) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.prefs_logbook_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.prefs_logbook_count, recordCount),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}

@Composable
fun LogbookDialog(
    records: List<QsoRecord>,
    satelliteCandidates: List<String>,
    uploadBusy: Boolean,
    uploadMessage: String,
    preview: com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview?,
    positionWarning: com.rtbishop.look4sat.core.domain.repository.LoTWPositionWarning? = null,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (QsoRecord) -> Unit,
    onUpload: () -> Unit,
    onConfirmUpload: () -> Unit,
    onDismissPreview: () -> Unit,
    onDismissMessage: () -> Unit,
    onIgnorePositionWarning: () -> Unit = {},
    onFixGrid: (List<String>) -> Unit = {},
    /** Rewrite the records whose own callsign differs from the certificate's. */
    onRewriteCallsign: () -> Unit = {},
    /** Leave the upload to import a different certificate instead. */
    onSwitchCertificate: () -> Unit = {},
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onStartSelection: (Long) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onExitSelection: () -> Unit = {},
    onResubmitSelected: () -> Unit = {},
    /** Wavelog mode: the prepared batch awaiting confirmation (null otherwise). */
    wavelogPreview: com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview? = null,
    /** True when uploads route through Wavelog — titles and previews follow the mode. */
    wavelogMode: Boolean = false,
    /** Wavelog station profiles for the 台址 selector (empty hides the selector). */
    stationOptions: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = emptyList(),
    stationFilter: String? = null,
    onStationFilterChange: (String?) -> Unit = {}
) {
    val swipeController = rememberSwipeController()
    // Entering selection mode closes any row left swiped open — reveals are off there.
    LaunchedEffect(selectionMode) { if (selectionMode) swipeController.close() }
    // Record currently open in the edit dialog (null when closed).
    var editTarget by remember { mutableStateOf<QsoRecord?>(null) }
    SharedDialog(
        title = stringResource(R.string.prefs_logbook_title),
        onDismissRequest = onDismiss,
        onCancel = if (selectionMode) onExitSelection else onDismiss,
        onAccept = if (selectionMode) onResubmitSelected else onUpload,
        acceptText = if (selectionMode) {
            stringResource(R.string.prefs_logbook_resubmit, selectedIds.size)
        } else {
            stringResource(R.string.prefs_logbook_upload)
        },
        acceptEnabled = if (selectionMode) selectedIds.isNotEmpty() && !uploadBusy else !uploadBusy
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (records.isEmpty()) {
                Text(stringResource(R.string.prefs_logbook_empty), fontSize = 14.sp)
            } else {
                // Selection mode shows the checked count; otherwise a hint that long-press
                // opens it (the path to resubmitting already-uploaded records).
                if (selectionMode) {
                    Text(
                        text = stringResource(R.string.prefs_logbook_selected, selectedIds.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.prefs_logbook_select_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (stationOptions.isNotEmpty()) {
                    // 台址 selector: filters the list to one Wavelog station profile
                    // (its QSOs) — "All" keeps everything. The menu scrolls natively.
                    var stationMenuOpen by remember { mutableStateOf(false) }
                    val currentLabel = stationOptions.firstOrNull { it.id == stationFilter }?.let { station ->
                        listOf(station.name, station.callsign).filter { it.isNotBlank() }.joinToString(" · ")
                    } ?: stringResource(R.string.prefs_logbook_station_all)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(R.string.prefs_logbook_station_label), fontSize = 12.sp)
                        Box {
                            TextButton(onClick = { stationMenuOpen = true }) {
                                Text(currentLabel, fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = stationMenuOpen,
                                onDismissRequest = { stationMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.prefs_logbook_station_all)) },
                                    onClick = {
                                        onStationFilterChange(null)
                                        stationMenuOpen = false
                                    }
                                )
                                stationOptions.forEach { station ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                listOf(station.name, station.callsign, station.grid)
                                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                            )
                                        },
                                        onClick = {
                                            onStationFilterChange(station.id)
                                            stationMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(records, key = { it.id }) { record ->
                        LogbookRow(
                            record = record,
                            selectionMode = selectionMode,
                            selected = record.id in selectedIds,
                            swipeController = swipeController,
                            onDelete = { onDelete(record.id) },
                            onClick = {
                                if (selectionMode) onToggleSelection(record.id) else editTarget = record
                            },
                            onLongClick = {
                                if (selectionMode) onToggleSelection(record.id) else onStartSelection(record.id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (positionWarning != null) {
        // Roaming guard: the current position sits outside the station location this batch
        // would be signed with — confirm the station location before the preview opens.
        LoTWPositionWarningDialog(
            warning = positionWarning,
            onFixStation = { onFixGrid(listOf(positionWarning.currentGrid)) },
            onIgnore = onIgnorePositionWarning
        )
    } else if (wavelogPreview != null) {
        WavelogUploadPreviewDialog(
            preview = wavelogPreview,
            busy = uploadBusy,
            onConfirm = onConfirmUpload,
            onDismiss = onDismissPreview
        )
    } else if (preview != null) {
        LogbookUploadPreviewDialog(
            preview = preview,
            busy = uploadBusy,
            onConfirm = onConfirmUpload,
            onDismiss = onDismissPreview,
            onRewriteCallsign = onRewriteCallsign,
            onSwitchCertificate = onSwitchCertificate
        )
    }
    if (uploadMessage.isNotBlank()) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            shape = sheetDialogShape(),
            containerColor = sheetDialogContainerColor(),
            title = { SheetDialogTitle(if (wavelogMode) "Wavelog Upload" else "LoTW Upload") },
            text = { Text(uploadMessage) },
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text("OK") }
            }
        )
    }

    editTarget?.let { target ->
        QsoEditDialog(
            record = target,
            satelliteCandidates = satelliteCandidates,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                onEdit(updated)
                editTarget = null
            }
        )
    }
}

@Composable
private fun LogbookUploadPreviewDialog(
    preview: com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRewriteCallsign: () -> Unit,
    onSwitchCertificate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle(stringResource(R.string.prefs_logbook_upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${preview.callsign}  DXCC ${preview.dxcc}  Grid ${preview.grid}", fontSize = 13.sp)
                Text(
                    "${preview.count} QSO(s) · ${preview.firstUtc} – ${preview.lastUtc}",
                    fontSize = 13.sp
                )
                if (preview.skipped > 0 || preview.unknownSkipped > 0 || preview.unavailableSkipped > 0) {
                    val parts = buildList {
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
                if (preview.resubmit) {
                    // LoTW treats an identical contact as an update, except grids already
                    // locked in by award credits — say so before the operator commits.
                    Text(
                        stringResource(R.string.prefs_logbook_resubmit_note),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (preview.missingCallsign > 0) {
                    // Logged while no certificate was installed; signed with the certificate's
                    // callsign instead of being refused.
                    Text(
                        stringResource(R.string.prefs_logbook_callsign_missing, preview.missingCallsign, preview.callsign),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (preview.callsignConflicts.isNotEmpty()) {
                    // These name another callsign than the certificate: the operator either rewrites
                    // them with the certificate's callsign or uploads them under that other one.
                    Text(
                        stringResource(R.string.prefs_logbook_callsign_different, preview.callsignConflicts.size, preview.callsign),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRewriteCallsign, enabled = !busy) {
                            Text(stringResource(R.string.prefs_logbook_callsign_rewrite, preview.callsign), fontSize = 12.sp)
                        }
                        TextButton(onClick = onSwitchCertificate, enabled = !busy) {
                            Text(stringResource(R.string.prefs_logbook_callsign_switch), fontSize = 12.sp)
                        }
                    }
                }
                Text(preview.contacts.joinToString("\n") { it }, fontSize = 12.sp, maxLines = 8)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.prefs_logbook_upload_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/** Confirmed-grid colour: the map's worked-grid green, so "confirmed" reads the same everywhere. */
private val ConfirmedGreen = Color(0xFF4CD964)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogbookRow(
    record: QsoRecord,
    selectionMode: Boolean,
    selected: Boolean,
    swipeController: SwipeController,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val time = remember(record.startUtcMillis) {
        SimpleDateFormat("MM-dd HH:mm'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(record.startUtcMillis))
    }
    val satShort = record.satelliteName.substringBefore('(').trim()
    val band = record.band.ifBlank { frequencyBand(record.txFrequencyHz) }
    val haptics = LocalHapticFeedback.current
    // 右划露出删除按钮（短信式）；整行点击不再删除。选择模式下右划禁用、长按进入多选。
    SwipeRevealRow(
        key = record.id.toString(),
        controller = swipeController,
        revealAction = onDelete,
        gesturesEnabled = !selectionMode,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Row style mirrors the worked-grid QSO details on the map: callsign in
        // monospace titleMedium, summary line in bodySmall with " · " separators.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Checkbox slot: only while picking records for a resubmit.
                if (selectionMode) {
                    Text(
                        text = if (selected) "✓" else "○",
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Text(
                    text = record.theirCallsign,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Confirmed contacts show the opposite station's grid set from the LoTW
                // report (single or multi-grid, abbreviated) in the worked-grid green
                // instead of the word QSL; without any grid the QSL text stays.
                if (record.lotwConfirmed) {
                    val oppositeGrids = gridsLabel(
                        record.theirVuccGrids.ifEmpty { listOf(record.theirGrid) }
                    )
                    if (oppositeGrids.isNotEmpty()) {
                        Text(text = oppositeGrids, fontSize = 13.sp, color = ConfirmedGreen, fontFamily = FontFamily.Monospace)
                    } else {
                        Text(text = "QSL", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                    }
                } else if (record.lotwUploaded) {
                    Text(text = "UP", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                // Own station grid: the set the upload was stamped with, or — for rows from
                // before the stamp existed — the grid the QSO was logged under, so every
                // stored contact shows where the operator was.
                text = listOf(
                    satShort, record.displayMode, band, time,
                    gridsLabel(record.vuccGrids.ifEmpty { listOf(record.myGrid) })
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
