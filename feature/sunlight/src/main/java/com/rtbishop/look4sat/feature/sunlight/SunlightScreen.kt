package com.rtbishop.look4sat.feature.sunlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import com.rtbishop.look4sat.core.domain.sunlight.SunlightPrediction
import com.rtbishop.look4sat.core.domain.sunlight.SunlightProblem
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import java.text.NumberFormat
import java.util.Locale
import java.util.TimeZone

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
    val page = state.page
    fun openConditions() = onAction(SunlightAction.OpenConditions)
    fun openDetails(interval: IlluminationInterval) {
        onAction(SunlightAction.OpenDetails(interval.startMillis))
    }
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
    val zone = remember(state.displayUtc, localZoneId) {
        TimeZone.getTimeZone(if (state.displayUtc) "UTC" else localZoneId)
    }
    val detail = remember(state.prediction, page.detailStart) {
        page.detailStart?.let { start ->
            state.prediction?.intervals?.firstOrNull { it.startMillis == start }
        }
    }
    ScreenColumn(topBar = {
        TopBar {
            IconButton(onClick = navigateUp) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.sunlight_back))
            }
            Text(stringResource(R.string.sunlight_title), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding().testTag("sunlight_results"),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "conditions") {
                ConditionsSummary(state, locale, ::openConditions, onAction)
            }
            if (state.loadingSatellites) item(key = "loading") {
                Text(stringResource(R.string.sunlight_loading))
            } else if (state.satellites.isEmpty()) item(key = "no-data") {
                Column {
                    Text(stringResource(R.string.sunlight_no_satellites))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = navigateToSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.sunlight_update_data))
                        }
                        TextButton(onClick = { onAction(SunlightAction.ReloadSatellites) },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.sunlight_reload))
                        }
                    }
                }
            }
            if (state.cancelled) item(key = "cancelled") {
                Text(stringResource(R.string.sunlight_cancelled))
            }
            val result = state.prediction
            if (result != null) {
                item(key = "timezone") {
                    Text(stringResource(R.string.sunlight_timezone, zone.id),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (result.problem != null) item(key = "partial") {
                    Text(stringResource(R.string.sunlight_partial,
                        preciseTimeText(result.validUntilMillis, locale, zone)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                item(key = "longest") { LongestSunlight(result, locale, zone, ::openDetails) }
                item(key = "statistics") {
                    SunlightStatistics(result, locale, zone, page.statisticsExpanded) {
                        onAction(SunlightAction.ToggleStatistics)
                    }
                }
                item(key = "filters") {
                    IntervalListHeader(state) { onAction(SunlightAction.ChangePanel(SunlightPanelPage.FILTER)) }
                }
                if (state.visibleIntervals.isEmpty()) item(key = "empty") {
                    Text(stringResource(R.string.sunlight_no_intervals))
                }
                items(state.visibleIntervals, key = { "${it.startMillis}-${it.state}" }) { interval ->
                    IntervalRow(interval, locale, zone) { openDetails(interval) }
                }
            } else if (state.problem != null && state.satellites.isNotEmpty()) {
                item(key = "error") {
                    Column {
                        Text(stringResource(problemLabel(state.problem)), color = MaterialTheme.colorScheme.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { onAction(SunlightAction.Predict) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                enabled = state.catalogNumber != null && state.daysValid) {
                                Text(stringResource(R.string.sunlight_restart))
                            }
                            TextButton(onClick = navigateToSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.sunlight_update_data))
                            }
                        }
                    }
                }
            } else if (!state.calculating && !state.cancelled && state.satellites.isNotEmpty()) {
                item(key = "initial") {
                    Text(stringResource(R.string.sunlight_initial), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (page.panel != SunlightPanelPage.NONE) {
        key(state.panelStateKey) {
            SunlightPanel(state, detail, locale, zone, onAction)
        }
    }
}

@Composable
private fun ConditionsSummary(
    state: SunlightState, locale: Locale, edit: () -> Unit, onAction: (SunlightAction) -> Unit
) {
    val (startDate, endDate) = remember(state.startUtcMillis, state.days, locale) {
        conditionDates(state.startUtcMillis,
            state.startUtcMillis + (state.days.toIntOrNull() ?: 30) * 86_400_000L, locale)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = edit)
                .heightIn(min = 48.dp).testTag("sunlight_conditions")) {
                Text(state.selectedSatellite?.name ?: stringResource(R.string.sunlight_choose_satellite),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.sunlight_condition_summary, startDate, endDate),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = edit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sunlight_edit_conditions))
                }
                Button(
                    onClick = { onAction(if (state.calculating) SunlightAction.Cancel else SunlightAction.Predict) },
                    enabled = state.calculating ||
                        (state.catalogNumber != null && state.daysValid && !state.loadingSatellites),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("sunlight_predict")
                ) {
                    Text(stringResource(if (state.calculating) R.string.btn_cancel else R.string.sunlight_predict))
                }
            }
            if (state.calculating) {
                Text(stringResource(R.string.sunlight_progress, (state.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { state.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LongestSunlight(
    result: SunlightPrediction, locale: Locale, zone: TimeZone, details: (IlluminationInterval) -> Unit
) {
    val longest = remember(result) { result.longestSunlight }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Balance the heading against the footer button's minimum touch height.
            Box(Modifier.fillMaxWidth().heightIn(min = if (longest != null) 48.dp else 0.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(stringResource(R.string.sunlight_longest_title), style = MaterialTheme.typography.titleSmall)
            }
            if (longest == null) Text(stringResource(R.string.sunlight_no_sunlight)) else {
                Text(intervalDuration(longest, locale), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary)
                IntervalDates(longest, locale, zone)
                BoundaryMarkers(longest)
                TextButton(onClick = { details(longest) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("sunlight_longest_details")) {
                    Text(stringResource(R.string.sunlight_interval_details))
                }
            }
        }
    }
}

@Composable
private fun SunlightStatistics(
    result: SunlightPrediction, locale: Locale, zone: TimeZone, expanded: Boolean, toggle: () -> Unit
) {
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 } }
    val durations = remember(result) {
        result.intervals.groupingBy { it.state }.fold(0L) { total, interval -> total + interval.durationMillis }
    }
    val durationLabels = remember(durations, locale) {
        Illumination.entries.associateWith { durationText(durations[it] ?: 0L, locale, compact = false) }
    }
    fun share(phase: Illumination) = if (result.validDurationMillis > 0)
        (durations[phase] ?: 0L).toDouble() / result.validDurationMillis else 0.0
    val summaries = Illumination.entries.associateWith { phase ->
        stringResource(R.string.sunlight_state_summary, stringResource(illuminationLabel(phase)),
            durationLabels.getValue(phase), percent.format(share(phase)))
    }
    val barDescription = summaries.values.joinToString("\n")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sunlight_sunlight_share, percent.format(share(Illumination.SUNLIGHT))),
                style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = barDescription }) {
                Illumination.entries.forEach { phase ->
                    val fraction = share(phase)
                    if (fraction > 0) Box(Modifier.weight(fraction.toFloat()).height(10.dp)
                        .background(illuminationColor(phase)))
                }
            }
            TextButton(onClick = toggle,
                modifier = Modifier.heightIn(min = 48.dp).testTag("sunlight_statistics_toggle")) {
                Text(stringResource(if (expanded) R.string.sunlight_collapse_statistics else R.string.sunlight_expand_statistics))
            }
            if (expanded) {
                Illumination.entries.forEach { phase ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(illuminationColor(phase)))
                        Text(summaries.getValue(phase), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.sunlight_epoch, preciseTimeText(
                    ((result.elements.epochDaynum * 86_400_000) + 315_446_400_000L).toLong(), locale, zone)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun IntervalListHeader(state: SunlightState, filters: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if (state.filter == Illumination.SUNLIGHT) R.string.sunlight_sunlight_intervals
                else state.filter?.let(::illuminationLabel) ?: R.string.sunlight_all_states),
                style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.sunlight_intervals,
                state.visibleIntervals.size, state.visibleIntervals.size), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (state.sort == SunlightSort.DURATION)
                R.string.sunlight_sort_duration else R.string.sunlight_sort_time),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.minimumMinutes > 0) Text(
                stringResource(R.string.sunlight_at_least, minuteText(state.minimumMinutes, locale)),
                style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = filters, modifier = Modifier.size(48.dp).testTag("sunlight_filter")) {
            Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.sunlight_filter))
        }
    }
}

