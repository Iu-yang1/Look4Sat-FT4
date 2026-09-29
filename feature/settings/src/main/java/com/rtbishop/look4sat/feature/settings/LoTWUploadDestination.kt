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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider

/**
 * Full-screen LoTW upload certificate + station location page.
 *
 * Reached from the Grid Finder's "Set as LoTW station location" button: the
 * grids the operator currently stands on (1 inside, 2 on a line, 4 on a corner)
 * are dropped into [container.pendingLoTWStationGrid] and consumed here as the
 * station grid prefill, so the next upload stamps those VUCC squares instead of
 * the certificate's home grid.
 */
@Composable
fun LoTWUploadDestination(navigateUp: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container, context))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Consume the prefill once; re-entering the page later keeps the saved grid.
    val initialGrid = container.pendingLoTWStationGrid.value.orEmpty()
    LaunchedEffect(Unit) {
        container.setPendingLoTWStationGrid(null)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LoTWUploadConfigDialog(
            certificate = uiState.lotwCertificate,
            station = uiState.lotwStation,
            stationMeta = uiState.lotwStationMeta,
            busy = uiState.lotwUploadBusy,
            error = uiState.lotwUploadError,
            errorDetail = uiState.lotwUploadErrorDetail,
            initialGrid = initialGrid,
            onDismiss = navigateUp,
            onImport = { bytes, password ->
                viewModel.onAction(SettingsAction.ImportLoTWCertificate(bytes, password))
            },
            onRemove = { viewModel.onAction(SettingsAction.RemoveLoTWCertificate) },
            onSaveStation = { viewModel.onAction(SettingsAction.SaveLoTWStation(it)) }
        )
    }
}
