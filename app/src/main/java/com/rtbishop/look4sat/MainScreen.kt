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
package com.rtbishop.look4sat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.model.BottomBarTool
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.presentation.DeeplinkResolver
import com.rtbishop.look4sat.core.presentation.ElevationThresholds
import com.rtbishop.look4sat.core.presentation.GridFinderDestination as GridFinderRoute
import com.rtbishop.look4sat.core.presentation.LocalElevationThresholds
import com.rtbishop.look4sat.core.presentation.LogbookDestination
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.LoTWUploadDestination as LoTWUploadRoute
import com.rtbishop.look4sat.core.presentation.RadarDestination as RadarRoute
import com.rtbishop.look4sat.core.presentation.MatchGridPickerDestination
import com.rtbishop.look4sat.core.presentation.RadarPassMapDestination
import com.rtbishop.look4sat.core.presentation.RadarMatchDestination
import com.rtbishop.look4sat.core.presentation.RadarMatchGridPickerDestination
import com.rtbishop.look4sat.core.presentation.Screen
import com.rtbishop.look4sat.core.presentation.popCurrentEntry
import com.rtbishop.look4sat.core.presentation.hasEnoughHeight
import com.rtbishop.look4sat.core.presentation.hasEnoughWidth
import com.rtbishop.look4sat.feature.ft4.Ft4ShellDestination
import com.rtbishop.look4sat.feature.jtty.JttyDestination
import com.rtbishop.look4sat.feature.logbook.LogbookScreenDestination
import com.rtbishop.look4sat.feature.gridfinder.GridFinderDestination
import com.rtbishop.look4sat.feature.map.MapDestination
import com.rtbishop.look4sat.feature.map.RadarPassMapScreenDestination
import com.rtbishop.look4sat.feature.map.MapFilterViewModel
import com.rtbishop.look4sat.feature.mutual.MutualScreen
import com.rtbishop.look4sat.feature.mutual.MutualViewModel
import com.rtbishop.look4sat.feature.passes.PassesDestination
import com.rtbishop.look4sat.feature.radar.RadarDestination
import com.rtbishop.look4sat.feature.radar.RadarGridPickResult
import com.rtbishop.look4sat.feature.satellites.SatellitesDestination
import com.rtbishop.look4sat.feature.settings.SettingsDestination
import com.rtbishop.look4sat.feature.settings.LoTWUploadDestination
import com.rtbishop.look4sat.feature.status.SatStatusDestination
import kotlinx.coroutines.delay

