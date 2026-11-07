// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.logbook.displayTheirGrid
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

enum class DigitalPage { SPECTRUM, CONTACT, LOG }

@Composable
fun DigitalSpectrumPane(
    renderState: SpectrumRenderState, selectedHz: Float, onSelected: (Float) -> Unit,
    selectionLabel: String, receiving: Boolean, onAudio: () -> Unit,
    expanded: Boolean, onExpanded: (Boolean) -> Unit,
    description: String? = null, txHz: Float? = null,
    diagnostics: @Composable ColumnScope.() -> Unit = {}
) {
    var display by rememberSaveable { mutableStateOf(false) }
    val height = if (expanded) (LocalConfiguration.current.screenHeightDp.dp * .55f).coerceAtLeast(180.dp) else 180.dp
    Box(Modifier.fillMaxWidth().height(height)) {
        DigitalSpectrumWaterfall(renderState, selectedHz, onSelected, Modifier.fillMaxSize(), description, txHz)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(selectionLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAudio) { Text(stringResource(R.string.digital_audio)) }
        TextButton(onClick = { display = true }) { Text(stringResource(R.string.digital_display)) }
    }
    Text(stringResource(if (receiving) R.string.digital_receiving else R.string.digital_receive_stopped),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (display) SharedDialog(title = stringResource(R.string.digital_display),
        onDismissRequest = { display = false }, onCancel = { display = false }) { padding ->
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .55f)
            .verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.digital_expand_spectrum), modifier = Modifier.weight(1f))
                Switch(checked = expanded, onCheckedChange = onExpanded)
            }
            diagnostics()
        }
    }
}

@Composable
fun DigitalReceiveActions(receiving: Boolean, onReceive: () -> Unit, onContact: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onReceive, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(if (receiving) R.string.ft4_stop_receive else R.string.ft4_start_receive))
        }
        Button(onClick = onContact, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.digital_contact))
        }
    }
}

