package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.utility.CalculatorReference
import org.junit.Assert.*
import org.junit.Test

class CalculatorReferenceStateTest {
    private val radio=SatRadio("linear","Linear Transponder",true,435000000,435100000,"USB",
        145900000,146000000,"USB",true,12345)
    private val service=RadioTrackingState(selectedTransponder=radio,txBaseFrequencyHz=145962000,
        nominalRxFrequencyHz=435057000)

    @Test fun eitherReferenceChangeRefreshesThePairForBothCalculatorPages() {
        var ui=RadioControlSubState(selectedTransponderUuid=radio.uuid)
        val first=ui.updatedCalculatorReference(service,2500)!!
        assertEquals(CalculatorReference(145962000,435057000,2500),first)
        ui=ui.copy(calculatorReference=first)
        val rx=ui.updatedCalculatorReference(service.copy(nominalRxFrequencyHz=435059000),2500)!!
        assertEquals(435059000L,rx.rxHz)
        assertEquals(145962000L,rx.txHz)
        val tx=ui.updatedCalculatorReference(service.copy(txBaseFrequencyHz=145965000),2500)!!
        assertEquals(145965000L,tx.txHz)
        assertEquals(435057000L,tx.rxHz)
        assertEquals(first,ui.calculatorReferenceFor(radio.uuid,2500))
    }

    @Test fun linkedFrequencyUpdatesDoNotMoveTheCalibrationOrApplyOffsetAgain() {
        val reference=CalculatorReference(145962000,435057000,2500)
        val ui=RadioControlSubState(selectedTransponderUuid=radio.uuid,calculatorReference=reference)
        repeat(5) { index ->
            assertEquals(reference,ui.updatedCalculatorReference(service.copy(calculatorLinked=true,
                calculatorReference=reference,txBaseFrequencyHz=145963000L+index,
                nominalRxFrequencyHz=435056000L-index),3500))
        }
        assertEquals(reference,ui.updatedCalculatorReference(service,3500))
        assertEquals(reference,RadioControlSubState().updatedCalculatorReference(service.copy(
            calculatorLinked=true,calculatorReference=reference),3500))
    }

    @Test fun selectionChangesAndReceiveOnlyStateCannotReuseTheOtherTranspondersReferences() {
        val ui=RadioControlSubState(selectedTransponderUuid=radio.uuid,
            calculatorReference=CalculatorReference(145962000,435057000))
        assertNull(ui.calculatorReferenceFor("other",0))
        val changed=ui.updatedCalculatorReference(service.copy(selectedTransponder=radio.copy(uuid="other")),1500)!!
        assertEquals(1500L,changed.offsetHz)
        assertNull(ui.updatedCalculatorReference(service.copy(txBaseFrequencyHz=null),0))
        assertNull(ui.updatedCalculatorReference(service.copy(nominalRxFrequencyHz=null),0))
    }
}
