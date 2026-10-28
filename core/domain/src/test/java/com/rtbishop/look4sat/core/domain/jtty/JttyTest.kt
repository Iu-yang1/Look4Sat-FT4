package com.rtbishop.look4sat.core.domain.jtty

import com.rtbishop.look4sat.core.domain.ft4.DigitalTransmitMode
import com.rtbishop.look4sat.core.domain.logbook.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class JttyTest {
    @Test fun variablesAreExpandedFromSharedFieldsAndMissingValuesFail() {
        val fields=mapOf("CALL" to "K1ABC/P","MYCALL" to "BG5JSU","RST" to "599","GRID" to "OL87")
        assertEquals("K1ABC/P DE BG5JSU 599 OL87",JttyText.expand("{CALL} DE {MYCALL} {RST} {GRID}",fields))
        assertTrue(runCatching { JttyText.expand("{UNKNOWN}",fields) }.isFailure)
        assertTrue(runCatching { JttyText.expand("{CALL}",fields+ ("CALL" to "")) }.isFailure)
        assertTrue(runCatching { JttyText.expand("{CALL",fields) }.isFailure)
        assertEquals("CQ CQ",JttyText.expand("CQ CQ",emptyMap()))
    }
    @Test fun protocolRejectsUnsupportedTextWithoutSilentlyReplacingIt() {
        assertEquals("CQ K1ABC/P",JttyText.normalize("cq k1abc/p"))
        for(text in listOf("", "A".repeat(81),"你好","ß","TEST~","A\nB")) assertTrue(runCatching { JttyText.normalize(text) }.isFailure)
        assertEquals(80,JttyText.normalize("A".repeat(80)).length)
    }
    @Test fun carrierIdsAreStableBoundedAndExpire() {
        val search=JttyCarrierSearch()
        search.confirm(1500f,4f,0);val id=search.snapshot(0).single().id
        search.confirm(1505f,7f,1000);assertEquals(id,search.snapshot(1000).single().id)
        repeat(20) { search.confirm(200f+it*100,5f,2000) }
        assertEquals(8,search.snapshot(2000).size);assertTrue(search.snapshot(8001).isEmpty())
        search.reset();assertTrue(search.snapshot(0).isEmpty())
    }
    @Test fun spectrumSearchFindsAudioCarrierWithoutATransmitControl() {
        val search=JttyCarrierSearch()
        search.push(FloatArray(8192) { sin(2*PI*1500*it/12000).toFloat() },0)
        assertTrue(search.snapshot(0).any { kotlin.math.abs(it.frequencyHz-1500)<5 })
    }
    @Test fun longJttyDoesNotRelaxFt4Watchdog() {
        assertEquals(30_208L,JttyEncoding("A".repeat(80),16).durationMillis)
        assertEquals(6_000L,DigitalTransmitMode.FT4.maximumWaveformMillis)
        assertEquals(35_000L,DigitalTransmitMode.JTTY.maximumPttMillis)
    }
    @Test fun adifRoundTripPreservesJttyWithoutAnInvalidSubmode() {
        val record=QsoRecord(startUtcMillis=1_700_000_000_000,theirCallsign="K1ABC",myCallsign="BG5JSU",mode="JTTY",status=QsoStatus.COMPLETE)
        val adi=AdifCodec.encode(listOf(record))
        assertTrue(adi.contains("<MODE:4>MFSK"));assertTrue(adi.contains("<APP_LOOK4SAT_MODE:4>JTTY"))
        assertFalse(adi.contains("<SUBMODE"));assertEquals("JTTY",AdifCodec.decode(adi).single().mode)
    }
}