/** Tabs switch presentation only. The feature owns its single radio/audio session. */
@Composable
fun DigitalWorkbench(
    title: String,
    panel: DigitalRadioPanelState,
    page: DigitalPage,
    onPageChange: (DigitalPage) -> Unit,
    status: String,
    transmitting: Boolean,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTrack: () -> Unit,
    onStopTransmit: () -> Unit,
    onSetBases: (RadioBaseFrequencies) -> Unit,
    onAudio: () -> Unit,
    parameterContent: @Composable ColumnScope.() -> Unit,
    radioDetails: @Composable ColumnScope.() -> Unit = {},
    timingContent: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    var large by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var parameters by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    val activeTransmit = transmitting || panel.radio.pttState != PttState.OFF ||
        panel.radio.catTransmitting || panel.radio.transmitPreparing
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 6.dp)) {
                TopBar {
                    IconCard(onBack, R.drawable.ic_back)
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    IconCard({ parameters = true }, R.drawable.ic_settings)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { controls = true }, modifier = Modifier.weight(1f)) {
                        Text(status, modifier = Modifier.weight(1f), maxLines = 2,
                            textAlign = TextAlign.Start, overflow = TextOverflow.Ellipsis)
                        Icon(painterResource(R.drawable.ic_radios), stringResource(R.string.digital_radio_controls))
                    }
                    if (activeTransmit) TextButton(onClick = onStopTransmit, modifier = Modifier.testTag("digitalStopTransmit")) {
                        Text(stringResource(R.string.ft4_stop_transmit), color = MaterialTheme.colorScheme.error)
                    }
                }
                timingContent()
            }
        },
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) {
                DigitalPage.entries.forEach { target ->
                    val label = stringResource(when (target) {
                        DigitalPage.SPECTRUM -> R.string.ft4_page_spectrum
                        DigitalPage.CONTACT -> R.string.digital_contact
                        DigitalPage.LOG -> R.string.nav_logbook
                    })
                    NavigationBarItem(
                        selected = page == target, onClick = { onPageChange(target) },
                        modifier = Modifier.testTag("digitalTab_${target.name}"),
                        icon = {
                            Icon(painterResource(when (target) {
                                DigitalPage.SPECTRUM -> R.drawable.ic_spectrum_ft8cn
                                DigitalPage.CONTACT -> R.drawable.ic_radios
                                DigitalPage.LOG -> R.drawable.ic_logbook
                            }), label)
                        },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    ) { padding ->
        pageStates.SaveableStateProvider(page.name) {
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DigitalStationSummary(panel, large, { large = it }, expanded, { expanded = it })
                if (panel.pass == null) {
                    Text(stringResource(R.string.ft4_select_pass_hint))
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ft4_go_to_passes))
                    }
                } else if (panel.radio.selectedTransponder == null) Text(stringResource(R.string.ft4_error_select_transponder),
                    color = MaterialTheme.colorScheme.error)
                DigitalReferenceControl(panel, activeTransmit, onSetBases)
                content()
            }
        }
    }
    if (controls) SharedDialog(
        title = stringResource(R.string.digital_radio_controls),
        onDismissRequest = { controls = false }, onCancel = { controls = false }
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .55f)
                .verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(status)
            panel.radio.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            panel.radio.lastCommandError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("TX ${panel.radio.txMode ?: "—"} · RX ${panel.radio.rxMode ?: "—"}",
                style = MaterialTheme.typography.bodySmall)
            radioDetails()
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(
                    if (panel.radio.txConnected || panel.radio.rxConnected) onDisconnect else onConnect,
                    stringResource(if (panel.radio.txConnected || panel.radio.rxConnected)
                        R.string.ft4_disconnect_radios else R.string.ft4_connect_radios),
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                CardButton(onTrack, stringResource(if (panel.radio.isActive)
                    R.string.ft4_stop_tracking else R.string.ft4_start_tracking),
                    modifier = Modifier.heightIn(min = 48.dp))
            }
            CardButton(onStopTransmit, stringResource(R.string.ft4_emergency_stop),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            TextButton(onClick = { controls = false; onAudio() }) {
                Text(stringResource(R.string.radio_audio_title))
            }
        }
    }
    if (parameters) SharedDialog(
        title = stringResource(R.string.digital_parameters),
        onDismissRequest = { parameters = false }, onCancel = { parameters = false }
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .55f)
                .verticalScroll(rememberScrollState()).padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            parameterContent()
        }
    }
}

@Composable
private fun DigitalStationSummary(
    panel: DigitalRadioPanelState, large: Boolean, onLarge: (Boolean) -> Unit,
    expanded: Boolean, onExpanded: (Boolean) -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(panel.pass?.catNum, panel.pass?.aosTime) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val pass = panel.pass
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onExpanded(!expanded) }, modifier = Modifier.weight(1f)) {
                Text(pass?.name ?: stringResource(R.string.ft4_no_pass), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "−" else "+")
            }
            DigitalRadarSizeButton(large, onLarge)
        }
        if (expanded) {
            val countdown = pass?.let {
                val before = now < it.aosTime
                val seconds = ((if (before) it.aosTime else it.losTime) - now).coerceAtLeast(0) / 1_000
                String.format(Locale.US, "%s %02d:%02d:%02d", if (before) "AOS" else "LOS",
                    seconds / 3600, seconds / 60 % 60, seconds % 60)
            }.orEmpty()
            DigitalSatellitePanel("", countdown, panel.orbitalPosition, panel.satelliteTrack, large,
                panel.orientationValues, panel.shouldShowSweep, panel.shouldUseCompass) {
                DigitalFrequencyLine("TX", panel.radio.targetTxFrequencyHz ?: panel.radio.txFrequencyHz
                    ?: panel.radio.nominalTxFrequencyHz)
                DigitalFrequencyLine("RX", panel.radio.targetRxFrequencyHz ?: panel.radio.rxFrequencyHz
                    ?: panel.radio.nominalRxFrequencyHz)
            }
        }
    }
}

