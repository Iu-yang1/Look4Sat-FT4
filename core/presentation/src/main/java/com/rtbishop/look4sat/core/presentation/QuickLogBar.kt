package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.logbook.QuickLogError
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer

@Composable
fun QuickLogBar(container: IMainContainer, onLogbook: () -> Unit, pass: OrbitalPass? = null) {
    val model: QuickLogViewModel = viewModel(factory = QuickLogViewModel.factory(container))
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(pass?.catNum, pass?.aosTime) { model.setContext(pass) }
    var satelliteMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                Box(Modifier.weight(1f)) {
                    TextButton(enabled = !state.saving, onClick = { satelliteMenu = true }) {
                        Text(
                            state.pass?.name ?: stringResource(R.string.quicklog_select_satellite),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    DropdownMenu(expanded = satelliteMenu, onDismissRequest = { satelliteMenu = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                        state.candidates.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = { model.selectPass(item); satelliteMenu = false }
                            )
                        }
                        if (state.candidates.isEmpty()) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.quicklog_no_passes)) }, onClick = {}, enabled = false)
                        }
                    }
                }
                Box {
                    TextButton(enabled = !state.saving, onClick = { modeMenu = true }) { Text("${state.mode} ▾") }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        listOf("CW", "SSB", "FM").forEach { mode ->
                            DropdownMenuItem(text = { Text(mode) }, onClick = { model.mode(mode); modeMenu = false })
                        }
                    }
                }
                TextButton(onClick = onLogbook) { Text(stringResource(R.string.nav_logbook)) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = state.callsign, onValueChange = model::callsign,
                    placeholder = { Text(stringResource(R.string.quicklog_callsign)) },
                    singleLine = true, enabled = !state.saving,
                    isError = state.error == QuickLogError.CALLSIGN,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next)
                )
                QuickReportField(state.sent, model::sent, R.string.quicklog_sent, !state.saving, state.error == QuickLogError.REPORT)
                QuickReportField(state.received, model::received, R.string.quicklog_received, !state.saving, state.error == QuickLogError.REPORT, model::save)
                TextButton(enabled = !state.saving && state.callsign.isNotBlank(), onClick = model::save) {
                    Text(stringResource(R.string.quicklog_save))
                }
            }
            val notice = state.error?.let { error ->
                stringResource(when (error) {
                    QuickLogError.CALLSIGN -> R.string.quicklog_invalid_callsign
                    QuickLogError.REPORT -> R.string.quicklog_invalid_report
                    QuickLogError.SATELLITE -> R.string.quicklog_select_satellite
                    QuickLogError.STORAGE -> R.string.quicklog_save_failed
                })
            } ?: state.savedCallsign.takeIf(String::isNotBlank)?.let { stringResource(R.string.quicklog_saved, it) }
            if (notice != null) Text(
                notice, style = MaterialTheme.typography.labelSmall,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun QuickReportField(
    value: String, onValue: (String) -> Unit, label: Int, enabled: Boolean, error: Boolean, onSave: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value, onValueChange = onValue, singleLine = true, enabled = enabled, isError = error,
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.width(62.dp), textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (onSave == null) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave?.invoke() })
    )
}
