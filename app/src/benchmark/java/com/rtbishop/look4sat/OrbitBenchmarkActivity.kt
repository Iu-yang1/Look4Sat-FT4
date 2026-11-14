package com.rtbishop.look4sat

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.rtbishop.look4sat.core.data.astro.OrekitOrbitEngine
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** Opt-in local benchmark build only; absent from normal APKs. Never changes user caches. */
class OrbitBenchmarkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as IContainerProvider).getMainContainer()
        val run = intent.getStringExtra("run")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]+")) } ?: "local"
        scope.launch {
            try {
                container.astroData.state.first { it.ready }
                val selected = container.satelliteRepo.satellites.first { it.isNotEmpty() }
                val station = container.settingsRepo.stationPosition.value
                val other = com.rtbishop.look4sat.core.domain.predict.GeoPos(31.25, 125.0)
                val start = intent.getLongExtra("start", 1794614400000L)
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
