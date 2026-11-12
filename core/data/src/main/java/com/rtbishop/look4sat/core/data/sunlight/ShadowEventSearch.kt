package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import kotlinx.coroutines.CancellationException
import org.hipparchus.analysis.UnivariateFunction
import org.hipparchus.analysis.solvers.BracketingNthOrderBrentSolver
import org.hipparchus.optim.MaxEval
import org.hipparchus.optim.nonlinear.scalar.GoalType
import org.hipparchus.optim.univariate.BrentOptimizer
import org.hipparchus.optim.univariate.SearchInterval
import org.hipparchus.optim.univariate.UnivariateObjectiveFunction
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** Signed Orekit event functions: negative penumbra = some occultation; negative umbra = total. */
internal data class ShadowSample(
    val penumbra: Double,
    val umbra: Double,
    val maxStepSeconds: Double = 30.0
) {
    init {
        require(penumbra.isFinite() && umbra.isFinite() && maxStepSeconds.isFinite())
        require(maxStepSeconds > 0)
    }

    val state: Illumination get() = when {
        umbra < 0.0 -> Illumination.UMBRA
        penumbra < 0.0 -> Illumination.PENUMBRA
        else -> Illumination.SUNLIGHT
    }
}

internal data class ShadowSearchResult(
    val intervals: List<IlluminationInterval>,
    val validUntilMillis: Long,
    val failure: Exception? = null
)

/**
 * Searches continuous event functions, not a minute-resolution Boolean grid. Three-point extrema
 * brackets find grazing events even when both ends have the same sign; Brent locates their two
 * crossings. Times are seconds relative to the request, avoiding Unix-epoch solver tolerances.
 */
internal class ShadowEventSearch(
    private val sample: (Double) -> ShadowSample,
    private val checkCancelled: () -> Unit = {}
) {
    private data class Point(val time: Double, val value: ShadowSample)

    fun search(startMillis: Long, endMillis: Long, onProgress: (Double) -> Unit): ShadowSearchResult {
        require(endMillis > startMillis)
        val end = (endMillis - startMillis) / 1000.0
        val roots = sortedSetOf(0.0, end)
        var validated = 0.0
        var failure: Exception? = null
        var firstSample: ShadowSample? = null
        var finalSample: ShadowSample? = null
        try {
            var left = point(0.0)
            firstSample = left.value
            var middle = point(min(end, left.value.maxStepSeconds))
            findCrossings(left, middle, roots)
            // Extrema right at the range boundaries have no three-point bracket.
            findExtrema(left, middle, roots)
            while (middle.time < end) {
                val right = point(min(end, middle.time + middle.value.maxStepSeconds))
                findCrossings(middle, right, roots)
                for (boundary in 0..1) {
                    val a = value(left, boundary)
                    val b = value(middle, boundary)
                    val c = value(right, boundary)
                    if ((b < a && b <= c) || (b > a && b >= c)) {
                        extremum(left, right, middle.time, boundary, b < a, roots)
                    }
                }
                // An unfinished extrema bracket must never be counted as known sunlight.
                validated = middle.time
                onProgress(validated / end)
                left = middle
                middle = right
            }
            findExtrema(left, middle, roots)
            validated = end
            finalSample = middle.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }

        val edges = (roots.filter { it < validated } + validated).distinct().sorted()
        val intervals = mutableListOf<IlluminationInterval>()
        var classifiedUntil = 0.0
        for ((a, b) in edges.zipWithNext()) {
            if (b <= a) continue
            val state = try {
                point((a + b) / 2).value.state
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                break
            }
            val start = startMillis + (a * 1000).roundToLong()
            val stop = startMillis + (b * 1000).roundToLong()
            if (stop > start) {
                val previous = intervals.lastOrNull()
                if (previous?.state == state && previous.endMillis == start) {
                    intervals[intervals.lastIndex] = previous.copy(endMillis = stop)
                } else {
                    intervals += IlluminationInterval(state, start, stop)
                }
            }
            classifiedUntil = b
        }
        if (intervals.isNotEmpty()) {
            intervals[0] = intervals.first().copy(startClipped = firstSample?.onBoundary() != true)
            // An error boundary is unknown, not "still sunlit after the requested end".
            if (failure == null && classifiedUntil == end) {
                intervals[intervals.lastIndex] = intervals.last().copy(endClipped = finalSample?.onBoundary() != true)
            }
        }
        return ShadowSearchResult(
            intervals,
            startMillis + (classifiedUntil * 1000).roundToLong(),
            failure
        )
    }

    private fun point(time: Double): Point {
        checkCancelled()
        return Point(time, sample(time))
    }

    private fun value(point: Point, boundary: Int): Double =
        if (boundary == 0) point.value.penumbra else point.value.umbra

    private fun ShadowSample.onBoundary(): Boolean = penumbra == 0.0 || umbra == 0.0

    private fun findCrossings(a: Point, b: Point, roots: MutableSet<Double>) {
        for (boundary in 0..1) crossing(a, b, boundary, roots)
    }

    private fun crossing(a: Point, b: Point, boundary: Int, roots: MutableSet<Double>) {
        val fa = value(a, boundary)
        val fb = value(b, boundary)
        if (fa == 0.0) roots += a.time
        if (fb == 0.0) roots += b.time
        if ((fa < 0 && fb > 0) || (fa > 0 && fb < 0)) {
            val root = BracketingNthOrderBrentSolver(0.05, 5).solve(
                100,
                UnivariateFunction { value(point(it), boundary) },
                a.time, b.time
            )
            if (roots.none { abs(it - root) < 0.001 }) roots += root
        }
    }

    private fun findExtrema(a: Point, b: Point, roots: MutableSet<Double>) {
        if (b.time - a.time < 0.1) return
        for (boundary in 0..1) {
            extremum(a, b, (a.time + b.time) / 2, boundary, true, roots)
            extremum(a, b, (a.time + b.time) / 2, boundary, false, roots)
        }
    }

    private fun extremum(
        a: Point, b: Point, initial: Double, boundary: Int,
        minimum: Boolean, roots: MutableSet<Double>
    ) {
        if (b.time - a.time < 0.1) return
        val optimum = BrentOptimizer(1e-10, 0.01).optimize(
            MaxEval(100),
            UnivariateObjectiveFunction { value(point(it), boundary) },
            if (minimum) GoalType.MINIMIZE else GoalType.MAXIMIZE,
            SearchInterval(a.time, b.time, initial)
        )
        val turningPoint = point(optimum.point)
        crossing(a, turningPoint, boundary, roots)
        crossing(turningPoint, b, boundary, roots)
    }
}
