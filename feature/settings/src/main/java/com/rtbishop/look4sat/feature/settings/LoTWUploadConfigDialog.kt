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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.repository.LoTWCertificate
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.presentation.R

@Composable
fun LoTWUploadCard(
    hasCertificate: Boolean,
    stationGrid: String,
    showUploadConfigDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { showUploadConfigDialog() }) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.prefs_lotw_upload_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (hasCertificate) {
                    stringResource(R.string.prefs_lotw_upload_configured, stationGrid)
                } else {
                    stringResource(R.string.prefs_lotw_upload_not_configured)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}

@Composable
fun LoTWUploadConfigDialog(
    certificate: LoTWCertificate?,
    station: LoTWStation?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: (ByteArray, CharArray) -> Unit,
    onRemove: () -> Unit,
    onSaveStation: (LoTWStation) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(station?.grid.orEmpty()) }
    var cqZone by remember { mutableStateOf(station?.cqZone.orEmpty()) }
    var ituZone by remember { mutableStateOf(station?.ituZone.orEmpty()) }
    var iota by remember { mutableStateOf(station?.iota.orEmpty()) }
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && password.isNotBlank()) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                onImport(bytes, password.toCharArray())
                password = ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prefs_lotw_upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.prefs_lotw_upload_busy), fontSize = 13.sp)
                    }
                }
                if (certificate == null) {
                    Text(stringResource(R.string.prefs_lotw_upload_cert_hint), fontSize = 13.sp)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.prefs_lotw_upload_password), fontSize = 13.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = password.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.prefs_lotw_upload_import), fontSize = 13.sp) }
                } else {
                    Text(stringResource(R.string.prefs_lotw_upload_cert_info, certificate.callsign, certificate.dxcc, certificate.expires), fontSize = 13.sp)
                    OutlinedButton(onClick = onRemove, enabled = !busy) {
                        Text(stringResource(R.string.prefs_lotw_upload_remove), fontSize = 13.sp)
                    }
                }
                Text(stringResource(R.string.prefs_lotw_upload_station_title), style = MaterialTheme.typography.titleSmall)
                // Grid field accepts a comma-separated multi-grid set (e.g. "OL62,OL72").
                OutlinedTextField(
                    value = grid,
                    onValueChange = { grid = it.uppercase() },
                    label = { Text(stringResource(R.string.prefs_lotw_upload_grid), fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = cqZone,
                        onValueChange = { cqZone = it },
                        label = { Text("CQZ", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = ituZone,
                        onValueChange = { ituZone = it },
                        label = { Text("ITUZ", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = iota,
                        onValueChange = { iota = it.uppercase() },
                        label = { Text("IOTA", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSaveStation(LoTWStation(grid, cqZone, ituZone, "", "", iota)) },
                enabled = grid.isNotBlank() && !busy
            ) { Text(stringResource(R.string.prefs_lotw_upload_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prefs_lotw_upload_close)) } }
    )
}
