package com.rtbishop.look4sat.feature.settings

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioControlOptionsTest {
    @Test fun voxIsAvailableForEveryModelIncludingUsbOnlyAndN76Models() {
        for (model in RadioControlSettings.SUPPORTED_RADIOS + "old-unknown-model") {
            assertTrue(model, RadioControlSettings.TRANSPORT_VOX in radioControlTransports(model))
        }
        assertEquals(listOf("BLUETOOTH", "VOX"), radioControlTransports(RadioControlSettings.MODEL_VERO_N76))
        for (model in listOf(RadioControlSettings.MODEL_QUANSHENG_UVK1,
            RadioControlSettings.MODEL_QUANSHENG_UVK5V3)) {
            assertEquals(listOf("USB", "VOX"), radioControlTransports(model))
        }
    }

    @Test fun ftx1OffersBluetoothAndUsbWithoutEnablingTcp() {
        assertEquals(listOf("BLUETOOTH", "USB", "VOX"),
            radioControlTransports(RadioControlSettings.MODEL_YAESU_FTX1))
    }

    @Test fun savingVoxChangesOnlyTransportAndEnableFlagRegardlessOfHiddenConfiguration() {
        for (model in RadioControlSettings.SUPPORTED_RADIOS + "old-unknown-model") {
            val initial = RadioControlSettings(false, model, "invalid address", "", "TX", "RX", -1,
                splitMode = true, civAddress = 999, tcpProtocol = "old", l4sDopplerStepHz = -1,
                l4sCorrectionEditStepHz = 0)
            for (enabled in listOf(false, true)) {
                assertEquals(initial.copy(enabled = enabled, catTransport = "VOX"), voxControlSettings(initial, enabled))
            }
        }
    }
}
