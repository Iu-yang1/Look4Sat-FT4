package com.rtbishop.look4sat.feature.sunlight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.SharedDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SunlightPanel(
    state: SunlightState,
    detail: IlluminationInterval?,
    locale: Locale,
    zone: TimeZone,
    onAction: (SunlightAction) -> Unit
) {
    val page = state.page
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(state.filter?.name) }
    var minimum by rememberSaveable { mutableStateOf(state.minimumMinutes.toString()) }
    var sort by rememberSaveable { mutableStateOf(state.sort) }
    val datePicker = if (page.panel == SunlightPanelPage.DATE) {
        rememberDatePickerState(initialSelectedDateMillis = page.draftStart)
    } else null
    LaunchedEffect(datePicker) {
        if (datePicker != null) snapshotFlow { datePicker.selectedDateMillis }
            .filterNotNull().distinctUntilChanged().collect {
                onAction(SunlightAction.DraftStart(it))
            }
    }
    val validDays = page.draftDays.toIntOrNull()?.let { it in 1..365 } == true
    val validMinimum = minimum.toLongOrNull()?.let { it >= 0 } == true
    val keyboard = LocalSoftwareKeyboardController.current
    val subpage = page.panel == SunlightPanelPage.SATELLITES || page.panel == SunlightPanelPage.DATE
    fun changePanel(target: SunlightPanelPage) = onAction(SunlightAction.ChangePanel(target))
    fun back() = changePanel(if (subpage) SunlightPanelPage.CONDITIONS else SunlightPanelPage.NONE)
    val title = when (page.panel) {
        SunlightPanelPage.SATELLITES -> R.string.sunlight_satellite
        SunlightPanelPage.DATE -> R.string.sunlight_start_label
        SunlightPanelPage.FILTER -> R.string.sunlight_filter
        SunlightPanelPage.DETAIL -> R.string.sunlight_interval_details
        else -> R.string.sunlight_conditions
    }
    SharedDialog(
        title = stringResource(title),
        onDismissRequest = { changePanel(SunlightPanelPage.NONE) },
        dismissOnBackPress = false
    ) { padding ->
        BackHandler(onBack = ::back)
        if (page.panel == SunlightPanelPage.SATELLITES) {
            SatelliteChoices(state, query, { query = it }) {
                onAction(SunlightAction.DraftCatalog(it))
                changePanel(SunlightPanelPage.CONDITIONS)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = sheetContentHeight())
                    .verticalScroll(rememberScrollState()).padding(horizontal = padding)
                    .testTag("sunlight_panel_body"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (page.panel) {
                    SunlightPanelPage.CONDITIONS -> {
                        Text(stringResource(R.string.sunlight_satellite), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { changePanel(SunlightPanelPage.SATELLITES) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sunlight_satellite"),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(state.satellites.firstOrNull { it.catnum == page.draftCatalog }?.name
                                ?: stringResource(R.string.sunlight_choose_satellite), Modifier.weight(1f))
                            Icon(painterResource(R.drawable.ic_arrow), contentDescription = null)
                        }
                        Text(stringResource(R.string.sunlight_start_label), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { changePanel(SunlightPanelPage.DATE) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sunlight_start_date"),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(utcDateText(page.draftStart, locale), Modifier.weight(1f))
                            Icon(painterResource(R.drawable.ic_calendar), contentDescription = null,
                                modifier = Modifier.size(20.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(7, 30, 90, 365).forEach { days ->
                                FilterChip(
                                    selected = page.draftDays.toIntOrNull() == days,
                                    onClick = { onAction(SunlightAction.DraftDays(days.toString())) },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("sunlight_preset_$days"),
                                    label = { Text(durationText(days * 86_400_000L, locale)) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = page.draftDays,
                            onValueChange = { onAction(SunlightAction.DraftDays(it)) },
                            modifier = Modifier.fillMaxWidth().testTag("sunlight_days"),
                            label = { Text(stringResource(R.string.sunlight_days)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = !validDays,
                            supportingText = if (!validDays) {
                                { Text(stringResource(R.string.sunlight_days_error)) }
                            } else null
                        )
                    }
                    SunlightPanelPage.DATE -> {
                        if (datePicker != null) DatePicker(datePicker, showModeToggle = true)
                    }
                    SunlightPanelPage.FILTER -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = filterName == null, onClick = { filterName = null },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(stringResource(R.string.sunlight_all_states)) })
                            Illumination.entries.forEach { phase ->
                                FilterChip(selected = filterName == phase.name, onClick = { filterName = phase.name },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text(stringResource(illuminationLabel(phase))) })
                            }
                        }
                        OutlinedTextField(
                            minimum, { minimum = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.sunlight_minimum)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = !validMinimum,
                            supportingText = if (!validMinimum) {
                                { Text(stringResource(R.string.sunlight_minimum_error)) }
                            } else null
                        )
                        SunlightSort.entries.forEach { choice ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = choice == sort, role = Role.RadioButton) { sort = choice },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = choice == sort, onClick = null)
                                Text(stringResource(if (choice == SunlightSort.DURATION)
                                    R.string.sunlight_sort_duration else R.string.sunlight_sort_time))
                            }
                        }
                    }
                    SunlightPanelPage.DETAIL -> if (detail != null) {
                        Text(stringResource(illuminationLabel(detail.state)), style = MaterialTheme.typography.titleSmall)
                        val duration = durationText(detail.durationMillis, locale, compact = false)
                        Text(stringResource(R.string.sunlight_duration,
                            if (detail.startClipped || detail.endClipped)
                                stringResource(R.string.sunlight_at_least, duration) else duration))
                        Text(stringResource(R.string.sunlight_timezone, zone.id), style = MaterialTheme.typography.bodySmall)
                        IntervalDates(detail, locale, zone, precise = true)
                        BoundaryMarkers(detail)
                    }
                    else -> Unit
                }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = padding).padding(bottom = padding),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = ::back, modifier = Modifier.heightIn(min = 48.dp).testTag("sunlight_panel_back")) {
                Text(stringResource(if (subpage) R.string.sunlight_back
                    else if (page.panel == SunlightPanelPage.DETAIL) R.string.sunlight_close else R.string.btn_cancel))
            }
            if (page.panel in listOf(SunlightPanelPage.CONDITIONS, SunlightPanelPage.DATE, SunlightPanelPage.FILTER)) {
                Button(
                    onClick = {
                        keyboard?.hide()
                        when (page.panel) {
                            SunlightPanelPage.CONDITIONS -> {
                                val id = page.draftCatalog
                                val days = page.draftDays.toIntOrNull()
                                if (id != null && days != null && days in 1..365) {
                                    onAction(SunlightAction.SubmitConditions(id, page.draftStart, days))
                                }
                            }
                            SunlightPanelPage.DATE -> datePicker?.selectedDateMillis?.let {
                                onAction(SunlightAction.DraftStart(it))
                                changePanel(SunlightPanelPage.CONDITIONS)
                            }
                            SunlightPanelPage.FILTER -> if (validMinimum) {
                                onAction(SunlightAction.Filter(Illumination.entries.firstOrNull { it.name == filterName },
                                    minimum.toLong(), sort))
                                changePanel(SunlightPanelPage.NONE)
                            }
                            else -> Unit
                        }
                    },
                    enabled = when (page.panel) {
                        SunlightPanelPage.CONDITIONS -> validDays && !state.loadingSatellites &&
                            state.satellites.any { it.catnum == page.draftCatalog }
                        SunlightPanelPage.DATE -> datePicker?.selectedDateMillis != null
                        else -> validMinimum
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("sunlight_panel_accept")
                ) {
                    Text(stringResource(if (page.panel == SunlightPanelPage.CONDITIONS)
                        R.string.sunlight_start_prediction else R.string.btn_accept))
                }
            }
        }
    }
}