@Composable
fun NavRoot(deeplink: String? = null) {
    val rootBackStack = rememberNavBackStack(Screen.Passes)
    val rootCurrentKey = rootBackStack.lastOrNull()
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val mutualViewModel: MutualViewModel = viewModel(
        viewModelStoreOwner = context as ViewModelStoreOwner,
        factory = MutualViewModel.factory(container)
    )
    val mapFilterViewModel: MapFilterViewModel = viewModel(
        viewModelStoreOwner = context as ViewModelStoreOwner,
        factory = MapFilterViewModel.factory()
    )
    val deeplinkResolver = DeeplinkResolver()
    var openMapRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(deeplink) {
        deeplink?.let { rootBackStack.add(deeplinkResolver.resolve(it)) }
    }
    var radarGridPickResult by remember { mutableStateOf<RadarGridPickResult?>(null) }
    val finishRadarGridPicker: (String?) -> Unit = { grid ->
        if (rootBackStack.popCurrentEntry(RadarMatchGridPickerDestination)) {
            radarGridPickResult = RadarGridPickResult(grid)
        }
    }
    val navigateBackFrom: (NavKey?) -> Unit = { entry ->
        if (entry is RadarMatchGridPickerDestination) finishRadarGridPicker(null)
        else rootBackStack.popCurrentEntry(entry)
    }
    val navigateBack: () -> Unit = { navigateBackFrom(rootCurrentKey) }
    val openRadarGridPicker: () -> Unit = {
        radarGridPickResult = null
        rootBackStack.add(RadarMatchGridPickerDestination)
    }
    val navigateToRadar: () -> Unit = { rootBackStack.add(RadarRoute) }
    val navigateToFt4: () -> Unit = { rootBackStack.add(Screen.Ft4) }
    val navigateToJtty: () -> Unit = { if(rootBackStack.lastOrNull()!=Screen.Jtty) rootBackStack.add(Screen.Jtty) }
    val navigateToLogbook: () -> Unit = { rootBackStack.add(LogbookDestination) }
    val navigateToMap: () -> Unit = {
        while (rootBackStack.size > 1) rootBackStack.removeAt(rootBackStack.size - 1)
        openMapRequest += 1
    }
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    val navigateToLoTWStation: () -> Unit = { rootBackStack.add(LoTWUploadRoute) }
    var requestedMainTab by remember { mutableStateOf<Screen?>(null) }
    var currentRadarMapPass by remember { mutableStateOf<RadarPassMapDestination?>(null) }
    val navigateToRadarPassMap: (Int, Long, Long) -> Unit = { catNum, aos, los ->
        if (los > aos) {
            val next = RadarPassMapDestination(catNum, aos, los)
            currentRadarMapPass = next
            rootBackStack.add(next)
        }
    }
    val switchRadarPassMap: (RadarPassMapDestination) -> Unit = { next ->
        if (rootBackStack.lastOrNull() is RadarPassMapDestination) currentRadarMapPass = next
    }
    val startRadarMatch: (String, Int, Long, Long, (Boolean) -> Unit) -> Unit = { grid, catNum, aos, los, onComplete ->
        mutualViewModel.prepareRadarMatchFromGrid(grid, catNum, aos, los) { selection ->
            if (selection == null) {
                onComplete(false)
            } else {
                container.setMutualPassData(selection.mutualPassData)
                container.satelliteRepo.selectPass(selection.catNum, selection.aosTime)
                if (rootBackStack.lastOrNull() is RadarMatchDestination) {
                    rootBackStack.popCurrentEntry(rootBackStack.lastOrNull())
                }
                rootBackStack.add(RadarMatchDestination(
                    stationBGrid = selection.stationBGrid,
                    catNum = selection.catNum,
                    aosTime = selection.aosTime,
                    sessionId = System.currentTimeMillis()
                ))
                onComplete(true)
            }
        }
    }
    // Incoming screen slides in from the right, outgoing drifts left at 1/3 speed (API35+ style)
    val pushTransition = slideInHorizontally(tween(300)) { it } togetherWith
        slideOutHorizontally(tween(300)) { -it / 3 }
    // Reverse: outgoing slides out to the right, incoming drifts in from the left
    val popTransition = slideInHorizontally(tween(300)) { -it / 3 } togetherWith
        slideOutHorizontally(tween(300)) { it }
    CompositionLocalProvider(
        LocalElevationThresholds provides ElevationThresholds(
            low = otherSettings.lowElevation,
            high = otherSettings.highElevation
        )
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = rootBackStack,
            onBack = navigateBack,
            transitionSpec = { pushTransition },
            popTransitionSpec = { popTransition },
            predictivePopTransitionSpec = { popTransition },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<Screen.Passes> {
                    MainScreen(
                        navigateToRadar = navigateToRadar,
                        navigateToFt4 = navigateToFt4,
                        navigateToJtty = navigateToJtty,
                        navigateToLoTWStation = navigateToLoTWStation,
                        openMapRequest = openMapRequest,
                        onOpenMapRequestHandled = { openMapRequest = 0 },
                        mutualViewModel = mutualViewModel,
                        mapFilterViewModel = mapFilterViewModel,
                        requestedTab = requestedMainTab,
                        onRequestedTabHandled = { requestedMainTab = null }
                    )
                }
                entry<RadarRoute> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RadarDestination(
                            navigateUp = { navigateBackFrom(RadarRoute) },
                            navigateToMap = navigateToMap,
                            onOpenLoTWStation = navigateToLoTWStation,
                            onOpenPassMap = navigateToRadarPassMap,
                            onStartMatch = startRadarMatch,
                            onOpenMatchGridPicker = openRadarGridPicker,
                            gridPickResult = if (rootBackStack.lastOrNull() is RadarRoute) radarGridPickResult else null,
                            onGridPickResultHandled = { radarGridPickResult = null }
                        )
                    }
                }
                entry<RadarMatchDestination> { match ->
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RadarDestination(
                            navigateUp = { navigateBackFrom(match) },
                            navigateToMap = navigateToMap,
                            onOpenLoTWStation = navigateToLoTWStation,
                            onOpenPassMap = navigateToRadarPassMap,
                            onStartMatch = startRadarMatch,
                            onOpenMatchGridPicker = openRadarGridPicker,
                            gridPickResult = if (rootBackStack.lastOrNull() == match) radarGridPickResult else null,
                            onGridPickResultHandled = { radarGridPickResult = null }
                        )
                    }
                }
                entry<RadarMatchGridPickerDestination> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MapDestination(
                            mapFilterViewModel = mapFilterViewModel,
                            gridPickerMode = true,
                            onPickGrid = { grid -> finishRadarGridPicker(grid) },
                            onNavigateBack = { finishRadarGridPicker(null) }
                        )
                    }
                }
                entry<RadarPassMapDestination> { pass ->
                    Look4SatNavigationScaffold(
                        selectedScreen = Screen.Map,
                        onSelectScreen = { screen ->
                            container.setMutualPassData(MutualPassData())
                            requestedMainTab = screen
                            while (rootBackStack.size > 1) rootBackStack.removeLastOrNull()
                        },
                        navigateToFt4 = navigateToFt4,
                        navigateToJtty = navigateToJtty,
                        allowReselect = true
                    ) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            RadarPassMapScreenDestination(
                                pass = currentRadarMapPass ?: pass,
                                onSwitchPass = switchRadarPassMap
                            )
                        }
                    }
                }
                entry<GridFinderRoute> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        GridFinderDestination(
                            navigateUp = { navigateBackFrom(GridFinderRoute) },
                            onOpenLoTWStation = navigateToLoTWStation
                        )
                    }
                }
                entry<LoTWUploadRoute> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LoTWUploadDestination(navigateUp = { navigateBackFrom(LoTWUploadRoute) })
                    }
                }
            entry<Screen.Jtty> {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    JttyDestination(navigateUp={navigateBackFrom(Screen.Jtty)},navigateToLogbook=navigateToLogbook)
                }
            }
            entry<Screen.Ft4> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Ft4ShellDestination(navigateUp = { navigateBackFrom(Screen.Ft4) }, navigateToLogbook = navigateToLogbook)
                }
            }
            entry<LogbookDestination> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LogbookScreenDestination(navigateUp = { navigateBackFrom(LogbookDestination) }, navigateToMap = navigateToMap)
                }
            }
        }
        )
    }
}

