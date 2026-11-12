package com.rtbishop.look4sat.feature.sunlight

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import com.rtbishop.look4sat.core.domain.sunlight.SunlightPrediction
import com.rtbishop.look4sat.core.domain.sunlight.SunlightProblem
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.SheetDialogTitle
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.sheetDialogContainerColor
import com.rtbishop.look4sat.core.presentation.sheetDialogShape
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

@Composable
fun SunlightDestination(navigateUp: () -> Unit, navigateToSettings: () -> Unit) {
    val container = (LocalContext.current.applicationContext as IContainerProvider).getMainContainer()
    val model: SunlightViewModel = viewModel(factory = SunlightViewModel.factory(container))
    val state by model.uiState.collectAsStateWithLifecycle()
    SunlightScreen(state, model::onAction, navigateUp, navigateToSettings)
}

@Composable
internal fun SunlightScreen(
    state: SunlightState,
    onAction: (SunlightAction) -> Unit,
    navigateUp: () -> Unit,
    navigateToSettings: () -> Unit
) {
    var satellitesOpen by rememberSaveable { mutableStateOf(false) }
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var localZoneId by remember { mutableStateOf(TimeZone.getDefault().id) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                localZoneId = TimeZone.getDefault().id
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val locale = LocalConfiguration.current.locales[0]
    val timezone = TimeZone.getTimeZone(if (state.displayUtc) "UTC" else localZoneId)
    val formatter = remember(locale, timezone.id) {
        resultTimeFormatter(locale, timezone)
    }
    val dateFormatter = remember(locale) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    ScreenColumn(topBar = {
        TopBar {
            IconButton(onClick = navigateUp) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.sunlight_back))
            }
            Text(stringResource(R.string.sunlight_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().testTag("sunlight_results"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
        ) {
            item(key = "conditions") {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            onClick = { satellitesOpen = true },
                            modifier = Modifier.fillMaxWidth().testTag("sunlight_satellite"),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.sunlight_satellite),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        state.selectedSatellite?.name ?: stringResource(R.string.sunlight_choose_satellite),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                Icon(painterResource(R.drawable.ic_arrow), contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val dateText = dateFormatter.format(Date(state.startUtcMillis))
                            if (maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    SunlightDateField(dateText, { dateOpen = true }, Modifier.weight(3f))
                                    SunlightDaysField(state, onAction, Modifier.weight(2f))
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SunlightDateField(dateText, { dateOpen = true }, Modifier.fillMaxWidth())
                                    SunlightDaysField(state, onAction, Modifier.fillMaxWidth())
                                }
                            }
                        }
                        if (!state.daysValid) {
                            Text(stringResource(R.string.sunlight_days_error),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = { onAction(SunlightAction.Predict) },
                            enabled = state.catalogNumber != null && state.daysValid && !state.loadingSatellites,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sunlight_predict")
                        ) {
                            Text(stringResource(if (state.calculating) R.string.sunlight_restart else R.string.sunlight_predict))
                        }
                        if (state.calculating) {
                            Text(stringResource(R.string.sunlight_progress, (state.progress * 100).toInt()))
                            LinearProgressIndicator(progress = { state.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(
                                onClick = { onAction(SunlightAction.Cancel) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.btn_cancel)) }
                        }
                        if (state.loadingSatellites) Text(stringResource(R.string.sunlight_loading))
                        else if (state.satellites.isEmpty()) {
                            Text(stringResource(R.string.sunlight_no_satellites))
                            TextButton(onClick = navigateToSettings) {
                                Text(stringResource(R.string.sunlight_update_data))
                            }
                            TextButton(onClick = { onAction(SunlightAction.ReloadSatellites) }) {
                                Text(stringResource(R.string.sunlight_reload))
                            }
                        }
                        if (state.cancelled) Text(stringResource(R.string.sunlight_cancelled))
                        state.problem?.let {
                            Text(stringResource(problemLabel(it)), color = MaterialTheme.colorScheme.error)
                            if (state.satellites.isNotEmpty() && it != SunlightProblem.COMPUTATION_FAILED) {
                                TextButton(onClick = navigateToSettings) { Text(stringResource(R.string.sunlight_update_data)) }
                            }
                        }
                    }
                }
            }
            state.prediction?.let { result ->
                item(key = "summary") {
                    SunlightSummary(result, locale, formatter)
                }
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.sunlight_timezone, timezone.id),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { filtersOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_filter), contentDescription = null)
                            Text(stringResource(R.string.sunlight_filter), Modifier.padding(start = 8.dp))
                        }
                        Text(
                            pluralStringResource(R.plurals.sunlight_intervals, state.visibleIntervals.size, state.visibleIntervals.size),
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (state.visibleIntervals.isEmpty()) Text(stringResource(R.string.sunlight_no_intervals))
                    }
                }
                items(state.visibleIntervals, key = { "${it.startMillis}-${it.state}" }) { interval ->
                    IntervalRow(interval, locale, formatter)
                }
            } ?: if (!state.calculating && state.problem == null && !state.cancelled) {
                item(key = "initial") {
                    Text(stringResource(R.string.sunlight_initial), style = MaterialTheme.typography.bodyMedium)
                }
            } else Unit
        }
    }
    if (satellitesOpen) {
        SatelliteSheet(state, { satellitesOpen = false }) { id ->
            onAction(SunlightAction.SelectSatellite(id))
            satellitesOpen = false
        }
    }
    if (dateOpen) {
        StartDateSheet(state.startUtcMillis, { dateOpen = false }) {
            onAction(SunlightAction.SetStart(it))
            dateOpen = false
        }
    }
    if (filtersOpen) {
        FilterSheet(state, { filtersOpen = false }) {
            onAction(it)
            filtersOpen = false
        }
    }
}

