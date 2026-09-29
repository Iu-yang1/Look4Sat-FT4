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
package com.rtbishop.look4sat.feature.gridfinder

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.repository.CompassAccuracy
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.utility.GridBoundaryDirection
import com.rtbishop.look4sat.core.domain.utility.GridFixStatus
import com.rtbishop.look4sat.core.domain.utility.GridGeometry
import com.rtbishop.look4sat.core.domain.utility.VUCC_BOUNDARY_TOLERANCE_METERS
import com.rtbishop.look4sat.core.presentation.ElevationHighColor
import com.rtbishop.look4sat.core.presentation.ElevationLowColor
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.SharedDialog
import com.rtbishop.look4sat.core.presentation.TopBar
import kotlin.math.abs

/**
 * Full-screen field tool: walks the operator onto a VUCC grid line or corner.
 * Reached from the map's grid view and from Settings; the live fix it uses
 * never moves the station unless the operator taps "Set as my station".
 */
@Composable
fun GridFinderDestination(
    navigateUp: () -> Unit,
    onOpenLoTWStation: () -> Unit = {}
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: GridFinderViewModel = viewModel(factory = GridFinderViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onAction(GridFinderAction.LocationPermissionResult(granted)) }
    val requestLocationPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    var hasAskedPermission by rememberSaveable { mutableStateOf(false) }
    // "Set as LoTW station": hand the grids the operator currently stands on
    // (1 inside, 2 on a line, 4 on a corner) to the upload station page.
    val openLoTWStation: () -> Unit = {
        val grids = uiState.claimableGrids
        if (grids.isNotEmpty()) {
            container.setPendingLoTWStationGrid(grids.joinToString(","))
            onOpenLoTWStation()
        }
    }

    DisposableEffect(viewModel) {
        viewModel.onAction(GridFinderAction.StartUpdates)
        onDispose { viewModel.onAction(GridFinderAction.StopUpdates) }
    }
    // Ask once automatically on the first visit; after a refusal the banner
    // offers the request again, so returning to the screen is not a prompt loop.
    LaunchedEffect(uiState.permissionGranted) {
        if (!uiState.permissionGranted && !hasAskedPermission) {
            hasAskedPermission = true
            requestLocationPermission()
        }
    }

    GridFinderScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        navigateUp = navigateUp,
        openLoTWStation = openLoTWStation,
        requestLocationPermission = requestLocationPermission
    )
}

