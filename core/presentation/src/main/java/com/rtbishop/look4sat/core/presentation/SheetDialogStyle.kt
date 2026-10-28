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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Styling of the app's sheet dialogs ([DialogShell] / [SharedDialog] / [InfoDialog], all built on
 * ModalBottomSheet). AlertDialog-based dialogs (logbook, QSO editor, upload preview) use these so
 * their container colour, corner radius and title look identical to the bottom sheets.
 */

/**
 * Container colour of the app's bottom sheets. Equal to `BottomSheetDefaults.ContainerColor`
 * (Material3 resolves that token to `ColorScheme.surfaceContainerLow`) — spelled out here so the
 * dialogs don't have to opt into the experimental bottom-sheet API.
 */
@Composable
fun sheetDialogContainerColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

/** Corner radius of the app's bottom sheets (`MaterialTheme.shapes.medium`, 12 dp). */
@Composable
fun sheetDialogShape(): Shape = MaterialTheme.shapes.medium

/** Title row of the app's sheet dialogs: 16 sp medium, primary colour, single line. */
@Composable
fun SheetDialogTitle(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
