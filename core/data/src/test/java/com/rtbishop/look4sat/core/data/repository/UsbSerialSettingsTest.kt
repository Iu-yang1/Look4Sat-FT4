package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.control.SerialUsbFlow
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbSerialSettingsTest {
    @Test fun reopeningSettingsKeepsManualDriverPortFlowAndIndependentModemLines() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("usb-settings", Context.MODE_PRIVATE)
        fun repository() = SettingsRepo(context.getSystemService(LocationManager::class.java), prefs, "test")
        assertEquals(SerialUsbFlow.NONE, repository().radioControlSettings.value.usbFlowControl)
        for (flow in SerialUsbFlow.entries) {
            val first = repository()
            val expected = first.radioControlSettings.value.copy(
                radioModel = RadioControlSettings.MODEL_ICOM_IC820,
                catTransport = RadioControlSettings.TRANSPORT_USB,
                txRadioAddress = "1:2:2:4660:22136:FTDI",
                usbFlowControl = flow, catDtr = true, catRts = false
            )
            first.updateRadioControlSettings(expected)
            val restored = repository().radioControlSettings.value
            assertEquals(expected.txRadioAddress, restored.txRadioAddress)
            assertEquals(flow, restored.usbFlowControl)
            assertTrue(restored.catDtr)
            assertFalse(restored.catRts)
        }
    }
}
