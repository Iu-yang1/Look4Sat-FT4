package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.IOrbitEngine
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.legacy.LegacyOrbitEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** Host comparisons use the same inputs and JVM; these are not handset benchmarks. */
class OrbitPerformanceTest {
    private val elements = OrbitalData("FO-29", 26316.10526760, 13.53280858, .0350827,
        98.5106, 144.6672, 72.5798, 291.3377, 24278, .000027114, -.00000006)
    private val time = 1794441600000L
    private val observer = GeoPos(23.13, 113.26)
    private val allocation = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private fun measure(name: String, action: () -> Unit) {
        val durations = mutableListOf<Double>()
        var allocated = 0L
        repeat(3) {
            val before = allocation?.takeIf { it.isThreadAllocatedMemorySupported }
                ?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: 0
            val started = System.nanoTime()
            action()
            durations += (System.nanoTime() - started) / 1_000_000.0
            val after = allocation?.takeIf { it.isThreadAllocatedMemorySupported }
                ?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: 0
            allocated += (after - before).coerceAtLeast(0)
        }
        println("ORBIT_PERFORMANCE name=$name medianMs=${durations.sorted()[1]} " +
            "meanThreadAllocatedBytes=${allocated / 3} rounds=3 environment=host-jvm")
    }

    @Test fun compareRepeatedPositionsMapBatchesAndPassSearches() {
        val current = AstroTestData.engine
        val legacy = LegacyOrbitEngine()
        for ((name, engine) in listOf("legacy" to legacy, "orekit" to current)) {
            val session = engine.openSession(engine.bind(elements))
            repeat(100) { session.position(observer, time + it * 1000L) }
            measure("$name-1000-positions") {
                repeat(1000) { index ->
                    assertTrue(session.position(observer, time + index * 1000L).distance.isFinite())
                }
            }
            measure("$name-map-100-targets") { mapBatch(engine) }
            measure("$name-24h-passes") {
                val windows = engine.openSession(engine.bind(elements)).passes(observer, time, time + 86400000)
                assertTrue(windows.isNotEmpty())
            }
        }
    }

    private fun mapBatch(engine: IOrbitEngine) {
        repeat(100) { index ->
            val target = engine.bind(elements.copy(catnum = 100000 + index, meanan = index * 3.6))
            assertTrue(engine.openSession(target).position(observer, time).latitude.isFinite())
        }
    }
}
