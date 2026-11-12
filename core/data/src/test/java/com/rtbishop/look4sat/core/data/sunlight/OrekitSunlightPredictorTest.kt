package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.SunlightException
import com.rtbishop.look4sat.core.domain.sunlight.SunlightProblem
import com.rtbishop.look4sat.core.domain.sunlight.SunlightRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy

class OrekitSunlightPredictorTest {
    private val fo29 = OrbitalData("FO-29", 26316.10526760, 13.53280858, 0.0350827,
        98.5106, 144.6672, 72.5798, 291.3377, 24278, 0.000027114, -0.00000006)
    private val start = 1_794_441_600_000L // 2026-11-12T00:00:00Z

    private fun source(elements: () -> OrbitalData?): ILocalSource =
        Proxy.newProxyInstance(ILocalSource::class.java.classLoader, arrayOf(ILocalSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getEntriesList" -> listOf(SatItem(24278, "FO-29"), SatItem(25544, "ISS"))
                "getEntriesWithIds" -> elements()?.let { listOf(it.getObject()) }.orEmpty()
                else -> error("Unexpected source operation ${method.name}")
            }
        } as ILocalSource

    @Test fun listsAllLocalSatellitesWithoutChangingSelection() = runTest {
        val predictor = OrekitSunlightPredictor(source { fo29 }, ControlDiagnosticsBuffer(), com.rtbishop.look4sat.core.data.astro.AstroTestData.engine)
        assertEquals(listOf(24278, 25544), predictor.satellites().map { it.catnum })
    }

    @Test fun elementSnapshotAndSummaryRemainConsistentAcrossADataRefresh() = runTest {
        var current: OrbitalData? = fo29
        val predictor = OrekitSunlightPredictor(source { current }, ControlDiagnosticsBuffer(), com.rtbishop.look4sat.core.data.astro.AstroTestData.engine)
        val request = SunlightRequest(24278, start, 1)
        val progress = mutableListOf<Double>()
        val result = predictor.predict(request) {
            progress += it
            // Simulate a data refresh after this request already loaded its elements.
            current = fo29.copy(epoch = 26317.0, meanan = 70.0)
        }
        assertEquals(fo29, result.elements)
        assertEquals(null, result.problem)
        assertEquals(request.endUtcMillis, result.validUntilMillis)
        assertEquals(86_400_000L, Illumination.entries.sumOf(result::durationMillis))
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1.0, progress.last(), 0.0)
    }

    @Test fun unavailableElementsAreAnActionableError() = runTest {
        val predictor = OrekitSunlightPredictor(source { null }, ControlDiagnosticsBuffer(), com.rtbishop.look4sat.core.data.astro.AstroTestData.engine)
        try {
            predictor.predict(SunlightRequest(24278, start, 1)) {}
            fail("Expected missing orbital data")
        } catch (error: SunlightException) {
            assertEquals(SunlightProblem.ELEMENTS_UNAVAILABLE, error.problem)
        }
    }

    @Test fun invalidElementsDoNotGenerateFullSunlight() = runTest {
        val predictor = OrekitSunlightPredictor(source { fo29.copy(eccn = Double.NaN) }, ControlDiagnosticsBuffer(), com.rtbishop.look4sat.core.data.astro.AstroTestData.engine)
        try {
            predictor.predict(SunlightRequest(24278, start, 1)) {}
            fail("Expected invalid orbital data")
        } catch (error: SunlightException) {
            assertEquals(SunlightProblem.INVALID_ORBIT, error.problem)
        }
    }

    @Test(expected = CancellationException::class)
    fun cancellationInProgressCallbackIsPropagated() = runTest {
        OrekitSunlightPredictor(source { fo29 }, ControlDiagnosticsBuffer(), com.rtbishop.look4sat.core.data.astro.AstroTestData.engine)
            .predict(SunlightRequest(24278, start, 365)) { throw CancellationException("cancel") }
    }

    @Test fun optInThirtyAnd365DayPerformanceAndAccounting() = runTest(
        timeout = kotlin.time.Duration.parse("30m")
    ) {
        // Explicitly opt in when a test/build run is authorized; do not make every CI run propagate a year.
        assumeTrue(System.getenv("SUNLIGHT_BENCHMARK") == "1")
        for (days in listOf(30, 365)) {
            val diagnostics = ControlDiagnosticsBuffer()
            val result = OrekitSunlightPredictor(source { fo29 }, diagnostics, com.rtbishop.look4sat.core.data.astro.AstroTestData.engine)
                .predict(SunlightRequest(24278, start, days)) {}
            assertEquals(null, result.problem)
            assertEquals(days * 86_400_000L, result.intervals.sumOf { it.durationMillis })
            println(diagnostics.exportText())
        }
        val progressed = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.Default) {
            OrekitSunlightPredictor(source { fo29 }, ControlDiagnosticsBuffer(), com.rtbishop.look4sat.core.data.astro.AstroTestData.engine)
                .predict(SunlightRequest(24278, start, 365)) {
                    if (it > 0.0) progressed.complete(Unit)
                }
        }
        progressed.await()
        val cancelledAt = System.nanoTime()
        worker.cancelAndJoin()
        println("Sunlight cancellationResponseMs=${(System.nanoTime() - cancelledAt) / 1_000_000}")
    }
}
