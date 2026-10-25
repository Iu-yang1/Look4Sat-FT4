package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.*
import org.junit.Test

class CalculatorTuningTest {
    private val transponder = SatRadio(uuid="linear",info="Linear Transponder",isAlive=true,
        downlinkLow=435000000,downlinkHigh=435100000,downlinkMode="USB",
        uplinkLow=145900000,uplinkHigh=146000000,uplinkMode="USB",isInverted=true,catnum=12345)

    @Test fun groundTxIsNotDopplerCorrectedTwice() {
        val tuning=CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_TX,145950000)
        for(rate in listOf(-7.0,0.0,7.0)) {
            val position=OrbitalPos(distanceRate=rate)
            val pair=tuning.frequencies(transponder,position,2500)!!
            assertEquals(145950000,pair.txHz)
            assertEquals(DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(145950000,transponder,position,2500),pair.rxHz)
        }
    }

    @Test fun groundRxKeepsReceiverFixedAndTracksInvertedUplink() {
        val tuning=CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_RX,435050000)
        for(rate in listOf(-7.0,0.0,7.0)) {
            val position=OrbitalPos(distanceRate=rate)
            val pair=tuning.frequencies(transponder,position,-1500)!!
            assertEquals(435050000,pair.rxHz)
            assertEquals(DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(435050000,transponder,position,-1500),pair.txHz)
        }
    }

    @Test fun passbandAnchorUsesFreshTimeEvenWhenCalculatorPageIsClosed() {
        val tuning=CalculatorTuning("linear",CalculatorFrequencyAnchor.SATELLITE,145940000)
        val first=tuning.frequencies(transponder,OrbitalPos(distanceRate=-3.0),0)!!
        val later=tuning.frequencies(transponder,OrbitalPos(distanceRate=3.0),0)!!
        assertEquals(145940000,first.nominalTxHz)
        assertEquals(145940000,later.nominalTxHz)
        assertNotEquals(first.txHz,later.txHz)
        assertNotEquals(first.rxHz,later.rxHz)
    }

    @Test fun invalidOrDifferentTransponderTuningCannotApply() {
        assertNull(CalculatorTuning("other",CalculatorFrequencyAnchor.GROUND_TX,145950000).frequencies(transponder,OrbitalPos(),0))
        assertNull(CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_TX,0).frequencies(transponder,OrbitalPos(),0))
        assertNull(CalculatorTuning("linear",CalculatorFrequencyAnchor.SATELLITE,145950000)
            .frequencies(transponder.copy(uplinkHigh=transponder.uplinkLow),OrbitalPos(),0))
    }

    @Test fun tuningOutsideTheMovingPassbandIsClamped() {
        val pair=CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_RX,1).frequencies(transponder,OrbitalPos(),0)!!
        assertEquals(435000000,pair.rxHz)
        assertEquals(146000000,pair.txHz)
    }

    @Test fun bothManualReferencesCalibrateThePassbandsWithoutChangingBandwidthOrInversion() {
        for (inverted in listOf(false, true)) {
            val original=transponder.copy(isInverted=inverted)
            val reference=CalculatorReference(146_950_000,436_055_000,2500)
            val calibrated=original.withCalculatorReference(reference)!!
            assertEquals(100000L,calibrated.uplinkHigh!!-calibrated.uplinkLow!!)
            assertEquals(100000L,calibrated.downlinkHigh!!-calibrated.downlinkLow!!)
            assertEquals(inverted,calibrated.isInverted)
            val center=CalculatorTuning(original.uuid,CalculatorFrequencyAnchor.SATELLITE,reference.txHz,reference)
                .frequencies(original,OrbitalPos(),2500)!!
            assertEquals(reference.txHz,center.txHz)
            assertEquals(reference.rxHz,center.rxHz)
            val next=CalculatorTuning(original.uuid,CalculatorFrequencyAnchor.SATELLITE,reference.txHz+1000,reference)
                .frequencies(original,OrbitalPos(),2500)!!
            assertEquals(reference.rxHz+if(inverted)-1000 else 1000,next.rxHz)
        }
    }

    @Test fun calibratedReferencesApplyOffsetAndDopplerOnceAcrossRepeatedUpdates() {
        val reference=CalculatorReference(145_962_000,435_057_000,2500)
        val tuning=CalculatorTuning(transponder.uuid,CalculatorFrequencyAnchor.SATELLITE,reference.txHz,reference)
        for(rate in listOf(-7.0,0.0,7.0,-7.0)) {
            val position=OrbitalPos(distanceRate=rate)
            val pair=tuning.frequencies(transponder,position,2500)!!
            assertEquals(position.getUplinkFreq(reference.txHz),pair.txHz)
            assertTrue(kotlin.math.abs(position.getDownlinkFreq(reference.rxHz)-pair.rxHz)<=3)
            val shifted=tuning.frequencies(transponder,position,3500)!!
            assertTrue(kotlin.math.abs(position.getDownlinkFreq(reference.rxHz+1000)-shifted.rxHz)<=3)
        }
    }

    @Test fun calibratedGroundAnchorsUseTheSameReferenceForBothDirections() {
        val reference=CalculatorReference(145_962_000,435_057_000)
        for(rate in listOf(-7.0,0.0,7.0)) {
            val position=OrbitalPos(distanceRate=rate)
            val tx=position.getUplinkFreq(reference.txHz)
            val pair=CalculatorTuning(transponder.uuid,CalculatorFrequencyAnchor.GROUND_TX,tx,reference)
                .frequencies(transponder,position,0)!!
            val reverse=CalculatorTuning(transponder.uuid,CalculatorFrequencyAnchor.GROUND_RX,pair.rxHz,reference)
                .frequencies(transponder,position,0)!!
            assertTrue(kotlin.math.abs(tx-reverse.txHz)<=3)
            assertEquals(pair.rxHz,reverse.rxHz)
        }
    }

    @Test fun invalidCalibrationAndReceiveOnlyEntriesCannotInventATransmitPassband() {
        for(reference in listOf(CalculatorReference(0,435000000),CalculatorReference(145950000,0),
            CalculatorReference(145950000,435000000,Long.MAX_VALUE))) {
            assertNull(transponder.withCalculatorReference(reference))
            assertNull(CalculatorTuning(transponder.uuid,CalculatorFrequencyAnchor.SATELLITE,145950000,reference)
                .frequencies(transponder,OrbitalPos(),0))
        }
        assertNull(transponder.copy(uplinkLow=null,uplinkHigh=null).withCalculatorReference(CalculatorReference(145950000,435000000)))
        assertEquals(transponder,transponder.withCalculatorReference(null))
    }
}
