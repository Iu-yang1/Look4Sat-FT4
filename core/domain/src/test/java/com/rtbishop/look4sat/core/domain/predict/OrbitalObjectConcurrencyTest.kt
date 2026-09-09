/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.predict

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class OrbitalObjectConcurrencyTest {
    @Test
    fun sharedPropagatorReturnsIsolatedDeterministicPositionsUnderConcurrency() {
        val satellite = OrbitalData(
            name = "ISS",
            epoch = 24_100.5,
            meanmo = 15.5,
            eccn = 0.0005,
            incl = 51.64,
            raan = 20.0,
            argper = 80.0,
            meanan = 180.0,
            catnum = 25_544,
            bstar = 0.0001
        ).getObject()
        val inputs = listOf(
            GeoPos(0.0, 0.0) to 1_712_404_800_000L,
            GeoPos(51.5, -0.1) to 1_712_405_321_000L,
            GeoPos(-33.9, 151.2) to 1_712_406_777_000L,
            GeoPos(35.7, 139.7) to 1_712_408_123_000L
        )
        val expected = inputs.map { (observer, time) -> satellite.getPosition(observer, time) }
        assertNotSame(expected[0], satellite.getPosition(inputs[0].first, inputs[0].second))

        val executor = Executors.newFixedThreadPool(8)
        try {
            val calls = List(400) { index ->
                Callable {
                    val inputIndex = index % inputs.size
                    val (observer, time) = inputs[inputIndex]
                    inputIndex to satellite.getPosition(observer, time)
                }
            }
            executor.invokeAll(calls).forEach { future ->
                val (inputIndex, actual) = future.get()
                val reference = expected[inputIndex]
                assertEquals(reference.time, actual.time)
                assertEquals(reference.azimuth, actual.azimuth, 1e-12)
                assertEquals(reference.elevation, actual.elevation, 1e-12)
                assertEquals(reference.distance, actual.distance, 1e-9)
                assertEquals(reference.distanceRate, actual.distanceRate, 1e-12)
                assertEquals(reference.latitude, actual.latitude, 1e-12)
                assertEquals(reference.longitude, actual.longitude, 1e-12)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
