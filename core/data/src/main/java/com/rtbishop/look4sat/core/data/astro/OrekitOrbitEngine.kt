package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
import com.rtbishop.look4sat.core.domain.astro.IOrbitEngine
import com.rtbishop.look4sat.core.domain.astro.IOrbitSession
import com.rtbishop.look4sat.core.domain.astro.OrbitPredictionException
import com.rtbishop.look4sat.core.domain.astro.OrbitDirection
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.predict.TemeState
import com.rtbishop.look4sat.core.domain.utility.round
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.orekit.propagation.SpacecraftState
import org.orekit.propagation.analytical.tle.TLE
import org.orekit.propagation.analytical.tle.TLEPropagator
import kotlin.math.PI
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class OrekitOrbitEngine(
    private val data: IAstroDataRepository,
    cacheDirectory: java.io.File? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val diagnostic: (String) -> Unit = {}
) : IOrbitEngine {
    private val passCache = cacheDirectory?.let(::OrbitPassCache)
    private val coordinator = OrbitComputationCoordinator(scope, diagnostic = diagnostic)
    private data class Request(
        val elements: OrbitalData, val runtime: Any?, val observers: List<List<Double>>,
        val start: Long, val end: Long
    )
    override fun bind(elements: OrbitalData) = OrbitalObject(elements, data.snapshot())
    override fun openSession(target: OrbitalObject): IOrbitSession =
        OrekitOrbitSession(target.copy(snapshot = target.snapshot ?: data.snapshot()), passCache, diagnostic)

    override suspend fun passes(target: OrbitalObject, observers: List<GeoPos>,
        startMillis: Long, endMillis: Long): List<List<OrbitalPass>> {
        require(endMillis > startMillis)
        val pinned = target.copy(snapshot = target.snapshot ?: data.snapshot())
        val hour = 3600000L
        val normalized = passCache != null && endMillis - startMillis <= 14L * 86400000
        val start = if (normalized) maxOf(Math.floorDiv(startMillis, hour) * hour,
            requireNotNull(pinned.snapshot).coverageStartMillis) else startMillis
        val end = if (normalized) minOf((Math.floorDiv(endMillis - 1, hour) + 1) * hour,
            requireNotNull(pinned.snapshot).coverageEndMillis - 1) else endMillis
        val key = Request(pinned.data, pinned.snapshot,
            observers.map { listOf(it.latitude, it.longitude, it.altitude) }, start, end)
        val windows = coordinator.compute(key) {
            val context = coroutineContext
            OrekitOrbitSession(pinned, passCache, diagnostic).batchPasses(observers, start, end) {
                context.ensureActive()
            }
        }
        val context = coroutineContext
        val cropSession = OrekitOrbitSession(pinned, diagnostic = diagnostic)
        return observers.indices.map { index ->
            cropSession.crop(observers[index], windows[index], startMillis, endMillis) { context.ensureActive() }
        }
    }
}

internal fun orekitTle(data: OrbitalData, runtime: OrekitRuntime): TLE {
    require(listOf(data.epoch, data.meanmo, data.eccn, data.incl, data.raan, data.argper,
        data.meanan, data.bstar, data.ndot).all(Double::isFinite))
    require(data.meanmo > 0 && data.eccn >= 0 && data.eccn < 1 && data.incl in 0.0..180.0)
    val epochMillis = (data.epochDaynum * 86_400_000 + 315446400000L).toLong()
    // Database ndot follows the TLE field convention: half the first derivative.
    return TLE(data.catnum, 'U', 2000, 0, "", TLE.DEFAULT, 0,
        org.orekit.time.AbsoluteDate(java.util.Date(epochMillis), runtime.utc),
        data.meanmo * 2 * PI / 86400, data.ndot * 2 * 2 * PI / (86400.0 * 86400),
        0.0, data.eccn, Math.toRadians(data.incl), Math.toRadians(data.argper),
        Math.toRadians(data.raan), Math.toRadians(data.meanan), 0, data.bstar, runtime.utc)
}

