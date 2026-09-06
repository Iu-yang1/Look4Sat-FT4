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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.audio.AudioSampleFormat
import com.rtbishop.look4sat.core.domain.ft4.Ft4EngineState
import com.rtbishop.look4sat.core.domain.ft4.Ft4SpectrumFrame
import com.rtbishop.look4sat.core.presentation.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun Ft4SpectrumPage(
    state: Ft4State,
    viewModel: Ft4ViewModel,
    onAction: (Ft4Action) -> Unit,
    requestMicrophone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val renderState = remember { SpectrumRenderState() }
    val shouldCollectSpectrum = state.settings.decodeEnabled &&
        state.hasMicrophonePermission &&
        state.capability.receiveAvailable &&
        state.isReceiving
    LaunchedEffect(viewModel, shouldCollectSpectrum) {
        if (shouldCollectSpectrum) {
            viewModel.spectrumFrames.collectLatest(renderState::update)
        }
    }
    val frame = renderState.frame()
    val inputLevel = frame?.inputLevelDb ?: -120f
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth().height(380.dp)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpectrumWaterfall(
                    renderState = renderState,
                    selectedFrequencyHz = state.selectedAudioFrequencyHz,
                    onFrequencySelected = { onAction(Ft4Action.SelectAudioFrequency(it)) },
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
                spectrumBlockingMessage(state, frame == null)?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ft4_spectrum_range), fontSize = 11.sp)
                    Text(stringResource(R.string.ft4_input_level, inputLevel), fontSize = 11.sp)
                    Text(stringResource(R.string.ft4_selected_frequency, state.selectedAudioFrequencyHz), fontSize = 11.sp)
                }
                Text(
                    audioOwnerText(state.audioHub),
                    fontSize = 11.sp,
                    color = if (state.audioHub.isUnavailable()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                ReceiveDiagnostics(state.engineState)
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
                            when {
                                !state.hasMicrophonePermission -> R.string.ft4_grant_microphone
                                state.isReceiving -> R.string.ft4_stop_receive
                                else -> R.string.ft4_start_receive
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun spectrumBlockingMessage(state: Ft4State, hasNoFrame: Boolean): String? = when {
    !state.settings.decodeEnabled -> stringResource(R.string.ft4_status_disabled)
    !state.capability.receiveAvailable -> stringResource(
        R.string.ft4_status_unavailable,
        state.capability.unavailableReason
    )
    !state.hasMicrophonePermission -> stringResource(R.string.ft4_microphone_required)
    state.audioHub is AudioHubState.Failed -> stringResource(R.string.ft4_error, state.audioHub.reason)
    hasNoFrame -> stringResource(R.string.ft4_waiting_audio)
    else -> null
}

@Composable
private fun SpectrumWaterfall(
    renderState: SpectrumRenderState,
    selectedFrequencyHz: Float,
    onFrequencySelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.ft4_receive_content_description)
    val frequencyDescription = stringResource(R.string.ft4_frequency_content_description)
    val combinedDescription = stringResource(R.string.ft4_spectrum_description, description, frequencyDescription)
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
                val label = if (frequency == 0) "0Hz" else "${frequency}Hz"
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
                val x = index.toFloat() / (magnitudes.size - 1).coerceAtLeast(1) * size.width
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
                "${selectedFrequencyHz.roundToInt()} Hz",
                selectedX.coerceIn(34.dp.toPx(), size.width - 34.dp.toPx()),
                size.height - 5.dp.toPx(),
                rulerPaint
            )
        }
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
    is AudioHubState.Capturing -> if (state.systemSilenced) {
        stringResource(R.string.ft4_audio_system_silenced)
    } else {
        stringResource(
            R.string.ft4_audio_owner,
            state.deviceName.ifBlank { stringResource(R.string.ft4_audio_device_unknown) },
            state.sampleRate,
            stringResource(
                when (state.format) {
                    AudioSampleFormat.PCM_FLOAT -> R.string.ft4_audio_pcm_float
                    AudioSampleFormat.PCM_16 -> R.string.ft4_audio_pcm_16
                }
            )
        )
    }
    is AudioHubState.Failed -> stringResource(R.string.ft4_error, state.reason)
}

private fun AudioHubState.isUnavailable(): Boolean =
    this is AudioHubState.Failed || (this is AudioHubState.Capturing && systemSilenced)

@Composable
private fun ReceiveDiagnostics(engineState: Ft4EngineState) {
    val receiving = engineState as? Ft4EngineState.Receiving ?: return
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = receiving.timestampResidualMillis?.let {
            stringResource(R.string.ft4_timestamp_residual, it)
        } ?: stringResource(R.string.ft4_timestamp_residual_pending),
        color = detailColor,
        fontSize = 11.sp
    )
    Text(
        text = stringResource(
            R.string.ft4_slot_diagnostics,
            receiving.assemblingSlotUtcMillis?.let(::formatSlotUtc)
                ?: stringResource(R.string.ft4_slot_pending),
            receiving.assembledSampleCount
        ),
        color = detailColor,
        fontSize = 11.sp
    )
    Text(
        text = stringResource(
            R.string.ft4_pipeline_diagnostics,
            receiving.captureQueueDepth,
            receiving.decodeQueueDepth,
            receiving.droppedAudioBlocks
        ),
        color = if (receiving.droppedAudioBlocks > 0L) MaterialTheme.colorScheme.error else detailColor,
        fontSize = 11.sp
    )
    Text(
        text = receiving.lastDecodeDurationMillis?.let {
            stringResource(R.string.ft4_decode_diagnostics, it, receiving.lastDecodeResultCount)
        } ?: stringResource(R.string.ft4_decode_diagnostics_pending),
        color = detailColor,
        fontSize = 11.sp
    )
}

private fun formatSlotUtc(millis: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))

private const val MAX_FREQUENCY_HZ = 3_000f
private const val MIN_DB = -100f
private const val MAX_DB = 0f
private const val SPECTRUM_HEIGHT_FRACTION = 0.18f
private const val FT4_MARKER_WIDTH_HZ = 100f
