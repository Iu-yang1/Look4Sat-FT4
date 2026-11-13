package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Fixed inputs for before/after comparisons on the same host and DE440 archive. */
class OrbitBatchPerformanceTest {
    private val time = 1794441600000L
    private val observer = GeoPos(23.13, 113.26)
    private val base = OrbitalData("FO-29", 26316.10526760, 13.53280858, .0350827,
        98.5106, 144.6672, 72.5798, 291.3377, 24278, .000027114, -.00000006)
    private val elements = (0 until 56).map { index ->
        when {
            index < 48 -> base.copy(catnum = 100000 + index, meanan = index * 7.0)
            index < 52 -> base.copy(catnum = 100000 + index, meanmo = 1.0027, eccn = .001,
                incl = .1, bstar = 0.0, meanan = index * 6.0)
            else -> base.copy(catnum = 100000 + index, meanmo = 2.0, eccn = .7,
                incl = 63.4, bstar = 0.0, meanan = index * 6.0)
        }
    }

    @Test fun mixedCataloguePassesAndMapRefresh() = runBlocking {
        assumeTrue(System.getenv("SUNLIGHT_BENCHMARK") == "1")
        val engine = AstroTestData.engine
        val targets = elements.map(engine::bind)
        engine.openSession(targets.first()).position(observer, time)
        val passTimes = mutableListOf<Double>()
        var reference = emptyList<OrbitalPass>()
        repeat(3) {
            val started = System.nanoTime()
            val result = withContext(Dispatchers.Default) {
                targets.orbitParallelMap { target ->
                    engine.openSession(target).passes(observer, time, time + 86400000)
                }.flatten().sortedWith(compareBy({ it.catNum }, { it.aosTime }))
            }
            passTimes += (System.nanoTime() - started) / 1_000_000.0
            assertTrue(result.isNotEmpty())
            reference = result
        }
        benchmarkLines("batch-passes.tsv", reference.map {
            "${it.catNum}\t${it.aosTime}\t${it.losTime}\t${it.tcaTime}\t${it.maxElevation}" +
                "\t${it.startClipped}\t${it.endClipped}"
        })
        println("PIPELINE_PERFORMANCE name=56-satellites-24h-passes medianMs=${passTimes.sorted()[1]} " +
            "rounds=3 processors=${Runtime.getRuntime().availableProcessors()} passes=${reference.size}")
    }
}

internal fun benchmarkLines(name: String, lines: List<String>) {
    val destination = System.getenv("ORBIT_BENCHMARK_OUTPUT") ?: return
    File(destination).also { check(it.isDirectory || it.mkdirs()) }
        .resolve(name).writeText(lines.joinToString("\n", postfix = "\n"))
}
