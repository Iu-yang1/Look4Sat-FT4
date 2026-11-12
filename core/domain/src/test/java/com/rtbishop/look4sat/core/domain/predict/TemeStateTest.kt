package com.rtbishop.look4sat.core.domain.predict.legacy

import com.rtbishop.look4sat.core.domain.predict.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class TemeStateTest {
    private val elements = OrbitalData(
        "ISS test", 24100.5, 15.5, 0.0005, 51.64, 20.0, 80.0, 180.0, 25544, 0.0001
    )

    @Test fun snapshotIsObserverIndependentAndIsNotRescaledByLaterCalls() {
        val orbit = elements.legacyObject()
        val time = 1_712_404_800_000L
        val before = orbit.getTemeState(time)
        orbit.getPosition(GeoPos(51.5, -0.1), time + 10_000)
        orbit.getElevation(GeoPos(-33.9, 151.2), time - 10_000)
        assertEquals(before, orbit.getTemeState(time))
        assertEquals(time, before.timeMillis)
        val radius = sqrt(before.xKm * before.xKm + before.yKm * before.yKm + before.zKm * before.zKm)
        val speed = sqrt(before.vxKmPerSecond * before.vxKmPerSecond +
            before.vyKmPerSecond * before.vyKmPerSecond + before.vzKmPerSecond * before.vzKmPerSecond)
        assertTrue(radius in 6500.0..7100.0)
        assertTrue(speed in 7.0..8.5)
    }

    @Test fun dedicatedDeepSpaceInstancesRemainIndependentDuringNonSequentialSearches() {
        val geo = elements.copy(name = "Synthetic GEO", meanmo = 1.0027, eccn = 0.001, incl = 0.1, bstar = 0.0)
        val first = geo.legacyObject()
        val second = geo.legacyObject()
        val time = 1_712_404_800_000L
        val expected = second.getTemeState(time)
        first.getTemeState(time + 365L * 86_400_000)
        first.getTemeState(time - 12 * 3_600_000)
        val actual = second.getTemeState(time)
        assertEquals(expected.xKm, actual.xKm, 1e-6)
        assertEquals(expected.yKm, actual.yKm, 1e-6)
        assertEquals(expected.zKm, actual.zKm, 1e-6)
    }
}
