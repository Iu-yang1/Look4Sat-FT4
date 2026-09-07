/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.repository

interface IRadioController {

    val isConnected: Boolean

    suspend fun connect(): Boolean

    suspend fun disconnect()

    suspend fun setFrequency(frequencyHz: Long): Boolean

    suspend fun setMode(mode: String): Boolean

    suspend fun setCtcssMode(enabled: Boolean): Boolean

    suspend fun setCtcssTone(toneHz: Double): Boolean

    suspend fun readFrequencyAndMode(): Pair<Long, String>?

    suspend fun pttOn(): Boolean

    suspend fun pttOff(): Boolean

    /** Generation captured while arming a transmission, before PTT can be requested. */
    fun pttSafetyGeneration(): Long = 0L

    /** Rejects PTT-ON if an emergency stop invalidated the captured generation. */
    suspend fun pttOnIfGeneration(expectedGeneration: Long): Boolean =
        expectedGeneration == pttSafetyGeneration() && pttOn()

    /** Invalidates queued PTT-ON work before an emergency PTT-OFF is enqueued. */
    fun invalidatePendingPttOn() = Unit

    // ── Extended operations (IC-705 / CI-V) ──────────────────────────────

    /**
     * Select the band matching [frequencyHz] via the band stacking register.
     * Must be called before [setFrequency] and [setMode] when first tracking.
     * Default: no-op (Yaesu radios auto-switch band via frequency).
     */
    suspend fun setBand(frequencyHz: Long): Boolean = false

    /**
     * Select the active VFO.
     * @param vfoA true → VFO-A (main/RX), false → VFO-B (sub/TX in split).
     */
    suspend fun setVfo(vfoA: Boolean): Boolean = false

    /**
     * Enable or disable SPLIT mode (TX on sub-VFO, RX on main VFO).
     * Default: not supported.
     */
    suspend fun setSplitMode(enabled: Boolean): Boolean = false

    /**
     * Configure both sides of a split setup as one serialized transaction.
     * Implementations should leave VFO-A selected and verify the written modes.
     */
    suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean {
        if (!setVfo(vfoA = true)) return false
        if (rxMode != null && !setMode(rxMode)) return false
        if (!setVfo(vfoA = false)) return false
        if (txMode != null && !setMode(txMode)) return false
        return setVfo(vfoA = true)
    }

    /**
     * Set the frequency of the currently active VFO (IC-705: CMD 0x25 sub 0x00).
     * Default: delegates to [setFrequency].
     */
    suspend fun setWorkingFrequency(frequencyHz: Long): Boolean = setFrequency(frequencyHz)

    /**
     * Set the frequency of the inactive/TX VFO (IC-705: CMD 0x25 sub 0x01).
     * Sent every tracking cycle alongside [setWorkingFrequency] in split mode.
     * Default: delegates to [setWorkingFrequency].
     */
    suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean = setWorkingFrequency(frequencyHz)

    /**
     * Read the frequency of the currently active VFO (IC-705: CMD 0x25 sub 0x00).
     * Default: delegates to [readFrequencyAndMode].
     */
    suspend fun readWorkingFrequency(): Long? = readFrequencyAndMode()?.first

    /**
     * Read the frequency of the inactive/TX VFO (IC-705: CMD 0x25 sub 0x01).
     * Used for tuning detection in split mode.
     * Default: delegates to [readWorkingFrequency].
     */
    suspend fun readTxVfoFrequency(): Long? = readWorkingFrequency()
}
