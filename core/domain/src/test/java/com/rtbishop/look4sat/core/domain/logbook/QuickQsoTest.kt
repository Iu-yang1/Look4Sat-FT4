package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.NearEarthObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickQsoTest {
    @Test fun cwUsesRstAndVoiceUsesRs() {
        assertNull(quickLogValidation("ja1abc/p", "599", "579", "CW"))
        assertNull(quickLogValidation("BA7OPF", "59", "57", "SSB"))
        assertNull(quickLogValidation("K1ABC", "-10", "+02", "FT4"))
        assertEquals(QuickLogError.REPORT, quickLogValidation("K1ABC", "599", "599", "FM"))
        assertEquals(QuickLogError.REPORT, quickLogValidation("K1ABC", "59", "59", "FT8"))
        assertEquals(QuickLogError.CALLSIGN, quickLogValidation("ABC", "59", "59", "FM"))
    }

    @Test fun linearPassbandEdgesAreNotLoggedAsTunedFrequencies() {
        val satellite = NearEarthObject(OrbitalData("RS-44", 0.0, 15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 44909, 0.0))
        val pass = OrbitalPass(1L, 0.0, 100L, 0.0, 44909, 40.0, satellite, 0f)
        val channel = SatRadio("test", "Linear", true, 435_610_000, 435_640_000, "USB", 145_935_000, 145_965_000, "LSB", true, 44909)
        val record = quickQsoRecord(10L, pass, "K1ABC", "59", "57", "SSB", "BA7OPF", "OL62", RadioTrackingState(), channel)
        assertEquals("SSB", record.mode)
        assertEquals("", record.submode)
        assertEquals("RS-44", record.satelliteName)
        assertEquals(QsoStatus.COMPLETE, record.status)
        assertEquals("2M", record.band)
        assertEquals("70CM", record.rxBand)
        assertNull(record.txFrequencyHz)
        assertNull(record.rxFrequencyHz)
    }

    @Test fun manualDetailsOverrideDefaultsAndDigitalModeUsesAdifSubmode() {
        val satellite = NearEarthObject(OrbitalData("RS-44", 0.0, 15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 44909, 0.0))
        val pass = OrbitalPass(1L, 0.0, 100L, 0.0, 44909, 40.0, satellite, 0f)
        val channel = SatRadio("test", "Linear", true, 435_610_000, 435_640_000, "USB", 145_935_000, 145_965_000, "LSB", true, 44909)

        val record = quickQsoRecord(
            now = 50L,
            pass = pass,
            callsign = "k1abc",
            sent = "-10",
            received = "-08",
            mode = "FT4",
            myCallsign = "ba7opf",
            myGrid = "ol62",
            radio = RadioTrackingState(),
            transponder = channel,
            details = QuickQsoDetails(
                startUtcMillis = 40L,
                theirGrid = "fn31",
                txFrequencyHz = 145_950_000,
                rxFrequencyHz = 435_625_000,
                satelliteMode = "V/U",
                comment = "portable",
                status = QsoStatus.DRAFT
            )
        )

        assertEquals(40L, record.startUtcMillis)
        assertNull(record.endUtcMillis)
        assertEquals("K1ABC", record.theirCallsign)
        assertEquals("FN31", record.theirGrid)
        assertEquals("BA7OPF", record.myCallsign)
        assertEquals("OL62", record.myGrid)
        assertEquals(145_950_000L, record.txFrequencyHz)
        assertEquals(435_625_000L, record.rxFrequencyHz)
        assertEquals("MFSK", record.mode)
        assertEquals("FT4", record.submode)
        assertEquals("V/U", record.satelliteMode)
        assertEquals("portable", record.comment)
        assertEquals(QsoStatus.DRAFT, record.status)
    }
}
