package com.rtbishop.look4sat.core.domain.predict.legacy

import com.rtbishop.look4sat.core.domain.astro.IOrbitEngine
import com.rtbishop.look4sat.core.domain.astro.IOrbitSession
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject as Target

fun OrbitalData.legacyObject(): OrbitalObject =
    if (isDeepSpace) DeepSpaceObject(this) else NearEarthObject(this)

/** Offline historical reference only; this source set is never packaged in the app. */
class LegacyOrbitEngine : IOrbitEngine {
    override fun bind(elements: OrbitalData) = Target(elements)
    override fun openSession(target: Target): IOrbitSession = object : IOrbitSession {
        override val target = target
        private val orbit = target.data.legacyObject()
        override fun position(observer: GeoPos, timeMillis: Long) = orbit.getPosition(observer, timeMillis)
        override fun elevation(observer: GeoPos, timeMillis: Long) = orbit.getElevation(observer, timeMillis)
        override fun temeState(timeMillis: Long) = orbit.getTemeState(timeMillis)
        override fun coverage(timeMillis: Long) = emptyList<GeoPos>()
        override fun passes(observer: GeoPos, startMillis: Long, endMillis: Long,
                            checkCancelled: () -> Unit): List<OrbitalPass> {
            val result = mutableListOf<OrbitalPass>()
            var start: Long? = startMillis.takeIf { elevation(observer, it) > 0 }
            var peak = 0.0
            var time = startMillis
            while (time < endMillis) {
                checkCancelled()
                val next = minOf(time + 10000, endMillis)
                val a = elevation(observer, time)
                val b = elevation(observer, next)
                peak = maxOf(peak, Math.toDegrees(a), Math.toDegrees(b))
                if (a * b < 0) {
                    var left = time
                    var right = next
                    while (right - left > 250) {
                        val mid = left + (right - left) / 2
                        if ((elevation(observer, mid) > 0) == (a > 0)) left = mid else right = mid
                    }
                    if (b > 0) { start = right; peak = 0.0 }
                    else {
                        start?.let { result.add(OrbitalPass(aosTime = it, losTime = right,
                            maxElevation = peak, orbitalObject = target, startClipped = it == startMillis)) }
                        start = null
                    }
                }
                time = next
            }
            start?.let { result.add(OrbitalPass(aosTime = it, losTime = endMillis,
                maxElevation = peak, orbitalObject = target, startClipped = it == startMillis,
                endClipped = true, continuousVisibility = it == startMillis)) }
            return result
        }
    }
}
