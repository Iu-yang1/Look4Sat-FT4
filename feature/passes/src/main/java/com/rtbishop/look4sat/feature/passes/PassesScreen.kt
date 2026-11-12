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
package com.rtbishop.look4sat.feature.passes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.EmptyListCard
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.NextPassRow
import com.rtbishop.look4sat.core.presentation.ProjectWikiBody
import com.rtbishop.look4sat.core.presentation.PassRadarPreview
import com.rtbishop.look4sat.core.presentation.PassDateDivider
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.LocalSpacing
import com.rtbishop.look4sat.core.presentation.layoutPadding
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.SwipeableItem
import com.rtbishop.look4sat.core.presentation.TimerRow
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.elevationColor
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun PassesDestination(
    navigateToRadar: (Int, Long) -> Unit,
    navigateToMap: () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: PassesViewModel = viewModel(factory = PassesViewModel.factory(container))
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val stationPosition = container.settingsRepo.stationPosition.collectAsStateWithLifecycle().value
    val passListFontScale = container.settingsRepo.otherSettings.collectAsStateWithLifecycle().value.passListFontScale
    PassesScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        navigateToRadar = navigateToRadar,
        navigateToMap = navigateToMap,
        satelliteRepo = container.satelliteRepo,
        stationPosition = stationPosition,
        passListFontScale = passListFontScale
    )
}

