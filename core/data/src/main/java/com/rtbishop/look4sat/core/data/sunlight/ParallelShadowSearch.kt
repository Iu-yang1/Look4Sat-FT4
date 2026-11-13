package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.data.astro.orbitParallelMap
import com.rtbishop.look4sat.core.data.astro.orbitWorkerCount
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import kotlin.math.max
import kotlin.math.min

/**
 * Independent propagators search overlapping ranges. Only the owned ranges are published;
 * two minutes of overlap preserve extrema brackets at worker seams.
 */
internal suspend fun parallelShadowSearch(
    start: Long,
    end: Long,
    onProgress: (Double) -> Unit,
    parallelism: Int = orbitWorkerCount(),
    search: (Long, Long, (Double) -> Unit) -> ShadowSearchResult
): ShadowSearchResult {
    require(end > start && parallelism > 0)
    val count = minOf(parallelism, ((end - start + 86_399_999) / 86_400_000).toInt())
    val progress = DoubleArray(count)
    var lastPercent = -1
    val parts = (0 until count).toList().orbitParallelMap(parallelism) { index ->
        val ownedStart = start + (end - start) * index / count
        val ownedEnd = start + (end - start) * (index + 1) / count
        val result = search(max(start, ownedStart - 120_000), min(end, ownedEnd + 120_000)) { value ->
            synchronized(progress) {
                progress[index] = max(progress[index], value)
                val combined = progress.sum() / count
                val percent = (combined * 100).toInt()
                if (percent > lastPercent) {
                    lastPercent = percent
                    onProgress(combined)
                }
            }
        }
        val validEnd = result.validUntilMillis.coerceIn(ownedStart, ownedEnd)
        val cropped = result.intervals.mapNotNull { interval ->
            val a = max(ownedStart, interval.startMillis)
            val b = min(validEnd, interval.endMillis)
            if (b <= a) null else interval.copy(
                startMillis = a, endMillis = b,
                startClipped = a == start && interval.startClipped,
                endClipped = b == end && interval.endClipped
            )
        }
        ShadowSearchResult(cropped, validEnd,
            result.failure.takeIf { result.validUntilMillis < ownedEnd })
    }
    val intervals = mutableListOf<IlluminationInterval>()
    var validEnd = start
    var failure: Exception? = null
    for (part in parts) {
        for (interval in part.intervals) {
            val previous = intervals.lastOrNull()
            if (previous?.state == interval.state && previous.endMillis == interval.startMillis) {
                intervals[intervals.lastIndex] = previous.copy(
                    endMillis = interval.endMillis, endClipped = interval.endClipped)
            } else {
                intervals += interval
            }
        }
        validEnd = part.validUntilMillis
        if (part.failure != null) {
            failure = part.failure
            break
        }
    }
    return ShadowSearchResult(intervals, validEnd, failure)
}
