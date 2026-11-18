package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.data.repository.FakeSettingsRepo
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.source.Sources
import kotlinx.coroutines.runBlocking
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.orekit.utils.Constants
import org.orekit.utils.CartesianDerivativesFilter
import org.orekit.utils.TimeStampedPVCoordinatesHermiteInterpolator
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.*

class IssOemOrbitTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
    private val observer = GeoPos(28.68, 115.9, 45.0)
    private val nasa = Sources.issOemUrl
    private val elements = OrbitalData("ISS (ZARYA)", 26322.0, 15.49, .0005, 51.64,
        123.0, 87.0, 3.0, 25544, 0.0001)
    private val radius = 6_780_000.0
    private val rate = sqrt(Constants.WGS84_EARTH_MU / radius.pow(3))

    private fun position(seconds: Double, offset: Double = 0.0) =
        Vector3D((radius + offset) * cos(rate * seconds), (radius + offset) * sin(rate * seconds), 0.0)

    private fun velocity(seconds: Double, offset: Double = 0.0) =
        Vector3D(-(radius + offset) * rate * sin(rate * seconds), (radius + offset) * rate * cos(rate * seconds), 0.0)

    private fun timestamp(time: Long) = Instant.ofEpochMilli(time).toString().removeSuffix("Z")

    private fun fixture(creation: Long = epoch, offset: Double = 0.0, gap: Boolean = false): ByteArray =
        buildString {
            appendLine("CCSDS_OEM_VERS = 2.0")
            appendLine("CREATION_DATE = ${timestamp(creation)}")
            appendLine("ORIGINATOR = TEST")
            val ranges = if (gap) listOf(0..4, 6..10) else listOf(0..10)
            for (range in ranges) {
                appendLine("META_START")
                appendLine("OBJECT_NAME = ISS")
                appendLine("OBJECT_ID = 1998-067-A")
                appendLine("CENTER_NAME = Earth")
                appendLine("REF_FRAME = EME2000")
                appendLine("TIME_SYSTEM = UTC")
                appendLine("START_TIME = ${timestamp(epoch + range.first * 240000L)}")
                appendLine("STOP_TIME = ${timestamp(epoch + range.last * 240000L)}")
                appendLine("META_STOP")
                appendLine("COMMENT Synthetic circular trajectory; not NASA observations")
                for (sample in range) {
                    val seconds = sample * 240.0
                    val q = position(seconds, offset).scalarMultiply(.001)
                    val v = velocity(seconds, offset).scalarMultiply(.001)
                    appendLine(String.format(Locale.ROOT, "%s %.12f %.12f %.12f %.12f %.12f %.12f",
                        timestamp(epoch + sample * 240000L), q.x, q.y, q.z, v.x, v.y, v.z))
                }
            }
        }.toByteArray()

    @Test fun contentDetectionDoesNotConsumeStream() {
        val bytes = fixture()
        val stream = bytes.inputStream().buffered()
        assertTrue(looksLikeOem(stream))
        assertArrayEquals(bytes, stream.readBytes())
        assertTrue(looksLikeOem("<?xml version=\"1.0\"?><oem version=\"2.0\">".byteInputStream().buffered()))
        assertFalse(looksLikeOem("ISS\n1 25544U\n2 25544 ".byteInputStream().buffered()))
    }

    @Test fun originalSamplesInterpolationAndFrameConversion() {
        val runtime = AstroTestData.runtime
        val snapshot = parseIssOem(fixture(), nasa, runtime)
        val segment = snapshot.segments.single()
        val session = OrekitOrbitSession(AstroTestData.engine.bind(elements).copy(ephemeris = snapshot))
        for (seconds in listOf(0.0, 120.0, 240.0, 840.0, 2280.0, 2400.0)) {
            val time = epoch + (seconds * 1000).toLong()
            val state = session.state(time)
            val pv = state.getPVCoordinates(runtime.context.frames.eme2000)
            assertTrue("PV interpolation residual at $seconds s", pv.position.distance(position(seconds)) < 5.0)
            assertTrue(pv.velocity.distance(velocity(seconds)) < .1)
            val teme = session.temeState(time)
            val expectedTeme = state.getPVCoordinates(runtime.teme)
            assertEquals(expectedTeme.position.x / 1000, teme.xKm, 1e-9)
            assertEquals(expectedTeme.velocity.z / 1000, teme.vzKmPerSecond, 1e-9)
            assertEquals("ISS OEM", session.position(observer, time).issOrbitSource)
        }
        val time = epoch + 360000L
        val actual = session.position(observer, time)
        val derivative = session.position(observer, time + 500).distance -
            session.position(observer, time - 500).distance
        assertEquals(derivative, actual.distanceRate, .001)
        assertEquals(segment.stop.toDate(runtime.utc).time, actual.orbitValidUntilMillis)
    }

    @Test fun invalidReplacementDoesNotDestroyCacheAndDisabledSourceFallsBack() = runBlocking {
        val settings = FakeSettingsRepo(DataSourcesSettings(satelliteUrls = listOf(nasa),
            transceiversUrls = emptyList(), satelliteEnabled = listOf(true)))
        val directory = temporary.newFolder()
        val repository = OemEphemerisRepository(directory, AstroTestData.repository, settings)
        val engine = OrekitOrbitEngine(AstroTestData.repository, ephemerides = repository)
        assertTrue(repository.importOem(nasa, fixture().inputStream()))
        val first = engine.bind(elements)
        assertNotNull(first.ephemeris)
        assertFalse(repository.importOem(nasa, "broken XML".byteInputStream()))
        assertEquals(first.ephemeris?.revision, engine.bind(elements).ephemeris?.revision)
        val afterRestart = OemEphemerisRepository(directory, AstroTestData.repository, settings)
        assertEquals(first.ephemeris?.revision,
            afterRestart.select(25544, AstroTestData.runtime)?.revision)
        assertTrue(repository.importOem(nasa, fixture(epoch + 1000, 200.0).inputStream()))
        val second = engine.bind(elements)
        assertNotEquals(first.ephemeris?.revision, second.ephemeris?.revision)
        val firstState = engine.openSession(first).temeState(epoch + 240000)
        val secondState = engine.openSession(second).temeState(epoch + 240000)
        assertTrue(abs(firstState.xKm - secondState.xKm) > .1)
        assertNull(engine.bind(elements.copy(catnum = 12345)).ephemeris)
        settings.updateDataSourcesSettings(settings.dataSourcesSettings.value.copy(satelliteEnabled = listOf(false)))
        assertNull(engine.bind(elements).ephemeris)
        assertEquals("SGP4", engine.openSession(engine.bind(elements)).position(observer, epoch).issOrbitSource)
        assertEquals("SGP4", engine.openSession(second).position(observer, epoch - 1).issOrbitSource)
        assertEquals("SGP4", engine.openSession(second).position(observer, epoch + 2400001).issOrbitSource)
        repository.clear()
        assertFalse(directory.exists())
    }

    @Test fun segmentsAndGapsUseDifferentSourcesWithoutExtrapolation() {
        val snapshot = parseIssOem(fixture(gap = true), nasa, AstroTestData.runtime)
        assertEquals(2, snapshot.segments.size)
        val session = OrekitOrbitSession(AstroTestData.engine.bind(elements).copy(ephemeris = snapshot))
        assertEquals("ISS OEM", session.position(observer, epoch + 960000).issOrbitSource)
        assertEquals("SGP4", session.position(observer, epoch + 1200000).issOrbitSource)
        assertEquals("ISS OEM", session.position(observer, epoch + 1440000).issOrbitSource)
        val start = epoch - 120000L
        val end = epoch + 2520000L
        val windows = session.passes(observer, start, end)
        assertTrue(windows.zipWithNext().all { (a, b) -> a.losTime <= b.aosTime })
        assertTrue(windows.all { it.aosTime >= start && it.losTime <= end &&
            it.tcaTime in it.aosTime..it.losTime })
    }

    @Test fun parsesRealNasaTxtAndXmlAndReportsIndependentHeldOutResiduals() {
        val paths = listOf("ISS_OEM_TXT", "ISS_OEM_XML").map { System.getenv(it) }
        assumeTrue("Set ISS_OEM_TXT and ISS_OEM_XML for operational NASA verification", paths.all { !it.isNullOrBlank() })
        val runtime = AstroTestData.runtime
        val snapshots = paths.map { path ->
            val file = File(requireNotNull(path))
            assertTrue(file.isFile)
            val bytes = file.readBytes()
            assertTrue("NASA OEM format: ${file.name}", looksLikeOem(bytes.inputStream().buffered()))
            val value = parseIssOem(bytes, file.name, runtime)
            val all = value.segments.flatMap { it.coordinates }
            assertTrue("Expected more than one orbit of real samples", all.size > 100)
            val errors = mutableListOf<Double>()
            val speedErrors = mutableListOf<Double>()
            val interpolator = TimeStampedPVCoordinatesHermiteInterpolator(4, CartesianDerivativesFilter.USE_PV)
            for (segment in value.segments) {
                val points = segment.coordinates
                val propagator = segment.propagator
                for (index in points.indices step 97) {
                    val expected = points[index]
                    if (expected.date < segment.start || expected.date > segment.stop) continue
                    val pv = propagator.getPVCoordinates(expected.date, segment.frame)
                    assertTrue("Original position residual", pv.position.distance(expected.position) < .01)
                    assertTrue("Original velocity residual", pv.velocity.distance(expected.velocity) < .001)
                }
                // A hold-out is independent of the four interpolation samples. Report normal 4-min cadence;
                // dense maneuver intervals require their own experiment, not a regular-spacing assumption.
                for (index in 2 until points.size - 2 step 13) {
                    val neighbors = points.subList(index - 2, index + 3).filterIndexed { i, _ -> i != 2 }
                    val expected = points[index]
                    val spacing = points.subList(index - 2, index + 3).zipWithNext().map { (a, b) ->
                        b.date.durationFrom(a.date)
                    }
                    if (spacing.any { abs(it - 240.0) > .1 }) continue
                    val pv = interpolator.interpolate(expected.date, neighbors)
                    errors.add(pv.position.distance(expected.position))
                    speedErrors.add(pv.velocity.distance(expected.velocity))
                }
            }
            assertTrue("Regular four-minute samples missing", errors.isNotEmpty())
            val rms = sqrt(errors.map { it * it }.average())
            println("NASA OEM ${file.name}: samples=${all.size} segments=${value.segments.size} " +
                "heldOut=${errors.size} rmsM=$rms p95M=${errors.sorted()[(errors.size * .95).toInt()]} " +
                "maxM=${errors.maxOrNull()} velocityMaxMps=${speedErrors.maxOrNull()}")
            assertTrue("Unexpected regular-interval interpolation error", rms < 100.0)
            value
        }
        // NASA may publish a new generation between the two HTTP requests.
        if (snapshots[0].creation == snapshots[1].creation) {
            val a = snapshots[0].segments.first().coordinates.first()
            val b = snapshots[1].segments.first().coordinates.first()
            assertEquals(a.date, b.date)
            assertTrue(a.position.distance(b.position) < .01)
            assertTrue(a.velocity.distance(b.velocity) < .001)
        }
    }
}
