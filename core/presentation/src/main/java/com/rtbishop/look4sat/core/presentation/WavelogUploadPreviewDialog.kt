/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.logbook.wavelogSkipSummary
import com.rtbishop.look4sat.core.domain.repository.WavelogUploadPreview

/**
 * Confirmation dialog shown before a Wavelog upload goes out: what will be submitted,
 * and which records were held back (e.g. grids the Wavelog station profile would skip).
 */
@Composable
fun WavelogUploadPreviewDialog(
    preview: WavelogUploadPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle("上传到 Wavelog") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (preview.stationLabel.isNotBlank()) {
                    Text(preview.stationLabel, fontSize = 13.sp)
                }
                Text("${preview.count} QSO(s) · ${preview.firstUtc} – ${preview.lastUtc}", fontSize = 13.sp)
                val skipText = wavelogSkipSummary(preview)
                if (skipText.isNotBlank()) {
                    Text(skipText, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Text(preview.contacts.joinToString("\n"), fontSize = 12.sp, maxLines = 8)
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
