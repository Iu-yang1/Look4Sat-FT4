/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.ft4

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.ft4.Ft4SpectrumFrame
import com.rtbishop.look4sat.core.presentation.R
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun Ft4SpectrumPage(
    state: Ft4State,
    viewModel: Ft4ViewModel,
    onAction: (Ft4Action) -> Unit,
    requestMicrophone: () -> Unit
) {
    val timing by viewModel.timingState.collectAsStateWithLifecycle()
    val renderState = remember { SpectrumRenderState() }
    LaunchedEffect(viewModel) {
        viewModel.spectrumFrames.collectLatest(renderState::update)
    }
    val frame = renderState.frame()
    val inputLevel = frame?.inputLevelDb ?: -120f
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ElevatedCard(modifier = Modifier.weight(1f)) {
            SpectrumWaterfall(
                renderState = renderState,
                selectedFrequencyHz = state.selectedAudioFrequencyHz,
                onFrequencySelected = { onAction(Ft4Action.SelectAudioFrequency(it)) },
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ft4_spectrum_range))
                    Text(stringResource(R.string.ft4_input_level, inputLevel))
                    Text(stringResource(R.string.ft4_selected_frequency, state.selectedAudioFrequencyHz))
                }
                LinearProgressIndicator(
                    progress = { timing.slotProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ft4_slot_progress, (timing.slotProgress * 100).roundToInt()), fontSize = 12.sp)
                    Text(
                        stringResource(
                            R.string.ft4_time_source,
                            clockSourceLabel(timing.clock.source),
                            timing.clock.uncertaintyMillis
                        ),
                        fontSize = 12.sp
                    )
                }
                Text(audioOwnerText(state.audioHub), fontSize = 12.sp)
                if (!state.hasMicrophonePermission) {
                    Text(stringResource(R.string.ft4_microphone_required), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        if (state.hasMicrophonePermission) onAction(Ft4Action.ToggleReceiving)
                        else requestMicrophone()
                    },
                    enabled = state.settings.decodeEnabled && state.capability.receiveAvailable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (state.isReceiving) R.string.ft4_stop_receive else R.string.ft4_start_receive
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SpectrumWaterfall(
    renderState: SpectrumRenderState,
    selectedFrequencyHz: Float,
    onFrequencySelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val spectrumColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.surface
    val description = stringResource(R.string.ft4_receive_content_description)
    val frequencyDescription = stringResource(R.string.ft4_frequency_content_description)
    val combinedDescription = stringResource(R.string.ft4_spectrum_description, description, frequencyDescription)
    val path = remember { Path() }
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
        drawRect(background)
        val spectrumHeight = size.height * SPECTRUM_HEIGHT_FRACTION
        repeat(4) { index ->
            val x = size.width * index / 3f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        path.reset()
        val frame = renderState.frame()
        val magnitudes = frame?.magnitudesDb
        if (magnitudes != null && magnitudes.isNotEmpty()) {
            magnitudes.forEachIndexed { index, db ->
                val x = index.toFloat() / (magnitudes.size - 1).coerceAtLeast(1) * size.width
                val normalized = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
                val y = spectrumHeight * (1f - normalized)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, spectrumColor, style = Stroke(width = 2f))
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(
                renderState.waterfall.bitmap,
                null,
                android.graphics.RectF(0f, spectrumHeight, size.width, size.height),
                renderState.waterfall.paint
            )
        }
        val selectedX = selectedFrequencyHz.coerceIn(0f, MAX_FREQUENCY_HZ) / MAX_FREQUENCY_HZ * size.width
        drawLine(selectedColor, Offset(selectedX, 0f), Offset(selectedX, size.height), strokeWidth = 3f)
    }
}

internal class SpectrumRenderState {
    val waterfall = WaterfallBitmap()
    private var latestFrame: Ft4SpectrumFrame? = null
    var version by mutableIntStateOf(0)
        private set

    fun update(frame: Ft4SpectrumFrame) {
        latestFrame = frame
        waterfall.add(frame.magnitudesDb)
        version++
    }

    fun frame(): Ft4SpectrumFrame? {
        @Suppress("UNUSED_VARIABLE") val currentVersion = version
        return latestFrame
    }
}

@SuppressLint("UseKtx")
internal class WaterfallBitmap(
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

    fun add(magnitudesDb: FloatArray) {
        System.arraycopy(pixels, 0, pixels, width, width * (height - 1))
        for (x in 0 until width) {
            val source = x * magnitudesDb.size / width
            val normalized = ((magnitudesDb[source] - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
            pixels[x] = palette[(normalized * 255).roundToInt()]
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

@Composable
internal fun clockSourceLabel(source: com.rtbishop.look4sat.core.domain.time.ClockSource): String =
    stringResource(
        when (source) {
            com.rtbishop.look4sat.core.domain.time.ClockSource.SYSTEM -> R.string.ft4_clock_system
            com.rtbishop.look4sat.core.domain.time.ClockSource.NTP -> R.string.ft4_clock_ntp
            com.rtbishop.look4sat.core.domain.time.ClockSource.GNSS -> R.string.ft4_clock_gnss
            com.rtbishop.look4sat.core.domain.time.ClockSource.HOLDOVER -> R.string.ft4_clock_holdover
        }
    )

@Composable
private fun audioOwnerText(state: AudioHubState): String = when (state) {
    AudioHubState.Idle -> stringResource(R.string.ft4_audio_owner_idle)
    is AudioHubState.Capturing -> stringResource(
        R.string.ft4_audio_owner,
        state.sampleRate,
        state.consumers.joinToString { it.name }
    )
    is AudioHubState.Failed -> stringResource(R.string.ft4_error, state.reason)
}

private const val MAX_FREQUENCY_HZ = 3_000f
private const val MIN_DB = -100f
private const val MAX_DB = 0f
private const val SPECTRUM_HEIGHT_FRACTION = 0.42f
