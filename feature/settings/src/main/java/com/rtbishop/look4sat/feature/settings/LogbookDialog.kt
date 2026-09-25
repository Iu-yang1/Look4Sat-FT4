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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
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
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit
) {
    SharedDialog(
        title = stringResource(R.string.prefs_logbook_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = null
    ) {
        if (records.isEmpty()) {
            Text(stringResource(R.string.prefs_logbook_empty), fontSize = 14.sp)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    LogbookRow(record, onDelete = { onDelete(record.id) })
                }
            }
        }
    }
}

@Composable
private fun LogbookRow(record: QsoRecord, onDelete: () -> Unit) {
    val time = remember(record.startUtcMillis) {
        SimpleDateFormat("MM-dd HH:mm'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(record.startUtcMillis))
    }
    val satShort = record.satelliteName.substringBefore('(').trim()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onDelete() }.padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$time  ${record.theirCallsign}  ${record.displayMode}",
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = satShort, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(
            text = if (record.lotwConfirmed) "✓" else "",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
