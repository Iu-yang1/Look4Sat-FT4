package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.utility.CalculatorReference
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTransponderFrequenciesTest {
    private val fm = SatRadio(
        "fm", "FM voice", true, 435_100_000L, null, "FM",
        145_900_000L, null, "FM", false, 1
    )
    private val linear = SatRadio(
        "linear", "Linear Transponder", true, 435_000_000L, 435_100_000L, "USB",
        145_900_000L, 146_000_000L, "LSB", true, 1
    )

    private fun raw(radio: SatRadio, pos: OrbitalPos) = radio.copy(
        uplinkLow = radio.uplinkLow?.let(pos::getUplinkFreq),
        uplinkHigh = radio.uplinkHigh?.let(pos::getUplinkFreq),
        downlinkLow = radio.downlinkLow?.let(pos::getDownlinkFreq),
        downlinkHigh = radio.downlinkHigh?.let(pos::getDownlinkFreq)
    )

    @Test fun editedFmReferencesUpdateBothLiveRowsAndReportFrequency() {
        val position = OrbitalPos(distanceRate = 5.0)
        val original = listOf(raw(fm, position), raw(linear, position))
        val state = RadioTrackingState(selectedTransponder = fm,
            txBaseFrequencyHz = 145_905_000L, nominalRxFrequencyHz = 435_103_000L)
        val updated = liveTransponderFrequencyRows(original, "fm", state, position)
        assertEquals(position.getUplinkFreq(145_905_000L), updated[0].uplinkLow)
        assertEquals(position.getDownlinkFreq(435_103_000L), updated[0].downlinkLow)
        assertEquals(position.getDownlinkFreq(435_103_000L),
            selectedLiveDownlinkFrequency(updated, "fm"))
        assertEquals(original[1], updated[1]) // unrelated transponders stay unchanged

        val editedAgain = liveTransponderFrequencyRows(original, "fm", state.copy(
            txBaseFrequencyHz = 145_906_000L, nominalRxFrequencyHz = 435_104_000L), position)
        assertEquals(position.getUplinkFreq(145_906_000L), editedAgain[0].uplinkLow)
        assertEquals(position.getDownlinkFreq(435_104_000L), editedAgain[0].downlinkLow)
    }

    @Test fun linearReferenceEditsShiftTheWholeBandWithoutLosingBandwidthOrDoppler() {
        val position = OrbitalPos(distanceRate = -3.0)
        val original = listOf(raw(linear, position))
        val state = RadioTrackingState(selectedTransponder = linear,
            txBaseFrequencyHz = 145_962_000L, nominalRxFrequencyHz = 435_057_000L)
        val updated = liveTransponderFrequencyRows(original, "linear", state, position).single()
        assertEquals(position.getUplinkFreq(145_912_000L), updated.uplinkLow)
        assertEquals(position.getUplinkFreq(146_012_000L), updated.uplinkHigh)
        assertEquals(position.getDownlinkFreq(435_007_000L), updated.downlinkLow)
        assertEquals(position.getDownlinkFreq(435_107_000L), updated.downlinkHigh)
        assertEquals((updated.downlinkLow!! + updated.downlinkHigh!!) / 2,
            selectedLiveDownlinkFrequency(listOf(updated), "linear"))
    }

    @Test fun linkedCalculatorDoesNotTurnEachTuneIntoANewReferenceCenter() {
        val position = OrbitalPos()
        val original = listOf(raw(linear, position))
        val reference = CalculatorReference(145_962_000L, 435_057_000L, 2_500L)
        val state = RadioTrackingState(selectedTransponder = linear, calculatorLinked = true,
            calculatorReference = reference, txBaseFrequencyHz = 145_999_000L,
            nominalRxFrequencyHz = 435_019_000L)
        val updated = liveTransponderFrequencyRows(original, "linear", state, position).single()
        assertEquals(145_912_000L, updated.uplinkLow)
        assertEquals(435_007_000L, updated.downlinkLow)
        // Without a calibration reference, a linked calculator must use the original passband.
        assertEquals(original, liveTransponderFrequencyRows(original, "linear",
            state.copy(calculatorReference = null), position))
    }

    @Test fun editingDifferentOrNoSelectedTransponderDoesNotRebaseAnotherRow() {
        val pos = OrbitalPos()
        val original = listOf(raw(fm, pos))
        val service = RadioTrackingState(selectedTransponder = fm,
            txBaseFrequencyHz = 145_905_000L, nominalRxFrequencyHz = 435_103_000L)
        assertEquals(original, liveTransponderFrequencyRows(original, "other", service, pos))
        assertEquals(original, liveTransponderFrequencyRows(original, null, service, pos))
        assertEquals(original, liveTransponderFrequencyRows(original, "fm", service, null))
    }
}
