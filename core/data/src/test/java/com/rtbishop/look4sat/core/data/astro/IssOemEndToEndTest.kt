package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.data.framework.FakeRotatorTransport
import com.rtbishop.look4sat.core.data.framework.RepositoryRotatorOrbitSource
import com.rtbishop.look4sat.core.data.framework.RotatorTrackingService
import com.rtbishop.look4sat.core.data.repository.FakeSettingsRepo
import com.rtbishop.look4sat.core.data.repository.SatelliteRepo
import com.rtbishop.look4sat.core.data.repository.TestLocalSource
import com.rtbishop.look4sat.core.data.sunlight.OrekitSunlightPredictor
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingPhase
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.SunlightRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.orekit.time.AbsoluteDate
import org.orekit.utils.TimeStampedPVCoordinates
import java.io.File
import kotlin.math.*

@OptIn(ExperimentalCoroutinesApi::class)
class IssOemEndToEndTest {
    @get:Rule val temp = TemporaryFolder()
    private val nasa = Sources.issOemUrl
    private val runtime get() = AstroTestData.runtime
    private val elements = OrbitalData("ISS (ZARYA)", 26322.0, 15.49, .0005, 51.64,
        123.0, 87.0, 3.0, 25544, .0001)
    private val settings = FakeSettingsRepo(DataSourcesSettings(satelliteUrls = listOf(nasa),
        transceiversUrls = emptyList(), satelliteEnabled = listOf(true)))
    private val radio = SatRadio("iss-fm", "ISS FM simulation", true, 437800000, null, "FM",
        145990000, null, "FM", false, 25544)

    private fun input(key: String): File {
        val path = System.getenv(key)
        assumeTrue("Set $key for NASA OEM integration tests", !path.isNullOrBlank())
        return File(checkNotNull(path)).also { assertTrue(it.isFile) }
    }

    private suspend fun engine(file: File): OrekitOrbitEngine {
        val cache = OemEphemerisRepository(temp.newFolder(), AstroTestData.repository, settings)
        assertTrue(cache.importOem(nasa, file.inputStream()))
        return OrekitOrbitEngine(AstroTestData.repository, temp.newFolder(), ephemerides = cache)
    }

    private fun local() = TestLocalSource(listOf(elements.getObject())) { emptyList() }
    private fun epoch(date: AbsoluteDate) = date.toDate(runtime.utc).time
    private fun stationAt(pv: TimeStampedPVCoordinates, offset: Double = 1.5): GeoPos {
        val point = runtime.earth.transform(pv.position, runtime.context.frames.getEME2000(), pv.date)
        return GeoPos(Math.toDegrees(point.latitude) + offset, Math.toDegrees(point.longitude))
    }

    // Independent projection and radial velocity from the source PV.
    private fun reference(pv: TimeStampedPVCoordinates, site: GeoPos): DoubleArray {
        val local = runtime.context.frames.getEME2000().getTransformTo(runtime.station(site), pv.date)
            .transformPVCoordinates(pv)
        val p = local.position
        val v = local.velocity
        return doubleArrayOf((Math.toDegrees(atan2(p.x, p.y)) + 360) % 360,
            Math.toDegrees(atan2(p.z, hypot(p.x, p.y))), p.norm, Vector3D.dotProduct(p,v) / p.norm)
    }

    private fun angleError(a: Double, b: Double) = abs((a - b + 540) % 360 - 180)

