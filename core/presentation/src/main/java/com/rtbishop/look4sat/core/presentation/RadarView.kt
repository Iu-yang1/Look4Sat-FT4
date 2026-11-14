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
package com.rtbishop.look4sat.core.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StampedPathEffectStyle
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.predict.CelestialState
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.predict.PI_2
import com.rtbishop.look4sat.core.domain.rotator.RotatorPosition
import com.rtbishop.look4sat.core.domain.utility.toRadians
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val CIRCLES = 3
private const val STROKE_WIDTH = 6f
private const val SWEEP_DURATION_MS = 8_000

@Composable
fun RadarViewCompose(
    item: OrbitalPos,
    items: List<OrbitalPos>,
    trackB: List<OrbitalPos>? = null,
    trackBColor: Color = MaterialTheme.colorScheme.tertiary,
    trackBPosition: OrbitalPos? = null,
    azimElev: Pair<Float, Float>,
    shouldShowSweep: Boolean,
    shouldUseCompass: Boolean,
    modifier: Modifier = Modifier,
    sunPosition: CelestialState.SunPosition? = null,
    moonPosition: CelestialState.MoonPosition? = null,
    rotatorPosition: RotatorPosition? = null,
    compactPassPreview: Boolean = false,
) {
    val aimColor = MaterialTheme.colorScheme.error
    val primaryColor = MaterialTheme.colorScheme.primary
    val radarColor = MaterialTheme.colorScheme.secondary
    val rotatorColor = MaterialTheme.colorScheme.tertiary
    val sunColor = MaterialTheme.colorScheme.primary
    val hasPulse = (item.elevation > 0 && !item.eclipsed) ||
        (trackBPosition?.let { it.elevation > 0 && !it.eclipsed } == true)
    val animScale = if (hasPulse) rememberInfiniteTransition(label = "animScale").animateFloat(
        initialValue = 16f,
        targetValue = 64f,
        animationSpec = infiniteRepeatable(tween(1000)),
        label = "animScale"
    ) else rememberUpdatedState(16f)
    // Drive the sweep from the animation framework to eliminate state mutation inside the draw block
    val sweepDegrees = if (shouldShowSweep) rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SWEEP_DURATION_MS, easing = LinearEasing)),
        label = "sweepDegrees"
    ) else rememberUpdatedState(0f)
    val measurer = rememberTextMeasurer()
    val sunPainter = painterResource(R.drawable.ic_sun)
    val moonPainter = painterResource(R.drawable.ic_moon)
    // Track path cache — keyed by both canvas size and items reference so it rebuilds
    // when the satellite track data arrives asynchronously after the first composition
    val geometry = remember(items, trackB) { RadarGeometry(items, trackB) }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val radius = size.minDimension / 2f * 0.95f
        // Rebuild track path and sweep brush when canvas size or track data changes
        geometry.prepare(radius, 4.dp.toPx())
        val trackPath = geometry.path
        val trackEffect = geometry.effect
        val trackBPath = geometry.pathB
        val trackBEffect = geometry.effectB
        rotate(if (shouldUseCompass) -azimElev.first else 0f) {
            if (shouldShowSweep) drawSweep(center, sweepDegrees.value, radius, geometry.brush(center, primaryColor))
            val radarStrokeWidth = if (compactPassPreview) 1.0.dp.toPx() else STROKE_WIDTH
            drawRadar(radius, radarColor, radarStrokeWidth)
            if (!compactPassPreview) drawElevationLabels(radius, primaryColor, measurer)
            translate(center.x, center.y) {
                if (compactPassPreview) {
                    drawPassPreviewTrack(geometry, primaryColor)
                } else {
                    drawTrack(trackPath, trackEffect, aimColor, primaryColor)
                }
                // Station-B overlay: full dashed track + directional arrows + live position dot
                // (same display mode as the local station: track line/arrow + pulsing dot)
                if (trackB != null && trackB.isNotEmpty() && !trackBPath.isEmpty) {
                    drawPath(
                        trackBPath, trackBColor,
                        style = Stroke(STROKE_WIDTH, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
                    )
                    drawPath(trackBPath, trackBColor, style = Stroke(pathEffect = trackBEffect))
                }
                trackBPosition?.let { posB ->
                    if (posB.elevation > 0) {
                        drawPosition(posB, radius, animScale.value, trackBColor)
                    }
                }
                if (item.elevation > 0) {
                    drawPosition(item, radius, animScale.value, primaryColor)
                }
                sunPosition?.let { sun ->
                    if (sun.elevation > 0) drawBodyIcon(sun.azimuth, sun.elevation, radius, sunColor, sunPainter, 52f)
                }
                moonPosition?.let { moon ->
                    if (moon.elevation > 0) drawBodyIcon(
                        moon.azimuth,
                        moon.elevation,
                        radius,
                        radarColor,
                        moonPainter,
                        52f
                    )
                }
                rotatorPosition?.let { position ->
                    drawRotatorAim(position, radius, rotatorColor)
                }
                if (shouldUseCompass) drawAim(azimElev.first, azimElev.second, radius, aimColor)
            }
        }
    }
}