@Composable
private fun GridFinderScreen(
    uiState: GridFinderState,
    onAction: (GridFinderAction) -> Unit,
    navigateUp: () -> Unit,
    openLoTWStation: () -> Unit,
    requestLocationPermission: () -> Unit
) {
    // Same top-bar → content rhythm as the settings page: ScreenColumn puts a
    // small gap between the header row and the rounded surfaceContainer panel.
    ScreenColumn(
        topBar = {
            TopBar {
                IconCard(action = navigateUp, resId = R.drawable.ic_back)
                Text(
                    text = stringResource(R.string.gridfinder_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 24.dp)
        ) {
                item { StandingCard(uiState, requestLocationPermission) }
                uiState.geometry?.let { geometry ->
                    item { LocationCard(uiState, onAction, openLoTWStation) }
                    item {
                        GuideCard(
                            title = stringResource(R.string.gridfinder_card_corner),
                            bearing = geometry.cornerBearingDegrees,
                            distanceMeters = geometry.cornerMeters,
                            direction = null,
                            grids = geometry.cornerGrids,
                            tint = ElevationHighColor,
                            heading = uiState.headingDegrees
                        )
                    }
                    item {
                        GuideCard(
                            title = stringResource(R.string.gridfinder_card_line),
                            bearing = geometry.nearestLineBearingDegrees,
                            distanceMeters = geometry.nearestLineMeters,
                            direction = null,
                            grids = geometry.nearestLineGrids,
                            tint = MaterialTheme.colorScheme.primary,
                            heading = uiState.headingDegrees,
                            extra = stringResource(
                                if (geometry.nearestLineIsLatitude) {
                                    R.string.gridfinder_line_latitude
                                } else {
                                    R.string.gridfinder_line_longitude
                                }
                            )
                        )
                    }
                    item { BoundaryLinesCard(geometry) }
                    item { ProximityCard(geometry, uiState.headingDegrees) }
                }
                item { FixQualityCard(uiState) }
                item { CompassCard(uiState, onAction) }
            }
        }
}

@Composable
private fun StandingCard(uiState: GridFinderState, requestLocationPermission: () -> Unit) {
    val grids = uiState.claimableGrids.joinToString(" · ")
    val (headline, tint) = when {
        !uiState.permissionGranted -> stringResource(R.string.gridfinder_status_blocked) to ElevationLowColor
        !uiState.providerEnabled -> stringResource(R.string.gridfinder_status_disabled) to ElevationLowColor
        uiState.fix == null -> stringResource(R.string.gridfinder_status_waiting) to MaterialTheme.colorScheme.onSurface
        uiState.status == null -> stringResource(R.string.gridfinder_status_unknown) to ElevationLowColor
        uiState.status == GridFixStatus.ON_GRID_CORNER -> stringResource(R.string.gridfinder_status_corner) to ElevationHighColor
        uiState.status == GridFixStatus.ON_GRID_LINE -> stringResource(R.string.gridfinder_status_line) to ElevationHighColor
        else -> stringResource(R.string.gridfinder_status_inside) to MaterialTheme.colorScheme.onSurface
    }
    GridCard(Modifier.fillMaxWidth()) {
        Text(
            text = headline,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.fillMaxWidth()
        )
        if (grids.isNotEmpty()) {
            GridMetricRow(
                label = stringResource(R.string.gridfinder_label_claimable),
                value = grids,
                valueColor = when (uiState.status) {
                    GridFixStatus.ON_GRID_CORNER, GridFixStatus.ON_GRID_LINE -> ElevationHighColor
                    else -> null
                }
            )
        }
        when {
            uiState.fix == null -> Unit
            uiState.status == GridFixStatus.INSIDE_GRID -> Text(
                text = stringResource(R.string.gridfinder_vucc_rule_note, VUCC_BOUNDARY_TOLERANCE_METERS),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            uiState.isStandingConfirmed -> Text(
                text = stringResource(R.string.gridfinder_status_confirmed, PRECISE_FIXES_REQUIRED),
                fontSize = 12.sp,
                color = ElevationHighColor
            )
            else -> Text(
                text = stringResource(
                    R.string.gridfinder_status_unconfirmed,
                    uiState.preciseFixesInARow,
                    PRECISE_FIXES_REQUIRED
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!uiState.permissionGranted) {
            Button(onClick = requestLocationPermission) {
                Text(text = stringResource(R.string.gridfinder_grant_permission))
            }
        }
    }
}

@Composable
private fun LocationCard(
    uiState: GridFinderState,
    onAction: (GridFinderAction) -> Unit,
    openLoTWStation: () -> Unit
) {
    GridCard(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gridfinder_card_location),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        val geometry = uiState.geometry
        val fix = uiState.fix
        GridMetricRow(stringResource(R.string.gridfinder_label_grid4), geometry?.grid4 ?: "-")
        GridMetricRow(stringResource(R.string.gridfinder_label_grid6), geometry?.grid6 ?: "-")
        GridMetricRow(stringResource(R.string.gridfinder_label_grid8), geometry?.grid8 ?: "-")
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_latitude),
            value = fix?.let { String.format("%+.5f°", it.latitude) } ?: "-"
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_longitude),
            value = fix?.let { String.format("%+.5f°", it.longitude) } ?: "-"
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_altitude),
            value = fix?.let { String.format("%.0f m", it.altitudeMeters) } ?: "-"
        )
        if (uiState.fix != null) {
            Button(
                onClick = { onAction(GridFinderAction.SetAsStation) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.gridfinder_action_set_station))
            }
            if (uiState.isStationSaved) {
                Text(
                    text = stringResource(R.string.gridfinder_action_station_saved),
                    fontSize = 12.sp,
                    color = ElevationHighColor
                )
            }
            val claimable = uiState.claimableGrids
            if (claimable.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Button(
                    onClick = openLoTWStation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.gridfinder_action_set_lotw_station,
                            claimable.joinToString(",")
                        )
                    )
                }
                Text(
                    text = stringResource(R.string.gridfinder_lotw_station_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GuideCard(
    title: String,
    bearing: Double,
    distanceMeters: Double,
    direction: GridBoundaryDirection?,
    grids: List<String>,
    tint: Color,
    heading: Float?,
    extra: String? = null
) {
    GridCard(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            GridCompassDial(
                bearingDegrees = bearing,
                headingDegrees = heading,
                tint = tint,
                modifier = Modifier.size(108.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GridMetricRow(
                    label = stringResource(R.string.gridfinder_label_distance),
                    value = formatDistance(distanceMeters),
                    valueColor = tint
                )
                GridMetricRow(
                    label = stringResource(R.string.gridfinder_label_bearing),
                    value = String.format("%.0f° %s", bearing, cardinal(bearing))
                )
                if (direction != null) {
                    GridMetricRow(stringResource(R.string.gridfinder_label_direction), directionLabel(direction))
                }
                if (grids.isNotEmpty()) {
                    GridMetricRow(stringResource(R.string.gridfinder_label_grids), grids.joinToString(" / "))
                }
                extra?.let {
                    Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BoundaryLinesCard(geometry: GridGeometry) {
    GridCard(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gridfinder_card_lines),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_lat_line),
            value = stringResource(
                R.string.gridfinder_line_value,
                formatDistance(geometry.latLineMeters),
                directionLabel(geometry.latLineDirection)
            )
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_lon_line),
            value = stringResource(
                R.string.gridfinder_line_value,
                formatDistance(geometry.lonLineMeters),
                directionLabel(geometry.lonLineDirection)
            )
        )
        Text(
            text = stringResource(R.string.gridfinder_vucc_rule_note, VUCC_BOUNDARY_TOLERANCE_METERS),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProximityCard(
    geometry: GridGeometry,
    heading: Float?
) {
    GridCard(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gridfinder_card_map),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        GridProximityMap(
            geometry = geometry,
            headingDegrees = heading,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        )
        Text(
            text = stringResource(R.string.gridfinder_map_legend),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FixQualityCard(uiState: GridFinderState) {
    val fix = uiState.fix
    GridCard(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gridfinder_card_fix),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_accuracy),
            value = fix?.let { String.format("± %.1f m", it.accuracyMeters) } ?: "-",
            valueColor = fix?.let {
                if (it.isPreciseEnough(VUCC_FIX_ACCURACY_LIMIT_METERS)) ElevationHighColor else ElevationLowColor
            }
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_fix_age),
            value = fix?.let { formatAge(it.epochMs) } ?: "-"
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_provider),
            value = stringResource(
                if (uiState.providerEnabled) {
                    R.string.gridfinder_value_provider_on
                } else {
                    R.string.gridfinder_value_provider_off
                }
            )
        )
        LinearProgressIndicator(
            progress = { uiState.preciseFixesInARow.toFloat() / PRECISE_FIXES_REQUIRED },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CompassCard(uiState: GridFinderState, onAction: (GridFinderAction) -> Unit) {
    var showCalibration by rememberSaveable { mutableStateOf(false) }
    GridCard(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.gridfinder_card_compass),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_heading),
            value = uiState.headingDegrees?.let { String.format("%.0f° %s", it, cardinal(it.toDouble())) } ?: "-"
        )
        GridMetricRow(
            label = stringResource(R.string.gridfinder_label_compass_accuracy),
            value = stringResource(compassAccuracyRes(uiState.compassAccuracy))
        )
        if (uiState.isCompassEnabled) {
            Button(
                onClick = { showCalibration = true },
                enabled = uiState.compassAccuracy != CompassAccuracy.UNAVAILABLE,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.gridfinder_action_calibrate))
            }
        }
        if (!uiState.isCompassEnabled) {
            Text(
                text = stringResource(R.string.gridfinder_compass_disabled_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (uiState.compassAccuracy == CompassAccuracy.UNAVAILABLE) {
            Text(
                text = stringResource(R.string.gridfinder_compass_unavailable_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.gridfinder_compass_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showCalibration) {
        GridCompassCalibrationDialog(
            accuracy = uiState.compassAccuracy,
            heading = uiState.headingDegrees,
            onSaveOffset = { offset -> onAction(GridFinderAction.SetCompassOffset(offset)) },
            onDismiss = { showCalibration = false }
        )
    }
}

/**
 * Compact figure-eight calibration dialog. It mirrors the settings screen's
 * compass calibration but is self-contained in this module; the trim is saved
 * through the same setting the radar page uses, so both stay in sync.
 */
@Composable
private fun GridCompassCalibrationDialog(
    accuracy: CompassAccuracy,
    heading: Float?,
    onSaveOffset: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var offsetText by rememberSaveable { mutableStateOf("") }
    val parsedOffset = offsetText.replace(',', '.').toFloatOrNull()?.takeIf { it in -180f..180f }
    val progress = when (accuracy) {
        CompassAccuracy.HIGH -> 1f
        CompassAccuracy.MEDIUM -> 0.66f
        CompassAccuracy.LOW -> 0.33f
        else -> 0f
    }
    SharedDialog(
        title = stringResource(R.string.prefs_compass_calibration_title),
        onDismissRequest = onDismiss,
        onCancel = onDismiss,
        acceptEnabled = parsedOffset != null,
        onAccept = {
            parsedOffset?.let(onSaveOffset)
            onDismiss()
        }
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = padding)
        ) {
            Text(
                text = "∞",
                fontSize = 84.sp,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 84.sp
            )
            Text(
                text = stringResource(R.string.prefs_compass_calibration_instruction),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.prefs_compass_accuracy, stringResource(compassAccuracyRes(accuracy))),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = heading?.let { String.format("%.0f° %s", it, cardinal(it.toDouble())) } ?: "-",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.prefs_compass_offset_support),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(text = stringResource(R.string.prefs_compass_offset)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun GridCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
            content = content
        )
    }
}

@Composable
private fun GridMetricRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun directionLabel(direction: GridBoundaryDirection): String = stringResource(
    when (direction) {
        GridBoundaryDirection.NORTH -> R.string.gridfinder_direction_north
        GridBoundaryDirection.SOUTH -> R.string.gridfinder_direction_south
        GridBoundaryDirection.EAST -> R.string.gridfinder_direction_east
        GridBoundaryDirection.WEST -> R.string.gridfinder_direction_west
    }
)

@Composable
private fun compassAccuracyRes(accuracy: CompassAccuracy): Int = when (accuracy) {
    CompassAccuracy.HIGH -> R.string.prefs_compass_accuracy_high
    CompassAccuracy.MEDIUM -> R.string.prefs_compass_accuracy_medium
    CompassAccuracy.LOW -> R.string.prefs_compass_accuracy_low
    CompassAccuracy.UNRELIABLE -> R.string.prefs_compass_accuracy_unreliable
    CompassAccuracy.UNAVAILABLE -> R.string.prefs_compass_accuracy_unavailable
}

private fun formatDistance(meters: Double): String {
    return if (abs(meters) < 1000.0) {
        String.format("%.1f m", meters)
    } else {
        String.format("%.2f km", meters / 1000.0)
    }
}

private fun formatAge(epochMs: Long): String {
    val seconds = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0L)
    return if (seconds < 60L) "${seconds}s" else "${seconds / 60L}min"
}

private fun cardinal(bearingDegrees: Double): String {
    val normalized = (bearingDegrees % 360.0 + 360.0) % 360.0
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
    return names[((normalized + 22.5) / 45.0).toInt().coerceIn(0, 8)]
}
