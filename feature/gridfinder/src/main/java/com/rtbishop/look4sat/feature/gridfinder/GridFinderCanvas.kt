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

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.utility.GridBoundaryDirection
import com.rtbishop.look4sat.core.domain.utility.GridGeometry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Compass rose with a needle pointing along the true-north [bearingDegrees].
 * When [headingDegrees] is available the rose turns with the device, so the
 * needle is read against the top edge of the phone ("walk the way the arrow
 * points"); without a magnetometer the rose stays north-up.
 */
@Composable
fun GridCompassDial(
    bearingDegrees: Double,
    headingDegrees: Float?,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val dialColor = MaterialTheme.colorScheme.outline
    // North is a longer, brighter tick; red is reserved for the heading needle
    // in the proximity map, so the legend stays unambiguous.
    val northColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f - 8.dp.toPx()
        if (radius <= 0f) return@Canvas
        val roseRotation = headingDegrees?.let { -it } ?: 0f

        drawCircle(color = dialColor, radius = radius, center = center, style = Stroke(1.5.dp.toPx()))
        drawCircle(
            color = dialColor.copy(alpha = 0.35f),
            radius = radius * 0.62f,
            center = center,
            style = Stroke(1.dp.toPx())
        )

        for (degrees in 0 until 360 step 30) {
            val angle = Math.toRadians((degrees + roseRotation).toDouble())
            val isNorth = degrees == 0
            val innerRadius = radius * if (isNorth) 0.70f else 0.86f
            val start = center.at(innerRadius, angle)
            val end = center.at(radius, angle)
            drawLine(
                color = if (isNorth) northColor else dialColor,
                start = start,
                end = end,
                strokeWidth = if (isNorth) 3.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (degrees, label) ->
            val angle = Math.toRadians((degrees + roseRotation).toDouble())
            val position = center.at(radius * 0.50f, angle)
            val layout = measurer.measure(
                AnnotatedString(label),
                TextStyle(fontSize = 12.sp, color = if (degrees == 0) northColor else labelColor)
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    position.x - layout.size.width / 2f,
                    position.y - layout.size.height / 2f
                )
            )
        }

        val needleAngle = Math.toRadians(bearingDegrees - (headingDegrees ?: 0f))
        val tip = center.at(radius * 0.88f, needleAngle)
        drawLine(
            color = tint,
            start = center,
            end = tip,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = tint, radius = 4.dp.toPx(), center = tip)
        drawCircle(color = tint, radius = 2.5.dp.toPx(), center = center)
    }
}

/**
 * North-up proximity sketch around the fix: dashed lines are the nearest grid
 * boundaries, the green dot is the grid corner, the ring marks the closest
 * point of the nearest line, and the amber needle is the device heading.
 */
@Composable
fun GridProximityMap(
    geometry: GridGeometry,
    headingDegrees: Float?,
    modifier: Modifier = Modifier
) {
    val outline = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    val cornerColor = MaterialTheme.colorScheme.secondary
    val headingColor = MaterialTheme.colorScheme.error
    val youColor = MaterialTheme.colorScheme.onSurface
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) / 2f - 8.dp.toPx()
        if (radius <= 0f) return@Canvas
        val dNorth = when (geometry.latLineDirection) {
            GridBoundaryDirection.NORTH -> geometry.latLineMeters
            else -> -geometry.latLineMeters
        }
        val dEast = when (geometry.lonLineDirection) {
            GridBoundaryDirection.EAST -> geometry.lonLineMeters
            else -> -geometry.lonLineMeters
        }
        val span = max(max(abs(dNorth), abs(dEast)), geometry.cornerMeters)
        val scale = radius / max(span * 1.15, 15.0).toFloat()

        drawCircle(color = outline.copy(alpha = 0.35f), radius = radius, center = center, style = Stroke(1.dp.toPx()))
        drawLine(outline, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx(), pathEffect = dash)
        drawLine(outline, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx(), pathEffect = dash)

        val latY = center.y - (dNorth * scale).toFloat()
        val lonX = center.x + (dEast * scale).toFloat()
        drawLine(lineColor, Offset(center.x - radius, latY), Offset(center.x + radius, latY), 2.dp.toPx())
        drawLine(lineColor, Offset(lonX, center.y - radius), Offset(lonX, center.y + radius), 2.dp.toPx())

        val corner = Offset(lonX, latY)
        drawCircle(color = cornerColor, radius = 5.dp.toPx(), center = corner)

        val nearestPoint = if (geometry.nearestLineIsLatitude) {
            Offset(center.x, latY)
        } else {
            Offset(lonX, center.y)
        }
        drawCircle(color = gridColor, radius = 5.dp.toPx(), center = nearestPoint, style = Stroke(2.dp.toPx()))

        headingDegrees?.let { heading ->
            val angle = Math.toRadians(heading.toDouble())
            val tip = center.at(radius * 0.92f, angle)
            drawLine(headingColor, center, tip, 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(headingColor, 3.dp.toPx(), tip)
        }

        drawCircle(color = youColor, radius = 3.dp.toPx(), center = center)
    }
}

private fun Offset.at(radius: Float, angleRadians: Double): Offset {
    return Offset(
        x + (radius * sin(angleRadians)).toFloat(),
        y - (radius * cos(angleRadians)).toFloat()
    )
}
