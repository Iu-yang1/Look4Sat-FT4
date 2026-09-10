/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.logbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.R as CoreR
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogbookScreenDestination(navigateUp: () -> Unit, navigateToMap: () -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: LogbookViewModel = viewModel(factory = LogbookViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exportContent by remember { mutableStateOf("") }
    var exportComplete by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (content == null) {
                    viewModel.onAction(LogbookAction.Error(LogbookError.IMPORT_READ))
                } else {
                    viewModel.onAction(LogbookAction.Import(content))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                viewModel.onAction(LogbookAction.Error(LogbookError.IMPORT_READ))
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: return@withContext false
                    stream.bufferedWriter().use { it.write(exportContent) }
                    true
                }
                if (written) {
                    exportComplete = true
                } else {
                    viewModel.onAction(LogbookAction.Error(LogbookError.EXPORT_WRITE))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                viewModel.onAction(LogbookAction.Error(LogbookError.EXPORT_WRITE))
            }
        }
    }
    val exportAdi: (Boolean) -> Unit = { includeIncomplete ->
        showExportOptions = false
        scope.launch {
            try {
                exportContent = viewModel.exportAdi(includeIncomplete)
                exportLauncher.launch("look4sat-${fileDate()}.adi")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                viewModel.onAction(LogbookAction.Error(LogbookError.EXPORT_PREPARE))
            }
        }
    }

    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text(stringResource(R.string.logbook_export)) },
            text = { Text(stringResource(R.string.logbook_export_scope_description)) },
            confirmButton = {
                TextButton(onClick = { exportAdi(false) }) {
                    Text(stringResource(R.string.logbook_export_complete_only))
                }
            },
            dismissButton = {
                TextButton(onClick = { exportAdi(true) }) {
                    Text(stringResource(R.string.logbook_export_all))
                }
            }
        )
    }

    LogbookScreen(
        state = state,
        navigateUp = navigateUp,
        onAction = viewModel::onAction,
        onImport = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
        onExport = { showExportOptions = true },
        onMap = {
            container.settingsRepo.updateOtherSettings { it.copy(stateOfMapGrid = true) }
            navigateToMap()
        },
        exportComplete = exportComplete,
        dismissExportComplete = { exportComplete = false }
    )
}

