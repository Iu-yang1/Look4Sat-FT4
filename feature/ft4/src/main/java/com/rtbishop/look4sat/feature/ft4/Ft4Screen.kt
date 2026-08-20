/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.ft4

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.RadarViewCompose
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
private sealed interface Ft4Page : NavKey {
    @Serializable data object Spectrum : Ft4Page
    @Serializable data object Decode : Ft4Page
}

@Composable
fun Ft4ShellDestination(navigateUp: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: Ft4ViewModel = viewModel(factory = Ft4ViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onAction(Ft4Action.MicrophonePermissionChanged(granted)) }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.onAction(Ft4Action.ConnectRadios) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                viewModel.onAction(Ft4Action.MicrophonePermissionChanged(granted))
            }
            if (event == Lifecycle.Event.ON_STOP) viewModel.onAction(Ft4Action.Leave)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onAction(Ft4Action.Leave)
        }
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onAction(Ft4Action.MicrophonePermissionChanged(true))
        } else {
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Ft4Shell(
        state = state,
        viewModel = viewModel,
        onAction = viewModel::onAction,
        navigateUp = navigateUp,
        requestMicrophone = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        connectRadios = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.onAction(Ft4Action.ConnectRadios)
            } else {
                bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    )
}

@Composable
private fun Ft4Shell(
    state: Ft4State,
    viewModel: Ft4ViewModel,
    onAction: (Ft4Action) -> Unit,
    navigateUp: () -> Unit,
    requestMicrophone: () -> Unit,
    connectRadios: () -> Unit
) {
    val backStack = rememberNavBackStack(Ft4Page.Spectrum)
    val current = backStack.lastOrNull()
    val pages = listOf(Ft4Page.Spectrum, Ft4Page.Decode)
    val timing by viewModel.timingState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize().keepScreenOn(),
        topBar = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                LinearProgressIndicator(
                    progress = { timing.slotProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.ft4_slot_progress, (timing.slotProgress * 100).roundToInt()),
                        fontSize = 10.sp
                    )
                    Text(
                        stringResource(
                            R.string.ft4_time_source,
                            clockSourceLabel(timing.clock.source),
                            timing.clock.uncertaintyMillis
                        ),
                        fontSize = 10.sp
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconCard(action = navigateUp, resId = R.drawable.ic_back)
                    ElevatedCard(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                stringResource(R.string.ft4_title),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                capabilityText(state),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (state.error.isNotBlank()) {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth().clickable { onAction(Ft4Action.ClearError) }
                    ) {
                        Text(
                            stringResource(R.string.ft4_error, state.error),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding().height(58.dp),
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                pages.forEach { page ->
                    val label = when (page) {
                        Ft4Page.Spectrum -> stringResource(R.string.ft4_page_spectrum)
                        Ft4Page.Decode -> stringResource(R.string.ft4_page_decode)
                    }
                    val icon = when (page) {
                        Ft4Page.Spectrum -> R.drawable.ic_radio_tower
                        Ft4Page.Decode -> R.drawable.ic_radios
                    }
                    NavigationBarItem(
                        selected = current == page,
                        onClick = {
                            if (current != page) {
                                backStack.removeLastOrNull()
                                backStack.add(page)
                            }
                        },
                        icon = { Icon(painterResource(icon), contentDescription = label) },
                        label = { Text(label, maxLines = 1, fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = navigateUp,
                modifier = Modifier.weight(1f),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = entryProvider {
                    entry<Ft4Page.Spectrum> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SatelliteRadioStatus(
                                state = state,
                                onAction = onAction,
                                connectRadios = connectRadios,
                                navigateToPasses = navigateUp,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Ft4SpectrumPage(
                                state = state,
                                viewModel = viewModel,
                                onAction = onAction,
                                requestMicrophone = requestMicrophone,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    entry<Ft4Page.Decode> {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SatelliteRadioStatus(
                                state = state,
                                onAction = onAction,
                                connectRadios = connectRadios,
                                navigateToPasses = navigateUp,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Ft4DecodePage(state, onAction, Modifier.fillMaxWidth())
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SatelliteRadioStatus(
    state: Ft4State,
    onAction: (Ft4Action) -> Unit,
    connectRadios: () -> Unit,
    navigateToPasses: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val radio = state.radio
    val pass = state.trackingPass
    val transponder = radio.selectedTransponder
    val notSet = stringResource(R.string.ft4_not_set)
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ft4_satellite_radio), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (radio.isActive) stringResource(R.string.ft4_tracking_on)
                    else stringResource(R.string.ft4_tracking_off),
                    color = if (radio.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Icon(
                    painterResource(if (expanded) R.drawable.ic_arrow else R.drawable.ic_add),
                    contentDescription = stringResource(
                        if (expanded) R.string.ft4_collapse_status else R.string.ft4_expand_status
                    )
                )
            }
            if (pass == null || transponder == null) {
                Text(stringResource(R.string.ft4_no_pass))
                Text(stringResource(R.string.ft4_select_pass_hint), fontSize = 12.sp)
                CardButton(
                    onClick = navigateToPasses,
                    text = stringResource(R.string.ft4_go_to_passes),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                if (expanded) {
                    Text(
                        stringResource(R.string.ft4_satellite_value, pass.name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        transponder.info,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    state.orbitalPosition?.let { position ->
                        RadarViewCompose(
                            item = position,
                            items = state.satelliteTrack,
                            azimElev = state.orientationValues,
                            shouldShowSweep = state.shouldShowSweep,
                            shouldUseCompass = state.shouldUseCompass,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        )
                    }
                    Text(
                        stringResource(
                            R.string.ft4_radar_position,
                            state.orbitalPosition?.let { Math.toDegrees(it.azimuth) } ?: radio.azimuth,
                            state.orbitalPosition?.let { Math.toDegrees(it.elevation) } ?: radio.elevation
                        ),
                        fontSize = 12.sp
                    )
                    FrequencyStatus(state)
                    Text(
                        stringResource(
                            R.string.ft4_radio_compact,
                            radio.txMode ?: notSet,
                            radio.rxMode ?: notSet,
                            radio.pttState.name,
                            stringResource(
                                if (radio.commandBusy) R.string.ft4_cat_busy else R.string.ft4_cat_ready
                            )
                        ),
                        fontSize = 12.sp
                    )
                    radio.lastCommandError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardButton(
                            onClick = if (radio.txConnected || radio.rxConnected) {
                                { onAction(Ft4Action.DisconnectRadios) }
                            } else connectRadios,
                            text = stringResource(
                                if (radio.txConnected || radio.rxConnected) R.string.ft4_disconnect_radios
                                else R.string.ft4_connect_radios
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        CardButton(
                            onClick = { onAction(Ft4Action.ToggleTracking) },
                            text = stringResource(
                                if (radio.isActive) R.string.ft4_stop_tracking else R.string.ft4_start_tracking
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    CardButton(
                        onClick = { onAction(Ft4Action.EmergencyStop) },
                        text = stringResource(R.string.ft4_emergency_stop),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.ft4_radar_summary,
                            pass.name,
                            state.orbitalPosition?.let { Math.toDegrees(it.azimuth) } ?: radio.azimuth,
                            state.orbitalPosition?.let { Math.toDegrees(it.elevation) } ?: radio.elevation
                        ),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FrequencyStatus(state: Ft4State) {
    val radio = state.radio
    val tx = radio.txFrequencyHz
    val nominalTx = radio.nominalTxFrequencyHz
    if (tx != null && nominalTx != null) {
        Text(
            stringResource(
                R.string.ft4_tx_frequency,
                tx / 1_000_000.0,
                nominalTx / 1_000_000.0,
                radio.txDopplerCorrectionHz ?: 0L
            ), fontSize = 12.sp
        )
    }
    val rx = radio.rxFrequencyHz
    val nominalRx = radio.nominalRxFrequencyHz
    if (rx != null && nominalRx != null) {
        Text(
            stringResource(
                R.string.ft4_rx_frequency,
                rx / 1_000_000.0,
                nominalRx / 1_000_000.0,
                radio.rxDopplerCorrectionHz ?: 0L
            ), fontSize = 12.sp
        )
    }
}

@Composable
private fun capabilityText(state: Ft4State): String = when {
    !state.settings.decodeEnabled -> stringResource(R.string.ft4_status_disabled)
    state.capability.receiveAvailable -> stringResource(R.string.ft4_status_ready, state.capability.abi)
    else -> stringResource(
        R.string.ft4_status_unavailable,
        state.capability.unavailableReason.ifBlank { state.capability.abi }
    )
}