@Composable
private fun IntervalRow(interval: IlluminationInterval, locale: Locale, zone: TimeZone, details: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = details)
        .heightIn(min = 48.dp).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(intervalDuration(interval, locale), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(illuminationLabel(interval.state)), color = illuminationColor(interval.state),
            style = MaterialTheme.typography.labelMedium)
        IntervalDates(interval, locale, zone)
        BoundaryMarkers(interval)
    }
    HorizontalDivider()
}

@Composable
private fun intervalDuration(interval: IlluminationInterval, locale: Locale): String {
    val duration = remember(interval.durationMillis, locale) { durationText(interval.durationMillis, locale) }
    return if (interval.startClipped || interval.endClipped)
        stringResource(R.string.sunlight_at_least, duration) else duration
}

@Composable
internal fun IntervalDates(interval: IlluminationInterval, locale: Locale, zone: TimeZone, precise: Boolean = false) {
    val (start, end) = remember(interval.startMillis, interval.endMillis, locale, zone.id, precise) {
        if (precise) {
            preciseTimeText(interval.startMillis, locale, zone) to preciseTimeText(interval.endMillis, locale, zone)
        } else intervalTimes(interval, locale, zone)
    }
    Text(stringResource(R.string.sunlight_begin, start), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.sunlight_end, end), style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun BoundaryMarkers(interval: IlluminationInterval) {
    if (interval.startClipped) Text(stringResource(R.string.sunlight_started_before),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (interval.endClipped) Text(stringResource(R.string.sunlight_continues_after),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
