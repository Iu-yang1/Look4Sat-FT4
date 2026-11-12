package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.AstroDateOutsideCoverageException
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class OrekitCelestialServiceTest {
    private fun time(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun geometricSunAndMoonUseThePinnedJplVectorsAndWgs84Station() {
        val data = AstroTestData.runtime
        val service = OrekitCelestialService(AstroTestData.repository)
        val observer = GeoPos(51.5, -.1, 70.0)
        val millis = time("2026-11-12T12:00:00Z")
        val date = data.date(millis)
        val station = data.station(observer)
        val expectedSun = station.getTrackingCoordinates(
            data.sun.getPosition(date, data.inertial), data.inertial, date)
        val expectedMoon = station.getTrackingCoordinates(
            data.moon.getPosition(date, data.inertial), data.inertial, date)
        assertEquals(Math.toDegrees(expectedSun.azimuth), service.sun(observer, millis).azimuth, 1e-9)
        assertEquals(Math.toDegrees(expectedSun.elevation), service.sun(observer, millis).elevation, 1e-9)
        assertEquals(Math.toDegrees(expectedMoon.azimuth), service.moon(observer, millis).azimuth, 1e-9)
        assertEquals(Math.toDegrees(expectedMoon.elevation), service.moon(observer, millis).elevation, 1e-9)
    }

    @Test fun riseSetHonorsTheSolarCenterThresholdAndDaylightSavingDayLength() {
        val service = OrekitCelestialService(AstroTestData.repository)
        val zone = ZoneId.of("Europe/London")
        val day = LocalDate.of(2026, 10, 25)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(25L * 3600000, end - start)
        val observer = GeoPos(51.5, -.1)
        val result = service.riseSet(observer, start, end)
        assertTrue(result.riseTimeMillis in start until end)
        assertTrue(result.setTimeMillis in start until end)
        assertEquals(-.8333, service.sun(observer, result.riseTimeMillis).elevation, .005)
        assertEquals(-.8333, service.sun(observer, result.setTimeMillis).elevation, .005)
    }

    @Test fun polarDayAndNightDoNotInventRiseOrSetEvents() {
        val service = OrekitCelestialService(AstroTestData.repository)
        val observer = GeoPos(90.0, 0.0)
        val summer = time("2026-06-21T00:00:00Z")
        val winter = time("2026-12-21T00:00:00Z")
        val day = service.riseSet(observer, summer, summer + 86400000)
        val night = service.riseSet(observer, winter, winter + 86400000)
        assertTrue(day.alwaysAbove)
        assertTrue(night.alwaysBelow)
        assertEquals(0L, day.riseTimeMillis)
        assertEquals(0L, night.setTimeMillis)
    }

    @Test fun datesOutsideTheEphemeridesAreExplicitlyUnavailable() {
        val service = OrekitCelestialService(AstroTestData.repository)
        assertThrows(AstroDateOutsideCoverageException::class.java) {
            service.moon(GeoPos(0.0, 0.0), time("2101-01-01T00:00:00Z"))
        }
    }
}