@Composable
private fun DigitalFrequencyLine(label: String, hz: Long?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
        Text(hz?.let(::formatFrequency) ?: "—", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 1,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()))
    }
}

fun digitalReferenceText(hz: Long): String =
    BigDecimal.valueOf(hz, 6).setScale(3, RoundingMode.HALF_UP).toPlainString()

fun parseDigitalReference(text: String): Long? = runCatching {
    text.trim().toBigDecimal().movePointRight(6).longValueExact()
}.getOrNull()?.takeIf { it in 1L..9_999_999_999L }

@Composable
private fun DigitalReferenceControl(
    panel: DigitalRadioPanelState, transmitting: Boolean, onSet: (RadioBaseFrequencies) -> Unit
) {
    val radio = panel.radio
    val rx = radio.nominalRxFrequencyHz
    val tx = radio.txBaseFrequencyHz ?: radio.nominalTxFrequencyHz
    var editing by rememberSaveable(panel.pass?.catNum, radio.selectedTransponder?.uuid) { mutableStateOf(false) }
    val enabled = rx != null && !radio.frequencyEditingLocked && !transmitting
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { editing = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(R.string.rc_panel_reference_compact), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.rc_panel_reference_pair, rx?.let(::digitalReferenceText) ?: "—",
                tx?.let(::digitalReferenceText) ?: "—"), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()))
            Icon(painterResource(R.drawable.ic_edit), null, modifier = Modifier.size(20.dp))
        }
    }
    if (editing) key(panel.pass?.catNum, radio.selectedTransponder?.uuid) {
        var rxText by rememberSaveable { mutableStateOf(rx?.let { BigDecimal.valueOf(it, 6).toPlainString() }.orEmpty()) }
        var txText by rememberSaveable { mutableStateOf(tx?.let { BigDecimal.valueOf(it, 6).toPlainString() }.orEmpty()) }
        var targetRx by rememberSaveable { mutableStateOf(tx == null) }
        val minimum = radio.remoteRadio?.capabilities?.minimumRxHz?.toLong() ?: 1L
        val maximum = radio.remoteRadio?.capabilities?.maximumRxHz?.toLong() ?: 9_999_999_999L
        val rxValue = parseDigitalReference(rxText)?.takeIf { it in minimum..maximum }
        val txValue = tx?.let { parseDigitalReference(txText)?.takeIf { hz -> hz in minimum..maximum } }
        SharedDialog(
            title = stringResource(R.string.rc_panel_reference), onDismissRequest = { editing = false },
            onCancel = { editing = false }, acceptEnabled = enabled && rxValue != null && (tx == null || txValue != null),
            onAccept = {
                if (enabled && rxValue != null && (tx == null || txValue != null)) {
                    onSet(RadioBaseFrequencies(rxValue, txValue)); editing = false
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .55f)
                .verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(rxText, { rxText = it }, label = { Text(stringResource(R.string.rc_l4s_rx_reference)) },
                    enabled = enabled, isError = rxValue == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (tx != null) OutlinedTextField(txText, { txText = it },
                    label = { Text(stringResource(R.string.rc_l4s_tx_reference)) }, enabled = enabled,
                    isError = txValue == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    FilterChip(selected = targetRx, onClick = { targetRx = true }, label = { Text("RX") })
                    if (tx != null) FilterChip(selected = !targetRx, onClick = { targetRx = false }, label = { Text("TX") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-10_000L, -1_000L, -100L, 100L, 1_000L, 10_000L).forEach { delta ->
                        OutlinedButton(enabled = enabled, onClick = {
                            val value = parseDigitalReference(if (targetRx) rxText else txText)
                            val adjusted = value?.let { runCatching { Math.addExact(it, delta) }.getOrNull() }
                            if (adjusted != null && adjusted in minimum..maximum) {
                                val text = BigDecimal.valueOf(adjusted, 6).toPlainString()
                                if (targetRx) rxText = text else txText = text
                            }
                        }) { Text(String.format(Locale.US, "%+d", delta)) }
                    }
                }
            }
        }
    }
}

@Composable
fun DigitalLogList(records: List<QsoRecord>, satellite: String?, onLogbook: () -> Unit) {
    var allSatellites by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(false) }
    val shown = digitalLogRecords(records, satellite, allSatellites)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.nav_logbook), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { filter = true }) {
            Text(stringResource(if (allSatellites) R.string.digital_all_satellites else R.string.digital_current_satellite))
        }
    }
    if (shown.isEmpty()) Text(stringResource(R.string.digital_no_logs), modifier = Modifier.padding(12.dp))
    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shown, key = { it.id }) { record ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(record.theirCallsign, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        Text(record.displayMode, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(digitalUtc(record.startUtcMillis, "yyyy-MM-dd HH:mm:ss") + " UTC · " + record.satelliteName,
                        style = MaterialTheme.typography.bodySmall)
                    Text(listOf(record.displayTheirGrid, gridsLabel(record.myGrid.split(',') + record.vuccGrids),
                        record.country).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    val confirmed = record.lotwConfirmed || record.qslConfirmed || record.eqslConfirmed
                    Text(stringResource(if (confirmed) R.string.digital_confirmed
                        else R.string.prefs_logbook_query_unconfirmed),
                        color = if (confirmed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    OutlinedButton(onClick = onLogbook, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.digital_full_logbook))
    }
    if (filter) SharedDialog(title = stringResource(R.string.digital_log_filter),
        onDismissRequest = { filter = false }, onCancel = { filter = false }) { padding ->
        Column(Modifier.fillMaxWidth().padding(padding)) {
            listOf(false, true).forEach { all ->
                TextButton(onClick = { allSatellites = all; filter = false }) {
                    RadioButton(selected = allSatellites == all, onClick = null)
                    Text(stringResource(if (all) R.string.digital_all_satellites else R.string.digital_current_satellite))
                }
            }
        }
    }
}

fun digitalUtc(millis: Long, pattern: String = "HH:mm:ss"): String =
    SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

fun digitalLogRecords(records: List<QsoRecord>, satellite: String?, allSatellites: Boolean): List<QsoRecord> =
    records.filter { allSatellites || satellite != null && it.satelliteName.equals(satellite, true) }
        .sortedByDescending { it.startUtcMillis }.take(100)

@Composable
fun DigitalAudioFrequencyControl(
    frequencyHz: Float, minimumHz: Float, maximumHz: Float, enabled: Boolean, onSet: (Float) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { editing = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.digital_tx_frequency, frequencyHz.toInt()))
    }
    if (editing) {
        var text by rememberSaveable { mutableStateOf(frequencyHz.toInt().toString()) }
        val value = text.toFloatOrNull()?.takeIf { it.isFinite() && it in minimumHz..maximumHz }
        SharedDialog(title = stringResource(R.string.ft4_tx_audio),
            onDismissRequest = { editing = false }, onCancel = { editing = false },
            acceptEnabled = enabled && value != null,
            onAccept = { if (enabled && value != null) { onSet(value); editing = false } }
        ) { padding ->
            Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .55f)
                .verticalScroll(rememberScrollState()).padding(padding)) {
                OutlinedTextField(text, { text = it }, singleLine = true, isError = value == null,
                    enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ft4_tx_audio)) })
                Slider(value = value ?: frequencyHz.coerceIn(minimumHz, maximumHz),
                    onValueChange = { text = it.toInt().toString() }, valueRange = minimumHz..maximumHz,
                    enabled = enabled)
            }
        }
    }
}

@Composable
fun DigitalMessageContent(text: String, metadata: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Text(metadata, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
