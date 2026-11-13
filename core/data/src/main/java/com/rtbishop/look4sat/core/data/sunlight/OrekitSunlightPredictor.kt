package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.sunlight.ISunlightPredictor
import com.rtbishop.look4sat.core.domain.sunlight.SunlightException
import com.rtbishop.look4sat.core.domain.sunlight.SunlightPrediction
import com.rtbishop.look4sat.core.domain.sunlight.SunlightProblem
import com.rtbishop.look4sat.core.domain.sunlight.SunlightRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

class OrekitSunlightPredictor(
    private val localSource: ILocalSource,
    private val diagnostics: IControlDiagnostics,
    private val engine: com.rtbishop.look4sat.core.domain.astro.IOrbitEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ISunlightPredictor {
    override suspend fun satellites(): List<SatItem> = withContext(Dispatchers.IO) {
        localSource.getEntriesList()
    }

    override suspend fun predict(
        request: SunlightRequest,
        onProgress: (Double) -> Unit
    ): SunlightPrediction = withContext(dispatcher) {
        val started = System.nanoTime()
        val context = currentCoroutineContext()
        val elements = withContext(Dispatchers.IO) {
            localSource.getEntriesWithIds(listOf(request.catalogNumber)).firstOrNull()?.data?.copy()
        } ?: throw SunlightException(SunlightProblem.ELEMENTS_UNAVAILABLE)
        val period = elements.orbitalPeriod * 60
        if (!period.isFinite() || period <= 0 || elements.eccn !in 0.0..<1.0) {
            throw SunlightException(SunlightProblem.INVALID_ORBIT)
        }
        val target = try {
            engine.bind(elements)
        } catch (error: Exception) {
            throw SunlightException(SunlightProblem.INVALID_ORBIT, error)
        }
        val model = try {
            OrekitShadowModel(target.snapshot as com.rtbishop.look4sat.core.data.astro.OrekitRuntime)
        } catch (error: Exception) {
            diagnostics.record(ControlDiagnosticSource.RADIO, "sunlight/initialization",
                error.toString(), ControlDiagnosticSeverity.ERROR)
            throw SunlightException(SunlightProblem.COMPUTATION_FAILED, error)
        }
        try {
            onProgress(0.0)
            val search = parallelShadowSearch(request.startUtcMillis, request.endUtcMillis, onProgress) { start, end, progress ->
                val orbit = try {
                    engine.openSession(target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@parallelShadowSearch ShadowSearchResult(emptyList(), start, error)
                }
                ShadowEventSearch(
                    sample = { seconds ->
                        model.sample(orbit.temeState(start + (seconds * 1000).roundToLong()), period)
                    },
                    checkCancelled = { context.ensureActive() }
                ).search(start, end, progress)
            }
            val failure = search.failure?.let {
                when (it) {
                    is com.rtbishop.look4sat.core.domain.astro.AstroDateOutsideCoverageException ->
                        SunlightProblem.DATA_OUTSIDE_COVERAGE
                    is IllegalArgumentException -> SunlightProblem.INVALID_ORBIT
                    else -> SunlightProblem.COMPUTATION_FAILED
                }
            }
            diagnostics.record(
                ControlDiagnosticSource.RADIO, "sunlight/result",
                "cat=${elements.catnum} epoch=${elements.epoch} days=${request.days} " +
                    "data=${target.snapshot?.version} " +
                    "intervals=${search.intervals.size} validUntil=${search.validUntilMillis} " +
                    "elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
                    "heapBytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} " +
                    "problem=$failure detail=${search.failure?.toString().orEmpty()}"
            )
            if (search.validUntilMillis == request.startUtcMillis && failure != null) {
                throw SunlightException(failure, search.failure)
            }
            onProgress(1.0)
            SunlightPrediction(request, elements, search.intervals, search.validUntilMillis, failure)
        } catch (cancelled: CancellationException) {
            diagnostics.record(ControlDiagnosticSource.RADIO, "sunlight/cancelled",
                "cat=${elements.catnum} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            throw cancelled
        }
    }
}
