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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.repository.LoTWGridWarning

/**
 * Pre-upload grid check: the prepared batch holds records whose own grids fall outside the
 * station-location grids they would be signed with — the fingerprint of uploading while
 * roaming with a station location that was never updated. The operator either jumps to the
 * station location to fix it, or ignores the warning and proceeds to the normal preview.
 */
@Composable
fun LoTWGridWarningDialog(
    warning: LoTWGridWarning,
    onFixStation: () -> Unit,
    onIgnore: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onIgnore,
        shape = sheetDialogShape(),
        containerColor = sheetDialogContainerColor(),
        title = { SheetDialogTitle(stringResource(R.string.lotw_upload_grid_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.lotw_upload_grid_mismatch,
                    warning.count,
                    gridsLabel(warning.recordGrids),
                    gridsLabel(warning.stationGrids)
                ),
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onFixStation) { Text(stringResource(R.string.lotw_upload_grid_fix)) }
        },
        dismissButton = {
            TextButton(onClick = onIgnore) { Text(stringResource(R.string.lotw_upload_grid_ignore)) }
        }
    )
}