/** Drawing caches are ordinary objects: writing them must not schedule recomposition. */
private class RadarGeometry(val items: List<OrbitalPos>, val itemsB: List<OrbitalPos>?) {
    var radius = Float.NaN
    var inset = Float.NaN
    var path = Path()
    var pathB = Path()
    var effect = PathEffect.cornerPathEffect(0f)
    var effectB = PathEffect.cornerPathEffect(0f)
    var start = Offset.Zero
    var end = Offset.Zero
    private var brushCenter = Offset.Unspecified
    private var brushColor = Color.Unspecified
    private var sweepBrush: ShaderBrush? = null

    fun prepare(nextRadius: Float, endpointInset: Float) {
        if (radius == nextRadius && inset == endpointInset) return
        radius = nextRadius
        inset = endpointInset
        path = createTrackPath(items, radius)
        effect = if (path.isEmpty) PathEffect.cornerPathEffect(0f) else createTrackEffect(path)
        pathB = itemsB?.let { createTrackPath(it, radius) } ?: Path()
        effectB = if (pathB.isEmpty) PathEffect.cornerPathEffect(0f) else createTrackEffect(pathB)
        fun point(index: Int) = items[index].let {
            sph2Cart(it.azimuth, it.elevation, radius.toDouble())
        }
        if (items.isNotEmpty()) {
            start = insetAlongTrack(point(0), point(minOf(1, items.lastIndex)), endpointInset)
            end = insetAlongTrack(point(items.lastIndex), point(maxOf(0, items.lastIndex - 1)), endpointInset)
        }
    }
    fun brush(center: Offset, color: Color): ShaderBrush {
        if (sweepBrush == null || center != brushCenter || color != brushColor) {
            sweepBrush = makeSweepBrush(center, color)
            brushCenter = center
            brushColor = color
        }
        return requireNotNull(sweepBrush)
    }
}

private fun DrawScope.drawRadar(radius: Float, color: Color, strokeWidth: Float = STROKE_WIDTH) {
    val step = radius / CIRCLES
    for (i in 0 until CIRCLES) {
        drawCircle(color, radius - step * i, style = Stroke(strokeWidth))
    }
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth)
}

private fun DrawScope.drawElevationLabels(radius: Float, color: Color, measurer: TextMeasurer) {
    val step = radius / CIRCLES
    val degStep = 90 / CIRCLES
    val style = TextStyle(color, 15.sp)
    for (i in 0 until CIRCLES) {
        val textY = (radius - step * i) - 32f
        drawText(measurer, " ${degStep * (CIRCLES - i)}°", Offset(center.x, textY), style = style)
    }
}

private fun DrawScope.drawTrack(path: Path, effect: PathEffect, color: Color, effectColor: Color) {
    drawPath(path, color, style = Stroke(STROKE_WIDTH))
    drawPath(path, effectColor, style = Stroke(pathEffect = effect))
}

/** Compact list preview: thin path with AOS (green) and LOS (red) endpoint dots, no arrows. */
private fun DrawScope.drawPassPreviewTrack(
    geometry: RadarGeometry,
    trackColor: Color,
) {
    if (geometry.items.isEmpty()) return

    // Keep the actual track identical to the full radar geometry. Only inset the
    // endpoint dots a short distance along the first/last track segments so they
    // remain visible when AOS or LOS is close to the radar edge.
    val path = geometry.path
    if (!path.isEmpty) {
        drawPath(path, trackColor, style = Stroke(width = 0.9.dp.toPx()))
    }

    val endpointRadius = 1.5.dp.toPx()
    drawCircle(Color(0xFF2E7D32), endpointRadius, geometry.start)
    drawCircle(Color(0xFFD32F2F), endpointRadius, geometry.end)
}

/** Move a marker from an endpoint toward its adjacent sample without changing the track. */
private fun insetAlongTrack(endpoint: Offset, toward: Offset, distance: Float): Offset {
    val dx = toward.x - endpoint.x
    val dy = toward.y - endpoint.y
    val segmentLength = sqrt(dx * dx + dy * dy)
    if (segmentLength <= 0.001f) return endpoint

    val inset = distance.coerceAtMost(segmentLength * 0.5f)
    return Offset(
        x = endpoint.x + dx / segmentLength * inset,
        y = endpoint.y + dy / segmentLength * inset
    )
}

