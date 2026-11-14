package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.model.TrajectoryPrecision
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrajectorySettingsTest {
    @Test fun defaultsToHighAndKeepsEverySavedLevelAfterReopening() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("trajectory-settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        fun repository() = SettingsRepo(context.getSystemService(LocationManager::class.java), preferences, "test")
        assertEquals(TrajectoryPrecision.HIGH, repository().otherSettings.value.trajectoryPrecision)
        assertEquals(TrajectoryPrecision.HIGH, TrajectoryPrecision.fromStored(null))
        for (precision in TrajectoryPrecision.entries) {
            repository().updateOtherSettings { it.copy(trajectoryPrecision = precision) }
            assertEquals(precision, repository().otherSettings.value.trajectoryPrecision)
        }
        preferences.edit().putString("trajectoryPrecision", "removed-option").commit()
        assertEquals(TrajectoryPrecision.HIGH, repository().otherSettings.value.trajectoryPrecision)
    }
}
