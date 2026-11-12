package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.predict.TemeState
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.orekit.propagation.SpacecraftState
import org.orekit.utils.AbsolutePVCoordinates
import org.orekit.utils.PVCoordinates
import kotlin.math.PI
import kotlin.math.min

/**
 * Orekit 13.1.4 (Apache-2.0), upstream 70439f1957f851ad131b9fb50d9e94db432d92ec.
 * Uses the same pinned JPL Sun and WGS84 Earth as tracking.
 */
internal class OrekitShadowModel(private val runtime: com.rtbishop.look4sat.core.data.astro.OrekitRuntime) {
    private val earth = runtime.earth
    private val teme = runtime.teme
    private val umbra = runtime.umbra
    private val penumbra = runtime.penumbra

    fun sample(snapshot: TemeState, periodSeconds: Double): ShadowSample {
        val components = doubleArrayOf(
            snapshot.xKm, snapshot.yKm, snapshot.zKm,
            snapshot.vxKmPerSecond, snapshot.vyKmPerSecond, snapshot.vzKmPerSecond
        )
        require(components.all { it.isFinite() }) { "Non-finite TEME state" }
        val position = Vector3D(snapshot.xKm * 1000, snapshot.yKm * 1000, snapshot.zKm * 1000)
        val velocity = Vector3D(
            snapshot.vxKmPerSecond * 1000, snapshot.vyKmPerSecond * 1000, snapshot.vzKmPerSecond * 1000
        )
        val date = runtime.date(snapshot.timeMillis)
        val terrestrialPosition = teme.getStaticTransformTo(runtime.terrestrial, date).transformPosition(position)
        require(!earth.isInside(terrestrialPosition)) { "Propagated position is inside Earth" }
        val state = SpacecraftState(AbsolutePVCoordinates(teme, date, PVCoordinates(position, velocity)))
        val angularRate = Vector3D.crossProduct(position, velocity).norm / position.normSq
        // Bound both elapsed time and orbital angle, including fast perigee motion.
        val step = min(30.0, min(periodSeconds / 180, (PI / 180) / angularRate)).coerceAtLeast(0.25)
        return ShadowSample(penumbra.g(state), umbra.g(state), step)
    }
}