@Composable
private fun SunlightDateField(date: String, onClick: () -> Unit, modifier: Modifier) {
    val description = stringResource(R.string.sunlight_start_date, date)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.sunlight_start_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().testTag("sunlight_start_date")
                .semantics { contentDescription = description },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(date, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null,
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SunlightDaysField(state: SunlightState, onAction: (SunlightAction) -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.sunlight_days)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.days,
            onValueChange = { onAction(SunlightAction.SetDays(it)) },
            modifier = Modifier.fillMaxWidth().testTag("sunlight_days").semantics { contentDescription = label },
            shape = MaterialTheme.shapes.medium,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !state.daysValid
        )
    }
}

@Composable
private fun SunlightSummary(result: SunlightPrediction, locale: Locale, formatter: DateFormat) {
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 } }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.elements.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sunlight_epoch, formatter.format(Date(
                ((result.elements.epochDaynum * 86_400_000) + 315_446_400_000L).toLong()
            ))), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().height(12.dp)) {
                if (result.validDurationMillis > 0) {
                    Illumination.entries.forEach { state ->
                        val duration = result.durationMillis(state)
                        if (duration > 0) {
                            Box(Modifier.weight((duration.toDouble() / result.validDurationMillis).toFloat())
                                .height(12.dp).background(illuminationColor(state)))
                        }
                    }
                }
            }
            Illumination.entries.forEach { state ->
                val fraction = if (result.validDurationMillis > 0)
                    result.durationMillis(state).toDouble() / result.validDurationMillis else 0.0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(illuminationColor(state)))
                    Text(stringResource(R.string.sunlight_state_summary,
                        stringResource(illuminationLabel(state)), durationText(result.durationMillis(state), locale),
                        percent.format(fraction)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            Text(pluralStringResource(R.plurals.sunlight_continuous_count, result.sunlightCount, result.sunlightCount))
            Text(stringResource(R.string.sunlight_longest,
                durationText(result.longestSunlight?.durationMillis ?: 0L, locale)))
            result.longestSunlight?.let {
                Text(stringResource(R.string.sunlight_range,
                    formatter.format(Date(it.startMillis)), formatter.format(Date(it.endMillis))),
                    style = MaterialTheme.typography.bodySmall)
                if (it.startClipped || it.endClipped) Text(stringResource(R.string.sunlight_longest_clipped),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (result.problem != null) {
                Text(stringResource(R.string.sunlight_partial, formatter.format(Date(result.validUntilMillis))),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun IntervalRow(interval: IlluminationInterval, locale: Locale, formatter: DateFormat) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(illuminationLabel(interval.state)), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 600.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(formatter.format(Date(interval.startMillis)), Modifier.weight(1f))
                        Text(formatter.format(Date(interval.endMillis)), Modifier.weight(1f))
                        Text(durationText(interval.durationMillis, locale), Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.sunlight_begin, formatter.format(Date(interval.startMillis))))
                        Text(stringResource(R.string.sunlight_end, formatter.format(Date(interval.endMillis))))
                        Text(durationText(interval.durationMillis, locale), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            if (interval.startClipped) Text(stringResource(R.string.sunlight_started_before), style = MaterialTheme.typography.bodySmall)
            if (interval.endClipped) Text(stringResource(R.string.sunlight_continues_after), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteSheet(state: SunlightState, dismiss: () -> Unit, select: (Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(state.satellites, query) {
        state.satellites.filter { it.name.contains(query, ignoreCase = true) || it.catnum.toString().contains(query) }
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        shape = sheetDialogShape(), containerColor = sheetDialogContainerColor()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetDialogTitle(stringResource(R.string.sunlight_satellite))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.sat_search_hint)) })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = sheetContentHeight())) {
                items(matches, key = { it.catnum }) { satellite ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = satellite.catnum == state.catalogNumber,
                                role = Role.RadioButton) { select(satellite.catnum) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = satellite.catnum == state.catalogNumber, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(satellite.name)
                            Text(satellite.catnum.toString(),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (satellite.isSelected) Text(stringResource(R.string.sat_group_selected),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (matches.isEmpty()) item { Text(stringResource(R.string.empty_list_message), Modifier.padding(16.dp)) }
            }
            TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateSheet(start: Long, dismiss: () -> Unit, accept: (Long) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = start)
    SharedDialog(
        title = stringResource(R.string.sunlight_start_label),
        onCancel = dismiss,
        onAccept = { picker.selectedDateMillis?.let(accept) },
        acceptEnabled = picker.selectedDateMillis != null
    ) {
        DatePicker(picker, modifier = Modifier.heightIn(max = sheetContentHeight())
            .verticalScroll(rememberScrollState()), showModeToggle = true)
    }
}

@Composable
private fun FilterSheet(state: SunlightState, dismiss: () -> Unit, accept: (SunlightAction.Filter) -> Unit) {
    var filterName by rememberSaveable { mutableStateOf(state.filter?.name) }
    var minimum by rememberSaveable { mutableStateOf(state.minimumMinutes.toString()) }
    var sortName by rememberSaveable { mutableStateOf(state.sort.name) }
    val validMinimum = minimum.toLongOrNull()?.let { it >= 0 } == true
    SharedDialog(
        title = stringResource(R.string.sunlight_filter),
        onCancel = dismiss,
        onAccept = {
            if (validMinimum) accept(SunlightAction.Filter(
                Illumination.entries.firstOrNull { it.name == filterName }, minimum.toLong(),
                SunlightSort.valueOf(sortName)
            ))
        },
        acceptEnabled = validMinimum
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = sheetContentHeight())
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filterName == null, onClick = { filterName = null },
                    label = { Text(stringResource(R.string.sunlight_all_states)) })
                Illumination.entries.forEach { phase ->
                    FilterChip(selected = filterName == phase.name, onClick = { filterName = phase.name },
                        label = { Text(stringResource(illuminationLabel(phase))) })
                }
            }
            OutlinedTextField(
                minimum, { minimum = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sunlight_minimum)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !validMinimum,
                supportingText = if (!validMinimum) { { Text(stringResource(R.string.sunlight_minimum_error)) } } else null
            )
            SunlightSort.entries.forEach { sort ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = sortName == sort.name,
                            role = Role.RadioButton) { sortName = sort.name },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = sort.name == sortName, onClick = null)
                    Text(stringResource(if (sort == SunlightSort.START) R.string.sunlight_sort_time
                        else R.string.sunlight_sort_duration), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

internal fun illuminationLabel(state: Illumination): Int = when (state) {
    Illumination.SUNLIGHT -> R.string.sunlight_full
    Illumination.PENUMBRA -> R.string.sunlight_penumbra
    Illumination.UMBRA -> R.string.sunlight_umbra
}

@Composable
private fun illuminationColor(state: Illumination): Color = when (state) {
    Illumination.SUNLIGHT -> MaterialTheme.colorScheme.primary
    Illumination.PENUMBRA -> MaterialTheme.colorScheme.tertiary
    Illumination.UMBRA -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun problemLabel(problem: SunlightProblem): Int = when (problem) {
    SunlightProblem.ELEMENTS_UNAVAILABLE -> R.string.sunlight_error_elements
    SunlightProblem.INVALID_ORBIT -> R.string.sunlight_error_orbit
    SunlightProblem.COMPUTATION_FAILED -> R.string.sunlight_error_computation
}

private fun durationText(millis: Long, locale: Locale): String {
    val seconds = max(0, millis / 1000)
    val measures = buildList {
        if (seconds >= 86_400) add(Measure(seconds / 86_400, MeasureUnit.DAY))
        if (seconds / 3600 % 24 > 0) add(Measure(seconds / 3600 % 24, MeasureUnit.HOUR))
        if (seconds / 60 % 60 > 0) add(Measure(seconds / 60 % 60, MeasureUnit.MINUTE))
        if (seconds % 60 > 0 || isEmpty()) add(Measure(seconds % 60, MeasureUnit.SECOND))
    }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(*measures.toTypedArray())
}

internal fun resultTimeFormatter(locale: Locale, zone: TimeZone): DateFormat =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG, locale).apply { timeZone = zone }

@Composable
private fun sheetContentHeight(): Dp {
    val density = LocalDensity.current
    val screen = LocalConfiguration.current.screenHeightDp.dp
    val bottom = with(density) {
        maxOf(WindowInsets.ime.getBottom(this), WindowInsets.navigationBars.getBottom(this)).toDp()
    }
    // DialogShell measures non-weighted children; explicitly bound each scroll viewport.
    return minOf(400.dp, screen * 0.6f, screen - bottom - 144.dp).coerceAtLeast(72.dp)
}
