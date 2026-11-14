package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QsoMaintenanceStateTest {
    @Test fun pendingSurvivesRepositoryRecreationAndOldRepairVersionRetries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("repair-state-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val first = StoredQsoMaintenanceState(preferences)
        assertTrue(first.needsMaintenance)
        first.markComplete()
        assertFalse(StoredQsoMaintenanceState(preferences).needsMaintenance)
        first.markPending()
        assertTrue(StoredQsoMaintenanceState(preferences).needsMaintenance)
        StoredQsoMaintenanceState(preferences).markComplete()
        assertFalse(first.needsMaintenance)
        preferences.edit().putInt("confirmationRepairVersion", 0).commit()
        assertTrue(StoredQsoMaintenanceState(preferences).needsMaintenance)
    }
}
