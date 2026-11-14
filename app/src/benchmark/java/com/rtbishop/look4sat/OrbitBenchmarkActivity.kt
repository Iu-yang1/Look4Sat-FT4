package com.rtbishop.look4sat

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.rtbishop.look4sat.core.data.astro.OrekitOrbitEngine
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.model.TrajectoryPrecision
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Opt-in local benchmark build only; absent from normal APKs. Never changes user caches. */
class OrbitBenchmarkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as IContainerProvider).getMainContainer()
        intent.getStringExtra("applyProfile")?.let { value ->
            val level = TrajectoryPrecision.valueOf(value)
            container.settingsRepo.updateOtherSettings { it.copy(trajectoryPrecision = level) }
            Log.i("OrbitBenchmark", "applied profile=${level.name}")
            finish()
            return
        }
        val run = intent.getStringExtra("run")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]+")) } ?: "local"
        scope.launch {
            try {
                container.astroData.state.first { it.ready }
                val selected = container.satelliteRepo.satellites.first { it.isNotEmpty() }
                val station = container.settingsRepo.stationPosition.value
                val other = com.rtbishop.look4sat.core.domain.predict.GeoPos(31.25, 125.0)
                val start = intent.getLongExtra("start", 1794614400000L)
                if (intent.getBooleanExtra("profiles", false)) {
                    benchmarkProfiles(container.astroData, selected, station, other, start, run)
                    return@launch
                }
                val rows = JSONArray()
                repeat(5) { round ->
                    val engine = OrekitOrbitEngine(container.astroData,
                        File(cacheDir, "orbit-benchmark/$run/$round"))
                    val context = coroutineContext
                    val allowed = Sources.amSatFmCatnums + Sources.amSatLinearCatnums
                    val targets = selected.filter { it.data.catnum in allowed }
                    for ((name, hours) in listOf("miss" to 24, "hit" to 24, "extension" to 25)) {
                        val began = System.nanoTime()
                        var count = 0
                        val windows = JSONArray()
                        for (target in targets) {
                            val stations = listOf(station, other)
                            val predicted = if (intent.getBooleanExtra("batch", false)) {
                                engine.passes(target, stations, start, start + hours * 3600000L)
                            } else {
                                val session = engine.openSession(target)
                                stations.map { observer ->
                                    session.passes(observer, start, start + hours * 3600000L) {
                                        context.ensureActive()
                                    }
                                }
                            }
                            for ((index, passes) in predicted.withIndex()) {
                                count += passes.size
                                passes.forEach { pass -> windows.put(window(pass, index)) }
                            }
                        }
                        val row = JSONObject().put("round", round + 1).put("kind", name)
                            .put("elapsedMs", (System.nanoTime() - began) / 1_000_000.0)
                            .put("targets", targets.size).put("windows", count).put("values", windows)
                        rows.put(row)
                        Log.i("OrbitBenchmark", row.toString().replace(Regex(",\"values\".*"), "}"))
                    }
                }
                val output = File(getExternalFilesDir(null), "orbit-benchmark-$run.json")
                output.writeText(JSONObject().put("start", start).put("station", station.toString())
                    .put("other", other.toString()).put("rows", rows).toString(2))
                Log.i("OrbitBenchmark", "complete file=${output.absolutePath}")
            } catch (error: Exception) {
                Log.e("OrbitBenchmark", "failed", error)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private suspend fun benchmarkProfiles(
        data: com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository,
        selected: List<OrbitalObject>, station: GeoPos, other: GeoPos, start: Long, run: String
    ) = coroutineScope {
        val allowed = Sources.amSatFmCatnums + Sources.amSatLinearCatnums
        val snapshot = data.snapshot()
        val targets = selected.filter { it.data.catnum in allowed }.sortedBy { it.data.catnum }.take(11)
            .map { it.copy(snapshot = snapshot) }
        require(targets.isNotEmpty()) { "No selected amateur satellites" }
        val rows = JSONArray()
        val levels = TrajectoryPrecision.entries
        val directory = File(cacheDir, "orbit-benchmark/$run")
        require(!directory.exists()) { "Benchmark run already exists; use a new run name" }
        require(directory.mkdirs())
        // Initialize the same propagation and frame paths before the timed rounds.
        val warm = OrekitOrbitEngine(data, scope = this)
        targets.forEach { warm.passes(it, listOf(station, other), start, start + 3600000L) }
        repeat(5) { round ->
            // Rotate the order to avoid always giving Ultra the warmest CPU/JIT.
            repeat(levels.size) { index ->
                val level = levels[(index + round) % levels.size]
                for ((workload, hours, observers) in listOf(
                    Triple("passes", 24, listOf(station)),
                    Triple("schedule", 72, listOf(station)),
                    Triple("mutual", 24, listOf(station, other))
                )) {
                    val workloadTargets = if (workload == "schedule") targets.take(1) else targets
                    val diagnostics = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val engine = OrekitOrbitEngine(data,
                        File(directory, "$round/${level.name}/$workload"), this,
                        precision = { level }, diagnostic = diagnostics::add)
                    val permits = Semaphore(
                        if (workload == "mutual") 4 else
                            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8))
                    for (kind in listOf("miss", "hit")) {
                        diagnostics.clear()
                        val first = AtomicLong()
                        val began = System.nanoTime()
                        val results = workloadTargets.map { target ->
                            async {
                                permits.withPermit {
                                    engine.passes(target, observers, start, start + hours * 3600000L)
                                        .also { if (it.any { passes -> passes.isNotEmpty() }) {
                                            first.compareAndSet(0, System.nanoTime())
                                        } }
                                }
                            }
                        }.awaitAll()
                        val ended = System.nanoTime()
                        val windows = JSONArray()
                        results.forEach { stations -> stations.forEachIndexed { observer, passes ->
                            passes.forEach { windows.put(window(it, observer)) }
                        } }
                        val propagationCalls = synchronized(diagnostics) {
                            diagnostics.sumOf { line ->
                                Regex("propagations=(\\d+)").find(line)?.groupValues?.get(1)?.toLong() ?: 0L
                            }
                        }
                        val row = JSONObject().put("round", round + 1).put("profile", level.name)
                            .put("workload", workload).put("kind", kind).put("hours", hours)
                            .put("elapsedMs", (ended - began) / 1_000_000.0)
                            .put("firstResultMs", if (first.get() == 0L) JSONObject.NULL
                                else (first.get() - began) / 1_000_000.0)
                            .put("targets", workloadTargets.size).put("windows", windows.length())
                            .put("propagations", propagationCalls).put("values", windows)
                        rows.put(row)
                        Log.i("OrbitBenchmark", "profile=${level.name} round=${round + 1} " +
                            "workload=$workload kind=$kind elapsedMs=${row.getDouble("elapsedMs")}")
                    }
                }
            }
            File(getExternalFilesDir(null), "orbit-benchmark-$run.json").writeText(JSONObject()
                .put("start", start).put("station", station.toString()).put("other", other.toString())
                .put("targets", JSONArray(targets.map { it.data.catnum }))
                .put("dataVersion", snapshot.version)
                .put("elements", JSONArray(targets.map { it.data.toString() }))
                .put("rows", rows).toString(2))
        }
        Log.i("OrbitBenchmark", "profiles complete run=$run rows=${rows.length()}")
    }

    private fun window(pass: OrbitalPass, station: Int) = JSONObject()
        .put("station", station).put("catnum", pass.catNum)
        .put("aos", pass.aosTime).put("los", pass.losTime).put("tca", pass.tcaTime)
        .put("maximum", pass.maxElevation).put("startClipped", pass.startClipped)
        .put("endClipped", pass.endClipped).put("continuous", pass.continuousVisibility)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