@Composable
private fun SatelliteChoices(state: SunlightState, query: String, onQuery: (String) -> Unit, select: (Int) -> Unit) {
    val matches = remember(state.satellites, query) {
        state.satellites.filter { it.name.contains(query, ignoreCase = true) || it.catnum.toString().contains(query) }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = sheetContentHeight()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().testTag("sunlight_satellite_search"),
            singleLine = true, label = { Text(stringResource(R.string.sat_search_hint)) })
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(matches, key = { it.catnum }) { satellite ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = satellite.catnum == state.page.draftCatalog,
                            role = Role.RadioButton) { select(satellite.catnum) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = satellite.catnum == state.page.draftCatalog, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(satellite.name)
                        Text(satellite.catnum.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (matches.isEmpty()) item {
                Text(stringResource(R.string.empty_list_message), Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun sheetContentHeight(): Dp {
    val density = LocalDensity.current
    val screen = LocalConfiguration.current.screenHeightDp.dp
    val bottom = with(density) {
        maxOf(WindowInsets.ime.getBottom(this), WindowInsets.navigationBars.getBottom(this)).toDp()
    }
    val controls = 160.dp * density.fontScale.coerceAtLeast(1f)
    return minOf(400.dp, screen * 0.6f, screen - bottom - controls).coerceAtLeast(72.dp)
}
