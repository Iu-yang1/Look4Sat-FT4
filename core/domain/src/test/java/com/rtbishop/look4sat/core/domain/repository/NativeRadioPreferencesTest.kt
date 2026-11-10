package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import org.junit.Assert.*
import org.junit.Test

class NativeRadioPreferencesTest {
    @Test fun profileIdentityUsesFullNameAndSeparatesTransponders() {
        val first=radioSatelliteProfileKey("ABCDEFGHIJKLMNOP Satellite One","FM")
        assertEquals(first,radioSatelliteProfileKey("ABCDEFGHIJKLMNOP Satellite One","FM"))
        assertNotEquals(first,radioSatelliteProfileKey("ABCDEFGHIJKLMNOP Satellite Two","FM"))
        assertNotEquals(first,radioSatelliteProfileKey("ABCDEFGHIJKLMNOP Satellite One","CW"))
        assertNotEquals(radioSatelliteProfileKey("卫星一","FM"),radioSatelliteProfileKey("卫星二","FM"))
        assertEquals(32,first.length)
    }
    @Test fun configurable250HzQuantumAndManualStepsAreIndependentOfHardwareResolution() {
        assertEquals(435100000L,l4sTrackingTarget(435100124,0,250))
        assertEquals(435100250L,l4sTrackingTarget(435100125,0,250))
        assertEquals(435100130L,l4sTrackingTarget(435100125,0,10))
        assertEquals(435101000L,l4sTrackingTarget(435100600,0,1000))
        assertEquals(10L,normalizeL4sStep(1));assertEquals(250L,normalizeL4sStep(259))
    }
    @Test fun default100HzControlQuantumKeepsIndependentRxTxAndTenHzCorrections() {
        assertEquals(100L, RadioControlSettings(
            enabled = true, radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1,
            txRadioAddress = "", rxRadioAddress = "", txRadioName = "", rxRadioName = "",
            baudRate = 9600
        ).l4sDopplerStepHz)
        assertEquals(435100000L, l4sTrackingTarget(435100049L, 0L, 100L))
        assertEquals(435100100L, l4sTrackingTarget(435100050L, 0L, 100L))
        assertEquals(435100110L, l4sTrackingTarget(435100050L, 10L, 100L))
        assertEquals(145900100L, l4sTrackingTarget(145900051L, 0L, 100L))
    }
    @Test fun rxAndTxCorrectionsApplyExactlyOnceAfterControlQuantization() {
        assertEquals(435100010L,l4sTrackingTarget(435100006,10,250))
        assertEquals(435099500L,l4sTrackingTarget(435100006,-500,250))
        assertEquals(145900750L,l4sTrackingTarget(145900004,750,250))
        assertThrows(IllegalArgumentException::class.java) {l4sTrackingTarget(1,-500,250)}
        assertThrows(IllegalArgumentException::class.java) {l4sTrackingTarget(0xfffffffaL,0,10000)}
    }
    @Test fun allFourCwPresetsSupportSafeEditingClearingAndNormalization() {
        assertEquals(4,CwTransmitPresets.defaults.size)
        CwTransmitPresets.defaults.forEach {assertEquals(it,CwTransmitPresets.normalize(it))}
        assertEquals("CQ DE BA7OPF K",CwTransmitPresets.normalize(" cq  de ba7opf k "))
        assertEquals("",CwTransmitPresets.normalize("  "))
        assertThrows(IllegalArgumentException::class.java) {CwTransmitPresets.normalize("你好")}
        assertThrows(IllegalArgumentException::class.java) {CwTransmitPresets.normalize("A".repeat(4097))}
    }
}