    @Test fun currentNasaOrbitReachesPassScheduleRadarMapMutualSunlightAndDoppler() = runBlocking {
        val file = input("ISS_OEM_TXT")
        val engine = engine(file)
        val target = engine.bind(elements)
        val snapshot = target.ephemeris as IssOemSnapshot
        val segment = snapshot.segments.first()
        val now = runtime.date(System.currentTimeMillis())
        assertTrue(snapshot.segments.any { now >= it.start && now <= it.stop })
        assertNull(engine.bind(elements.copy(catnum = 40931)).ephemeris)
        assertThrows(IllegalArgumentException::class.java) {
            parseIssOem(file.readText().replace("1998-067-A", "1998-067-B").toByteArray(), nasa, runtime)
        }
        val point = segment.coordinates[segment.coordinates.size / 2]
        val site = stationAt(point)
        val siteB = site.copy(latitude = site.latitude + .5)
        val start = epoch(point.date) - 600000
        val end = epoch(point.date) + 600000
        val session = engine.openSession(target)
        val batch = engine.passes(target, listOf(site, siteB), start, end)
        assertEquals(2, batch.size)
        val pass = batch.first().single { epoch(point.date) in it.aosTime..it.losTime }
        val other = batch[1].single { epoch(point.date) in it.aosTime..it.losTime }
        assertTrue(max(pass.aosTime, other.aosTime) < min(pass.losTime, other.losTime))
        assertEquals(target.ephemeris?.revision, pass.orbitalObject.ephemeris?.revision)
        assertEquals(batch, engine.passes(target, listOf(site, siteB), start, end))
        val local = local()
        (settings.selectedIds as MutableStateFlow).value = listOf(25544)
        (settings.stationPosition as MutableStateFlow).value = site
        val repo = SatelliteRepo(Dispatchers.Default, local, settings, engine,
            OrekitCelestialService(AstroTestData.repository), AstroTestData.repository)
        val job = launch { repo.initRepository() }
        try {
            withTimeout(30000) { repo.satellites.first { it.any { sat -> sat.ephemeris != null } } }
            val scheduled = repo.getSchedulePasses(setOf(25544), start, 1, 0.0)
            assertTrue(scheduled.any { epoch(point.date) in it.aosTime..it.losTime })
            assertTrue(scheduled.all { it.orbitalObject.ephemeris?.revision == target.ephemeris?.revision })
            val radar = repo.getRadarTrack(target, site, pass.aosTime, pass.losTime)
            assertTrue(radar.size > 10)
            assertEquals(pass.aosTime, radar.first().time)
            assertEquals(pass.losTime, radar.last().time)
            val track = repo.getTrack(target, site, pass.aosTime, pass.losTime)
            assertTrue(track.size > 10 && track.all { it.issOrbitSource == "ISS OEM" })
            val actual = repo.getPosition(target, site, epoch(point.date))
            val expected = reference(point, site)
            assertTrue(angleError(Math.toDegrees(actual.azimuth), expected[0]) < 1e-6)
            assertEquals(expected[1], Math.toDegrees(actual.elevation), 1e-6)
            assertEquals(expected[2], actual.distance * 1000, .01)
            assertEquals(expected[3], actual.distanceRate * 1000, .001)
            assertEquals("ISS OEM", actual.issOrbitSource)
            val subpoint = runtime.earth.transform(point.position, segment.frame, point.date)
            assertEquals(subpoint.latitude, actual.latitude, 1e-9)
            assertEquals(subpoint.longitude, actual.longitude, 1e-9)
            assertEquals(subpoint.altitude / 1000, actual.altitude, 1e-6)
            val footprint = repo.getCoverage(target, epoch(point.date))
            assertTrue(footprint.size >= 360 && footprint.all { abs(session.elevation(it, epoch(point.date))) < .002 })
            val correction = repo.getRadios(target, site, listOf(radio), epoch(point.date)).single()
            assertEquals((437800000 * (1-expected[3]/299792458.0)).toLong(), correction.downlinkLow)
            assertEquals((145990000 * (1+expected[3]/299792458.0)).toLong(), correction.uplinkLow)
            val predictor = OrekitSunlightPredictor(local, ControlDiagnosticsBuffer(), engine)
            val day = SunlightRequest(25544, start, 1)
            val illumination = predictor.predict(day) {}
            assertNull(illumination.problem)
            assertEquals(day.endUtcMillis, illumination.validUntilMillis)
            assertEquals(86400000L, Illumination.entries.sumOf(illumination::durationMillis))
            assertTrue(illumination.intervals.any { it.state == Illumination.UMBRA })
            assertTrue(illumination.intervals.any { it.state == Illumination.SUNLIGHT })
            println("CURRENT ISS 25544 validFrom=" + snapshot.segments.first().start +
                " validUntil=" + snapshot.segments.last().stop +
                " passAOS=" + pass.aosTime + " LOS=" + pass.losTime +
                " radarPoints=" + radar.size + " mapPoints=" + track.size +
                " coverage=" + footprint.size + " mutual=true sunlightIntervals=" + illumination.intervals.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun realArchivedManeuverNonUniformSamplesAndHeldOutPoints() {
        val file = input("ISS_OEM_MANEUVER")
        val snapshot = parseIssOem(file.readBytes(), file.name, runtime)
        val segment = snapshot.segments.maxBy { s -> s.coordinates.zipWithNext().count { (a,b) ->
            abs(b.date.durationFrom(a.date) - 2.0) < .1 } }
        val points = segment.coordinates
        val steps = points.zipWithNext().map { (a,b) -> b.date.durationFrom(a.date) }
        assertTrue(steps.count { abs(it - 2) < .1 } >= 20)
        assertTrue(steps.any { it >= 200 })
        val target = AstroTestData.engine.bind(elements).copy(ephemeris = snapshot)
        val session = OrekitOrbitSession(target)
        var kp = 0.0
        var kv = 0.0
        var maxRateResidual = 0.0
        var checked = 0
        for (i in 1 until points.lastIndex) {
            if (steps[i-1] >= 239.9 && steps[i] >= 239.9) continue
            val p = points[i]
            if (p.date < segment.start || p.date > segment.stop) continue
            val actual = session.state(epoch(p.date)).getPVCoordinates(segment.frame)
            kp = max(kp, actual.position.distance(p.position))
            kv = max(kv, actual.velocity.distance(p.velocity))
            val site = stationAt(p)
            val pos = session.position(site, epoch(p.date))
            assertEquals("ISS OEM", pos.issOrbitSource)
            maxRateResidual = max(maxRateResidual, abs(pos.distanceRate * 1000 - reference(p, site)[3]))
            checked++
        }
        assertTrue("Exact sampled position residual $kp m", kp < 5)
        assertTrue("Exact sampled velocity residual $kv m/s", kv < .1)
        assertTrue("Sampled range-rate residual $maxRateResidual m/s", maxRateResidual < .1)
        assertTrue(checked >= 20)
        val held = points.indices.filter { i -> i >= 2 && i < points.lastIndex - 1 &&
            i % 7 == 0 && (i-2..i+1).all { abs(steps[it]-2.0) < .1 } &&
            points[i].date >= segment.start && points[i].date <= segment.stop }.map { points[it] }
        assertTrue(held.size >= 2)
        val heldEpochs = held.map { epoch(it.date) }.toSet()
        val thinned = file.readLines().filterNot { line ->
            val date = line.trim().split(Regex("\\s+")).firstOrNull()
            date != null && date.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) &&
                runCatching { epoch(AbsoluteDate(date.removeSuffix("Z"), runtime.utc)) in heldEpochs }.getOrDefault(false)
        }.joinToString("\n").toByteArray()
        val reduced = parseIssOem(thinned, file.name, runtime)
        val interpolating = OrekitOrbitSession(target.copy(ephemeris = reduced))
        var positionMax = 0.0
        var velocityMax = 0.0
        held.forEach { expected ->
            val actual = interpolating.state(epoch(expected.date)).getPVCoordinates(segment.frame)
            positionMax = max(positionMax, actual.position.distance(expected.position))
            velocityMax = max(velocityMax, actual.velocity.distance(expected.velocity))
        }
        assertTrue("Maneuver holdout max position $positionMax m", positionMax < 10)
        assertTrue("Maneuver holdout max velocity $velocityMax m/s", velocityMax < .2)
        var maxSpeed = 0.0
        for (i in steps.indices) {
            if (steps[i] >= 239.9 && (i == 0 || steps[i-1] >= 239.9) &&
                (i == steps.lastIndex || steps[i+1] >= 239.9)) continue
            for (fraction in listOf(.25,.5,.75)) {
                val date = points[i].date.shiftedBy(steps[i] * fraction)
                if (date < segment.start || date > segment.stop) continue
                val pv = session.state(epoch(date)).getPVCoordinates(segment.frame)
                assertTrue("Nonphysical position at $date", pv.position.norm in 6.5e6..7.3e6)
                maxSpeed = max(maxSpeed,pv.velocity.norm)
                assertTrue("Nonphysical speed at $date", pv.velocity.norm in 6500.0..8500.0)
            }
        }
        println("ARCHIVE " + file.name + " validFrom=" + segment.start + " validUntil=" + segment.stop +
            " dense2s=" + steps.count { abs(it-2.0) < .1 } + " nonuniform=" + checked +
            " exactMaxM=$kp exactMaxMps=$kv rangeRateMaxMps=$maxRateResidual heldOut=" + held.size +
            " heldMaxM=$positionMax heldMaxMps=$velocityMax interiorMaxSpeed=$maxSpeed")
    }

    @Test fun rotctldTrackingAndRadioCommandsFollowArchivedManeuver() = runTest {
        val file = input("ISS_OEM_MANEUVER")
        val engine = engine(file)
        val target = engine.bind(elements)
        val snapshot = target.ephemeris as IssOemSnapshot
        val segment = snapshot.segments.maxBy { s -> s.coordinates.zipWithNext().count { (a,b) ->
            abs(b.date.durationFrom(a.date)-2) < .1 } }
        val dense = segment.coordinates.zipWithNext().filter { (a,b) ->
            abs(b.date.durationFrom(a.date)-2) < .1 &&
                a.date >= segment.start.shiftedBy(600.0) && b.date <= segment.stop.shiftedBy(-600.0) }.map { it.first }
        assertTrue(dense.size >= 20)
        val center = dense[dense.size / 2]
        val site = stationAt(center)
        val centerTime = epoch(center.date)
        val session = engine.openSession(target)
        val pass = session.passes(site, centerTime-600000, centerTime+600000)
            .single { centerTime in it.aosTime..it.losTime }
        val repo = SatelliteRepo(UnconfinedTestDispatcher(testScheduler), local(), settings, engine,
            OrekitCelestialService(AstroTestData.repository), AstroTestData.repository)
        val source = RepositoryRotatorOrbitSource(repo) { site }
        val transport = FakeRotatorTransport()
        var now = centerTime
        val options = RotatorSettings(enabled = true, deadbandDegrees = 0.0, updateIntervalMillis = 1000L)
        val control = RotatorTrackingService(backgroundScope, source, { now }, { options }, { transport },
            { site }, positionQueryIntervalMillis = 0)
        control.connect()
        control.startTracking(pass)
        try {
            var maxAz = 0.0
            var maxEl = 0.0
            val run = dense.filter { epoch(it.date) in centerTime..(centerTime + 40000) }.take(15)
            assertTrue(run.size >= 3)
            for (sample in run) {
                now = epoch(sample.date)
                advanceTimeBy(1000)
                runCurrent()
                val wire = transport.writes.last { it.decodeToString().startsWith("P ") }
                    .decodeToString().trim().split(Regex("\\s+"))
                val expected = reference(sample, site)
                maxAz = max(maxAz, angleError(wire[1].toDouble(), expected[0]))
                maxEl = max(maxEl, abs(wire[2].toDouble() - expected[1]))
                assertEquals(RotatorTrackingPhase.TRACKING, control.state.value.trackingPhase)
                val look = source.look(pass, now)
                assertTrue(angleError(look.azimuthDegrees, expected[0]) < .002)
                assertEquals(expected[1], look.elevationDegrees, .002)
                val actual = repo.getRadios(target, site, listOf(radio), now).single()
                assertTrue(abs(checkNotNull(actual.downlinkLow) -
                    (437800000 * (1-expected[3]/299792458.0)).toLong()) <= 1)
                assertTrue(abs(checkNotNull(actual.uplinkLow) -
                    (145990000 * (1+expected[3]/299792458.0)).toLong()) <= 1)
            }
            assertTrue("ROTCTLD azimuth residual $maxAz deg", maxAz <= .06)
            assertTrue("ROTCTLD elevation residual $maxEl deg", maxEl <= .06)
            println("ROTCTLD archived OEM commands=" + transport.writes.count { it.decodeToString().startsWith("P ") } +
                " maxAzDeg=$maxAz maxElDeg=$maxEl Doppler=145990000/437800000 Hz")
        } finally {
            control.disconnect()
        }
    }
}
