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

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.CompassAccuracy
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.PrimaryIconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsDestination() {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container, context))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        radioAudioSession=container.radioAudioSession,
        audioSettingsForModel=container.settingsRepo::getRadioAudioSettings
    )
}

@Composable
private fun SettingsScreen(
    uiState: SettingsState,
    onAction: (SettingsAction) -> Unit,
    radioAudioSession: com.rtbishop.look4sat.core.domain.audio.IRadioAudioSession,
    audioSettingsForModel: (String)->com.rtbishop.look4sat.core.domain.audio.RadioAudioSettings
) {
    val context = LocalContext.current
    var showUpdateChecker by rememberSaveable { mutableStateOf(false) }
    var showMapSettings by rememberSaveable { mutableStateOf(false) }
    var showProjectWiki by rememberSaveable { mutableStateOf(false) }
    var showDebugMenu by rememberSaveable { mutableStateOf(false) }
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val isDebuggableBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val exportPreferencesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/xml")
    ) { uri ->
        if (uri != null) {
            try {
                val preferencesFile = File(
                    context.applicationInfo.dataDir,
                    "shared_prefs/${context.packageName}_preferences.xml"
                )
                // Wait for pending SharedPreferences.apply() writes before copying the raw XML.
                context.getSharedPreferences(
                    "${context.packageName}_preferences",
                    android.content.Context.MODE_PRIVATE
                ).edit().commit()
                check(preferencesFile.isFile) { "Preferences XML file does not exist yet." }
                val contents = preferencesFile.readBytes()
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(contents)
                } ?: error("Unable to open the selected destination.")
                debugExportMessage = context.getString(R.string.debug_export_success)
            } catch (error: Exception) {
                debugExportMessage = context.getString(
                    R.string.debug_export_failed,
                    error.localizedMessage ?: error.javaClass.simpleName
                )
            }
        }
    }
    // Only one collapsible settings section may be open at a time.
    // 0 = none, 1 = data output, 2 = FT4/time sync, 3 = display, 4 = other settings.
    var expandedSettingsSection by rememberSaveable { mutableStateOf(0) }
    val outputSettingsExpanded = expandedSettingsSection == 1
    val ft4SettingsExpanded = expandedSettingsSection == 2
    val displaySettingsExpanded = expandedSettingsSection == 3
    val otherSettingsExpanded = expandedSettingsSection == 4
    var passListFontScaleSlider by remember(uiState.otherSettings.passListFontScale) {
        mutableFloatStateOf(uiState.otherSettings.passListFontScale)
    }
    if (showDebugMenu && isDebuggableBuild) {
        AlertDialog(
            onDismissRequest = { showDebugMenu = false },
            title = { Text(stringResource(R.string.debug_menu_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.debug_menu_description))
                    debugExportMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    debugExportMessage = null
                    // Match: adb exec-out run-as <package> cat shared_prefs/<package>_preferences.xml
                    context.getSharedPreferences(
                        "${context.packageName}_preferences",
                        android.content.Context.MODE_PRIVATE
                    ).edit().commit()
                    exportPreferencesLauncher.launch("look4sat_preferences.xml")
                }) {
                    Text(stringResource(R.string.debug_export_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugMenu = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
    if (showProjectWiki) {
        ProjectWikiDialog(onDismiss = { showProjectWiki = false })
    }
    if (showUpdateChecker) {
        UpdateCheckerScreen(
            currentVersion = uiState.appVersionName,
            state = uiState.updateChecker,
            onBack = { showUpdateChecker = false },
            onCheck = { onAction(SettingsAction.CheckForUpdate) },
            onDownload = { onAction(SettingsAction.DownloadUpdate) },
            onConsumeApk = { onAction(SettingsAction.ConsumeDownloadedApk) }
        )
    }
    val dialogs = rememberDialogVisibility()
    val pendingCustomSourcesGrant = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingCustomSourcesDeny = remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberSettingsPermissions(
        sendAction = onAction,
        onBluetoothGranted = { dialogs.bluetooth = true },
        onNetworkGranted = { dialogs.network = true },
        onCustomSourcesPermissionGranted = {
            pendingCustomSourcesGrant.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        },
        onCustomSourcesPermissionDenied = {
            pendingCustomSourcesDeny.value?.invoke()
            pendingCustomSourcesGrant.value = null
            pendingCustomSourcesDeny.value = null
        }
    )
    LaunchedEffect(uiState.controlDiagnosticsExportText) {
        val text = uiState.controlDiagnosticsExportText ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Look4Sat control diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.control_diagnostics_export)))
        onAction(SettingsAction.ConsumeControlDiagnosticsExport)
    }

    if (showMapSettings) {
        MapSettingsDialog(
            settings = uiState.otherSettings,
            onDismiss = { showMapSettings = false },
            onSave = { mapSource, tiandituKey ->
                onAction(SettingsAction.UpdateMapSettings(mapSource, tiandituKey))
                showMapSettings = false
            }
        )
    }

    // Dialogs
    if (dialogs.position) {
        PositionDialog(
            uiState.positionSettings.stationPos.latitude,
            uiState.positionSettings.stationPos.longitude,
            dismiss = { dialogs.position = false },
            save = { lat, lon -> onAction(SettingsAction.SetGeoPosition(lat, lon)) }
        )
    }
    if (dialogs.locator) {
        LocatorDialog(
            uiState.positionSettings.stationPos.qthLocator,
            dismiss = { dialogs.locator = false },
            save = { onAction(SettingsAction.SetQthPosition(it)) }
        )
    }
    if (dialogs.dataSources) {
        DataSourcesDialog(
            satelliteUrls = uiState.dataSourcesSettings.satelliteUrls,
            transceiversUrls = uiState.dataSourcesSettings.transceiversUrls,
            satelliteEnabled = uiState.dataSourcesSettings.satelliteEnabled,
            transceiversEnabled = uiState.dataSourcesSettings.transceiversEnabled,
            statusCodes = uiState.dataSourcesStatus,
            onImportTle = { permissions.launchTleImport(); dialogs.dataSources = false },
            onImportTransceivers = { permissions.launchTransceiverImport(); dialogs.dataSources = false },
            onDismiss = { dialogs.dataSources = false },
            onSave = { satUrls, txUrls, satEnabled, txEnabled ->
                val newSettings = DataSourcesSettings(
                    satelliteUrls = satUrls,
                    transceiversUrls = txUrls,
                    satelliteEnabled = satEnabled,
                    transceiversEnabled = txEnabled
                )
                if (newSettings != uiState.dataSourcesSettings) onAction(SettingsAction.UpdateDataSources(newSettings))
                onAction(SettingsAction.UpdateFromWeb)
            }
        )
    }
    if (dialogs.network) {
        NetworkOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.network = false },
            onSave = { freqState, freqAddr, freqPort, freqFmt, freqOffsetHz ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            frequencyState = freqState, frequencyAddress = freqAddr,
                            frequencyPort = freqPort, frequencyFormat = freqFmt,
                            frequencyOffsetHz = freqOffsetHz
                        )
                    )
                )
            }
        )
    }
    if (dialogs.bluetooth) {
        BluetoothOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.bluetooth = false },
            onSave = { freqState, freqAddr, freqFmt ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            bluetoothFrequencyState = freqState,
                            bluetoothFrequencyAddress = freqAddr, bluetoothFrequencyFormat = freqFmt
                        )
                    )
                )
            }
        )
    }
    if (dialogs.radioControl) {
        RadioControlDialog(
            initialSettings = uiState.radioControlSettings,
            onDismiss = { dialogs.radioControl = false },
            onSave = { onAction(SettingsAction.UpdateRadioControl(it)) },
            frequencyLocked = uiState.radioTrackingState.frequencyEditingLocked,
            radioAudioSession=radioAudioSession,
            audioSettingsForModel=audioSettingsForModel
        )
    }
    if (dialogs.rotatorControl) {
        RotatorControlDialog(
            initialSettings = uiState.rotatorSettings,
            trackingState = uiState.rotatorTrackingState,
            onDismiss = { dialogs.rotatorControl = false },
            onSave = { onAction(SettingsAction.UpdateRotatorControl(it)) },
            onConnect = { onAction(SettingsAction.ConnectRotator) },
            onDisconnect = { onAction(SettingsAction.DisconnectRotator) },
            onTestPoint = { azimuth, elevation ->
                onAction(SettingsAction.TestRotatorPoint(azimuth, elevation))
            },
            onPark = { onAction(SettingsAction.ParkRotator) },
            onStop = { onAction(SettingsAction.StopRotator) }
        )
    }
    if (dialogs.compassCalibration) {
        LaunchedEffect(Unit) { onAction(SettingsAction.StartCompassCalibration) }
        CompassCalibrationDialog(
            accuracy = uiState.compassAccuracy,
            headingDegrees = uiState.compassHeadingDegrees,
            initialOffsetDegrees = uiState.otherSettings.compassOffsetDegrees,
            onDismiss = {
                onAction(SettingsAction.StopCompassCalibration)
                dialogs.compassCalibration = false
            },
            onSave = { onAction(SettingsAction.SetCompassOffset(it)) }
        )
    }
    if (uiState.wavelogSwitchWarning) {
        WavelogSwitchWarningDialog(
            onConfirm = { onAction(SettingsAction.ConfirmWavelogSwitch) },
            onCancel = { onAction(SettingsAction.CancelWavelogSwitch) }
        )
    }

    // URLs for top bar
    val uriHandler = LocalUriHandler.current
    val upstreamTitle = stringResource(R.string.prefs_upstream_title)
    val upstreamUrl = stringResource(R.string.prefs_upstream_url)
    val gitHubTitle = stringResource(R.string.prefs_github_title)
    val gitHubUrl = stringResource(R.string.prefs_github_url)
    val licenseUrl = stringResource(R.string.prefs_license_url)
    val privacyUrl = stringResource(R.string.prefs_privacy_url)
    val safeOpenUri: (String) -> Unit = { url ->
        try { uriHandler.openUri(url) } catch (_: Exception) {}
    }

    ScreenColumn(
        removeLightThemePanelBackground = true,
        topBar = { isVerticalLayout ->
            if (isVerticalLayout) {
                TopBar {
                    TopCard(
                        onClick = { showUpdateChecker = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                }
                TopBar {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(upstreamUrl) },
                            resId = R.drawable.ic_github,
                            text = upstreamTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            } else {
                TopBar {
                    TopCard(
                        onClick = { showUpdateChecker = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(upstreamUrl) },
                            resId = R.drawable.ic_github,
                            text = upstreamTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(action = { safeOpenUri(licenseUrl) }, resId = R.drawable.ic_license)
                    IconCard(action = { safeOpenUri(privacyUrl) }, resId = R.drawable.ic_policy)
                }
            }
        }
    ) { _ ->
        val isVerticalLayout = isVerticalLayout()
        val wavelogMode = uiState.logbookSyncProvider == com.rtbishop.look4sat.core.domain.model.LogbookSyncProvider.WAVELOG
        val settingsGridState = rememberLazyGridState()
        val settingsScrollScope = rememberCoroutineScope()
        // Keep the viewport from before the first section was opened. Switching sections
        // preserves this origin; closing the active section returns to it.
        var settingsOriginalScrollPosition by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var settingsAutoScrollJob by remember { mutableStateOf<Job?>(null) }

        fun toggleSettingsSection(sectionId: Int, itemIndex: Int) {
            settingsAutoScrollJob?.cancel()
            if (expandedSettingsSection == sectionId) {
                val restorePosition = settingsOriginalScrollPosition
                expandedSettingsSection = 0
                settingsAutoScrollJob = settingsScrollScope.launch {
                    // Let the collapse animation finish and the grid remeasure before restoring.
                    delay(300)
                    if (restorePosition != null) {
                        settingsGridState.animateScrollToItem(
                            index = restorePosition.first,
                            scrollOffset = restorePosition.second
                        )
                    }
                    // Keep the original viewport if the user opens another section mid-animation.
                    if (expandedSettingsSection == 0) settingsOriginalScrollPosition = null
                }
            } else {
                // Save the original position only when opening from the fully-collapsed state.
                // If another section is already open, retain the same origin while switching.
                if (expandedSettingsSection == 0 && settingsOriginalScrollPosition == null) {
                    settingsOriginalScrollPosition =
                        settingsGridState.firstVisibleItemIndex to settingsGridState.firstVisibleItemScrollOffset
                }
                expandedSettingsSection = sectionId
                settingsAutoScrollJob = settingsScrollScope.launch {
                    // Let the newly-expanded content enter composition before scrolling to its heading.
                    delay(32)
                    settingsGridState.animateScrollToItem(index = itemIndex, scrollOffset = 0)
                }
            }
        }

        LazyVerticalGrid(
            state = settingsGridState,
            columns = GridCells.Fixed(if (isVerticalLayout) 1 else 2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
        ) {
            item {
                LocationCard(
                    settings = uiState.positionSettings,
                    setGpsPos = permissions.launchLocation,
                    showPosDialog = { dialogs.position = true },
                    showLocDialog = { dialogs.locator = true },
                    dismissPosMessage = { onAction(SettingsAction.DismissPosMessages) },
                    onAction = onAction
                )
            }
            item {
                DataCard(
                    settings = uiState.dataSettings,
                    updateFromWeb = { onAction(SettingsAction.UpdateFromWeb) },
                    clearAllData = { onAction(SettingsAction.ClearAllData) },
                    showDataSourcesDialog = { dialogs.dataSources = true }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CollapsibleSettingsSection(
                    titleResId = R.string.prefs_data_output_title,
                    expanded = outputSettingsExpanded,
                    onToggle = { toggleSettingsSection(sectionId = 1, itemIndex = 2) }
                ) {
                    OutputCard(
                        onNetworkClick = permissions.launchNetwork,
                        onBluetoothClick = permissions.launchBluetooth,
                        onRadioControlClick = { dialogs.radioControl = true },
                        onRotatorControlClick = { dialogs.rotatorControl = true },
                        diagnosticCount = uiState.controlDiagnosticCount,
                        onExportDiagnostics = { onAction(SettingsAction.ExportControlDiagnostics) },
                        onClearDiagnostics = { onAction(SettingsAction.ClearControlDiagnostics) }
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CollapsibleSettingsSection(
                    titleResId = R.string.prefs_ft4_title,
                    expanded = ft4SettingsExpanded,
                    onToggle = { toggleSettingsSection(sectionId = 2, itemIndex = 3) }
                ) {
                    Ft4SettingsCard(
                        state = uiState,
                        editGrid = { dialogs.locator = true },
                        enableGnss = permissions.launchGnss,
                        onAction = onAction
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CollapsibleSettingsSection(
                    titleResId = R.string.prefs_display_title,
                    expanded = displaySettingsExpanded,
                    onToggle = { toggleSettingsSection(sectionId = 3, itemIndex = 4) }
                ) {
                    DisplaySettingsCard(
                        value = passListFontScaleSlider,
                        onValueChange = { passListFontScaleSlider = it },
                        onValueChangeFinished = {
                            onAction(SettingsAction.SetPassListFontScale(passListFontScaleSlider))
                        },
                        settings = uiState.otherSettings,
                        onAction = onAction
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CollapsibleSettingsSection(
                    titleResId = R.string.prefs_other_title,
                    expanded = otherSettingsExpanded,
                    onToggle = { toggleSettingsSection(sectionId = 4, itemIndex = 5) }
                ) {
                    OtherCard(
                        settings = uiState.otherSettings,
                        onCompassCalibration = { dialogs.compassCalibration = true },
                        onMapSettings = { showMapSettings = true },
                        loTWEnabled = !wavelogMode,
                        onAction = onAction
                    )
                }
            }
            if (isDebuggableBuild) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CardButton(
                        onClick = { debugExportMessage = null; showDebugMenu = true },
                        text = stringResource(R.string.debug_menu_title),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item { ProjectWikiCard(onClick = { showProjectWiki = true }) }
            item { CardCredits() }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CardButton(
                    onClick = { showUpdateChecker = true },
                    text = stringResource(R.string.update_check_button),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CollapsibleSettingsSection(
    titleResId: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(titleResId),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                val arrowRotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(durationMillis = 220),
                    label = "settingsSectionArrowRotation"
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow),
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 220),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(durationMillis = 140))
            ) {
                Column {
                    HorizontalDivider()
                    content()
                }
            }
        }
    }
}

@Composable
private fun Ft4SettingsCard(
    state: SettingsState,
    editGrid: () -> Unit,
    enableGnss: () -> Unit,
    onAction: (SettingsAction) -> Unit
) {
    val settings = state.ft4Settings
    val capability = state.ft4Capability
    val clock = state.clockSnapshot
    val synchronization = state.timeSynchronizationState
    val timeStatus = if (clock.healthy && clock.source != ClockSource.SYSTEM) {
        stringResource(R.string.prefs_ft4_time_synced, clock.offsetMillis)
    } else {
        stringResource(R.string.prefs_ft4_time_unsynced, clock.offsetMillis)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        OutlinedTextField(
            value = settings.operatorCallsign,
            onValueChange = { onAction(SettingsAction.SetFt4Callsign(it)) },
            label = { Text(stringResource(R.string.prefs_ft4_callsign)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    R.string.prefs_ft4_grid,
                    state.positionSettings.stationPos.qthLocator
                ),
                modifier = Modifier.weight(1f)
            )
            CardButton(
                onClick = editGrid,
                text = stringResource(R.string.prefs_ft4_edit_grid)
            )
        }
        SwitchRow(
            labelResId = R.string.prefs_ft4_decode,
            checked = settings.decodeEnabled,
            enabled = capability.receiveAvailable
        ) { onAction(SettingsAction.ToggleFt4Decode(it)) }

        // Keep input and decoder depth configurable even when decoding is currently disabled.
        Text(
            text = stringResource(R.string.prefs_ft4_audio_input),
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = settings.audioInputDeviceKey.isBlank(),
                onClick = { onAction(SettingsAction.SetAudioInputDevice(null)) },
                label = { Text(stringResource(R.string.prefs_ft4_audio_default)) }
            )
            state.audioInputDevices.forEach { device ->
                FilterChip(
                    selected = settings.audioInputDeviceKey == device.key,
                    onClick = { onAction(SettingsAction.SetAudioInputDevice(device.key)) },
                    label = {
                        Text(
                            text = if (device.external) {
                                stringResource(R.string.prefs_ft4_audio_external, device.name)
                            } else {
                                device.name
                            },
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Text(
            text = stringResource(R.string.prefs_ft4_decode_depth),
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                1 to R.string.prefs_ft4_decode_fast,
                2 to R.string.prefs_ft4_decode_balanced,
                3 to R.string.prefs_ft4_decode_deep
            ).forEach { (depth, label) ->
                FilterChip(
                    selected = settings.decodeDepth == depth,
                    onClick = { onAction(SettingsAction.SetFt4DecodeDepth(depth)) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SwitchRow(R.string.prefs_ft4_ntp, settings.ntpSynchronizationEnabled) {
            onAction(SettingsAction.ToggleNtpSynchronization(it))
        }
        SwitchRow(R.string.prefs_ft4_gnss, settings.gnssSynchronizationEnabled) { enabled ->
            if (enabled) enableGnss() else onAction(SettingsAction.ToggleGnssSynchronization(false))
        }
        CardButton(
            onClick = { onAction(SettingsAction.SynchronizeTimeNow) },
            text = if (synchronization.synchronizing) {
                stringResource(R.string.prefs_ft4_synchronizing)
            } else {
                stringResource(R.string.prefs_ft4_sync_now)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(text = timeStatus, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DisplaySettingsCard(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    settings: OtherSettings,
    onAction: (SettingsAction) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(stringResource(R.string.prefs_display_font_size_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.prefs_display_font_size_value, (value * 100f).roundToInt()),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(stringResource(R.string.prefs_display_font_size_hint), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0.6f..1.4f,
            steps = 0
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf(
                R.string.prefs_display_font_size_tiny,
                R.string.prefs_display_font_size_small,
                R.string.prefs_display_font_size_standard,
                R.string.prefs_display_font_size_large,
                R.string.prefs_display_font_size_huge
            ).forEach { labelRes ->
                Text(
                    text = stringResource(labelRes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1
                )
            }
        }
        HorizontalDivider()
        SwitchRow(R.string.prefs_other_switch_light_theme, settings.stateOfLightTheme) {
            onAction(SettingsAction.ToggleLightTheme(it))
        }
        SwitchRow(
            R.string.prefs_other_switch_night_mode,
            settings.stateOfNightMode,
            enabled = !settings.stateOfLightTheme
        ) {
            onAction(SettingsAction.ToggleNightMode(it))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LocationCardPreview() = MainTheme {
    val stationPos = GeoPos(0.0, 0.0, 0.0, "IO91vl", 0L)
    val settings = PositionSettings(true, stationPos, 0)
    LocationCard(
        settings = settings,
        setGpsPos = {},
        showPosDialog = {},
        showLocDialog = {},
        dismissPosMessage = {},
        onAction = {}
    )
}

@Composable
private fun LocationCard(
    settings: PositionSettings,
    setGpsPos: () -> Unit,
    showPosDialog: () -> Unit,
    showLocDialog: () -> Unit,
    dismissPosMessage: () -> Unit,
    onAction: (SettingsAction) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_loc_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.stationPos.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Lat: ${settings.stationPos.latitude}°")
                Text(text = "Lon: ${settings.stationPos.longitude}°")
                Text(text = "Qth: ${settings.stationPos.qthLocator}")
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                CardButton(
                    onClick = setGpsPos,
                    text = stringResource(id = R.string.prefs_loc_gps_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showPosDialog,
                    text = stringResource(id = R.string.prefs_loc_input_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showLocDialog,
                    text = stringResource(id = R.string.prefs_loc_qth_title),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    if (settings.messageResId != 0) {
        val errorString = stringResource(id = settings.messageResId)
        LaunchedEffect(key1 = settings.messageResId) {
            onAction(SettingsAction.ShowToast(errorString))
            dismissPosMessage()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DataCardPreview() = MainTheme {
    val settings = DataSettings(true, 5000, 2500, 0L)
    DataCard(settings = settings, updateFromWeb = {}, clearAllData = {}, showDataSourcesDialog = {})
}

@Composable
private fun DataCard(
    settings: DataSettings,
    updateFromWeb: () -> Unit,
    clearAllData: () -> Unit,
    showDataSourcesDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_data_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.prefs_data_entries, settings.entriesTotal))
                Text(text = stringResource(R.string.prefs_data_radios, settings.radiosTotal))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = updateFromWeb,
                    text = stringResource(id = R.string.prefs_data_update),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = showDataSourcesDialog,
                    text = stringResource(id = R.string.prefs_data_import),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = clearAllData,
                    text = stringResource(id = R.string.prefs_data_clear),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OutputCardPreview() = MainTheme {
    CollapsibleSettingsSection(
        titleResId = R.string.prefs_data_output_title,
        expanded = true,
        onToggle = {}
    ) {
        OutputCard({}, {}, {}, {}, 0, {}, {})
    }
}

@Composable
private fun OutputCard(
    onNetworkClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onRadioControlClick: () -> Unit,
    onRotatorControlClick: () -> Unit,
    diagnosticCount: Int,
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = onNetworkClick,
                    text = stringResource(id = R.string.prefs_net_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onBluetoothClick,
                    text = stringResource(id = R.string.prefs_bt_output),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(R.string.control_diagnostics_count, diagnosticCount),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = onExportDiagnostics,
                    text = stringResource(R.string.control_diagnostics_export),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onClearDiagnostics,
                    text = stringResource(R.string.control_diagnostics_clear),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = onRadioControlClick,
                    text = stringResource(id = R.string.prefs_cat_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onRotatorControlClick,
                    text = stringResource(id = R.string.prefs_rotator_output),
                    modifier = Modifier.weight(1f)
                )
            }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtherCardPreview() = MainTheme {
    val values = OtherSettings(
        stateOfAutoUpdate = true,
        stateOfSensors = true,
        stateOfSweep = true,
        stateOfUtc = false,
        stateOfLightTheme = false,
        stateOfNightMode = false,
        stateOfMapGrid = false,
        shouldSeeWarning = false,
        shouldSeeWhatsNew = false
    )
    CollapsibleSettingsSection(
        titleResId = R.string.prefs_other_title,
        expanded = true,
        onToggle = {}
    ) {
        OtherCard(settings = values, onCompassCalibration = {}, onMapSettings = {}, onAction = {})
    }
}


@Composable
private fun OtherCard(
    settings: OtherSettings,
    onCompassCalibration: () -> Unit,
    onMapSettings: () -> Unit,
    loTWEnabled: Boolean = true,
    onAction: (SettingsAction) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
            SwitchRow(R.string.prefs_other_switch_utc, settings.stateOfUtc) {
                onAction(SettingsAction.ToggleUtc(it))
            }
            SwitchRow(R.string.prefs_other_switch_update, settings.stateOfAutoUpdate) {
                onAction(SettingsAction.ToggleUpdate(it))
            }
            SwitchRow(
                R.string.prefs_other_switch_lotw_sync,
                settings.stateOfAutoLotwSync,
                enabled = loTWEnabled
            ) {
                onAction(SettingsAction.ToggleAutoLotwSync(it))
            }
            SwitchRow(R.string.prefs_other_switch_sweep, settings.stateOfSweep) {
                onAction(SettingsAction.ToggleSweep(it))
            }
            SwitchRow(R.string.prefs_other_switch_sensors, settings.stateOfSensors) {
                onAction(SettingsAction.ToggleSensor(it))
            }
            CardButton(
                onClick = onCompassCalibration,
                text = stringResource(R.string.prefs_compass_calibration_button),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Text(
                text = stringResource(R.string.prefs_map_title),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            CardButton(
                onClick = onMapSettings,
                text = stringResource(R.string.prefs_map_configure),
                modifier = Modifier.fillMaxWidth()
            )
    }
}

@Composable
private fun CompassCalibrationDialog(
    accuracy: CompassAccuracy,
    headingDegrees: Float,
    initialOffsetDegrees: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit
) {
    var offsetText by rememberSaveable(initialOffsetDegrees) {
        mutableStateOf(initialOffsetDegrees.toString())
    }
    val parsedOffset = offsetText.replace(',', '.').toFloatOrNull()?.takeIf { it in -180f..180f }
    val statusRes = when (accuracy) {
        CompassAccuracy.HIGH -> R.string.prefs_compass_accuracy_high
        CompassAccuracy.MEDIUM -> R.string.prefs_compass_accuracy_medium
        CompassAccuracy.LOW -> R.string.prefs_compass_accuracy_low
        CompassAccuracy.UNRELIABLE -> R.string.prefs_compass_accuracy_unreliable
        CompassAccuracy.UNAVAILABLE -> R.string.prefs_compass_accuracy_unavailable
    }
    val progress = when (accuracy) {
        CompassAccuracy.HIGH -> 1f
        CompassAccuracy.MEDIUM -> 0.66f
        CompassAccuracy.LOW -> 0.33f
        CompassAccuracy.UNRELIABLE, CompassAccuracy.UNAVAILABLE -> 0f
    }
    SharedDialog(
        title = stringResource(R.string.prefs_compass_calibration_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        onAccept = {
            parsedOffset?.let {
                onSave(it)
                onDismiss()
            }
        }
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = padding)
        ) {
            Text(
                text = "∞",
                fontSize = 92.sp,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 92.sp
            )
            Text(
                text = stringResource(R.string.prefs_compass_calibration_instruction),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.prefs_compass_accuracy, stringResource(statusRes)),
                fontWeight = FontWeight.Medium,
                color = if (accuracy == CompassAccuracy.HIGH) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.prefs_compass_heading, headingDegrees),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it },
                label = { Text(stringResource(R.string.prefs_compass_offset)) },
                supportingText = { Text(stringResource(R.string.prefs_compass_offset_support)) },
                isError = parsedOffset == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            CardButton(
                onClick = { offsetText = "0" },
                text = stringResource(R.string.prefs_compass_offset_reset),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SwitchRow(
    labelResId: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(id = labelResId))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LoTWCard(
    settings: com.rtbishop.look4sat.core.domain.model.LoTWSettings,
    workedGridsCount: Int,
    lastSyncEpochMs: Long,
    isSyncing: Boolean,
    syncProgress: com.rtbishop.look4sat.core.domain.repository.LoTWProgress?,
    enabled: Boolean = true,
    showLoTWDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .alpha(if (enabled) 1f else 0.5f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_lotw_sync_title),
                    color = MaterialTheme.colorScheme.primary
                )
                // Keep the card indicator determinate even before byte counts arrive.
                LinearProgressIndicator(
                    progress = {
                        if (isSyncing) (syncProgress?.fraction ?: 0f).coerceIn(0f, 1f) else 0f
                    },
                    drawStopIndicator = {},
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (settings.isConfigured) {
                    stringResource(R.string.prefs_lotw_configured, workedGridsCount)
                } else {
                    stringResource(R.string.prefs_lotw_not_configured)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            // Last successful sync, shown exactly like the ephemeris update time.
            if (lastSyncEpochMs != 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatUpdateTime(updateTime = lastSyncEpochMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!enabled) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.prefs_lotw_disabled_wavelog),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            CardButton(
                onClick = showLoTWDialog,
                text = stringResource(id = R.string.prefs_wavelog_configure),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WavelogUploadCard(
    settings: com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings,
    lastSyncEpochMs: Long,
    showDialog: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_wavelog_upload_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.prefs_wavelog_exclusive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (settings.isReady) {
                    stringResource(
                        R.string.prefs_wavelog_upload_configured,
                        listOf(settings.stationName, settings.stationCallsign, settings.stationGrid)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    )
                } else {
                    stringResource(R.string.prefs_wavelog_upload_not_configured)
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            if (lastSyncEpochMs != 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatUpdateTime(updateTime = lastSyncEpochMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            CardButton(
                onClick = showDialog,
                text = stringResource(id = R.string.prefs_wavelog_configure),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun formatUpdateTime(updateTime: Long): String {
    val timePattern = stringResource(id = R.string.prefs_updated_time)
    val placeholder = stringResource(id = R.string.pass_time_placeholder)
    val updateDate = remember(updateTime) {
        if (updateTime != 0L) {
            SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(updateTime))
        } else {
            placeholder
        }
    }
    return stringResource(id = R.string.prefs_updated_title, updateDate)
}

@Composable
private fun UpdateIndicator(isUpdating: Boolean, modifier: Modifier = Modifier) = if (isUpdating) {
    LinearProgressIndicator(modifier = modifier.padding(start = 6.dp))
} else {
    LinearProgressIndicator(
        progress = { 0f },
        drawStopIndicator = {},
        modifier = modifier.padding(start = 6.dp)
    )
}

@Composable
private fun ProjectWikiCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(painter = painterResource(id = R.drawable.ic_policy), contentDescription = null)
            Text(
                text = stringResource(R.string.prefs_project_wiki),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CardCreditsPreview() = MainTheme { CardCredits() }

@Composable
private fun CardCredits(modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_outro_title),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_notice),
                fontSize = 12.sp
            )
            HorizontalDivider()
            Text(
                text = stringResource(id = R.string.prefs_outro_thanks),
                fontSize = 12.sp
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_license),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TopCard(onClick: () -> Unit, modifier: Modifier = Modifier, version: String) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(
                painter = painterResource(id = R.drawable.ic_satellites),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.prefs_app_title, version),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

@Composable
private fun BotCard(onClick: () -> Unit, resId: Int, text: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(painter = painterResource(id = resId), contentDescription = null)
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

// region Dialog visibility state holder

@Stable
private class DialogVisibility {
    var position by mutableStateOf(false)
    var locator by mutableStateOf(false)
    var dataSources by mutableStateOf(false)
    var network by mutableStateOf(false)
    var bluetooth by mutableStateOf(false)
    var radioControl by mutableStateOf(false)
    var compassCalibration by mutableStateOf(false)
    var wavelog by mutableStateOf(false)
    var rotatorControl by mutableStateOf(false)
    var wavelogUpload by mutableStateOf(false)
}

@Composable
private fun rememberDialogVisibility(): DialogVisibility {
    return rememberSaveable(saver = run {
        androidx.compose.runtime.saveable.Saver(
            save = {
                listOf(
                    it.position, it.locator, it.dataSources, it.network, it.bluetooth,
                    it.radioControl, it.wavelog, false, it.compassCalibration,
                    it.rotatorControl, false, false, it.wavelogUpload
                )
            },
            restore = {
                DialogVisibility().apply {
                    position = it[0]; locator = it[1]; dataSources = it[2]
                    network = it[3]; bluetooth = it[4]; radioControl = it[5]; wavelog = it[6]
                    compassCalibration = it.getOrElse(8) { false }
                    rotatorControl = it.getOrElse(9) { false }
                    wavelogUpload = it.getOrElse(12) { false }
                }
            }
        )
    }) { DialogVisibility() }
}

// endregion

// region Permission launchers holder

@Stable
private class SettingsPermissions(
    val launchLocation: () -> Unit,
    val launchGnss: () -> Unit,
    val launchTleImport: () -> Unit,
    val launchTransceiverImport: () -> Unit,
    val launchBluetooth: () -> Unit,
    val launchNetwork: () -> Unit,
    val launchCustomSourcesPermission: () -> Unit
)

@Composable
private fun rememberSettingsPermissions(
    sendAction: (SettingsAction) -> Unit,
    onBluetoothGranted: () -> Unit,
    onNetworkGranted: () -> Unit,
    onCustomSourcesPermissionGranted: () -> Unit,
    onCustomSourcesPermissionDenied: () -> Unit
): SettingsPermissions {
    val locationError = stringResource(R.string.prefs_loc_gps_error)
    val locationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) sendAction(SettingsAction.SetGpsPosition)
        else sendAction(SettingsAction.ShowToast(locationError))
    }
    val gnssPermissionError = stringResource(R.string.prefs_ft4_gnss_permission)
    val gnssRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            sendAction(SettingsAction.ToggleGnssSynchronization(true))
        } else {
            sendAction(SettingsAction.ToggleGnssSynchronization(false))
            sendAction(SettingsAction.ShowToast(gnssPermissionError))
        }
    }

    val satellitesImportError = stringResource(R.string.prefs_data_import_satellites_error)
    val transceiversImportError = stringResource(R.string.prefs_data_import_transceivers_error)

    val tleRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTLEFromFile(it.toString(), satellitesImportError)) }
    }

    val transceiversRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTransceiversFromFile(it.toString(), transceiversImportError)) }
    }

    val bluetoothError = stringResource(R.string.prefs_bt_perm_error)
    val bluetoothPerm = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        Manifest.permission.BLUETOOTH else Manifest.permission.BLUETOOTH_CONNECT
    val bluetoothRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onBluetoothGranted()
        else sendAction(SettingsAction.ShowToast(bluetoothError))
    }

    val networkError = stringResource(R.string.prefs_net_perm_error)
    val networkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onNetworkGranted()
        else sendAction(SettingsAction.ShowToast(networkError))
    }

    val customSourcesRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onCustomSourcesPermissionGranted()
        } else {
            onCustomSourcesPermissionDenied()
            sendAction(SettingsAction.ShowToast(networkError))
        }
    }

    return remember {
        SettingsPermissions(
            launchLocation = {
                locationRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            },
            launchGnss = {
                gnssRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            },
            launchTleImport = { tleRequest.launch("*/*") },
            launchTransceiverImport = { transceiversRequest.launch("*/*") },
            launchBluetooth = { bluetoothRequest.launch(bluetoothPerm) },
            launchNetwork = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    networkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onNetworkGranted()
                }
            },
            launchCustomSourcesPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    customSourcesRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onCustomSourcesPermissionGranted()
                }
            }
        )
    }
}

// endregion