@Composable
private fun Look4SatNavigationScaffold(
    selectedScreen: Screen?,
    onSelectScreen: (Screen) -> Unit,
    navigateToFt4: () -> Unit,
    navigateToJtty: () -> Unit,
    allowReselect: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    var showMoreSheet by rememberSaveable { mutableStateOf(false) }
    val primaryTool = bottomBarScreen(otherSettings.bottomBarTool)
    val navItems = listOf(
        Screen.Satellites,
        Screen.Passes,
        Screen.Logbook,
        primaryTool,
        Screen.More,
        Screen.Settings
    )
    // Keep every original More destination available, even when one is also
    // assigned to the configurable bottom shortcut. When FT4 is replaced in
    // the bottom bar, expose FT4 in More so it never becomes inaccessible.
    val moreItems = buildList {
        add(Screen.GridFinder)
        add(Screen.Map)
        add(Screen.Mutual)
        add(Screen.AMSAT)
        add(Screen.PassSchedule)
        add(Screen.Jtty)
        if (primaryTool != Screen.Ft4) add(Screen.Ft4)
    }
    if (showMoreSheet) {
        MoreDestinationsSheet(
            destinations = moreItems,
            currentScreen = selectedScreen,
            currentTool = otherSettings.bottomBarTool,
            onDismiss = { showMoreSheet = false },
            onSaveBottomTool = { tool ->
                container.settingsRepo.updateOtherSettings {
                    it.copy(bottomBarTool = BottomBarTool.normalize(tool))
                }
            },
            onDestinationSelected = { screen ->
                showMoreSheet = false
                if(screen is Screen.Jtty) {
                    navigateToJtty()
                } else if (screen is Screen.Ft4) {
                    navigateToFt4()
                } else if (selectedScreen != screen || allowReselect) {
                    onSelectScreen(screen)
                }
            }
        )
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            navItems.forEach { screen ->
                val isSelected = when {
                    selectedScreen == screen -> true
                    screen is Screen.More && selectedScreen in moreItems -> true
                    screen is Screen.More && showMoreSheet -> true
                    else -> false
                }
                item(
                    icon = { Icon(painterResource(screen.iconResId), stringResource(screen.titleResId)) },
                    label = {
                        Text(
                            text = stringResource(bottomBarLabelRes(screen)),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    },
                    selected = isSelected,
                    onClick = {
                        if (screen is Screen.More) {
                            showMoreSheet = true
                            return@item
                        }
                        if (isSelected && !allowReselect) return@item
                        if (screen is Screen.Jtty) {
                            navigateToJtty()
                            return@item
                        } else if (screen is Screen.Ft4) {
                            navigateToFt4()
                            return@item
                        }
                        onSelectScreen(screen)
                    }
                )
            }
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        layoutType = when {
            !hasEnoughHeight() && hasEnoughWidth() -> NavigationSuiteType.NavigationRail
            !hasEnoughWidth() -> NavigationSuiteType.ShortNavigationBarCompact
            else -> NavigationSuiteType.ShortNavigationBarMedium
        }
    ) {
        content()
    }
}

@Composable
fun MainScreen(
    navigateToRadar: () -> Unit = {},
    navigateToFt4: () -> Unit = {},
    navigateToJtty: () -> Unit = {},
    navigateToLoTWStation: () -> Unit = {},
    openMapRequest: Int = 0,
    onOpenMapRequestHandled: () -> Unit = {},
    mutualViewModel: MutualViewModel,
    mapFilterViewModel: MapFilterViewModel,
    requestedTab: Screen? = null,
    onRequestedTabHandled: () -> Unit = {}
) {
    val backStack = rememberNavBackStack(Screen.Passes)
    val currentKey = backStack.lastOrNull()
    val navigateBackFrom: (NavKey?) -> Unit = { entry ->
        if (backStack.popCurrentEntry(entry) && entry is MatchGridPickerDestination) {
            mutualViewModel.requestListScrollRestore()
        }
    }
    val navigateBack: () -> Unit = { navigateBackFrom(currentKey) }
    val navigateToScreen: (Screen) -> Unit = { screen ->
        val leavingGridPicker = backStack.lastOrNull() is MatchGridPickerDestination
        while (backStack.size > 1) backStack.removeAt(backStack.size - 1)
        if (screen !is Screen.Passes) backStack.add(screen)
        if (leavingGridPicker) mutualViewModel.requestListScrollRestore()
    }
    val navigateToMap: () -> Unit = { navigateToScreen(Screen.Map) }
    LaunchedEffect(openMapRequest) {
        if (openMapRequest > 0) {
            navigateToMap()
            onOpenMapRequestHandled()
        }
    }
    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            navigateToScreen(it)
            onRequestedTabHandled()
        }
    }
    val fadeTransition = fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(350))
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val trackingState by container.radioTrackingService.state.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    // Map grid-QSO dialog "Match" button: pre-fill the match page for that
    // grid and run the query BEFORE navigating, so the page's first frame
    // already contains the results (time-range card at the top, pass curves
    // right below — no scroll flicker). Navigation happens once the query
    // finishes (see the collector below).
    val mutualState by mutualViewModel.uiState.collectAsStateWithLifecycle()
    val matchCalculating = mutualState.pendingNavigation && mutualState.isCalculating

    LaunchedEffect(mutualViewModel) {
        mutualViewModel.uiState.collect { state ->
            if (state.pendingNavigation && !state.isCalculating) {
                mutualViewModel.consumePendingNavigation()
                // Push Mutual on top of the Map entry instead of replacing the
                // stack (bottom-nav style): the system back gesture then pops
                // back to the map page, which is the page the user came from.
                if (backStack.lastOrNull() !is Screen.Mutual) {
                    backStack.add(Screen.Mutual)
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalElevationThresholds provides ElevationThresholds(
            low = otherSettings.lowElevation,
            high = otherSettings.highElevation
        )
    ) {
        Look4SatNavigationScaffold(
            selectedScreen = currentKey as? Screen,
            onSelectScreen = navigateToScreen,
            navigateToFt4 = navigateToFt4,
            navigateToJtty = navigateToJtty
        ) {
            Column {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.weight(1f),
                    onBack = navigateBack,
                    transitionSpec = { fadeTransition },
                    popTransitionSpec = { fadeTransition },
                    predictivePopTransitionSpec = { fadeTransition },
                    entryDecorators = listOf(
                        // Required for saving Compose state per entry
                        rememberSaveableStateHolderNavEntryDecorator(),
                        // Required for ViewModel scoping per entry
                        rememberViewModelStoreNavEntryDecorator()
                    ),
                    entryProvider = entryProvider {
                        entry<Screen.Satellites> {
                            SatellitesDestination(
                                navigateUp = { navigateBackFrom(Screen.Satellites) },
                                navigateToAmSat = { backStack.add(Screen.AMSAT) }
                            )
                        }
                        entry<Screen.Passes> {
                            PassesDestination(
                                navigateToRadar = { catNum, aosTime ->
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    navigateToRadar()
                                },
                                navigateToMap = navigateToMap
                            )
                        }
                        entry<Screen.Map> {
                            MapDestination(
                                mapFilterViewModel = mapFilterViewModel,
                                matchCalculating = matchCalculating,
                                onMatchGrid = { grid ->
                                    // Grid-QSO dialog "Match" button: pre-fill
                                    // the match page for that grid and start the
                                    // query. Navigation to the match page is
                                    // handled by the uiState collector above,
                                    // once the query finishes.
                                    mutualViewModel.prefillMatchFromGrid(grid)
                                }
                            )
                        }
                        entry<Screen.GridFinder> {
                            GridFinderDestination(
                                navigateUp = { navigateBackFrom(Screen.GridFinder) },
                                onOpenLoTWStation = navigateToLoTWStation
                            )
                        }
                        entry<MatchGridPickerDestination> { picker ->
                            MapDestination(
                                mapFilterViewModel = mapFilterViewModel,
                                gridPickerMode = true,
                                onPickGrid = { grid ->
                                    if (backStack.lastOrNull() == picker) {
                                        if (picker.stationA) mutualViewModel.onStationAGrid(grid)
                                        else mutualViewModel.onStationBGrid(grid)
                                        navigateBackFrom(picker)
                                    }
                                },
                                onNavigateBack = { navigateBackFrom(picker) }
                            )
                        }
                        entry<Screen.Mutual> {
                            MutualScreen(
                                viewModel = mutualViewModel,
                                navigateToRadar = { catNum, aosTime, pass ->
                                    container.setMutualPassData(pass ?: MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    navigateToRadar()
                                },
                                onPickGrid = { stationA ->
                                    backStack.add(MatchGridPickerDestination(stationA))
                                }
                            )
                        }
                        entry<Screen.AMSAT> { SatStatusDestination() }
                        entry<Screen.PassSchedule> {
                            PassScheduleDestination(
                                navigateUp = { navigateBackFrom(Screen.PassSchedule) },
                                navigateToRadar = { catNum, aosTime ->
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    navigateToRadar()
                                }
                            )
                        }
                        entry<Screen.Logbook> {
                            LogbookScreenDestination(navigateUp = { navigateBackFrom(Screen.Logbook) }, navigateToMap = navigateToMap)
                        }
                        entry<Screen.Settings> {
                            SettingsDestination()
                        }
                    }
                )
                // Radio tracking status banner
                if (trackingState.isActive) {
                    val infiniteTransition = rememberInfiniteTransition(label = "trackingPulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f, targetValue = 0.4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ), label = "pulseAlpha"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable {
                                val pass = trackingState.currentPass
                                if (pass != null) {
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(pass.catNum, pass.aosTime)
                                    navigateToRadar()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50).copy(alpha = alpha))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.main_tracking,
                                trackingState.currentPass?.name.orEmpty()
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        val txOk = if (trackingState.txConnected) stringResource(R.string.ft4_tx_label) else ""
                        val rxOk = if (trackingState.rxConnected) stringResource(R.string.ft4_rx_label) else ""
                        Text(
                            text = listOf(txOk, rxOk).filter { it.isNotBlank() }.joinToString("/"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}


private fun bottomBarLabelRes(screen: Screen): Int = when (screen) {
    Screen.AMSAT -> R.string.nav_amsat_short
    Screen.PassSchedule -> R.string.nav_pass_schedule_short
    Screen.GridFinder -> R.string.nav_grid_finder_short
    else -> screen.titleResId
}

private fun bottomBarScreen(value: String): Screen = when (BottomBarTool.normalize(value)) {
    BottomBarTool.MAP -> Screen.Map
    BottomBarTool.GRID_FINDER -> Screen.GridFinder
    BottomBarTool.MUTUAL -> Screen.Mutual
    BottomBarTool.AMSAT -> Screen.AMSAT
    BottomBarTool.PASS_SCHEDULE -> Screen.PassSchedule
    else -> Screen.Ft4
}

private fun bottomBarToolTitleRes(value: String): Int = when (BottomBarTool.normalize(value)) {
    BottomBarTool.MAP -> R.string.nav_map
    BottomBarTool.GRID_FINDER -> R.string.gridfinder_title
    BottomBarTool.MUTUAL -> R.string.nav_mutual
    BottomBarTool.AMSAT -> R.string.nav_amsat
    BottomBarTool.PASS_SCHEDULE -> R.string.pass_schedule_title
    else -> R.string.nav_ft4
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MoreDestinationsSheet(
    destinations: List<Screen>,
    currentScreen: Screen?,
    currentTool: String,
    onDismiss: () -> Unit,
    onSaveBottomTool: (String) -> Unit,
    onDestinationSelected: (Screen) -> Unit
) {
    var showToolSettings by rememberSaveable { mutableStateOf(false) }
    var selectedTool by rememberSaveable(currentTool) {
        mutableStateOf(BottomBarTool.normalize(currentTool))
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var hasVisitedToolSettings by remember { mutableStateOf(false) }
    LaunchedEffect(showToolSettings) {
        if (showToolSettings) {
            hasVisitedToolSettings = true
            sheetState.expand()
        } else if (hasVisitedToolSettings) {
            // Let the More menu slide back into place before shrinking the sheet;
            // otherwise the old compact height can clip the destination list during
            // the 220 ms page transition and make the other destinations appear gone.
            delay(260)
            sheetState.partialExpand()
            hasVisitedToolSettings = false
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // Register from inside the sheet content so this handler takes precedence
        // over the sheet's own Back handler while the nested page is visible.
        BackHandler(enabled = showToolSettings) { showToolSettings = false }
        AnimatedContent(
            targetState = showToolSettings,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(220)) +
                        fadeIn(animationSpec = tween(160))) togetherWith
                        (slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(220)) +
                            fadeOut(animationSpec = tween(140)))
                } else {
                    (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(220)) +
                        fadeIn(animationSpec = tween(160))) togetherWith
                        (slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(220)) +
                            fadeOut(animationSpec = tween(140)))
                }
            },
            label = "moreMenuPageTransition"
        ) { showSettingsPage ->
        if (showSettingsPage) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { showToolSettings = false }) {
                    Text("‹ ${stringResource(R.string.nav_more)}")
                }
                Text(
                    text = stringResource(R.string.prefs_bottom_tool_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.prefs_bottom_tool_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val options = listOf(
                    BottomBarTool.FT4 to R.string.nav_ft4,
                    BottomBarTool.MAP to R.string.nav_map,
                    BottomBarTool.GRID_FINDER to R.string.gridfinder_title,
                    BottomBarTool.MUTUAL to R.string.nav_mutual,
                    BottomBarTool.AMSAT to R.string.nav_amsat,
                    BottomBarTool.PASS_SCHEDULE to R.string.pass_schedule_title
                )
                options.forEach { (tool, labelRes) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedTool = tool }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTool == tool,
                            onClick = { selectedTool = tool }
                        )
                        Text(
                            text = stringResource(labelRes),
                            modifier = Modifier.padding(start = 6.dp),
                            color = if (selectedTool == tool) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showToolSettings = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                    Button(
                        onClick = {
                            onSaveBottomTool(selectedTool)
                            showToolSettings = false
                        },
                        enabled = selectedTool != BottomBarTool.normalize(currentTool)
                    ) { Text(stringResource(R.string.btn_accept)) }
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
        } else {
            // Keep the complete More menu scrollable within the sheet's current
            // height. This is especially important when returning from the full-height
            // shortcut sub-page to the compact More menu.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.nav_more),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                destinations.forEach { screen ->
                    val selected = currentScreen == screen
                    ListItem(
                        headlineContent = { Text(stringResource(screen.titleResId)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(screen.iconResId),
                                contentDescription = null
                            )
                        },
                        trailingContent = {
                            if (selected) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_done),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDestinationSelected(screen) }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.prefs_bottom_tool_title)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.prefs_bottom_tool_current,
                                stringResource(bottomBarToolTitleRes(currentTool))
                            )
                        )
                    },
                    leadingContent = {
                        Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = null)
                    },
                    trailingContent = {
                        Icon(painter = painterResource(R.drawable.ic_arrow), contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedTool = BottomBarTool.normalize(currentTool)
                            showToolSettings = true
                        }
                )
                Spacer(modifier = Modifier.size(16.dp))
            }
        }
        }
    }
}
