package com.rtbishop.look4sat.feature.radar.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.QuickLogError
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.presentation.QuickLogViewModel
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.quickLogModes

@Composable
fun LogsPage(
    container: IMainContainer,
    onLogbook: () -> Unit,
    pass: OrbitalPass?,
    transponders: List<SatRadio>,
    selectedTransponderUuid: String?
) {
    val model: QuickLogViewModel = viewModel(factory = QuickLogViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(pass?.catNum, pass?.aosTime, transponders, selectedTransponderUuid) {
        model.setContext(pass, transponders, selectedTransponderUuid)
    }
    var satelliteMenu by remember { mutableStateOf(false) }
    var transponderMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.quicklog_defaults_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onLogbook) { Text(stringResource(R.string.nav_logbook)) }
        }

        val notice = state.error?.let { error ->
            stringResource(
                when (error) {
                    QuickLogError.CALLSIGN -> R.string.quicklog_invalid_callsign
                    QuickLogError.GRID -> R.string.quicklog_invalid_grid
                    QuickLogError.REPORT -> R.string.quicklog_invalid_report
                    QuickLogError.FREQUENCY -> R.string.quicklog_invalid_frequency
                    QuickLogError.UTC -> R.string.quicklog_invalid_utc
                    QuickLogError.SATELLITE -> R.string.quicklog_select_satellite
                    QuickLogError.STORAGE -> R.string.quicklog_save_failed
                }
            )
        } ?: state.savedCallsign.takeIf(String::isNotBlank)?.let {
            stringResource(R.string.quicklog_saved, it)
        }
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                SelectorButton(
                    text = state.pass?.name ?: stringResource(R.string.quicklog_select_satellite),
                    enabled = !state.saving,
                    onClick = { satelliteMenu = true }
                )
                DropdownMenu(
                    expanded = satelliteMenu,
                    onDismissRequest = { satelliteMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    state.candidates.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.name) },
                            onClick = {
                                model.selectPass(item)
                                satelliteMenu = false
                            }
                        )
                    }
                    if (state.candidates.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.quicklog_no_passes)) },
                            onClick = {},
                            enabled = false
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                SelectorButton(
                    text = state.transponder?.info ?: stringResource(R.string.quicklog_no_transponder),
                    enabled = !state.saving && state.transponders.isNotEmpty(),
                    onClick = { transponderMenu = true }
                )
                DropdownMenu(
                    expanded = transponderMenu,
                    onDismissRequest = { transponderMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    state.transponders.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.info) },
                            onClick = {
                                model.selectTransponder(item)
                                transponderMenu = false
                            }
                        )
                    }
                }
            }
        }

        LogField(
            value = state.utcText,
            onValueChange = model::utc,
            label = stringResource(R.string.quicklog_utc),
            enabled = !state.saving,
            error = state.error == QuickLogError.UTC
        )

        Text(stringResource(R.string.quicklog_mode), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(quickLogModes) { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { model.mode(mode) },
                    enabled = !state.saving,
                    label = { Text(mode) }
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogField(
                value = state.callsign,
                onValueChange = model::callsign,
                label = stringResource(R.string.quicklog_callsign),
                enabled = !state.saving,
                error = state.error == QuickLogError.CALLSIGN,
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.weight(1f)
            )
            LogField(
                value = state.theirGrid,
                onValueChange = model::theirGrid,
                label = stringResource(R.string.quicklog_grid),
                enabled = !state.saving,
                error = state.error == QuickLogError.GRID,
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogField(
                value = state.sent,
                onValueChange = model::sent,
                label = stringResource(R.string.quicklog_sent),
                enabled = !state.saving,
                error = state.error == QuickLogError.REPORT,
                modifier = Modifier.weight(1f)
            )
            LogField(
                value = state.received,
                onValueChange = model::received,
                label = stringResource(R.string.quicklog_received),
                enabled = !state.saving,
                error = state.error == QuickLogError.REPORT,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider()
        Text(stringResource(R.string.quicklog_prefilled_details), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogField(
                value = state.txFrequencyHz,
                onValueChange = model::txFrequency,
                label = stringResource(R.string.quicklog_tx_frequency),
                enabled = !state.saving,
                error = state.error == QuickLogError.FREQUENCY,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
            LogField(
                value = state.rxFrequencyHz,
                onValueChange = model::rxFrequency,
                label = stringResource(R.string.quicklog_rx_frequency),
                enabled = !state.saving,
                error = state.error == QuickLogError.FREQUENCY,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogField(
                value = state.myCallsign,
                onValueChange = model::myCallsign,
                label = stringResource(R.string.quicklog_my_callsign),
                enabled = !state.saving,
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.weight(1f)
            )
            LogField(
                value = state.myGrid,
                onValueChange = model::myGrid,
                label = stringResource(R.string.quicklog_my_grid),
                enabled = !state.saving,
                error = state.error == QuickLogError.GRID,
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.weight(1f)
            )
        }

        Text(stringResource(R.string.quicklog_status), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(QsoStatus.entries) { status ->
                FilterChip(
                    selected = state.status == status,
                    onClick = { model.status(status) },
                    enabled = !state.saving,
                    label = { Text(statusLabel(status)) }
                )
            }
        }
        LogField(
            value = state.comment,
            onValueChange = model::comment,
            label = stringResource(R.string.quicklog_comment),
            enabled = !state.saving
        )
        Button(
            onClick = model::save,
            enabled = !state.saving && state.callsign.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.quicklog_save))
        }
    }
}

@Composable
private fun SelectorButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    error: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType
        ),
        modifier = modifier
    )
}

@Composable
private fun statusLabel(status: QsoStatus): String = stringResource(
    when (status) {
        QsoStatus.DRAFT -> R.string.quicklog_status_draft
        QsoStatus.COMPLETE -> R.string.quicklog_status_complete
        QsoStatus.ABORTED -> R.string.quicklog_status_aborted
    }
)
