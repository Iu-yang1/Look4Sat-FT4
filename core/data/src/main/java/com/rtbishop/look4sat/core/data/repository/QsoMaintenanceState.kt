package com.rtbishop.look4sat.core.data.repository

import android.content.SharedPreferences

/** Durable before-write marker; separate from the QSO schema and upload state. */
interface QsoMaintenanceState {
    val needsMaintenance: Boolean
    fun markPending()
    fun markComplete()
}

internal class StoredQsoMaintenanceState(private val preferences: SharedPreferences) : QsoMaintenanceState {
    override val needsMaintenance: Boolean
        get() = preferences.getInt("confirmationRepairVersion", 0) != VERSION ||
            preferences.getBoolean("confirmationRepairPending", true)
    override fun markPending() {
        if (!preferences.getBoolean("confirmationRepairPending", true)) {
            check(preferences.edit().putBoolean("confirmationRepairPending", true).commit())
        }
    }
    override fun markComplete() {
        check(preferences.edit().putInt("confirmationRepairVersion", VERSION)
            .putBoolean("confirmationRepairPending", false).commit())
    }
    private companion object { const val VERSION = 1 }
}

internal class MemoryQsoMaintenanceState : QsoMaintenanceState {
    override var needsMaintenance = true
        private set
    override fun markPending() { needsMaintenance = true }
    override fun markComplete() { needsMaintenance = false }
}
