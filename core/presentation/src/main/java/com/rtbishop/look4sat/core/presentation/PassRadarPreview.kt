/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import kotlinx.coroutines.CancellationException

/**
 * Compact square radar preview for a single upcoming pass. It deliberately uses the same
 * RadarViewCompose renderer as the full-screen satellite-tracking page, so the preview shares
 * its circular elevation rings, azimuth crosshairs, and predicted azimuth/elevation path while
 * omitting full-size labels and direction arrows. Track samples are calculated on demand.
 */
@Composable
fun PassRadarPreview(
    pass: OrbitalPass,
    satelliteRepo: ISatelliteRepo?,
    stationPosition: GeoPos,
    modifier: Modifier = Modifier
) {
    val observer = remember(stationPosition.latitude, stationPosition.longitude, stationPosition.altitude) {
        GeoPos(stationPosition.latitude, stationPosition.longitude, stationPosition.altitude)
    }
    var positions by remember(
        pass.catNum, pass.aosTime, pass.losTime, pass.orbitalObject, observer
    ) { mutableStateOf(emptyList<OrbitalPos>()) }

    LaunchedEffect(
        pass.catNum, pass.aosTime, pass.losTime, pass.orbitalObject, observer,
        pass.continuousVisibility, satelliteRepo
    ) {
        positions = if (satelliteRepo == null || pass.continuousVisibility || pass.losTime <= pass.aosTime) {
            emptyList()
        } else {
            try {
                satelliteRepo.getRadarTrack(
                    sat = pass.orbitalObject,
                    pos = observer,
                    start = pass.aosTime,
                    end = pass.losTime
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isLightTheme) Modifier
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ),
        contentAlignment = Alignment.Center
    ) {
        RadarViewCompose(
            // The preview shows the predicted path, not a live-position pulse.
            item = OrbitalPos(),
            items = positions,
            azimElev = 0f to 0f,
            shouldShowSweep = false,
            shouldUseCompass = false,
            compactPassPreview = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}
