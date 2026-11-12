package com.rtbishop.look4sat.core.domain.sunlight

import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.predict.OrbitalData

enum class Illumination { SUNLIGHT, PENUMBRA, UMBRA }

data class SunlightRequest(val catalogNumber: Int, val startUtcMillis: Long, val days: Int) {
    init {
        require(days in 1..365)
    }

    val endUtcMillis: Long = Math.addExact(startUtcMillis, days * 86_400_000L)
}

data class IlluminationInterval(
    val state: Illumination,
    val startMillis: Long,
    val endMillis: Long,
    val startClipped: Boolean = false,
    val endClipped: Boolean = false
) {
    init { require(endMillis > startMillis) }
    val durationMillis: Long get() = endMillis - startMillis
}

enum class SunlightProblem { ELEMENTS_UNAVAILABLE, INVALID_ORBIT, COMPUTATION_FAILED }

data class SunlightPrediction(
    val request: SunlightRequest,
    val elements: OrbitalData,
    val intervals: List<IlluminationInterval>,
    val validUntilMillis: Long,
    val problem: SunlightProblem? = null
) {
    val validDurationMillis: Long get() = validUntilMillis - request.startUtcMillis
    val sunlightCount: Int get() = intervals.count { it.state == Illumination.SUNLIGHT }
    val longestSunlight: IlluminationInterval?
        get() = intervals.filter { it.state == Illumination.SUNLIGHT }.maxByOrNull { it.durationMillis }

    fun durationMillis(state: Illumination): Long =
        intervals.asSequence().filter { it.state == state }.sumOf { it.durationMillis }
}

class SunlightException(val problem: SunlightProblem, cause: Throwable? = null) : Exception(cause)

interface ISunlightPredictor {
    /** All locally available satellites, without altering the application's selected satellites. */
    suspend fun satellites(): List<SatItem>

    /** Freezes one element set for the whole request. Progress is in [0, 1]. Cancellation propagates. */
    suspend fun predict(request: SunlightRequest, onProgress: (Double) -> Unit): SunlightPrediction
}