@Composable
private fun LogbookScreen(
    state: LogbookState,
    navigateUp: () -> Unit,
    onAction: (LogbookAction) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onMap: () -> Unit,
    exportComplete: Boolean,
    dismissExportComplete: () -> Unit
) {
    state.editor?.let { LogbookEditorDialog(it, onAction, state.isBusy, state.error) }
    if (state.showLoTW) LoTWDialog(state, onAction)
    if (state.showStation) LogbookStationDialog(state, onAction)
    state.deleteCandidate?.let { record ->
        AlertDialog(
            onDismissRequest = { onAction(LogbookAction.DismissDelete) },
            title = { Text(stringResource(R.string.logbook_delete)) },
            text = { Text(stringResource(R.string.logbook_confirm_delete, record.theirCallsign)) },
            confirmButton = {
                TextButton(onClick = { onAction(LogbookAction.ConfirmDelete) }) {
                    Text(stringResource(R.string.logbook_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(LogbookAction.DismissDelete) }) {
                    Text(stringResource(R.string.logbook_cancel))
                }
            }
        )
    }
    ScreenColumn {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TopBar {
                IconCard(action = navigateUp, resId = CoreR.drawable.ic_back)
                ElevatedCard(modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.logbook_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.logbook_counts, state.records.size, state.records.count { it.lotwConfirmed }),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                IconCard(action = { onAction(LogbookAction.ShowStation) }, resId = CoreR.drawable.ic_settings)
                IconCard(action = { onAction(LogbookAction.Add) }, resId = CoreR.drawable.ic_add)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                CardButton(onClick = onImport, text = stringResource(R.string.logbook_import), modifier = Modifier.weight(1f))
                CardButton(
                    onClick = onExport,
                    text = stringResource(R.string.logbook_export),
                    enabled = state.records.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = { onAction(LogbookAction.ShowLoTW) },
                    text = stringResource(R.string.logbook_lotw_button),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMap) {
                    Icon(painterResource(CoreR.drawable.ic_map), stringResource(R.string.logbook_grid_map))
                }
            }
            LogbookFilters(state, onAction)
            val notice = when {
                state.error != null -> logbookErrorText(state.error)
                state.importResult != null -> stringResource(
                    R.string.logbook_import_result,
                    state.importResult.imported,
                    state.importResult.skipped,
                    state.importResult.updated
                )
                state.lotwResult != null -> stringResource(
                    R.string.logbook_sync_result, state.lotwResult.imported, state.lotwResult.updated, state.lotwResult.skipped
                )
                exportComplete -> stringResource(R.string.logbook_exported)
                else -> null
            }
            notice?.let {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onAction(LogbookAction.ClearNotice)
                        dismissExportComplete()
                    }
                ) { Text(it, modifier = Modifier.padding(10.dp)) }
            }
            val filtered = remember(state.records, state.query, state.modeFilter, state.confirmationFilter) { state.filteredRecords }
            if (filtered.isEmpty()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.records.isEmpty()) R.string.logbook_empty else R.string.logbook_no_matches), modifier = Modifier.padding(16.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.groupByCallsign) {
                        val groups = filtered.groupBy { it.theirCallsign }
                        groups.forEach { (call, contacts) ->
                            item(key = "call_$call") {
                                Text(
                                    stringResource(R.string.logbook_group_header, call, contacts.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            items(contacts, key = { it.id }) { record ->
                                QsoCard(record, onEdit = { onAction(LogbookAction.Edit(record)) }, onDelete = {
                                    onAction(LogbookAction.RequestDelete(record))
                                })
                            }
                        }
                    } else {
                        items(filtered, key = { it.id }) { record ->
                            QsoCard(record, onEdit = { onAction(LogbookAction.Edit(record)) }, onDelete = {
                                onAction(LogbookAction.RequestDelete(record))
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QsoCard(record: QsoRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    val notSet = stringResource(R.string.logbook_not_set)
    val unavailable = stringResource(R.string.logbook_value_unavailable)
    val txFrequencyHz = record.txFrequencyHz
    val rxFrequencyHz = record.rxFrequencyHz
    val txFrequency = if (txFrequencyHz == null) notSet else {
        stringResource(R.string.logbook_mhz_value, txFrequencyHz / 1_000_000.0)
    }
    val rxFrequency = if (rxFrequencyHz == null) notSet else {
        stringResource(R.string.logbook_mhz_value, rxFrequencyHz / 1_000_000.0)
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.theirCallsign, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (record.lotwConfirmed) {
                            stringResource(R.string.logbook_lotw_confirmed_short)
                        } else {
                            statusLabel(record.status)
                        },
                        color = MaterialTheme.colorScheme.primary, fontSize = 12.sp
                    )
                    Text(record.displayMode, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    stringResource(
                        R.string.logbook_qso_summary,
                        stringResource(R.string.logbook_utc_value, formatUtc(record.startUtcMillis)),
                        record.satelliteName.ifBlank { notSet }
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.logbook_reports_grid,
                        record.sentReport.ifBlank { unavailable },
                        record.receivedReport.ifBlank { unavailable },
                        record.theirGrid.ifBlank { unavailable }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.logbook_frequency_summary,
                        txFrequency,
                        rxFrequency,
                        record.submode.ifBlank { record.mode }.ifBlank { notSet }
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (record.comment.isNotBlank()) Text(
                    record.comment, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(CoreR.drawable.ic_delete), stringResource(R.string.logbook_delete))
            }
        }
    }
}

@Composable
private fun LogbookEditorDialog(
    editor: LogbookEditor,
    onAction: (LogbookAction) -> Unit,
    busy: Boolean,
    error: LogbookError?
) {
    var showDetails by remember(editor.id) { mutableStateOf(false) }
    fun update(transform: (LogbookEditor) -> LogbookEditor) = onAction(LogbookAction.Update(transform(editor)))
    AlertDialog(
        onDismissRequest = { onAction(LogbookAction.DismissEditor) },
        title = { Text(stringResource(R.string.logbook_edit)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                error?.let { Text(logbookErrorText(it), color = MaterialTheme.colorScheme.error) }
                EditorField(editor.utcText, R.string.logbook_utc_time) { value -> update { it.copy(utcText = value) } }
                EditorField(editor.theirCallsign, R.string.logbook_callsign) { value -> update { it.copy(theirCallsign = value) } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(listOf("CW", "SSB", "FM", "FT4", "FT8", "RTTY", "AM")) { mode ->
                        FilterChip(
                            selected = (editor.submode.ifBlank { editor.mode }) == mode,
                            onClick = { update { it.copy(mode = if (mode == "FT4") "MFSK" else mode, submode = if (mode == "FT4") "FT4" else "") } },
                            label = { Text(mode) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditorField(editor.sentReport, R.string.logbook_report_sent, Modifier.weight(1f)) { value -> update { it.copy(sentReport = value) } }
                    EditorField(editor.receivedReport, R.string.logbook_report_received, Modifier.weight(1f)) { value -> update { it.copy(receivedReport = value) } }
                }
                EditorField(editor.satelliteName, R.string.logbook_satellite) { value -> update { it.copy(satelliteName = value) } }
                EditorField(editor.comment, R.string.logbook_comment) { value -> update { it.copy(comment = value) } }
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(
                        stringResource(R.string.logbook_advanced) + stringResource(
                            if (showDetails) R.string.logbook_collapse_marker
                            else R.string.logbook_expand_marker
                        )
                    )
                }
                if (showDetails) {
                    EditorField(editor.myCallsign, R.string.logbook_my_callsign) { value -> update { it.copy(myCallsign = value) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditorField(editor.theirGrid, R.string.logbook_grid, Modifier.weight(1f)) { value -> update { it.copy(theirGrid = value) } }
                        EditorField(editor.myGrid, R.string.logbook_my_grid, Modifier.weight(1f)) { value -> update { it.copy(myGrid = value) } }
                    }
                    EditorField(editor.txFrequencyHz, R.string.logbook_tx_frequency) { value -> update { it.copy(txFrequencyHz = value) } }
                    EditorField(editor.rxFrequencyHz, R.string.logbook_rx_frequency) { value -> update { it.copy(rxFrequencyHz = value) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditorField(editor.mode, R.string.logbook_mode, Modifier.weight(1f)) { value -> update { it.copy(mode = value) } }
                        EditorField(editor.submode, R.string.logbook_submode, Modifier.weight(1f)) { value -> update { it.copy(submode = value) } }
                    }
                    EditorField(editor.transponderName, R.string.logbook_transponder) { value -> update { it.copy(transponderName = value) } }
                    EditorField(editor.satelliteMode, R.string.logbook_satellite_mode) { value -> update { it.copy(satelliteMode = value) } }
                }
                if (editor.source?.lotwConfirmed == true) {
                    Text(stringResource(R.string.logbook_confirmed), color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.logbook_status), fontWeight = FontWeight.Medium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(QsoStatus.entries) { status ->
                        FilterChip(
                            selected = editor.status == status,
                            onClick = { update { it.copy(status = status) } },
                            label = { Text(statusLabel(status), fontSize = 11.sp) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.logbook_automatic), modifier = Modifier.weight(1f))
                    Switch(checked = editor.automatic, onCheckedChange = { value -> update { it.copy(automatic = value) } })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onAction(LogbookAction.Save) }) { Text(stringResource(R.string.logbook_save)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { onAction(LogbookAction.DismissEditor) }) { Text(stringResource(R.string.logbook_cancel)) }
        }
    )
}

@Composable
private fun EditorField(
    value: String,
    labelRes: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions.Default,
        modifier = modifier
    )
}

@Composable
private fun statusLabel(status: QsoStatus): String = stringResource(
    when (status) {
        QsoStatus.DRAFT -> R.string.logbook_draft
        QsoStatus.COMPLETE -> R.string.logbook_complete
        QsoStatus.ABORTED -> R.string.logbook_aborted
    }
)

@Composable
internal fun logbookErrorText(error: LogbookError): String = stringResource(
    when (error) {
        LogbookError.CALLSIGN_REQUIRED -> R.string.logbook_error_callsign_required
        LogbookError.CALLSIGN_INVALID -> R.string.logbook_error_callsign_invalid
        LogbookError.MODE_REQUIRED -> R.string.logbook_error_mode_required
        LogbookError.FREQUENCY_INVALID -> R.string.logbook_error_frequency_invalid
        LogbookError.UTC_INVALID -> R.string.logbook_error_utc_invalid
        LogbookError.IMPORT_READ -> R.string.logbook_error_import_read
        LogbookError.EXPORT_WRITE -> R.string.logbook_error_export_write
        LogbookError.EXPORT_PREPARE -> R.string.logbook_error_export_prepare
        LogbookError.SAVE -> R.string.logbook_error_save
        LogbookError.DELETE -> R.string.logbook_error_delete
        LogbookError.IMPORT -> R.string.logbook_error_import
    }
)

private fun formatUtc(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(value))

private fun fileDate(): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())
