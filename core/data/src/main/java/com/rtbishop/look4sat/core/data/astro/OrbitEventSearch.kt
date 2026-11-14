package com.rtbishop.look4sat.core.data.astro

import org.hipparchus.analysis.UnivariateFunction
import org.hipparchus.analysis.solvers.BrentSolver
import kotlin.math.min

internal class OrbitEventSearchException(
    val validUntilMillis: Long,
    val boundaries: List<Long>,
    cause: Exception
) : IllegalStateException("Event search ended at $validUntilMillis", cause)

internal class OrbitBatchSearchException(
    val validUntilMillis: Long, val boundaries: List<List<Long>>, cause: Exception
) : IllegalStateException("Batch search ended at $validUntilMillis", cause)

internal class OrbitEventFunction(
    val value: (Long) -> Double, val derivative: (Long) -> Double,
    val onExtremum: (Long) -> Unit
)
/**
 * Include local extrema as brackets: equal endpoint visibility does not exclude a short
 * grazing event between them. Callers bound steps by orbital angle as well as elapsed time.
 */
internal fun eventBoundaries(
    start: Long, end: Long, value: (Long) -> Double, derivative: (Long) -> Double,
    stepMillis: (Long) -> Long, checkCancelled: () -> Unit,
    onExtremum: (Long) -> Unit = {}
): List<Long> {
    return try {
        eventBoundariesBatch(start, end, listOf(OrbitEventFunction(value, derivative, onExtremum)),
            stepMillis, checkCancelled).single()
    } catch (error: OrbitBatchSearchException) {
        throw OrbitEventSearchException(error.validUntilMillis, error.boundaries.single(),
            error.cause as Exception)
    }
}

/** All stations visit the identical coarse instants, with independent precise roots. */
internal fun eventBoundariesBatch(
    start: Long, end: Long, functions: List<OrbitEventFunction>,
    stepMillis: (Long) -> Long, checkCancelled: () -> Unit
): List<List<Long>> {
    require(end > start)
    val roots = functions.map { sortedSetOf<Long>() }
    val solver = BrentSolver(0.1)
    fun root(function: (Long) -> Double, a: Long, b: Long): Long {
        val origin = a
        return origin + solver.solve(100, UnivariateFunction {
            checkCancelled()
            function(origin + (it * 1000).toLong()).also { result -> require(result.isFinite()) }
        }, 0.0, (b - a) / 1000.0).times(1000).toLong()
    }
    fun bracket(index: Int, a: Long, b: Long) {
        val value = functions[index].value
        val va = value(a)
        val vb = value(b)
        require(va.isFinite() && vb.isFinite())
        if (va == 0.0) roots[index] += a
        if (vb == 0.0) roots[index] += b
        if (va * vb < 0) roots[index] += root(value, a, b)
    }
    var a = start
    try {
        val derivatives = DoubleArray(functions.size) { functions[it].derivative(a) }
        while (a < end) {
            checkCancelled()
            val b = min(end, a + stepMillis(a).coerceIn(100, 30_000))
            functions.forEachIndexed { index, function ->
                val da = derivatives[index]
                val db = function.derivative(b)
                require(da.isFinite() && db.isFinite())
                if (da == 0.0 && db != 0.0) function.onExtremum(a)
                if (db == 0.0 && da != 0.0) function.onExtremum(b)
                if (da * db < 0) {
                    val extremum = root(function.derivative, a, b)
                    function.onExtremum(extremum)
                    bracket(index, a, extremum)
                    bracket(index, extremum, b)
                } else {
                    bracket(index, a, b)
                }
                derivatives[index] = db
            }
            a = b
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw OrbitBatchSearchException(a, roots.map { times -> times.filter { it > start && it < a } }, error)
    }
    return roots.map { times -> times.filter { it > start && it < end } }
}
