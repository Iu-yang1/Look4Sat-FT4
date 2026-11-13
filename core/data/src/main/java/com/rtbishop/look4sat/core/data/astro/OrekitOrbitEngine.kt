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

class OrekitOrbitEngine(
    private val data: IAstroDataRepository,
    cacheDirectory: java.io.File? = null
) : IOrbitEngine {
    private val passCache = cacheDirectory?.let(::OrbitPassCache)
    override fun bind(elements: OrbitalData) = OrbitalObject(elements, data.snapshot())
    override fun openSession(target: OrbitalObject): IOrbitSession =
        OrekitOrbitSession(target.copy(snapshot = target.snapshot ?: data.snapshot()), passCache)
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
    private val passCache: OrbitPassCache? = null
) : IOrbitSession {
    internal val runtime = target.snapshot as OrekitRuntime
    private val propagator = TLEPropagator.selectExtrapolator(orekitTle(target.data, runtime), runtime.teme)
    private val stations = mutableMapOf<GeoPos, org.orekit.frames.TopocentricFrame>()
    private fun station(observer: GeoPos) = stations.getOrPut(observer) { runtime.station(observer) }
    internal fun state(time: Long): SpacecraftState {
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
        return observers.map { observer ->
            val coordinates = station(observer).getTrackingCoordinates(state.position, state.frame, state.date)
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
                        checkCancelled: () -> Unit): List<OrbitalPass> {
        require(endMillis > startMillis)
        checkCancelled()
        val cache = passCache
        if (cache == null || endMillis - startMillis > 14L * 86400000) {
            return searchPasses(observer, startMillis, endMillis, checkCancelled)
        }
        val key = cache.key(target, observer, runtime.cacheIdentity)
        val entry = cache.get(key, startMillis, endMillis) ?: run {
            val hour = 3600000L
            val start = maxOf(Math.floorDiv(startMillis, hour) * hour, runtime.coverageStartMillis)
            val end = minOf((Math.floorDiv(endMillis, hour) + 1) * hour, runtime.coverageEndMillis - 1)
            try {
                val windows = searchPasses(observer, start, end, checkCancelled)
                checkCancelled()
                cache.put(key, start, end, windows)
                cache.get(key, startMillis, endMillis)
            } catch (error: OrbitPredictionException) {
                // Padding must never turn a valid requested interval into a failure.
                return searchPasses(observer, startMillis, endMillis, checkCancelled)
            }
        } ?: return searchPasses(observer, startMillis, endMillis, checkCancelled)
        return entry.windows.flatMap { window ->
            checkCancelled()
            val start = maxOf(startMillis, window.aos)
            val end = minOf(endMillis, window.los)
            when {
                end <= start -> emptyList()
                (start != window.aos || end != window.los) && window.tca in start..end -> {
                    val first = directions(listOf(observer), start).single()
                    val last = directions(listOf(observer), end).single()
                    val startClipped = if (start == window.aos) window.startClipped else first.elevation > 0
                    val endClipped = if (end == window.los) window.endClipped else last.elevation > 0
                    listOf(window.restore(target).copy(
                        aosTime = start, aosAzimuth = Math.toDegrees(first.azimuth).round(1),
                        losTime = end, losAzimuth = Math.toDegrees(last.azimuth).round(1),
                        startClipped = startClipped, endClipped = endClipped,
                        continuousVisibility = startClipped && endClipped,
                        hasDecayed = target.data.hasDecayed(start)
                    ))
                }
                start != window.aos || end != window.los ->
                    searchPasses(observer, start, end, checkCancelled)
                else -> listOf(window.restore(target))
            }
        }
    }

    private fun searchPasses(observer: GeoPos, startMillis: Long, endMillis: Long,
                             checkCancelled: () -> Unit): List<OrbitalPass> {
        val topo = station(observer)
        // Bracketing revisits endpoints for elevation, slope and adaptive steps.
        // Keep only this search's recent samples; never share propagators across jobs.
        class Sample(val state: SpacecraftState) {
            private val local by lazy(LazyThreadSafetyMode.NONE) {
                state.frame.getKinematicTransformTo(topo, state.date).transformOnlyPV(state.pvCoordinates)
            }
            // Same Hipparchus angle and derivative used by Orekit's elevation detectors;
            // share the expensive frame conversion for both event functions.
            val value by lazy(LazyThreadSafetyMode.NONE) { local.position.delta }
            val derivative by lazy(LazyThreadSafetyMode.NONE) {
                local.toUnivariateDerivative1Vector().delta.firstDerivative
            }
        }
        val samples = object : LinkedHashMap<Long, Sample>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Sample>?) = size > 16
        }
        fun sample(time: Long): Sample {
            checkCancelled()
            return samples.getOrPut(time) { Sample(state(time)) }
        }
        val value = { time: Long -> sample(time).value }
        val maxima = mutableListOf<Long>()
        var failure: OrbitEventSearchException? = null
        val events = try {
            eventBoundaries(startMillis, endMillis, value,
                { time -> sample(time).derivative }, { time -> step(sample(time).state) }, checkCancelled,
                { maxima.add(it) })
        } catch (error: OrbitEventSearchException) {
            failure = error
            error.boundaries
        }
        val validEnd = failure?.validUntilMillis ?: endMillis
        val boundaries = listOf(startMillis) + events + validEnd
        val passes = boundaries.zipWithNext().mapNotNull { (a, b) ->
            checkCancelled()
            if (b <= a) return@mapNotNull null
            if (value(a + (b - a) / 2) <= 0) return@mapNotNull null
            val candidates = listOf(a, b) + maxima.filter { it in a..b }
            val tca = candidates.maxBy { value(it) }
            fun coordinates(time: Long) = sample(time).state.let {
                topo.getTrackingCoordinates(it.position, it.frame, it.date)
            }
            val first = coordinates(a)
            val last = coordinates(b)
            val peak = sample(tca).state
            val peakAltitude = runtime.earth.transform(peak.position, peak.frame, peak.date).altitude / 1000
            val clippedStart = a == startMillis && value(a) > 0
            val clippedEnd = b == validEnd && value(b) > 0
            OrbitalPass(a, Math.toDegrees(first.azimuth).round(1), b, Math.toDegrees(last.azimuth).round(1),
                peakAltitude.toInt(), Math.toDegrees(value(tca)).round(1), target,
                hasDecayed = target.data.hasDecayed(a), startClipped = clippedStart,
                endClipped = clippedEnd, continuousVisibility = clippedStart && clippedEnd, tcaTime = tca)
        }
        failure?.let { throw OrbitPredictionException(validEnd, passes, it) }
        return passes
    }
}
