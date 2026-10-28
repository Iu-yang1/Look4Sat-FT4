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
import kotlin.math.sqrt

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

/**
 * Shared with the matching-station marker in MapScreen: labels and markers must
 * use precisely the same shade of blue.
 */
internal val MATCHED_STATION_BLUE = 0xFF1976D2.toInt()
private val LOCAL_ELEVATION_YELLOW = 0xFFF5E6A6.toInt()

/**
 * Topocentric elevation of the selected satellite from another ground location.
 *
 * OrbitalPos latitude/longitude are radians; GeoPos uses degrees. Both the
 * matched-station reading and the existing local-station reading use the same
 * selected satellite and scrubbed instant (MapState.footprint).
 */
internal fun elevationAtGroundStation(satellite: OrbitalPos?, station: GeoPos?): Double? {
    if (satellite == null || station == null || !satellite.altitude.isFinite() ||
        satellite.altitude <= 0.0
    ) return null

    val observerLat = Math.toRadians(station.latitude)
    val observerLon = Math.toRadians(station.longitude)
    val cosCentralAngle = (
        sin(satellite.latitude) * sin(observerLat) +
        cos(satellite.latitude) * cos(observerLat) * cos(satellite.longitude - observerLon)
    ).coerceIn(-1.0, 1.0)
    val earthToSatelliteRadius = 6370.0 / (6370.0 + satellite.altitude)
    val sinCentralAngle = sqrt((1.0 - cosCentralAngle * cosCentralAngle).coerceAtLeast(0.0))
    return Math.toDegrees(atan2(cosCentralAngle - earthToSatelliteRadius, sinCentralAngle))
}

internal class ElevationContourOverlay : FolderOverlay() {
    // Muted Morandi palette (Option A); keep the order stable when users add rings.
    private val palette = intArrayOf(
        0xFFA8C3CC.toInt(), // powder blue
        0xFFB4C3A5.toInt(), // sage
        0xFFD1BDCF.toInt(), // dusty lilac
        0xFFD8C29F.toInt(), // sand
        0xFFCBAEAC.toInt(), // dusty rose
        0xFFABB8C5.toInt()  // blue grey
    )

    fun update(satellite: OrbitalPos?, elevations: List<Double>, enabled: Boolean, mapView: MapView) {
        items.clear()
        if (!enabled || satellite == null) return

        elevations.forEachIndexed { index, angle ->
            val circle = elevationContour(satellite, angle)
            if (circle.size < 360) return@forEachIndexed
            val color = palette[index % palette.size]
            val text = if (angle % 1.0 == 0.0) "${angle.toInt()}°" else "${angle}°"

            // Find the easternmost point in the selected satellite's local longitude
            // frame, not the clipped ±180° world copy. This avoids putting a label
            // on the left of the ring when the satellite is near the date line.
            val satelliteLon = Math.toDegrees(satellite.longitude)
            val anchorIndex = (0 until 360).maxByOrNull { i ->
                ((circle[i].longitude - satelliteLon + 540.0) % 360.0) - 180.0
            } ?: return@forEachIndexed
            val anchorGeo = circle[anchorIndex]
            val anchorPx = mapView.projection.toPixels(anchorGeo, Point())
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 27f }
            val halfGapPx = labelPaint.measureText(text) / 2f + 9f

            // Cut enough actual contour points to fit the label in SCREEN pixels.
            // A fixed angular gap would become tiny on zoom-in and huge on zoom-out.
            fun gapSteps(direction: Int): Int {
                var count = 0
                for (step in 1..70) {
                    val sample = circle[(anchorIndex + direction * step + 360) % 360]
                    val p = mapView.projection.toPixels(sample, Point())
                    val distance = kotlin.math.hypot(
                        (p.x - anchorPx.x).toDouble(), (p.y - anchorPx.y).toDouble()
                    )
                    // Projection can wrap at the anti-meridian; do not interpret
                    // that world-width jump as a long segment of the contour.
                    if (distance > halfGapPx && distance < mapView.width.coerceAtLeast(1) * 0.8) break
                    count++
                }
                return count
            }
            val gap = maxOf(gapSteps(-1), gapSteps(1)).coerceAtMost(70)
            val startIndex = (anchorIndex + gap + 1) % 360
            val pointCount = 360 - 2 * gap - 1

            // Start immediately after the deleted right-hand section, traverse
            // the rest of the ring and stop before the gap. This also keeps the
            // top of the circle continuous instead of splitting at bearing=0.
            var segment = ArrayList<GeoPoint>(pointCount)
            fun flush() {
                if (segment.size < 2) return
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    alpha = 217
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    pathEffect = DashPathEffect(floatArrayOf(13f, 9f), 0f)
                }
                add(Polyline().apply {
                    outlinePaint.set(stroke)
                    setPoints(segment)
                })
                segment = ArrayList()
            }
            repeat(pointCount) { offset ->
                val point = circle[(startIndex + offset) % 360]
                val lon = ((point.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                if (segment.isNotEmpty() && kotlin.math.abs(segment.last().longitude - lon) > 180.0) flush()
                segment.add(GeoPoint(point.latitude, lon))
            }
            flush()
            // Text is a separate overlay *above* the segmented dashed lines;
            // it takes exactly the space cleared on the ring's rightmost edge.
            add(ElevationCircleLabelOverlay(anchorGeo, text, color))
        }
    }
}

private class ElevationCircleLabelOverlay(
    private val anchor: GeoPoint,
    private val text: String,
    private val color: Int
) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@ElevationCircleLabelOverlay.color
        textSize = 27f
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.BLACK
        alpha = 210
        textSize = 27f
        strokeWidth = 3f
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
    }
    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val point = mapView.projection.toPixels(anchor, Point())
        // Paint baseline shifted so the number sits centrally in the missing section.
        val baseline = point.y - (fill.ascent() + fill.descent()) / 2f
        canvas.drawText(text, point.x.toFloat(), baseline, outline)
        canvas.drawText(text, point.x.toFloat(), baseline, fill)
    }
}

/**
 * On-map elevation readouts at both ground stations. The local reading is
 * supplied by the existing predictor, while the matched station's elevation
 * is calculated from the same OrbitalPos instant.
 */
internal class StationElevationOverlay : Overlay() {
    var position: GeoPos? = null
    var elevation: Double? = null
    var otherPosition: GeoPos? = null
    var otherElevation: Double? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
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
        drawElevation(canvas, mapView, position, elevation, LOCAL_ELEVATION_YELLOW)
        drawElevation(canvas, mapView, otherPosition, otherElevation, MATCHED_STATION_BLUE)
    }

    private fun drawElevation(
        canvas: Canvas,
        mapView: MapView,
        station: GeoPos?,
        value: Double?,
        color: Int
    ) {
        val position = station ?: return
        val angle = value?.takeIf { it.isFinite() && it >= 0.0 } ?: return
        val point = mapView.projection.toPixels(GeoPoint(position.latitude, position.longitude), Point())
        val label = String.format(java.util.Locale.US, "%.1f°", angle)
        val x = point.x + 18f
        val y = point.y - 12f
        textPaint.color = color
        canvas.drawText(label, x, y, outlinePaint)
        canvas.drawText(label, x, y, textPaint)
    }
}