/** Thread-confined session; callers must not share it between jobs. */
internal class OrekitOrbitSession(
    override val target: OrbitalObject,
    private val passCache: OrbitPassCache? = null,
    private val diagnostic: (String) -> Unit = {}
) : IOrbitSession {
    internal val runtime = target.snapshot as OrekitRuntime
    private val propagator = TLEPropagator.selectExtrapolator(orekitTle(target.data, runtime), runtime.teme)
    private val stations = mutableMapOf<GeoPos, org.orekit.frames.TopocentricFrame>()
    private var propagationCount = 0L
    private var earthTransformCount = 0L
    private fun station(observer: GeoPos) = stations.getOrPut(observer) { runtime.station(observer) }
    internal fun state(time: Long): SpacecraftState {
        propagationCount++
        val state = propagator.propagate(runtime.date(time))
        val radius = state.position.norm
        require(radius.isFinite() && state.pvCoordinates.velocity.norm.isFinite()) { "Non-finite propagated orbit" }
        // The equatorial radius bounds the whole ellipsoid; only smaller radii need a
        // body-frame containment check. This avoids a duplicate transform for normal orbits.
        if (radius <= org.orekit.utils.Constants.WGS84_EARTH_EQUATORIAL_RADIUS) {
            require(!runtime.earth.isInside(state.getPVCoordinates(runtime.terrestrial).position)) {
                "Propagated orbit is inside Earth"
            }
        }
        return state
    }
    override fun elevation(observer: GeoPos, timeMillis: Long): Double {
        val state = state(timeMillis)
        return station(observer).getElevation(state.position, state.frame, state.date)
    }
    override fun directions(observers: List<GeoPos>, timeMillis: Long): List<OrbitDirection> {
        val state = state(timeMillis)
        val body = state.getPVCoordinates(runtime.terrestrial).position
        return observers.map { observer ->
            val coordinates = station(observer).getTrackingCoordinates(body, runtime.terrestrial, state.date)
            OrbitDirection(coordinates.azimuth, coordinates.elevation)
        }
    }
    override fun temeState(timeMillis: Long): TemeState {
        val pv = state(timeMillis).getPVCoordinates(runtime.teme)
        return TemeState(timeMillis, pv.position.x / 1000, pv.position.y / 1000, pv.position.z / 1000,
            pv.velocity.x / 1000, pv.velocity.y / 1000, pv.velocity.z / 1000)
    }
    override fun position(observer: GeoPos, timeMillis: Long): OrbitalPos {
        val state = state(timeMillis)
        val coordinates = station(observer).getTrackingCoordinates(state.position, state.frame, state.date)
        val ground = runtime.earth.transform(state.position, state.frame, state.date)
        val penumbra = runtime.penumbra.g(state)
        return OrbitalPos(
            azimuth = coordinates.azimuth, elevation = coordinates.elevation,
            latitude = ground.latitude, longitude = ground.longitude, altitude = ground.altitude / 1000,
            distance = coordinates.range / 1000,
            distanceRate = station(observer).getRangeRate(state.pvCoordinates, state.frame, state.date) / 1000,
            time = timeMillis, phase = org.orekit.orbits.KeplerianOrbit(state.orbit).meanAnomaly,
            eclipseDepth = -penumbra, eclipsed = penumbra <= 0,
            aboveHorizon = coordinates.elevation > 0,
            velocityKmPerSecond = state.getPVCoordinates(runtime.inertial).velocity.norm / 1000,
            dataVersion = runtime.version,
            elementAgeDays = kotlin.math.abs(OrbitalData.timeToDaynum(timeMillis) - target.data.epochDaynum),
            approximateEarthOrientation = timeMillis !in runtime.eopStartMillis..runtime.eopEndMillis,
            surfaceGeometry = OrekitSurfaceGeometry(runtime,
                state.getPVCoordinates(runtime.terrestrial).position, state.date)
        )
    }
    override fun coverage(timeMillis: Long): List<GeoPos> {
        val state = state(timeMillis)
        val bodyPosition = state.getPVCoordinates(runtime.terrestrial).position
        val axis1 = bodyPosition.orthogonal()
        val axis2 = Vector3D.crossProduct(bodyPosition.normalize(), axis1)
        return (0..90).map { index ->
            val angle = 2 * PI * index / 90
            val reference = Vector3D(kotlin.math.cos(angle), axis1, kotlin.math.sin(angle), axis2)
            val limb = runtime.earth.pointOnLimb(bodyPosition, reference)
            val point = runtime.earth.transform(limb, runtime.terrestrial, state.date)
            GeoPos(Math.toDegrees(point.latitude), Math.toDegrees(point.longitude))
        }
    }
    private fun step(state: SpacecraftState): Long {
        val pv = state.pvCoordinates
        val rate = Vector3D.crossProduct(pv.position, pv.velocity).norm / pv.position.normSq
        return (1000 * min(30.0, min(target.data.orbitalPeriod * 60 / 720, (PI / 180) / rate))).toLong()
    }
    override fun passes(observer: GeoPos, startMillis: Long, endMillis: Long,
                        checkCancelled: () -> Unit): List<OrbitalPass> =
        batchPasses(listOf(observer), startMillis, endMillis, checkCancelled).single()

    private data class CachePlan(
        val key: String?, val start: Long, val end: Long,
        val retained: List<OrbitalPass>, val searches: List<Pair<Long, Long>>
    )

    internal fun batchPasses(observers: List<GeoPos>, startMillis: Long, endMillis: Long,
        checkCancelled: () -> Unit): List<List<OrbitalPass>> {
        require(endMillis > startMillis)
        checkCancelled()
        val cache = passCache
        if (cache == null || endMillis - startMillis > 14L * 86400000) {
            return searchPassesBatch(observers, startMillis, endMillis, checkCancelled)
        }
        var horizon = 86400000L
        while (horizon < endMillis - startMillis) horizon *= 2
        fun key(observer: GeoPos, span: Long) =
            cache.key(target, observer, "${runtime.cacheIdentity}:window-$span")
        val spans = generateSequence(horizon) { (it * 2).takeIf { next -> next <= 16L * 86400000 } }.toList()
        val allSpans = generateSequence(86400000L) { (it * 2).takeIf { next -> next <= 16L * 86400000 } }.toList()
        val keys = observers.flatMap { observer -> allSpans.map { key(observer, it) } }
        return cache.withKeys(keys) {
            checkCancelled()
            val plans = observers.map { observer ->
                val hit = spans.firstNotNullOfOrNull { cache.get(key(observer, it), startMillis, endMillis) }
                if (hit != null) {
                    diagnostic("cat=${target.data.catnum} cache=hit start=$startMillis end=$endMillis")
                    CachePlan(null, hit.start, hit.end, hit.windows.map { it.restore(target) }, emptyList())
                } else {
                    val hour = 3600000L
                    val start = maxOf(Math.floorDiv(startMillis, hour) * hour, runtime.coverageStartMillis)
                    val end = minOf((Math.floorDiv(endMillis - 1, hour) + 1) * hour, runtime.coverageEndMillis - 1)
                    val ownKey = key(observer, horizon)
                    val previous = cache.peek(ownKey) ?: allSpans.mapNotNull { cache.peek(key(observer, it)) }
                        .filter { it.start < end && it.end > start }
                        .maxByOrNull { minOf(it.end, end) - maxOf(it.start, start) }
                    incrementalPlan(ownKey, previous, start, end, horizon)
                }
            }
            val windows = plans.map { it.retained.toMutableList() }
            val searches = linkedMapOf<Pair<Long, Long>, MutableList<Int>>()
            plans.forEachIndexed { index, plan ->
                plan.searches.forEach { range -> searches.getOrPut(range) { mutableListOf() }.add(index) }
            }
            try {
                searches.forEach { (range, indices) ->
                    val result = searchPassesBatch(indices.map(observers::get), range.first, range.second, checkCancelled)
                    indices.forEachIndexed { offset, index -> windows[index].addAll(result[offset]) }
                }
                checkCancelled()
            } catch (error: OrbitPredictionException) {
                // Never publish partial coverage, including a completed left extension
                // followed by failure in the right extension.
                return@withKeys searchPassesBatch(observers, startMillis, endMillis, checkCancelled)
            }
            val ordered = windows.map { it.sortedBy(OrbitalPass::aosTime) }
            plans.forEachIndexed { index, plan ->
                plan.key?.let { cache.put(it, plan.start, plan.end, ordered[index]) }
            }
            observers.indices.map { index ->
                crop(observers[index], ordered[index], startMillis, endMillis, checkCancelled)
            }
        }
    }

    private fun incrementalPlan(key: String, old: OrbitPassCache.Entry?, start: Long, end: Long,
        span: Long): CachePlan {
        val from = minOf(start, old?.start ?: start)
        val to = maxOf(end, old?.end ?: end)
        if (old == null || old.end <= start || old.start >= end || to - from > 2 * span) {
            diagnostic("cat=${target.data.catnum} cache=miss start=$start end=$end")
            return CachePlan(key, start, end, emptyList(), listOf(start to end))
        }
        val ranges = mutableListOf<Pair<Long, Long>>()
        if (start < old.start) {
            val edge = old.windows.firstOrNull()?.takeIf { it.aos == old.start }
            // Finish inside the already searched invisible gap, not at a numerically
            // solved LOS whose residual could look like a clipped visible endpoint.
            val finish = edge?.let {
                it.los + ((old.windows.getOrNull(1)?.aos ?: old.end) - it.los) / 2
            } ?: old.start
            ranges += from to finish
        }
        if (end > old.end) {
            val edge = old.windows.lastOrNull()?.takeIf { it.los == old.end }
            val begin = edge?.let {
                val previous = old.windows.getOrNull(old.windows.lastIndex - 1)?.los ?: old.start
                previous + (it.aos - previous) / 2
            } ?: old.end
            ranges += begin to to
        }
        val merged = if (ranges.size == 2 && ranges[0].second >= ranges[1].first) {
            listOf(from to to)
        } else ranges
        val retained = old.windows.filter { window ->
            merged.none { (a, b) -> window.aos < b && window.los > a }
        }.map { it.restore(target) }
        diagnostic("cat=${target.data.catnum} cache=extension start=$from end=$to searches=$merged")
        return CachePlan(key, from, to, retained, merged)
    }

    internal fun crop(observer: GeoPos, windows: List<OrbitalPass>, startMillis: Long,
        endMillis: Long, checkCancelled: () -> Unit): List<OrbitalPass> =
        windows.flatMap { window ->
            checkCancelled()
            val start = maxOf(startMillis, window.aosTime)
            val end = minOf(endMillis, window.losTime)
            when {
                end <= start -> emptyList()
                (start != window.aosTime || end != window.losTime) && window.tcaTime in start..end -> {
                    val first = directions(listOf(observer), start).single()
                    val last = directions(listOf(observer), end).single()
                    val startClipped = if (start == window.aosTime) window.startClipped else first.elevation > 0
                    val endClipped = if (end == window.losTime) window.endClipped else last.elevation > 0
                    listOf(window.copy(
                        aosTime = start, aosAzimuth = Math.toDegrees(first.azimuth).round(1),
                        losTime = end, losAzimuth = Math.toDegrees(last.azimuth).round(1),
                        startClipped = startClipped, endClipped = endClipped,
                        continuousVisibility = startClipped && endClipped,
                        hasDecayed = target.data.hasDecayed(start)
                    ))
                }
                start != window.aosTime || end != window.losTime ->
                    searchPassesBatch(listOf(observer), start, end, checkCancelled).single().map { clipped ->
                        // Searching a truncated peak can end exactly at a previously
                        // solved real boundary. Preserve its known event identity.
                        val clippedStart = if (clipped.aosTime == window.aosTime)
                            window.startClipped else clipped.startClipped
                        val clippedEnd = if (clipped.losTime == window.losTime)
                            window.endClipped else clipped.endClipped
                        clipped.copy(startClipped = clippedStart, endClipped = clippedEnd,
                            continuousVisibility = clippedStart && clippedEnd)
                    }
                else -> listOf(window)
            }
        }

    private fun searchPassesBatch(observers: List<GeoPos>, startMillis: Long, endMillis: Long,
                             checkCancelled: () -> Unit): List<List<OrbitalPass>> {
        val topos = observers.map(::station)
        val propagationStart = propagationCount
        val transformStart = earthTransformCount
        // Bracketing revisits endpoints for elevation, slope and adaptive steps.
        // Keep only this search's recent samples; never share propagators across jobs.
        class Sample(val state: SpacecraftState) {
            private val body by lazy(LazyThreadSafetyMode.NONE) {
                earthTransformCount++
                state.getPVCoordinates(runtime.terrestrial)
            }
            private val locals = arrayOfNulls<org.orekit.utils.TimeStampedPVCoordinates>(topos.size)
            fun local(index: Int): org.orekit.utils.TimeStampedPVCoordinates =
                locals[index] ?: runtime.terrestrial.getKinematicTransformTo(topos[index], state.date)
                    .transformOnlyPV(body).also { locals[index] = it }
            fun value(index: Int) = local(index).position.delta
            fun derivative(index: Int) =
                local(index).toUnivariateDerivative1Vector().delta.firstDerivative
            fun direction(index: Int) = topos[index].getTrackingCoordinates(
                body.position, runtime.terrestrial, state.date)
        }
        val samples = object : LinkedHashMap<Long, Sample>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Sample>?) = size > 64
        }
        fun sample(time: Long): Sample {
            checkCancelled()
            return samples.getOrPut(time) { Sample(state(time)) }
        }
        val maxima = observers.map { mutableListOf<Long>() }
        var failure: OrbitBatchSearchException? = null
        val events = try {
            eventBoundariesBatch(startMillis, endMillis, observers.indices.map { index ->
                OrbitEventFunction({ sample(it).value(index) }, { sample(it).derivative(index) },
                    { maxima[index].add(it) })
            }, { time -> step(sample(time).state) }, checkCancelled)
        } catch (error: OrbitBatchSearchException) {
            failure = error
            error.boundaries
        }
        val validEnd = failure?.validUntilMillis ?: endMillis
        val results = observers.indices.map { index ->
            val boundaries = listOf(startMillis) + events[index] + validEnd
            boundaries.zipWithNext().mapNotNull { (a, b) ->
                checkCancelled()
                if (b <= a || sample(a + (b - a) / 2).value(index) <= 0) return@mapNotNull null
                val candidates = listOf(a, b) + maxima[index].filter { it in a..b }
                val tca = candidates.maxBy { sample(it).value(index) }
                val first = sample(a).direction(index)
                val last = sample(b).direction(index)
                val peak = sample(tca).state
                val peakAltitude = runtime.earth.transform(peak.position, peak.frame, peak.date).altitude / 1000
                val clippedStart = a == startMillis && sample(a).value(index) > 0
                val clippedEnd = b == validEnd && sample(b).value(index) > 0
                OrbitalPass(a, Math.toDegrees(first.azimuth).round(1), b, Math.toDegrees(last.azimuth).round(1),
                    peakAltitude.toInt(), Math.toDegrees(sample(tca).value(index)).round(1), target,
                    hasDecayed = target.data.hasDecayed(a), startClipped = clippedStart,
                    endClipped = clippedEnd, continuousVisibility = clippedStart && clippedEnd, tcaTime = tca)
            }
        }
        diagnostic("cat=${target.data.catnum} stations=${observers.size} searchStart=$startMillis " +
            "searchEnd=$endMillis propagations=${propagationCount - propagationStart} " +
            "earthTransforms=${earthTransformCount - transformStart} partial=${failure != null}")
        failure?.let { throw OrbitPredictionException(validEnd, results.firstOrNull().orEmpty(), it) }
        return results
    }
}
