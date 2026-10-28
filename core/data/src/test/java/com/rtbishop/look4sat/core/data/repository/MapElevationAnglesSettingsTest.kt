package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapElevationAnglesSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("elevation-ring-tests", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun repo() = SettingsRepo(
        context.getSystemService(LocationManager::class.java), prefs, "test"
    )

    @Test
    fun allMapsShareSavedAnglesAndTheirOrdering() {
        val firstMap = repo()
        assertEquals(listOf(10.0), firstMap.mapElevationAngles.value)
        firstMap.setMapElevationAngles(listOf(5.0, 10.5, 30.0))
        assertEquals(listOf(5.0, 10.5, 30.0), firstMap.mapElevationAngles.value)
        val radarMap = repo()
        assertEquals(listOf(5.0, 10.5, 30.0), radarMap.mapElevationAngles.value)
    }

    @Test
    fun invalidValuesAreNotPersisted() {
        val settings = repo()
        settings.setMapElevationAngles(listOf(-1.0, 15.0, 15.0, 91.0, Double.NaN, 40.0))
        assertEquals(listOf(15.0, 40.0), repo().mapElevationAngles.value)
    }

    @Test
    fun corruptedPreferencesFallBackToDefault() {
        prefs.edit().putString("mapElevationAngles", "not-json").commit()
        assertEquals(listOf(10.0), repo().mapElevationAngles.value)
    }
}
