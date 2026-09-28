package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCapabilitiesTest {

    @Test
    fun catalogDefinesEverySupportedModelExactlyOnce() {
        assertEquals(
            listOf(
                RadioControlSettings.MODEL_YAESU_FT817,
                RadioControlSettings.MODEL_YAESU_FT857,
                RadioControlSettings.MODEL_ICOM_IC705,
                RadioControlSettings.MODEL_ICOM_IC9700,
                RadioControlSettings.MODEL_ICOM_IC910
            ),
            RadioModelCatalog.supportedModels
        )
        assertEquals(RadioModelCatalog.supportedModels, RadioControlSettings.SUPPORTED_RADIOS)
    }

    @Test
    fun capabilitiesDescribeProtocolAndDuplexBoundaries() {
        val ft817 = radioModelDescriptor(RadioControlSettings.MODEL_YAESU_FT817)
        assertEquals(RadioProtocolFamily.YAESU_CAT, ft817.protocolFamily)
        assertFalse(ft817.capabilities.fullDuplex)
        assertFalse(ft817.capabilities.singleRadioSplit)
        assertTrue(ft817.capabilities.dataMode)
        assertTrue(ft817.capabilities.narrowFm)
        assertNull(ft817.defaultCivAddress)

        val ic705 = radioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC705)
        assertEquals(RadioProtocolFamily.ICOM_CIV, ic705.protocolFamily)
        assertTrue(ic705.capabilities.singleRadioSplit)
        assertFalse(ic705.capabilities.fullDuplex)
        assertFalse(ic705.capabilities.satelliteMode)
        assertEquals(0xA4, ic705.defaultCivAddress)

        val ic9700 = radioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC9700)
        assertTrue(ic9700.capabilities.fullDuplex)
        assertTrue(ic9700.capabilities.satelliteMode)
        assertTrue(ic9700.capabilities.dataMode)
        assertTrue(RadioBand.TWENTY_THREE_CENTIMETERS in ic9700.capabilities.bands)

        val ic910 = radioModelDescriptor(RadioControlSettings.MODEL_ICOM_IC910)
        assertTrue(ic910.capabilities.fullDuplex)
        assertTrue(ic910.capabilities.satelliteMode)
        assertFalse(ic910.capabilities.dataMode)
        assertFalse(ic910.capabilities.narrowFm)
    }

    @Test
    fun unknownModelsUseTheLegacyFt817Fallback() {
        assertEquals(
            radioModelDescriptor(RadioControlSettings.MODEL_YAESU_FT817),
            radioModelDescriptor("Unknown radio")
        )
    }
}
