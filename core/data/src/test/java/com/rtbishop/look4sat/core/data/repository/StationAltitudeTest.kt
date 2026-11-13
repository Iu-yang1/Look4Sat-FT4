package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [StationLocationManager::class])
class StationAltitudeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("station-altitude", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(LocationManager::class.java)
    private fun repository() = SettingsRepo(manager, preferences, "test")

    @Before fun setup() {
        preferences.edit().clear().commit()
        StationLocationManager.fix = null
        StationLocationManager.listener = null
        StationLocationManager.removed = null
        StationLocationManager.denied = false
    }

    private fun fix(provider: String = LocationManager.GPS_PROVIDER, height: Double? = 1234.5) =
        Location(provider).apply {
            latitude = 23.13; longitude = 113.26; time = System.currentTimeMillis()
            height?.let { altitude = it }
        }

    @Test fun gnssHeightIsEllipsoidMetersAndSurvivesReopen() {
        StationLocationManager.fix = fix()
        val repo = repository()
        assertTrue(repo.setStationPosition())
        assertEquals(1234.5, repo.stationPosition.value.altitude, 0.0)
        assertEquals(1234.5, repository().stationPosition.value.altitude, 0.0)
        assertNotNull(StationLocationManager.removed)
    }

    @Test fun missingNonFiniteAndNetworkHeightUseZeroButNegativeGnssHeightIsRetained() {
        for ((provider, height, expected) in listOf(
            Triple(LocationManager.GPS_PROVIDER, null, 0.0),
            Triple(LocationManager.GPS_PROVIDER, Double.NaN, 0.0),
            Triple(LocationManager.NETWORK_PROVIDER, 500.0, 0.0),
            Triple(LocationManager.GPS_PROVIDER, -21.3, -21.3)
        )) {
            StationLocationManager.fix = fix(provider, height)
            val repo = repository()
            assertTrue(repo.setStationPosition())
            assertEquals(expected, repo.stationPosition.value.altitude, 0.0)
            assertEquals(expected, repository().stationPosition.value.altitude, 0.0)
        }
    }

    @Test fun manualCoordinatesAndGridClearGnssHeightAndIgnoreLateCallback() {
        val repo = repository()
        assertTrue(repo.setStationPosition())
        val callback = requireNotNull(StationLocationManager.listener)
        assertTrue(repo.setStationPosition(20.0, 110.0, 999.0))
        callback.onLocationChanged(fix())
        assertEquals(20.0, repo.stationPosition.value.latitude, 0.0)
        assertEquals(0.0, repo.stationPosition.value.altitude, 0.0)
        StationLocationManager.fix = fix()
        assertTrue(repo.setStationPosition())
        assertEquals(1234.5, repo.stationPosition.value.altitude, 0.0)
        assertTrue(repo.setStationPosition("OL62"))
        assertEquals(0.0, repository().stationPosition.value.altitude, 0.0)
    }

    @Test fun unprovenLegacyAltitudeAndPermissionDenialDoNotCreateGnssHeight() {
        preferences.edit().putString("stationAltitude", "800").commit()
        assertEquals(0.0, repository().stationPosition.value.altitude, 0.0)
        StationLocationManager.denied = true
        assertFalse(repository().setStationPosition())
    }

    @Test fun replacingLocationRequestRejectsPriorRequestsQueuedFix() {
        val repo = repository()
        assertTrue(repo.setStationPosition())
        val old = requireNotNull(StationLocationManager.listener)
        assertTrue(repo.setStationPosition())
        val current = requireNotNull(StationLocationManager.listener)
        old.onLocationChanged(fix(height = 2000.0))
        assertEquals(0.0, repo.stationPosition.value.altitude, 0.0)
        current.onLocationChanged(fix(height = 300.0))
        assertEquals(300.0, repo.stationPosition.value.altitude, 0.0)
    }
}

@Implements(LocationManager::class)
class StationLocationManager {
    companion object {
        var fix: Location? = null
        var listener: LocationListener? = null
        var removed: LocationListener? = null
        var denied = false
    }
    @Implementation fun isLocationEnabled() = true
    @Implementation fun hasProvider(provider: String) = provider == LocationManager.GPS_PROVIDER
    @Implementation fun isProviderEnabled(provider: String) = provider == LocationManager.GPS_PROVIDER
    @Implementation fun getLastKnownLocation(provider: String): Location? {
        if (denied) throw SecurityException("denied")
        return fix
    }
    @Implementation fun requestLocationUpdates(
        provider: String, minTimeMs: Long, minDistanceM: Float, callback: LocationListener
    ) { listener = callback }
    @Implementation fun removeUpdates(callback: LocationListener) { removed = callback; listener = null }
}
