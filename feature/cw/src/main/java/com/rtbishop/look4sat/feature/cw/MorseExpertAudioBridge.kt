/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.cw

import com.rtbishop.look4sat.core.domain.audio.AudioConsumer
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.audio.StreamingAudioResampler
import com.ve3nea.morse_expert.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MorseExpertAudioBridge(
    private val audioHub: IAudioHub,
    private val controller: MainActivity
) {
    suspend fun collect() {
        withContext(Dispatchers.Default) {
            var sourceRate = 0
            var resampler: StreamingAudioResampler? = null
            audioHub.audioFlow(AudioConsumer.MORSE_EXPERT).collect { chunk ->
                if (sourceRate != chunk.sampleRate) {
                    sourceRate = chunk.sampleRate
                    resampler = StreamingAudioResampler(sourceRate, MORSE_EXPERT_SAMPLE_RATE)
                }
                controller.feedAudioSamples(requireNotNull(resampler).process(chunk.samples))
            }
        }
    }

    companion object {
        private const val MORSE_EXPERT_SAMPLE_RATE = 8_000
    }
}