private fun DrawScope.drawPosition(item: OrbitalPos, radius: Float, posRadius: Float, color: Color) {
    val pos = sph2Cart(item.azimuth, item.elevation, radius.toDouble())
    drawCircle(color, 16f, pos)
    if (!item.eclipsed) drawCircle(color.copy(alpha = 1 - (posRadius / 64f)), posRadius, pos)
}

private fun DrawScope.drawAim(azim: Float, elev: Float, radius: Float, color: Color) {
    val azimRad = azim.toDouble().toRadians()
    val elevRad = elev.toDouble().toRadians().coerceAtMost(0.0)
    val pos = sph2Cart(azimRad, -elevRad, radius.toDouble())
    drawAimMarker(pos, color)
}

private fun DrawScope.drawRotatorAim(position: RotatorPosition, radius: Float, color: Color) {
    val pos = sph2Cart(
        position.azimuthDegrees.toRadians(),
        position.elevationDegrees.toRadians(),
        radius.toDouble()
    )
    drawAimMarker(pos, color)
}

private fun DrawScope.drawAimMarker(pos: Offset, color: Color) {
    val size = 36f
    drawLine(color, Offset(pos.x - size, pos.y), Offset(pos.x + size, pos.y), STROKE_WIDTH)
    drawLine(color, Offset(pos.x, pos.y - size), Offset(pos.x, pos.y + size), STROKE_WIDTH)
    drawCircle(color, size / 2, pos, style = Stroke(STROKE_WIDTH))
}

private fun makeSweepBrush(center: Offset, color: Color): ShaderBrush {
    val colors = listOf(Color.Transparent, color.copy(alpha = 0.5f), color)
    val colorStops = listOf(0.64f, 0.995f, 1f)
    return ShaderBrush(SweepGradientShader(center, colors, colorStops))
}

private fun DrawScope.drawSweep(center: Offset, degrees: Float, radius: Float, brush: ShaderBrush) {
    rotate(-90 + degrees, center) { drawCircle(brush, radius, style = Fill) }
}

private fun createTrackPath(positions: List<OrbitalPos>, radius: Float): Path {
    val trackPath = Path()
    var lastAzim: Double? = null
    positions.forEachIndexed { index, pos ->
        val offset = sph2Cart(pos.azimuth, pos.elevation, radius.toDouble())
        // Split the path when azimuth wraps 0°/360° to avoid a line across the plot
        val wrap = lastAzim != null && abs(azimuthDeltaRad(pos.azimuth - lastAzim!!)) > PI
        if (index == 0 || wrap) trackPath.moveTo(offset.x, offset.y) else trackPath.lineTo(offset.x, offset.y)
        lastAzim = pos.azimuth
    }
    return trackPath
}

/** Normalize an azimuth delta (radians) into the [-PI, PI] range. */
private fun azimuthDeltaRad(deltaRad: Double): Double {
    var d = deltaRad % (2 * PI)
    if (d > PI) d -= 2 * PI
    if (d < -PI) d += 2 * PI
    return d
}

private fun createTrackEffect(trackPath: Path): PathEffect {
    // Pass previews can render before their samples load, and deep-space passes have no
    // local azimuth/elevation track. Avoid constructing a stamped effect with zero spacing.
    if (trackPath.isEmpty) return PathEffect.cornerPathEffect(0f)

    val shapeRadius = 24f
    val angle = 120.0.toRadians()
    val shape = Path().apply {
        moveTo((shapeRadius * cos(angle)).toFloat(), (shapeRadius * sin(angle)).toFloat())
        for (i in 1 until 3) {
            lineTo(
                x = (shapeRadius * cos(angle - angle * i)).toFloat(),
                y = (shapeRadius * sin(angle - angle * i)).toFloat()
            )
        }
        close()
    }
    val trackLength = PathMeasure().apply { setPath(trackPath, false) }.length
    return PathEffect.stampedPathEffect(shape, trackLength / 2f, trackLength / 4f, StampedPathEffectStyle.Rotate)
}

private fun DrawScope.drawBodyIcon(
    azimDeg: Double,
    elevDeg: Double,
    radius: Float,
    color: Color,
    painter: Painter,
    iconSize: Float
) {
    val azimRad = azimDeg.toRadians()
    val elevRad = elevDeg.toRadians()
    val pos = sph2Cart(azimRad, elevRad, radius.toDouble())
    val half = iconSize / 2f
    withTransform({
        translate(pos.x - half, pos.y - half)
    }) {
        with(painter) {
            draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(color))
        }
    }
}

private fun sph2Cart(azim: Double, elev: Double, r: Double): Offset {
    val radius = r * (PI_2 - elev) / PI_2
    return Offset(
        x = (radius * cos(PI_2 - azim)).toFloat(),
        y = -(radius * sin(PI_2 - azim)).toFloat()
    )
}
