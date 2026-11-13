package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.AstroDataSnapshot
import com.rtbishop.look4sat.core.domain.astro.AstroDateOutsideCoverageException
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.data.DataLoader
import org.orekit.data.DirectoryCrawler
import org.orekit.data.LazyLoadedDataContext
import org.orekit.frames.TopocentricFrame
import org.orekit.propagation.events.EclipseDetector
import org.orekit.time.AbsoluteDate
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions
import java.io.File
import java.io.InputStream
import java.util.Date

/** One immutable data generation. Never installs a process-global Orekit context. */
internal class OrekitRuntime(val directory: File, metadata: AstroPackageMetadata) : AstroDataSnapshot {
    override val version = metadata.version
    override val coverageStartMillis = metadata.start
    override val coverageEndMillis = metadata.end
    override val eopEndMillis = metadata.eopEnd
    val builtIn = metadata.builtIn
    val cacheIdentity = directory.name + ":" + metadata.version + ":" +
        metadata.files.joinToString(";") { "${it.name}:${it.sha256}" }
    val context = LazyLoadedDataContext().apply {
        val crawler = DirectoryCrawler(directory)
        dataProvidersManager.addProvider { names, loader, manager ->
            crawler.feed(names, object : DataLoader {
                override fun stillAcceptsData() = loader.stillAcceptsData()
                override fun loadData(input: InputStream, name: String) {
                    input.buffered(128 * 1024).use { loader.loadData(it, name) }
                }
            }, manager)
        }
    }
    private fun <T> load(stage: String, block: () -> T): T {
        val started = System.nanoTime()
        return block().also {
            android.util.Log.i("Look4SatStartup",
                "stage=$stage elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    }
    val utc = load("utc") { context.timeScales.utc }
    private val cachedEop = load("eop-cache") {
        OrekitEopCache.read(directory, cacheIdentity, utc)
    }
    init {
        cachedEop?.let { entries ->
            context.frames.clearEOPHistoryLoaders()
            context.frames.addEOPHistoryLoader(IERSConventions.IERS_2010) { _, history ->
                history.addAll(entries)
            }
        }
    }
    val teme = context.frames.teme
    val inertial = context.frames.gcrf
    val terrestrial = load("earth-frame") { context.frames.getITRF(IERSConventions.IERS_2010, false) }
    val earth = OneAxisEllipsoid(
        Constants.WGS84_EARTH_EQUATORIAL_RADIUS, Constants.WGS84_EARTH_FLATTENING, terrestrial
    )
    val sun = load("sun") { context.celestialBodies.sun }
    val moon = load("moon") { context.celestialBodies.moon }
    val umbra = EclipseDetector(sun, Constants.SUN_RADIUS, earth).withUmbra()
    val penumbra = umbra.withPenumbra()
    private val eopHistory = context.frames.getEOPHistory(IERSConventions.IERS_2010, false)
    override val eopStartMillis = eopHistory.startDate.toDate(utc).time

    init {
        require(coverageStartMillis <= 1577836800000L && coverageEndMillis >= 2556144000000L) {
            "Astronomy package must cover 2020–2050"
        }
        require(kotlin.math.abs(eopHistory.endDate.toDate(utc).time - eopEndMillis) <= 86400000L) {
            "Earth orientation coverage does not match the package manifest"
        }
        metadata.eopStart?.let { start ->
            require(kotlin.math.abs(eopStartMillis - start) <= 86400000L) {
                "Earth orientation start does not match the package manifest"
            }
        }
        if (cachedEop == null) {
            try {
                OrekitEopCache.write(directory, cacheIdentity, eopHistory.entries)
            } catch (error: java.io.IOException) {
                android.util.Log.w("Look4SatStartup", "Unable to cache parsed EOP", error)
            } catch (error: IllegalStateException) {
                android.util.Log.w("Look4SatStartup", "Unable to cache parsed EOP", error)
            }
        }
    }

    /** Run once when accepting a package, not on every launch of an unchanged generation. */
    fun validateCoverage(checkCancelled: () -> Unit) {
        for (time in listOf(coverageStartMillis, coverageEndMillis - 1)) {
            checkCancelled()
            val date = date(time)
            for (body in listOf(sun, moon)) {
                val pv = body.getPVCoordinates(date, inertial)
                require(pv.position.norm.isFinite() && pv.position.norm > 0)
            }
            terrestrial.getTransformTo(inertial, date)
        }
    }

    fun date(timeMillis: Long): AbsoluteDate {
        if (timeMillis < coverageStartMillis || timeMillis >= coverageEndMillis) {
            throw AstroDateOutsideCoverageException()
        }
        return AbsoluteDate(Date(timeMillis), utc)
    }

    fun station(observer: GeoPos) = TopocentricFrame(
        earth, GeodeticPoint(Math.toRadians(observer.latitude), Math.toRadians(observer.longitude),
            observer.altitude), "station"
    )
}
