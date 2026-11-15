package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.domain.audio.TimestampedAudioChunk
import com.rtbishop.look4sat.core.domain.maintenance.ReceiveMeasurement
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal class ReceiveMeter {
    private var count = 0L
    private var squares = 0.0
    private var peak = 0.0
    private var clips = 0L
    private var gaps = 0L
    private var expected: Long? = null
    private var rate = 0

    fun add(chunk: TimestampedAudioChunk) {
        require(chunk.sampleRate > 0 && chunk.samples.all { it.isFinite() }) { "Invalid audio samples" }
        if (expected != null && (chunk.discontinuity || chunk.framePosition != expected || rate != chunk.sampleRate)) gaps++
        rate = chunk.sampleRate
        expected = chunk.framePosition + chunk.samples.size
        for (value in chunk.samples) {
            val magnitude = abs(value.toDouble())
            squares += value.toDouble() * value
            peak = max(peak, magnitude)
            if (magnitude >= 0.999) clips++
        }
        count += chunk.samples.size
    }

    fun measurement(source: String) = ReceiveMeasurement(
        rate, count, db(if (count == 0L) 0.0 else sqrt(squares / count)), db(peak), clips, gaps, source
    )

    private fun db(value: Double) = if (value <= 0.000001) -120.0 else 20 * log10(value)
}
