package com.rtbishop.look4sat.feature.radar.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    pass: OrbitalPass?,
    transponders: List<SatRadio>,
    selectedTransponderUuid: String?
) {
    val model: QuickLogViewModel = viewModel(factory = QuickLogViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(pass?.catNum, pass?.aosTime, transponders, selectedTransponderUuid) {
        model.setContext(pass, transponders, selectedTransponderUuid)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val notice = state.error?.let { error ->
                stringResource(
                    when (error) {
                        QuickLogError.CALLSIGN -> R.string.quicklog_invalid_callsign
                        QuickLogError.GRID -> R.string.quicklog_invalid_grid
                        QuickLogError.REPORT -> R.string.quicklog_invalid_report
                        QuickLogError.SATELLITE -> R.string.quicklog_no_satellite
                        QuickLogError.STORAGE -> R.string.quicklog_save_failed
                    }
                )
            } ?: state.savedCallsign.takeIf(String::isNotBlank)?.let {
                stringResource(R.string.quicklog_saved, it)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
            ) {
                if (notice != null) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.quicklog_mode),
                        style = MaterialTheme.typography.labelLarge
                    )
                    quickLogModes.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { model.mode(mode) },
                            enabled = !state.saving,
                            label = { Text(mode) },
                            modifier = Modifier.weight(1f)
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
            }

            Button(
                onClick = model::save,
                enabled = !state.saving && state.callsign.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = stringResource(R.string.quicklog_save),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
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
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error,
        keyboardOptions = KeyboardOptions(capitalization = capitalization),
        modifier = modifier
    )
}
