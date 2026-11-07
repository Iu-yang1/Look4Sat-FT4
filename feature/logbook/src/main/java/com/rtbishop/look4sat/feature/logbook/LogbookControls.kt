package com.rtbishop.look4sat.feature.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.rtbishop.look4sat.core.domain.logbook.OwnStationFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.presentation.SharedDialog

@Composable
internal fun LogbookConfirmationSheet(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    busy: Boolean = false,
    error: LogbookError? = null
) {
    SharedDialog(
        title = title,
        onDismissRequest = { if (!busy) onDismiss() },
        onCancel = { if (!busy) onDismiss() },
        onAccept = onConfirm,
        acceptText = confirmText,
        acceptEnabled = !busy,
        dismissOnClickOutside = !busy,
        dismissOnBackPress = !busy,
        dismissOnSwipe = !busy
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.5f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = padding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(message)
            error?.let { Text(logbookErrorText(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun LogbookFilters(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    val stations = remember(state.records) {
        state.records.map {
            OwnStationFilter(
                it.myCallsign.trim().uppercase(java.util.Locale.ROOT),
                it.myGrid.trim().uppercase(java.util.Locale.ROOT)
            )
        }.filter { it.callsign.isNotBlank() || it.grid.isNotBlank() }
            .distinct().sortedWith(compareBy({ it.callsign }, { it.grid }))
    }
    var expanded by remember { mutableStateOf(false) }
    if (expanded) {
        SharedDialog(
            title = stringResource(R.string.logbook_station),
            onDismissRequest = { expanded = false },
            onCancel = { expanded = false }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.5f)
                    .padding(horizontal = padding)
            ) {
                item {
                    TextButton(onClick = {
                        expanded = false
                        onAction(LogbookAction.OwnStation(null))
                    }) { Text(stringResource(R.string.logbook_all)) }
                }
                items(stations) { station ->
                    TextButton(onClick = {
                        expanded = false
                        onAction(LogbookAction.OwnStation(station))
                    }) {
                        Text(listOf(station.callsign, station.grid).filter(String::isNotBlank).joinToString(" · "))
                    }
                }
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column {
            TextField(
                value = state.query,
                onValueChange = { onAction(LogbookAction.Search(it)) },
                placeholder = { Text(stringResource(R.string.logbook_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.weight(1f)) {
                    Text(
                        state.ownStationFilter?.let {
                            listOf(it.callsign, it.grid).filter(String::isNotBlank).joinToString(" · ")
                        } ?: stringResource(R.string.logbook_all),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { onAction(LogbookAction.ToggleGrouping) }) {
                    Text(stringResource(if (state.groupByCallsign) R.string.logbook_group_calls else R.string.logbook_group_time))
                }
            }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items(LogbookFilter.entries.filter { it != LogbookFilter.DRAFTS }) { filter ->
            itemFilter(state.confirmationFilter == filter, filterLabel(filter)) {
                onAction(LogbookAction.ConfirmationFilter(filter))
            }
        }
        item {
            itemFilter(
                state.modeFilter == SATELLITE_MODE_FILTER,
                stringResource(R.string.logbook_satellite_mode)
            ) {
                onAction(
                    LogbookAction.ModeFilter(
                        if (state.modeFilter == SATELLITE_MODE_FILTER) "" else SATELLITE_MODE_FILTER
                    )
                )
            }
        }
        item {
            itemFilter(state.modeFilter.isBlank(), stringResource(R.string.logbook_all_modes)) {
                onAction(LogbookAction.ModeFilter(""))
            }
        }
        items(state.records.map { it.displayMode }.filter(String::isNotBlank).distinct().sorted()) { mode ->
            itemFilter(state.modeFilter == mode, mode) {
                onAction(LogbookAction.ModeFilter(if (state.modeFilter == mode) "" else mode))
            }
        }
    }
}

@Composable
private fun itemFilter(selected: Boolean, text: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

@Composable
private fun filterLabel(filter: LogbookFilter): String = stringResource(
    when (filter) {
        LogbookFilter.ALL -> R.string.logbook_all
        LogbookFilter.CONFIRMED -> R.string.logbook_confirmed
        LogbookFilter.UNCONFIRMED -> R.string.logbook_unconfirmed
        LogbookFilter.DRAFTS -> R.string.logbook_incomplete
    }
)

@Composable
internal fun LoTWDialog(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    LoTWUploadDialog(state, onAction)
}

@Composable
internal fun LogbookStationDialog(state: LogbookState, onAction: (LogbookAction) -> Unit) {
    SharedDialog(
        onDismissRequest = { onAction(LogbookAction.DismissStation) },
        title = stringResource(R.string.logbook_station),
        onCancel = { onAction(LogbookAction.DismissStation) },
        onAccept = { onAction(LogbookAction.SaveStation) },
        acceptText = stringResource(R.string.logbook_save),
        acceptEnabled = !state.isBusy
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.55f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = padding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.logbook_station_hint))
            OutlinedTextField(
                value = state.stationCallsign,
                onValueChange = { onAction(LogbookAction.StationCallsign(it)) },
                label = { Text(stringResource(R.string.logbook_my_callsign)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            if (state.records.isNotEmpty()) {
                TextButton(
                    onClick = {
                        onAction(LogbookAction.DismissStation)
                        onAction(LogbookAction.RequestClearAll)
                    },
                    enabled = !state.isBusy
                ) {
                    Text(
                        stringResource(R.string.logbook_clear_all_title),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            state.error?.let { error ->
                Text(logbookErrorText(error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
