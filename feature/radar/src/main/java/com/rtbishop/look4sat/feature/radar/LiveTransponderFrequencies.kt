/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper

/**
 * The repository's Doppler list is calculated from SatNOGS frequencies. Rebase only the
 * selected transponder on the user's saved RX/TX references; otherwise every one-second
 * refresh would restore the old displayed frequencies after a reference edit.
 *
 * A linked calculator owns an immutable calibration reference. Its changing tuned
 * frequency is not a new passband center.
 */
internal fun liveTransponderFrequencyRows(
    dopplerRadios: List<SatRadio>,
    selectedUuid: String?,
    tracking: RadioTrackingState,
    orbitalPos: OrbitalPos?
): List<SatRadio> {
    val original = tracking.selectedTransponder ?: return dopplerRadios
    // Collapsing the row clears selectedUuid, but must not undo its saved reference.\n    if ((selectedUuid != null && selectedUuid != original.uuid) || orbitalPos == null) return dopplerRadios

    val baseTx = if (tracking.calculatorLinked) tracking.calculatorReference?.txHz
        else tracking.txBaseFrequencyHz
    val baseRx = if (tracking.calculatorLinked) tracking.calculatorReference?.rxHz
        else tracking.nominalRxFrequencyHz

    val txCenter = original.uplinkLow?.let { low ->
        original.uplinkHigh?.let { high -> low + (high - low) / 2 } ?: low
    }
    // Match RadioTrackingService.updateSelectedTransponder's initial RX reference,
    // including the low-frequency fallback for receive-only entries.
    val rxReference = txCenter?.let { TransponderMapper.mapUplinkToDownlink(it, original) }
        ?: original.downlinkLow
    val txShift = if (txCenter != null && baseTx != null) baseTx - txCenter else null
    val rxShift = if (rxReference != null && baseRx != null) baseRx - rxReference else null
    if ((txShift == null || txShift == 0L) && (rxShift == null || rxShift == 0L)) return dopplerRadios

    return dopplerRadios.map { live ->
        if (live.uuid != original.uuid) live else {
            val tx = correctedReferencePair(
                original.uplinkLow, original.uplinkHigh, live.uplinkLow, live.uplinkHigh, txShift,
                orbitalPos::getUplinkFreq
            )
            val rx = correctedReferencePair(
                original.downlinkLow, original.downlinkHigh, live.downlinkLow, live.downlinkHigh, rxShift,
                orbitalPos::getDownlinkFreq
            )
            live.copy(uplinkLow = tx.first, uplinkHigh = tx.second,
                downlinkLow = rx.first, downlinkHigh = rx.second)
        }
    }
}

private fun correctedReferencePair(
    originalLow: Long?, originalHigh: Long?,
    liveLow: Long?, liveHigh: Long?, shift: Long?,
    doppler: (Long) -> Long
): Pair<Long?, Long?> {
    if (shift == null || shift == 0L || originalLow == null) return liveLow to liveHigh
    val shifted = runCatching {
        val low = Math.addExact(originalLow, shift)
        val high = originalHigh?.let { Math.addExact(it, shift) }
        if (low !in 1L..9_999_999_999L ||
            high?.let { it !in 1L..9_999_999_999L } == true) return@runCatching null
        doppler(low) to high?.let(doppler)
    }.getOrNull()
    return shifted ?: (liveLow to liveHigh)
}

/** Keep the selected frequency used by external reporters in step with the rows. */
internal fun selectedLiveDownlinkFrequency(radios: List<SatRadio>, selectedUuid: String?): Long? {
    val selected = radios.firstOrNull { it.uuid == selectedUuid } ?: return null
    val low = selected.downlinkLow ?: return null
    return selected.downlinkHigh?.let { low + (it - low) / 2 } ?: low
}
