package com.rtbishop.look4sat.feature.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-elevation contours: the observer's look angle to the satellite is constant at
 * each point of a contour. The sub-satellite point is the spherical-circle centre.
 * altitude and EARTH_RADIUS are in km, lat/lon in radians in OrbitalPos.
 */
internal fun elevationContour(satellite: OrbitalPos, elevationDegrees: Double): List<GeoPoint> {
    if (elevationDegrees !in 0.0..90.0 || satellite.altitude <= 0.0) return emptyList()
    val earthRadiusKm = 6370.0
    val ratio = earthRadiusKm / (earthRadiusKm + satellite.altitude)
    val elevation = Math.toRadians(elevationDegrees)
    // sin(elevation) = (cos(beta) - ratio) / sqrt(1 + ratio^2 - 2*ratio*cos(beta)).
    // This closed form chooses the physical, near-side solution.
    val cosBeta = ratio * cos(elevation).let { it * it } +
        sin(elevation) * kotlin.math.sqrt(1.0 - ratio * ratio * cos(elevation) * cos(elevation))
    val beta = acos(cosBeta.coerceIn(-1.0, 1.0))
    val sinLat = sin(satellite.latitude)
    val cosLat = cos(satellite.latitude)
    return (0..360).map { step ->
        val bearing = Math.toRadians(step.toDouble())
        val lat = asin((sinLat * cos(beta) + cosLat * sin(beta) * cos(bearing)).coerceIn(-1.0, 1.0))
        val lon = satellite.longitude + atan2(
            sin(bearing) * sin(beta) * cosLat,
            cos(beta) - sinLat * sin(lat)
        )
        GeoPoint(Math.toDegrees(lat), Math.toDegrees(lon))
    }
}

internal class ElevationContourOverlay : FolderOverlay() {
    private val palette = intArrayOf(
        Color.CYAN, Color.YELLOW, Color.MAGENTA, Color.GREEN,
        Color.rgb(255, 145, 0), Color.rgb(142, 119, 255)
    )

    fun update(satellite: OrbitalPos?, elevations: List<Double>, enabled: Boolean) {
        items.clear()
        if (!enabled || satellite == null) return
        elevations.forEachIndexed { index, angle ->
            val contour = elevationContour(satellite, angle)
            if (contour.isEmpty()) return@forEachIndexed
            // Split at the anti-meridian to prevent a spurious line across the world.
            var segment = ArrayList<GeoPoint>()
            fun flush() {
                if (segment.size < 2) return
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette[index % palette.size]
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    pathEffect = DashPathEffect(floatArrayOf(13f, 9f), 0f)
                }
                add(Polyline().apply {
                    outlinePaint.set(stroke)
                    setPoints(segment)
                })
                segment = ArrayList()
            }
            for (point in contour) {
                val lon = ((point.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                if (segment.isNotEmpty() && kotlin.math.abs(segment.last().longitude - lon) > 180.0) flush()
                segment.add(GeoPoint(point.latitude, lon))
            }
            flush()
        }
    }
}

/** On-map real/scrubbed elevation readout beside the local station marker. */
internal class StationElevationOverlay : Overlay() {
    var position: GeoPos? = null
    var elevation: Double? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val station = position ?: return
        val angle = elevation?.takeIf { it >= 0.0 && it.isFinite() } ?: return
        val point = mapView.projection.toPixels(GeoPoint(station.latitude, station.longitude), Point())
        val label = String.format(java.util.Locale.US, "%.1f°", angle)
        val x = point.x + 18f
        val y = point.y - 12f
        canvas.drawText(label, x, y, outlinePaint)
        canvas.drawText(label, x, y, textPaint)
    }
}
