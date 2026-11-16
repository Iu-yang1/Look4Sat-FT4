package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsInitializationTest {
    @Test fun formatMigrationInitializesItsKeyBeforeRunningAndDoesNotCreateNullKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("format-migration-initialization", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("rotatorFormat", "AZ_ EL_").commit()
        fun repository() = SettingsRepo(context.getSystemService(LocationManager::class.java), preferences, "test")
        repository()
        assertTrue(preferences.getBoolean("rcFormatsMigrated", false))
        assertFalse(preferences.all.containsKey(null))
        assertEquals("\\AZ_ EL_", preferences.getString("rotatorFormat", ""))
        val once = preferences.all
        repository()
        assertEquals(once, preferences.all)
    }
}
