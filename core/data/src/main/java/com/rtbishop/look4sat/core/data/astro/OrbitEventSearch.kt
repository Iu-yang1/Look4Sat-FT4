package com.rtbishop.look4sat.core.data.astro

import org.hipparchus.analysis.UnivariateFunction
import org.hipparchus.analysis.solvers.BrentSolver
import kotlin.math.min

internal class OrbitEventSearchException(
    val validUntilMillis: Long,
    val boundaries: List<Long>,
    cause: Exception
) : IllegalStateException("Event search ended at $validUntilMillis", cause)

/**
 * Include local extrema as brackets: equal endpoint visibility does not exclude a short
 * grazing event between them. Callers bound steps by orbital angle as well as elapsed time.
 */
internal fun eventBoundaries(
    start: Long, end: Long, value: (Long) -> Double, derivative: (Long) -> Double,
    stepMillis: (Long) -> Long, checkCancelled: () -> Unit,
    onExtremum: (Long) -> Unit = {}
): List<Long> {
    require(end > start)
    val roots = sortedSetOf<Long>()
    val solver = BrentSolver(0.1)
    fun root(function: (Long) -> Double, a: Long, b: Long): Long {
        val origin = a
        return origin + solver.solve(100, UnivariateFunction {
            checkCancelled()
            function(origin + (it * 1000).toLong()).also { result -> require(result.isFinite()) }
        }, 0.0, (b - a) / 1000.0).times(1000).toLong()
    }
    fun bracket(a: Long, b: Long) {
        val va = value(a)
        val vb = value(b)
        require(va.isFinite() && vb.isFinite())
        if (va == 0.0) roots += a
        if (vb == 0.0) roots += b
        if (va * vb < 0) roots += root(value, a, b)
    }
    var a = start
    try {
        var da = derivative(a)
        while (a < end) {
            checkCancelled()
            val b = min(end, a + stepMillis(a).coerceIn(100, 30_000))
            val db = derivative(b)
            require(da.isFinite() && db.isFinite())
            if (da == 0.0 && db != 0.0) onExtremum(a)
            if (db == 0.0 && da != 0.0) onExtremum(b)
            if (da * db < 0) {
                val extremum = root(derivative, a, b)
                onExtremum(extremum)
                bracket(a, extremum)
                bracket(extremum, b)
            } else {
                bracket(a, b)
            }
            a = b
            da = db
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw OrbitEventSearchException(a, roots.filter { it > start && it < a }, error)
    }
    return roots.filter { it > start && it < end }
}
