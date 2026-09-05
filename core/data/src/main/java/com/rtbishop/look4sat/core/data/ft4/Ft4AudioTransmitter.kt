/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.ft4

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmitState
import com.rtbishop.look4sat.core.domain.ft4.Ft4PlaybackTiming
import com.rtbishop.look4sat.core.domain.ft4.Ft4TransmissionRequest
import com.rtbishop.look4sat.core.domain.ft4.IFt4AudioTransmitter
import com.rtbishop.look4sat.core.domain.ft4.IFt4Service
import com.rtbishop.look4sat.core.domain.ft4.IFt4TransmitCoordinator
import com.rtbishop.look4sat.core.domain.ft4.TxLease
import com.rtbishop.look4sat.core.domain.ft4.TxRequest
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Ft4AudioTransmitter(
    context: Context?,
    private val ft4Service: IFt4Service,
    private val coordinator: IFt4TransmitCoordinator,
    private val clock: IDisciplinedClock,
    outputFactory: Ft4AudioOutputFactory? = null
) : IFt4AudioTransmitter {
    private val outputFactory = outputFactory ?: AndroidFt4AudioOutputFactory(requireNotNull(context))
    private val executionMutex = Mutex()
    private val mutableState = MutableStateFlow<Ft4TransmitState>(Ft4TransmitState.Idle)
    private val mutablePlaybackTiming = MutableStateFlow(Ft4PlaybackTiming())
    @Volatile private var activeJob: Job? = null

    override val state: StateFlow<Ft4TransmitState> = mutableState.asStateFlow()
    override val playbackTiming: StateFlow<Ft4PlaybackTiming> = mutablePlaybackTiming.asStateFlow()

    override suspend fun transmit(request: Ft4TransmissionRequest) = executionMutex.withLock {
        check(activeJob == null) { "FT4 transmitter is already active" }
        activeJob = coroutineContext[Job]
        var lease: TxLease? = null
        var output: Ft4AudioOutput? = null
        try {
            require(request.audioFrequencyHz in 0f..3_000f) { "FT4 audio frequency is outside 0-3000 Hz" }
            require(request.volume in 0f..1f) { "FT4 output volume is outside 0-1" }
            checkAutomaticClock(request)
            mutableState.value = Ft4TransmitState.Preparing(request.message, request.slotStartUtcMillis)
            val waveform = ft4Service.generateWaveform(
                message = request.message,
                audioFrequencyHz = request.audioFrequencyHz,
                outputSampleRate = OUTPUT_SAMPLE_RATE
            )
            check(waveform.size == EXPECTED_WAVEFORM_SAMPLES) { "Unexpected FT4 waveform length" }
            check(waveform.all(Float::isFinite)) { "FT4 waveform contains invalid samples" }
            output = outputFactory.create(OUTPUT_SAMPLE_RATE, request.volume)
            output.prepare(waveform)

            waitUntil(request, request.slotStartUtcMillis - coordinator.recommendedPrepareLeadMillis())
            checkAutomaticClock(request)
            lease = coordinator.beginTransmit(
                TxRequest(
                    sessionGeneration = request.sessionGeneration,
                    waveformStartUtcMillis = request.slotStartUtcMillis,
                    waveformDurationMillis = WAVEFORM_DURATION_MILLIS,
                    expectedSatelliteCatalogNumber = request.satelliteCatalogNumber,
                    expectedTransponderUuid = request.transponderUuid
                )
            )
            checkAutomaticClock(request)
            coordinator.confirmTransmitReady(lease)
            waitUntil(request, request.slotStartUtcMillis)
            checkAutomaticClock(request)
            check(clock.nowMillis() <= request.slotStartUtcMillis + MAX_START_LATENESS_MILLIS) {
                "FT4 transmit slot was missed"
            }
            mutableState.value = Ft4TransmitState.Transmitting(request.message, lease)
            val playCallErrorMillis = clock.nowMillis() - request.slotStartUtcMillis
            val playback = output.play(waveform)
            mutablePlaybackTiming.value = Ft4PlaybackTiming(
                requestedSlotUtcMillis = request.slotStartUtcMillis,
                startErrorMillis = playback.startDelayMillis?.plus(playCallErrorMillis),
                underrunCount = playback.underrunCount
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = Ft4TransmitState.Failed(error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            withContext(NonCancellable) {
                runCatching { output?.close() }
                val released = runCatching {
                    if (lease != null) coordinator.endTransmit(lease) else coordinator.emergencyPttOff()
                }.isSuccess
                if (!released) runCatching { coordinator.emergencyPttOff() }
            }
            activeJob = null
            if (mutableState.value !is Ft4TransmitState.Failed) {
                mutableState.value = Ft4TransmitState.Idle
            }
        }
    }

    override suspend fun stop() {
        val job = activeJob
        if (job != null && job != currentCoroutineContext()[Job]) job.cancelAndJoin()
        coordinator.emergencyPttOff()
        mutableState.value = Ft4TransmitState.Idle
    }

    override suspend fun emergencyStop() {
        activeJob?.cancelAndJoin()
        coordinator.emergencyPttOff()
        mutableState.value = Ft4TransmitState.Idle
    }

    private suspend fun waitUntil(request: Ft4TransmissionRequest, utcMillis: Long) {
        while (true) {
            currentCoroutineContext().ensureActive()
            checkAutomaticClock(request)
            val remaining = utcMillis - clock.nowMillis()
            if (remaining <= 0L) return
            mutableState.value = Ft4TransmitState.Waiting(request.message, remaining)
            delay(remaining.coerceAtMost(WAIT_UPDATE_MILLIS))
        }
    }

    private fun checkAutomaticClock(request: Ft4TransmissionRequest) {
        if (request.automatic) {
            check(clock.automaticFt4TransmitAllowed()) {
                clock.automaticFt4TransmitBlockReason()
            }
        }
    }

    private companion object {
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val EXPECTED_WAVEFORM_SAMPLES = 241_920
        const val WAVEFORM_DURATION_MILLIS = 5_040L
        const val MAX_START_LATENESS_MILLIS = 150L
        const val WAIT_UPDATE_MILLIS = 100L
    }
}

fun interface Ft4AudioOutputFactory {
    fun create(sampleRate: Int, volume: Float): Ft4AudioOutput
}

interface Ft4AudioOutput : AutoCloseable {
    suspend fun prepare(samples: FloatArray) = Unit
    suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult
}

data class Ft4AudioPlaybackResult(val startDelayMillis: Double?, val underrunCount: Int)

private class AndroidFt4AudioOutputFactory(context: Context) : Ft4AudioOutputFactory {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun create(sampleRate: Int, volume: Float): Ft4AudioOutput =
        AndroidFt4AudioOutput(audioManager, sampleRate, volume)
}

private class AndroidFt4AudioOutput(
    private val audioManager: AudioManager,
    private val sampleRate: Int,
    private val volume: Float
) : Ft4AudioOutput {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var preparedCount = 0
    private var usingFloat = false

    override suspend fun prepare(samples: FloatArray) = withContext(Dispatchers.IO) {
        check(requestAudioFocus()) { "Audio focus was denied" }
        val floatTrack = buildTrack(AudioFormat.ENCODING_PCM_FLOAT)
        if (floatTrack != null) {
            track = floatTrack
            usingFloat = true
            preparedCount = writeFloatChunk(floatTrack, samples, 0, PRELOAD_SAMPLES)
        } else {
            val pcm16Track = checkNotNull(buildTrack(AudioFormat.ENCODING_PCM_16BIT)) {
                "AudioTrack could not be initialized"
            }
            track = pcm16Track
            usingFloat = false
            preparedCount = writePcm16Chunk(pcm16Track, samples, 0, PRELOAD_SAMPLES)
        }
        check(preparedCount > 0) { "AudioTrack preload failed" }
    }

    override suspend fun play(samples: FloatArray): Ft4AudioPlaybackResult = withContext(Dispatchers.IO) {
        if (track == null) prepare(samples)
        val audioTrack = checkNotNull(track)
        val playRequestNanos = System.nanoTime()
        audioTrack.play()
        var offset = preparedCount
        while (offset < samples.size) {
            currentCoroutineContext().ensureActive()
            val written = if (usingFloat) {
                writeFloatChunk(audioTrack, samples, offset, WRITE_CHUNK_SAMPLES)
            } else {
                writePcm16Chunk(audioTrack, samples, offset, WRITE_CHUNK_SAMPLES)
            }
            check(written > 0) { "AudioTrack write failed: $written" }
            offset += written
        }
        val startDelayMillis = waitForPlayback(audioTrack, samples.size, playRequestNanos)
        val underruns = audioTrack.underrunCount
        check(underruns == 0) { "AudioTrack underrun" }
        Ft4AudioPlaybackResult(startDelayMillis, underruns)
    }

    private fun buildTrack(encoding: Int): AudioTrack? {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minimum <= 0) return null
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val candidate = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate / 2))
                .build()
        }.getOrNull()
        if (candidate?.state != AudioTrack.STATE_INITIALIZED) {
            candidate?.release()
            return null
        }
        candidate.setVolume(volume)
        return candidate
    }

    private fun writeFloatChunk(track: AudioTrack, samples: FloatArray, offset: Int, maximum: Int): Int =
        track.write(samples, offset, minOf(maximum, samples.size - offset), AudioTrack.WRITE_BLOCKING)

    private fun writePcm16Chunk(track: AudioTrack, samples: FloatArray, offset: Int, maximum: Int): Int {
        val count = minOf(maximum, samples.size - offset)
        val buffer = ShortArray(count)
        for (index in 0 until count) {
            buffer[index] = (samples[offset + index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
    }

    private suspend fun waitForPlayback(
        audioTrack: AudioTrack,
        sampleCount: Int,
        playRequestNanos: Long
    ): Double? {
        val deadline = SystemClock.elapsedRealtime() + sampleCount * 1_000L / sampleRate + PLAYBACK_DRAIN_GRACE_MILLIS
        var startDelayMillis: Double? = null
        val timestamp = AudioTimestamp()
        while (audioTrack.playbackHeadPosition.toLong() < sampleCount) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "AudioTrack playback timed out" }
            if (startDelayMillis == null && audioTrack.getTimestamp(timestamp)) {
                val firstFrameNanos = timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / sampleRate
                startDelayMillis = (firstFrameNanos - playRequestNanos) / 1_000_000.0
            }
            delay(PLAYBACK_POLL_MILLIS)
        }
        return startDelayMillis
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    override fun close() {
        val current = track
        track = null
        runCatching { current?.pause() }
        runCatching { current?.flush() }
        runCatching { current?.stop() }
        runCatching { current?.release() }
        if (focusHeld) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let(audioManager::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
        focusRequest = null
        focusHeld = false
        preparedCount = 0
        usingFloat = false
    }

    private companion object {
        const val PRELOAD_SAMPLES = 4_096
        const val WRITE_CHUNK_SAMPLES = 2_048
        const val PLAYBACK_POLL_MILLIS = 10L
        const val PLAYBACK_DRAIN_GRACE_MILLIS = 1_000L
    }
}
