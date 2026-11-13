package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot
import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
import com.rtbishop.look4sat.core.domain.astro.ICelestialService
import com.rtbishop.look4sat.core.domain.predict.CelestialState
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlin.math.asin

class OrekitCelestialService(private val data: IAstroDataRepository) : ICelestialService {
    private fun runtime(snapshot: AstroDataSnapshot?) = (snapshot ?: data.snapshot()) as OrekitRuntime

    override fun sun(observer: GeoPos, timeMillis: Long, snapshot: AstroDataSnapshot?): CelestialState.SunPosition {
        val runtime = runtime(snapshot)
        val date = runtime.date(timeMillis)
        val station = runtime.station(observer)
        val pv = runtime.sun.getPVCoordinates(date, runtime.inertial)
        val direction = station.getTrackingCoordinates(pv.position, runtime.inertial, date)
        val ground = runtime.earth.transform(pv.position, runtime.inertial, date)
        return CelestialState.SunPosition(
            Math.toDegrees(direction.azimuth), Math.toDegrees(direction.elevation), direction.range / 1000,
            station.getRangeRate(pv, runtime.inertial, date) / 1000,
            Math.toDegrees(ground.latitude), Math.toDegrees(ground.longitude),
            (Math.toDegrees(pv.position.alpha) + 360) % 360, Math.toDegrees(pv.position.delta)
        )
    }

    override fun moon(observer: GeoPos, timeMillis: Long, snapshot: AstroDataSnapshot?): CelestialState.MoonPosition {
        val runtime = runtime(snapshot)
        val date = runtime.date(timeMillis)
        val station = runtime.station(observer)
        val pv = runtime.moon.getPVCoordinates(date, runtime.inertial)
        val direction = station.getTrackingCoordinates(pv.position, runtime.inertial, date)
        val ground = runtime.earth.transform(pv.position, runtime.inertial, date)
        return CelestialState.MoonPosition(
            Math.toDegrees(direction.azimuth), Math.toDegrees(direction.elevation),
            (Math.toDegrees(pv.position.alpha) + 360) % 360, Math.toDegrees(pv.position.delta),
            (-Math.toDegrees(ground.longitude) + 360) % 360,
            Math.toDegrees(2 * asin(1737400.0 / direction.range)),
            station.getRangeRate(pv, runtime.inertial, date),
            Math.toDegrees(ground.latitude), Math.toDegrees(ground.longitude)
        )
    }

    override fun riseSet(observer: GeoPos, startMillis: Long, endMillis: Long, moon: Boolean,
                         snapshot: AstroDataSnapshot?, checkCancelled: () -> Unit): CelestialState.RiseSetTimes {
        val runtime = runtime(snapshot)
        val station = runtime.station(observer)
        val body = if (moon) runtime.moon else runtime.sun
        val threshold = if (moon) 0.0 else Math.toRadians(-0.8333)
        val value = { time: Long ->
            val date = runtime.date(time)
            station.getElevation(body.getPosition(date, runtime.inertial), runtime.inertial, date) - threshold
        }
        val derivative = { time: Long ->
            (value((time + 500).coerceAtMost(endMillis)) -
                value((time - 500).coerceAtLeast(startMillis)))
        }
        val events = eventBoundaries(startMillis, endMillis, value, derivative, { 30_000 }, checkCancelled)
        val rise = events.firstOrNull { value((it + 1000).coerceAtMost(endMillis)) > 0 } ?: 0L
        val set = events.firstOrNull { value((it + 1000).coerceAtMost(endMillis)) < 0 } ?: 0L
        return CelestialState.RiseSetTimes(rise, set,
            alwaysAbove = events.isEmpty() && value(startMillis) > 0,
            alwaysBelow = events.isEmpty() && value(startMillis) <= 0)
    }
}
