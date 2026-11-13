package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.IOrbitSurfaceGeometry
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import org.hipparchus.analysis.UnivariateFunction
import org.hipparchus.analysis.solvers.BrentSolver
import org.hipparchus.geometry.euclidean.threed.Line
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.orekit.frames.TopocentricFrame
import org.orekit.time.AbsoluteDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Immutable body-fixed position and pinned ellipsoid, shared only for surface geometry. */
internal class OrekitSurfaceGeometry(
    private val runtime: OrekitRuntime,
    private val position: Vector3D,
    private val date: AbsoluteDate
) : IOrbitSurfaceGeometry {
    override fun elevationDegrees(observer: GeoPos): Double = Math.toDegrees(
        runtime.station(observer).getElevation(position, runtime.terrestrial, date)
    )

    override fun contour(elevationDegrees: Double, checkCancelled: () -> Unit): List<GeoPos> {
        require(elevationDegrees in 0.0..90.0)
        val earth = runtime.earth
        val subpoint = earth.transform(position, runtime.terrestrial, date)
        val ground = org.orekit.bodies.GeodeticPoint(subpoint.latitude, subpoint.longitude, 0.0)
        if (elevationDegrees == 90.0) return List(361) {
            GeoPos(Math.toDegrees(ground.latitude), Math.toDegrees(ground.longitude))
        }
        val radial = earth.transform(ground).normalize()
        val east = ground.east
        val north = Vector3D.crossProduct(radial, east).normalize()
        val target = Math.toRadians(elevationDegrees)
        return (0..360).map { index ->
            checkCancelled()
            val bearing = Math.toRadians(index.toDouble())
            val horizontal = Vector3D(cos(bearing), north, sin(bearing), east)
            fun point(angle: Double): org.orekit.bodies.GeodeticPoint {
                val direction = Vector3D(cos(angle), radial, sin(angle), horizontal)
                return requireNotNull(earth.getIntersectionPoint(
                    Line(Vector3D.ZERO, direction, 1e-10),
                    direction.scalarMultiply(earth.equatorialRadius), runtime.terrestrial, date
                ))
            }
            val angle = BrentSolver(1e-11).solve(100, UnivariateFunction {
                checkCancelled()
                TopocentricFrame(earth, point(it), "contour")
                    .getElevation(position, runtime.terrestrial, date) - target
            }, 0.0, PI)
            val result = point(angle)
            GeoPos(Math.toDegrees(result.latitude), Math.toDegrees(result.longitude))
        }
    }
}
