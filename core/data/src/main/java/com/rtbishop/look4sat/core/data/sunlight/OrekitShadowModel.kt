package com.rtbishop.look4sat.core.data.sunlight

import com.rtbishop.look4sat.core.domain.predict.TemeState
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.orekit.bodies.AnalyticalSolarPositionProvider
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.data.ClasspathCrawler
import org.orekit.data.LazyLoadedDataContext
import org.orekit.propagation.SpacecraftState
import org.orekit.propagation.events.EclipseDetector
import org.orekit.time.AbsoluteDate
import org.orekit.utils.AbsolutePVCoordinates
import org.orekit.utils.Constants
import org.orekit.utils.PVCoordinates
import java.util.Date
import kotlin.math.PI
import kotlin.math.min

/**
 * Orekit 13.1.4 (Apache-2.0), upstream 70439f1957f851ad131b9fb50d9e94db432d92ec.
 * No global DataContext, JPL download, host orekit-data directory, or observer is used.
 */
internal class OrekitShadowModel {
    private val context = LazyLoadedDataContext().apply {
        dataProvidersManager.addProvider(ClasspathCrawler("orekit/UTC-TAI.history"))
    }
    private val utc = context.getTimeScales().getUTC()
    private val teme = context.getFrames().getTEME()

    // The WGS84 surface is axisymmetric: rotation about TEME's true-equator pole cannot
    // change its limb. This geometric model neglects polar motion and atmosphere.
    private val earth = OneAxisEllipsoid(
        Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
        Constants.WGS84_EARTH_FLATTENING, teme
    )
    private val sun = AnalyticalSolarPositionProvider(context)
    private val umbra = EclipseDetector(sun, Constants.SUN_RADIUS, earth).withUmbra()
    private val penumbra = umbra.withPenumbra()

    fun sample(snapshot: TemeState, periodSeconds: Double): ShadowSample {
        val components = doubleArrayOf(
            snapshot.xKm, snapshot.yKm, snapshot.zKm,
            snapshot.vxKmPerSecond, snapshot.vyKmPerSecond, snapshot.vzKmPerSecond
        )
        require(components.all { it.isFinite() }) { "Non-finite TEME state" }
        val position = Vector3D(snapshot.xKm * 1000, snapshot.yKm * 1000, snapshot.zKm * 1000)
        require(!earth.isInside(position)) { "Propagated position is inside Earth" }
        val velocity = Vector3D(
            snapshot.vxKmPerSecond * 1000, snapshot.vyKmPerSecond * 1000, snapshot.vzKmPerSecond * 1000
        )
        val date = AbsoluteDate(Date(snapshot.timeMillis), utc)
        val state = SpacecraftState(AbsolutePVCoordinates(teme, date, PVCoordinates(position, velocity)))
        val angularRate = Vector3D.crossProduct(position, velocity).norm / position.normSq
        // Bound both elapsed time and orbital angle, including fast perigee motion.
        val step = min(30.0, min(periodSeconds / 180, (PI / 180) / angularRate)).coerceAtLeast(0.25)
        return ShadowSample(penumbra.g(state), umbra.g(state), step)
    }
}
