/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadIssueKind

/**
 * Shared LoTW error dialog for both the radar quick-upload and Settings logbook flows.
 * Unknown outcomes never offer a direct retry; only a failure proven to happen before
 * request headers started exposes the safe retry action.
 */
@Composable
fun LoTWUploadResultDialog(
    kind: LoTWUploadIssueKind,
    summary: String,
    technicalDetails: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val title = when (kind) {
        LoTWUploadIssueKind.NOT_SENT -> R.string.lotw_upload_result_not_sent_title
        LoTWUploadIssueKind.REJECTED -> R.string.lotw_upload_result_rejected_title
        LoTWUploadIssueKind.UNKNOWN -> R.string.lotw_upload_result_unknown_title
        LoTWUploadIssueKind.PREVIOUS_UNKNOWN -> R.string.lotw_upload_result_previous_unknown_title
    }
    val guidance = when (kind) {
        LoTWUploadIssueKind.NOT_SENT -> R.string.lotw_upload_result_not_sent_guidance
        LoTWUploadIssueKind.REJECTED -> R.string.lotw_upload_result_rejected_guidance
        LoTWUploadIssueKind.UNKNOWN -> R.string.lotw_upload_result_unknown_guidance
        LoTWUploadIssueKind.PREVIOUS_UNKNOWN -> R.string.lotw_upload_result_previous_unknown_guidance
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (summary.isNotBlank()) Text(summary, fontSize = 14.sp)
                Text(stringResource(guidance), fontSize = 14.sp)
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(
                        stringResource(
                            if (detailsExpanded) R.string.lotw_upload_result_hide_details
                            else R.string.lotw_upload_result_show_details
                        )
                    )
                }
                if (detailsExpanded) {
                    Text(
                        text = technicalDetails.ifBlank {
                            stringResource(R.string.lotw_upload_result_no_details)
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(technicalDetails.ifBlank {
                            stringResource(R.string.lotw_upload_result_no_details)
                        }))
                    }) {
                        Text(stringResource(R.string.lotw_upload_result_copy_details))
                    }
                }
            }
        },
        confirmButton = {
            if (kind == LoTWUploadIssueKind.NOT_SENT) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.lotw_upload_result_retry))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.lotw_upload_result_close))
                }
            }
        },
        dismissButton = {
            if (kind == LoTWUploadIssueKind.NOT_SENT) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.lotw_upload_result_close))
                }
            }
        }
    )
}