@Composable
private fun PassesScreen(
    uiState: PassesState,
    onAction: (PassesAction) -> Unit,
    navigateToRadar: (Int, Long) -> Unit,
    navigateToMap: () -> Unit,
    satelliteRepo: ISatelliteRepo,
    stationPosition: GeoPos,
    passListFontScale: Float
) {
    if (uiState.isPassesDialogShown) {
        PassesFilterDialog(
            hours = uiState.hours,
            hoursBefore = uiState.hoursBefore,
            elevation = uiState.elevation,
            lowElevation = uiState.lowElevation,
            highElevation = uiState.highElevation,
            aosStartMinute = uiState.aosStartMinute,
            aosEndMinute = uiState.aosEndMinute,
            invertAosTimeWindow = uiState.invertAosTimeWindow,
            showDeepSpace = uiState.showDeepSpace,
            cancel = { onAction(PassesAction.TogglePassesDialog) },
            accept = { params ->
                onAction(
                    PassesAction.FilterPasses(
                        hoursAhead = params.hours,
                        hoursBefore = params.hoursBefore,
                        minElevation = params.elevation,
                        lowElevation = params.lowElevation,
                        highElevation = params.highElevation,
                        aosStartMinute = params.aosStartMinute,
                        aosEndMinute = params.aosEndMinute,
                        invertAosTimeWindow = params.invertAosTimeWindow,
                        showDeepSpace = params.showDeepSpace
                    )
                )
            }
        )
    }
    if (uiState.isRadiosDialogShown) {
        RadiosDialog(
            modes = uiState.modes,
            cancel = { onAction(PassesAction.ToggleRadiosDialog) }
        ) { modes ->
            onAction(PassesAction.FilterRadios(modes))
        }
    }
    if (uiState.shouldSeeWhatsNew) {
        val dismiss = { onAction(PassesAction.DismissWhatsNew) }
        SharedDialog(
            title = stringResource(R.string.prefs_project_wiki),
            onDismissRequest = dismiss,
            onAccept = dismiss,
            titleFontSize = 18
        ) { padding ->
            // The wiki is long; keep the dialog frame fixed and let the
            // text scroll inside it (matches the update-checker page pattern).
            ProjectWikiBody(
                modifier = Modifier
                    .padding(horizontal = padding)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
    // Keep the home screen layout self-contained. This prevents shared ScreenColumn
    // background/layout tweaks from measuring the header and pass list incorrectly.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().layoutPadding(),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.extraSmall)
        ) {
            TopBar(
                isVerticalLayout = isVerticalLayout(),
                startAction = {
                    IconCard(action = { onAction(PassesAction.TogglePassesDialog) }, resId = R.drawable.ic_filter)
                },
                topInfo = {
                    TimerRow(timeString = uiState.nextTime, isTimeAos = uiState.isNextTimeAos)
                },
                bottomInfo = {
                    NextPassRow(pass = uiState.nextPass, isUtc = uiState.isUtc)
                },
                endAction = {
                    IconCard(action = navigateToMap, resId = R.drawable.ic_map)
                }
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density,
                        fontScale = baseDensity.fontScale * passListFontScale
                    )
                ) {
                    PassesList(
                        isRefreshing = uiState.isRefreshing,
                        isUtc = uiState.isUtc,
                        passes = uiState.itemsList,
                        groupedPasses = uiState.groupedPasses,
                        sunTimes = uiState.sunTimes,
                        focusedCatNum = uiState.focusedCatNum,
                        navigateToRadar = navigateToRadar,
                        onAction = onAction,
                        satelliteRepo = satelliteRepo,
                        stationPosition = stationPosition
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassesList(
    isRefreshing: Boolean,
    isUtc: Boolean,
    passes: List<OrbitalPass>,
    groupedPasses: Map<String, List<OrbitalPass>>,
    sunTimes: Map<String, Pair<String, String>>,
    focusedCatNum: Int?,
    navigateToRadar: (Int, Long) -> Unit,
    onAction: (PassesAction) -> Unit,
    satelliteRepo: ISatelliteRepo,
    stationPosition: GeoPos
) {
    val isVerticalLayout = isVerticalLayout()
    val refreshState = rememberPullToRefreshState()
    ElevatedCard(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = refreshState,
            onRefresh = { onAction(PassesAction.RefreshPasses) },
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = refreshState,
                    isRefreshing = isRefreshing,
                    color = MaterialTheme.colorScheme.background,
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        ) {
            if (passes.isEmpty()) {
                EmptyListCard(message = stringResource(R.string.pass_empty_list_message))
            } else {
                AnimatedContent(
                    targetState = focusedCatNum,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "PassesFocusTransition"
                ) { targetFocus ->
                    LazyVerticalGrid(columns = GridCells.Adaptive(320.dp), modifier = Modifier.fillMaxSize()) {
                        for ((dateLabel, dayPasses) in groupedPasses) {
                            val headerVisible = targetFocus == null || dayPasses.any { it.catNum == targetFocus }
                            if (headerVisible) {
                                stickyHeader(key = "header_$dateLabel") {
                                    val (rise, set) = sunTimes[dateLabel] ?: ("--:--" to "--:--")
                                    PassDateDivider(label = dateLabel, sunriseTime = rise, sunsetTime = set)
                                }
                            }
                            val visiblePasses = if (targetFocus == null) dayPasses
                            else dayPasses.filter { it.catNum == targetFocus }
                            items(items = visiblePasses, key = { item -> item.catNum + item.aosTime }) { pass ->
                                SwipeableItem(
                                    onSwipeRight = { onAction(PassesAction.FocusCatNum(pass.catNum)) },
                                    onSwipeLeft = { onAction(PassesAction.ClearFocus) }
                                ) {
                                    PassItem(
                                        pass = pass,
                                        navigateToRadar = navigateToRadar,
                                        modifier = Modifier.animateItem(),
                                        isVerticalLayout = isVerticalLayout,
                                        isUtc = isUtc,
                                        satelliteRepo = satelliteRepo,
                                        stationPosition = stationPosition
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeepSpacePassPreview() {
    val data = OrbitalData("Satellite", 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 45000, 0.0)
    val satellite = OrbitalObject(data)
    val pass = OrbitalPass(1L, 180.0, 10L, 360.0, 36650, 45.0, satellite, 0.5f)
    MainTheme { PassItem(pass = pass, { _, _ -> }) }
}

@Preview(showBackground = true)
@Composable
private fun NearEarthPassPreview() {
    val data = OrbitalData("Satellite", 0.0, 15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 45000, 0.0)
    val satellite = OrbitalObject(data)
    val pass = OrbitalPass(1L, 180.0, 10L, 360.0, 36650, 45.0, satellite, 0.5f)
    MainTheme { PassItem(pass = pass, { _, _ -> }) }
}

@Composable
private fun PassItem(
    pass: OrbitalPass,
    navigateToRadar: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
    isVerticalLayout: Boolean = true,
    isUtc: Boolean = false,
    satelliteRepo: ISatelliteRepo? = null,
    stationPosition: GeoPos = GeoPos(0.0, 0.0)
) {
    val horizontalPadding = if (isVerticalLayout) 6.dp else 10.dp
    val timeZone = remember(isUtc) {
        if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }
    val sdfTime = remember(isUtc) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).also { it.timeZone = timeZone }
    }
    val aosTimeStr = remember(pass.aosTime, isUtc) { sdfTime.format(Date(pass.aosTime)) }
    val losTimeStr = remember(pass.losTime, isUtc) { sdfTime.format(Date(pass.losTime)) }
    val durationStr = remember(pass.aosTime, pass.losTime) {
        val seconds = (pass.losTime - pass.aosTime) / 1000
        "${seconds / 60}m ${seconds % 60}s"
    }

    Column(modifier = modifier.clickable { navigateToRadar(pass.catNum, pass.aosTime) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = MaterialTheme.colorScheme.surface)
                .padding(horizontal = horizontalPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pass.name,
                        modifier = Modifier.weight(1f).padding(end = 5.dp).infiniteMarquee(),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        lineHeight = 19.sp
                    )
                    val elevColor = elevationColor(pass.maxElevation)
                    Icon(
                        painter = painterResource(id = R.drawable.ic_elevation),
                        contentDescription = null,
                        tint = elevColor,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = "${pass.maxElevation}°", color = elevColor, fontSize = 17.sp, lineHeight = 18.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (pass.continuousVisibility) stringResource(R.string.astro_continuous) else durationStr,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pass.altitude} km",
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Text(
                        text = stringResource(
                            id = R.string.pass_aosLos,
                            pass.aosAzimuth.toInt(),
                            pass.losAzimuth.toInt()
                        ),
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val defaultTime = "--:--:--"
                    Text(
                        text = if (pass.continuousVisibility) defaultTime else aosTimeStr,
                        fontSize = 14.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    LinearProgressIndicator(
                        progress = { if (pass.continuousVisibility) 1f else pass.progress.coerceIn(0f, 1f) },
                        drawStopIndicator = {},
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    Text(
                        text = if (pass.continuousVisibility) defaultTime else losTimeStr,
                        fontSize = 14.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
                com.rtbishop.look4sat.core.presentation.PassBoundaryNote(pass)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            PassRadarPreview(
                pass = pass,
                satelliteRepo = satelliteRepo,
                stationPosition = stationPosition,
                modifier = Modifier.size(44.dp)
            )
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.background)
    }
}
