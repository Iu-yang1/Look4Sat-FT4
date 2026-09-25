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

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.repository.LoTWCertificate
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog

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
    error: LoTWUploadError?,
    onDismiss: () -> Unit,
    onImport: (ByteArray, CharArray) -> Unit,
    onRemove: () -> Unit,
    onSaveStation: (LoTWStation) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var grid by remember { mutableStateOf(station?.grid.orEmpty()) }
    var cqZone by remember { mutableStateOf(station?.cqZone.orEmpty()) }
    var ituZone by remember { mutableStateOf(station?.ituZone.orEmpty()) }
    var iota by remember { mutableStateOf(station?.iota.orEmpty()) }
    val context = LocalContext.current

    // Pick the file first, then ask for the password — matches normal usage.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectedFile = uri
    }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    SharedDialog(
        title = stringResource(R.string.prefs_lotw_upload_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = null
    ) {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.prefs_lotw_upload_busy), fontSize = 13.sp)
            }
        }
        if (certificate == null) {
            Text(stringResource(R.string.prefs_lotw_upload_cert_hint), fontSize = 13.sp)
            CardButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                text = stringResource(R.string.prefs_lotw_upload_import),
                isEnabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Text(
                    text = stringResource(
                        when (it) {
                            LoTWUploadError.PASSWORD -> R.string.prefs_lotw_upload_error_password
                            LoTWUploadError.EXPIRED -> R.string.prefs_lotw_upload_error_expired
                            LoTWUploadError.INVALID_FILE -> R.string.prefs_lotw_upload_error_invalid
                            LoTWUploadError.UNKNOWN -> R.string.prefs_lotw_upload_error_unknown
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            selectedFile?.let { uri ->
                Text(
                    text = displayName(uri),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.prefs_lotw_upload_password), fontSize = 13.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                CardButton(
                    onClick = {
                        val bytes = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes != null && bytes.isNotEmpty()) {
                            onImport(bytes, password.toCharArray())
                            password = ""
                            selectedFile = null
                        }
                    },
                    text = stringResource(R.string.prefs_lotw_upload_import_confirm),
                    isEnabled = password.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
        CardButton(
            onClick = { onSaveStation(LoTWStation(grid, cqZone, ituZone, "", "", iota)) },
            text = stringResource(R.string.prefs_lotw_upload_save),
            isEnabled = grid.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
