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
package com.rtbishop.look4sat.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.model.MapSource
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog

@Composable
fun MapSettingsDialog(
    settings: OtherSettings,
    onDismiss: () -> Unit,
    onSave: (mapSource: String, tiandituKey: String) -> Unit
) {
    var selectedSource by rememberSaveable {
        mutableStateOf(MapSource.normalize(settings.mapSource))
    }
    var tiandituKey by rememberSaveable { mutableStateOf(settings.tiandituKey) }

    SharedDialog(
        title = stringResource(R.string.prefs_map_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = { onSave(selectedSource, tiandituKey) }
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = padding)
        ) {
            Text(
                text = stringResource(R.string.prefs_map_source_title),
                color = MaterialTheme.colorScheme.primary
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapSourceChip(
                    selected = selectedSource == MapSource.OSM,
                    label = stringResource(R.string.prefs_map_source_osm),
                    onClick = { selectedSource = MapSource.OSM }
                )
                MapSourceChip(
                    selected = selectedSource == MapSource.TIANDITU_VECTOR,
                    label = stringResource(R.string.prefs_map_source_tianditu_vector),
                    onClick = { selectedSource = MapSource.TIANDITU_VECTOR }
                )
                MapSourceChip(
                    selected = selectedSource == MapSource.TIANDITU_IMAGE,
                    label = stringResource(R.string.prefs_map_source_tianditu_image),
                    onClick = { selectedSource = MapSource.TIANDITU_IMAGE }
                )
            }
            Text(
                text = stringResource(R.string.prefs_map_tianditu_key_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = tiandituKey,
                onValueChange = { tiandituKey = it },
                label = { Text(stringResource(R.string.prefs_map_tianditu_key)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (selectedSource != MapSource.OSM && tiandituKey.isBlank()) {
                Text(
                    text = stringResource(R.string.prefs_map_tianditu_key_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MapSourceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
