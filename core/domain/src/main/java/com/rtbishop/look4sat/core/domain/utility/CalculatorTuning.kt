package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos

enum class CalculatorFrequencyAnchor { GROUND_TX, GROUND_RX, SATELLITE }

/** Ground Hz for TX/RX anchors; satellite uplink Hz for the passband anchor. */
data class CalculatorTuning(val transponderUuid: String, val anchor: CalculatorFrequencyAnchor, val frequencyHz: Long,
    val reference: CalculatorReference? = null)

/** Nominal RX/TX reference pair, including the offset already present when it was captured. */
data class CalculatorReference(val txHz: Long, val rxHz: Long, val offsetHz: Long = 0) {
    fun valid() = txHz in 1..9_999_999_999L && rxHz in 1..9_999_999_999L && offsetHz in -10_000_000..10_000_000L
}

/** Translate both passbands while retaining bandwidth and inversion. */
fun SatRadio.withCalculatorReference(reference: CalculatorReference?): SatRadio? {
    if (reference == null) return this
    if (!reference.valid() || !DopplerFrequencyCalculator.isLinearTransponder(this)) return null
    val midpoint = uplinkLow!! + (uplinkHigh!! - uplinkLow) / 2
    val downlink = TransponderMapper.mapUplinkToDownlink(midpoint, this) ?: return null
    val txShift = reference.txHz - midpoint
    val rxShift = reference.rxHz - downlink - reference.offsetHz
    return copy(uplinkLow = uplinkLow + txShift, uplinkHigh = uplinkHigh + txShift,
        downlinkLow = downlinkLow!! + rxShift, downlinkHigh = downlinkHigh!! + rxShift)
        .takeIf { radio -> listOf(radio.uplinkLow, radio.uplinkHigh, radio.downlinkLow, radio.downlinkHigh)
            .all { it != null && it in 1..9_999_999_999L } }
}

data class CalculatorFrequencyPair(val txHz: Long, val rxHz: Long, val nominalTxHz: Long, val nominalRxHz: Long)

fun CalculatorTuning.frequencies(transponder: SatRadio, position: OrbitalPos, offsetHz: Long): CalculatorFrequencyPair? {
    if (transponderUuid != transponder.uuid || frequencyHz <= 0) return null
    val calibrated = transponder.withCalculatorReference(reference) ?: return null
    return uncalibratedFrequencies(calibrated, position, offsetHz)
}

private fun CalculatorTuning.uncalibratedFrequencies(transponder: SatRadio, position: OrbitalPos, offsetHz: Long): CalculatorFrequencyPair? {
    val txRange = DopplerFrequencyCalculator.groundUplinkRange(transponder, position) ?: return null
    val rxRange = DopplerFrequencyCalculator.groundDownlinkRangeWithOffset(transponder, position, offsetHz) ?: return null
    val tx: Long
    val rx: Long
    when (anchor) {
        CalculatorFrequencyAnchor.GROUND_TX -> {
            tx = frequencyHz.coerceIn(txRange)
            rx = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(tx,transponder,position,offsetHz) ?: return null
        }
        CalculatorFrequencyAnchor.GROUND_RX -> {
            rx = frequencyHz.coerceIn(rxRange)
            tx = DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(rx,transponder,position,offsetHz) ?: return null
        }
        CalculatorFrequencyAnchor.SATELLITE -> {
            val base = frequencyHz.coerceIn(minOf(transponder.uplinkLow!!,transponder.uplinkHigh!!),
                maxOf(transponder.uplinkLow,transponder.uplinkHigh))
            tx = position.getUplinkFreq(base)
            rx = DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(tx,transponder,position,offsetHz) ?: return null
        }
    }
    val appliedTx = tx.coerceIn(txRange)
    val appliedRx = rx.coerceIn(rxRange)
    val nominalTx = if (anchor == CalculatorFrequencyAnchor.SATELLITE)
        frequencyHz.coerceIn(minOf(transponder.uplinkLow!!,transponder.uplinkHigh!!), maxOf(transponder.uplinkLow,transponder.uplinkHigh))
    else position.getDownlinkFreq(appliedTx)
    return CalculatorFrequencyPair(appliedTx,appliedRx,nominalTx,position.getUplinkFreq(appliedRx))
}
