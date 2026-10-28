// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2019-2026 Arty Bishop and contributors.
package com.rtbishop.look4sat.core.presentation

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.ft4.Ft4SpectrumFrame
import com.rtbishop.look4sat.core.presentation.R
import kotlin.math.roundToInt

@Composable
fun DigitalSpectrumWaterfall(
    renderState: SpectrumRenderState,
    selectedFrequencyHz: Float,
    onFrequencySelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
    descriptionOverride: String? = null
) {
    val description = stringResource(R.string.ft4_receive_content_description)
    val frequencyDescription = stringResource(R.string.ft4_frequency_content_description)
    val combinedDescription = descriptionOverride ?: stringResource(R.string.ft4_spectrum_description, description, frequencyDescription)
    val context = LocalContext.current
    val selectedFrequencyLabel = stringResource(R.string.ft4_frequency_hz, selectedFrequencyHz.roundToInt())
    val path = remember { Path() }
    val rulerPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val version = renderState.version
    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = combinedDescription
            }
            .pointerInput(onFrequencySelected) {
                detectTapGestures { offset ->
                    onFrequencySelected(offset.x / size.width * MAX_FREQUENCY_HZ)
                }
            }
            .pointerInput(onFrequencySelected) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onFrequencySelected(change.position.x / size.width * MAX_FREQUENCY_HZ)
                }
            }
    ) {
        @Suppress("UNUSED_VARIABLE") val redraw = version
        val cyan = Color(0xFF00E5FF)
        val red = Color(0xFFFF3B30)
        val grid = cyan.copy(alpha = 0.22f)
        val rulerHeight = 30.dp.toPx()
        val spectrumBottom = rulerHeight + (size.height - rulerHeight) * SPECTRUM_HEIGHT_FRACTION
        drawRect(Color.Black)

        rulerPaint.color = cyan.toArgb()
        rulerPaint.textSize = 10.sp.toPx()
        rulerPaint.strokeWidth = 1.dp.toPx()
        for (frequency in 0..MAX_FREQUENCY_HZ.toInt() step 100) {
            val x = frequency / MAX_FREQUENCY_HZ * size.width
            val major = frequency % 500 == 0
            val tickTop = if (major) 1.dp.toPx() else rulerHeight * 0.55f
            drawLine(cyan, Offset(x, tickTop), Offset(x, rulerHeight), strokeWidth = if (major) 2f else 1f)
            if (major) {
                val label = context.getString(R.string.ft4_frequency_hz, frequency)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(label, x, 11.sp.toPx(), rulerPaint)
                }
                drawLine(grid, Offset(x, rulerHeight), Offset(x, size.height), strokeWidth = 1f)
            }
        }

        val selectedX = selectedFrequencyHz.coerceIn(0f, MAX_FREQUENCY_HZ) / MAX_FREQUENCY_HZ * size.width
        val markerWidth = size.width * FT4_MARKER_WIDTH_HZ / MAX_FREQUENCY_HZ
        drawRect(
            color = red.copy(alpha = 0.45f),
            topLeft = Offset(selectedX - markerWidth / 2f, 0f),
            size = androidx.compose.ui.geometry.Size(markerWidth, rulerHeight)
        )
        path.reset()
        val frame = renderState.frame()
        val magnitudes = frame?.magnitudesDb
        if (magnitudes != null && magnitudes.isNotEmpty()) {
            magnitudes.forEachIndexed { index, db ->
                val x = index * frame.frequencyStepHz / MAX_FREQUENCY_HZ * size.width
                val normalized = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
                val y = spectrumBottom - normalized * (spectrumBottom - rulerHeight)
                path.moveTo(x, spectrumBottom)
                path.lineTo(x, y)
            }
            drawPath(path, cyan, style = Stroke(width = 1f))
        }
        drawLine(cyan, Offset(0f, rulerHeight), Offset(size.width, rulerHeight), strokeWidth = 2f)
        drawLine(cyan, Offset(0f, spectrumBottom), Offset(size.width, spectrumBottom), strokeWidth = 2f)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(
                renderState.waterfall.bitmap,
                null,
                android.graphics.RectF(0f, spectrumBottom, size.width, size.height),
                renderState.waterfall.paint
            )
        }
        drawLine(cyan, Offset(selectedX, rulerHeight), Offset(selectedX, size.height), strokeWidth = 2f)
        rulerPaint.color = cyan.toArgb()
        rulerPaint.textSize = 11.sp.toPx()
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(
                selectedFrequencyLabel,
                selectedX.coerceIn(34.dp.toPx(), size.width - 34.dp.toPx()),
                size.height - 5.dp.toPx(),
                rulerPaint
            )
        }
    }
}

class SpectrumRenderState {
    val waterfall = WaterfallBitmap()
    private var latestFrame: Ft4SpectrumFrame? = null
    var version by mutableIntStateOf(0)
        private set

    fun update(frame: Ft4SpectrumFrame) {
        latestFrame = frame
        waterfall.add(frame.magnitudesDb, frame.frequencyStepHz)
        version++
    }

    fun frame(): Ft4SpectrumFrame? {
        @Suppress("UNUSED_VARIABLE") val currentVersion = version
        return latestFrame
    }
}

@SuppressLint("UseKtx")
class WaterfallBitmap(
    private val width: Int = 513,
    private val height: Int = 120
) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(width * height)
    private val palette = IntArray(256) { index ->
        val level = index / 255f
        val red = (255 * (level * 1.7f - 0.7f).coerceIn(0f, 1f)).toInt()
        val green = (255 * (1f - kotlin.math.abs(level - 0.55f) * 2f).coerceIn(0f, 1f)).toInt()
        val blue = (255 * (1.2f - level * 1.4f).coerceIn(0f, 1f)).toInt()
        android.graphics.Color.rgb(red, green, blue)
    }

    fun add(magnitudesDb: FloatArray, frequencyStepHz: Float = MAX_FREQUENCY_HZ / (magnitudesDb.size - 1).coerceAtLeast(1)) {
        if (magnitudesDb.isEmpty() || frequencyStepHz <= 0f) return
        System.arraycopy(pixels, 0, pixels, width, width * (height - 1))
        for (x in 0 until width) {
            val source = (x * MAX_FREQUENCY_HZ / (width - 1) / frequencyStepHz).roundToInt()
            val normalized = ((magnitudesDb.getOrElse(source) { MIN_DB } - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
            pixels[x] = palette[(normalized * 255).roundToInt()]
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private const val MAX_FREQUENCY_HZ = 3_000f
private const val MIN_DB = -100f
private const val MAX_DB = 0f
private const val SPECTRUM_HEIGHT_FRACTION = 0.18f
private const val FT4_MARKER_WIDTH_HZ = 100f
