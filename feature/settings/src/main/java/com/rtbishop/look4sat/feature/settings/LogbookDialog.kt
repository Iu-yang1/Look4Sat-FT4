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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.frequencyBand
import com.rtbishop.look4sat.core.presentation.LocalSpacing
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
    uploadBusy: Boolean,
    uploadMessage: String,
    preview: com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview?,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
    onUpload: () -> Unit,
    onConfirmUpload: () -> Unit,
    onDismissPreview: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val swipeController = rememberSwipeController()
    SharedDialog(
        title = stringResource(R.string.prefs_logbook_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = onUpload,
        acceptText = stringResource(R.string.prefs_logbook_upload),
        acceptEnabled = !uploadBusy
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.large),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (records.isEmpty()) {
                Text(stringResource(R.string.prefs_logbook_empty), fontSize = 14.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(records, key = { it.id }) { record ->
                        LogbookRow(record, swipeController, onDelete = { onDelete(record.id) })
                    }
                }
            }
        }
    }

    if (preview != null) {
        LogbookUploadPreviewDialog(
            preview = preview,
            busy = uploadBusy,
            onConfirm = onConfirmUpload,
            onDismiss = onDismissPreview
        )
    }
    if (uploadMessage.isNotBlank()) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("LoTW Upload") },
            text = { Text(uploadMessage) },
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text("OK") }
            }
        )
    }
}

@Composable
private fun LogbookUploadPreviewDialog(
    preview: com.rtbishop.look4sat.core.domain.repository.LoTWUploadPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prefs_logbook_upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${preview.callsign}  DXCC ${preview.dxcc}  Grid ${preview.grid}", fontSize = 13.sp)
                Text(
                    "${preview.count} QSO(s) · ${preview.firstUtc} – ${preview.lastUtc}",
                    fontSize = 13.sp
                )
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

@Composable
private fun LogbookRow(record: QsoRecord, swipeController: SwipeController, onDelete: () -> Unit) {
    val time = remember(record.startUtcMillis) {
        SimpleDateFormat("MM-dd HH:mm'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(record.startUtcMillis))
    }
    val satShort = record.satelliteName.substringBefore('(').trim()
    val band = record.band.ifBlank { frequencyBand(record.txFrequencyHz) }
    // 右划露出删除按钮（短信式）；整行点击不再删除。
    SwipeRevealRow(
        key = record.id.toString(),
        controller = swipeController,
        revealAction = onDelete,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Row style mirrors the worked-grid QSO details on the map: callsign in
        // monospace titleMedium, summary line in bodySmall with " · " separators.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = record.theirCallsign,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFFFE082),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (record.lotwConfirmed) {
                    Text(text = "QSL", fontSize = 13.sp, color = Color(0xFFFFE082), fontFamily = FontFamily.Monospace)
                } else if (record.lotwUploaded) {
                    Text(text = "UP", fontSize = 13.sp, color = Color(0xFFFFE082), fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                text = listOf(satShort, record.displayMode, band, time).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
