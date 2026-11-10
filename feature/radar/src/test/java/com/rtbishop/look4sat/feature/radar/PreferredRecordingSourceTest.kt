package com.rtbishop.look4sat.feature.radar

import android.media.AudioDeviceInfo
import com.rtbishop.look4sat.core.domain.audio.RecordingSource
import com.rtbishop.look4sat.core.domain.audio.RecordingSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredRecordingSourceTest {
    private val n76=RecordingSource("n76","N76",RecordingSourceKind.N76)
    private val usb=RecordingSource("${AudioDeviceInfo.TYPE_USB_DEVICE}:USB","USB",RecordingSourceKind.DEVICE)
    private val ext=RecordingSource("4:Line","Line",RecordingSourceKind.DEVICE)
    private val wlan=RecordingSource("wlan","IC-705 WLAN",RecordingSourceKind.WLAN)
    private val mic=RecordingSource("15:Mic","Mic",RecordingSourceKind.MICROPHONE)

    @Test fun disconnectedN76FallsBackToUsbWithoutHidingWlanOrMicrophone() {
        assertEquals(usb,preferredRecordingSource(listOf(n76,mic,wlan,usb),null,false))
        assertEquals(n76,preferredRecordingSource(listOf(n76,mic,wlan,usb),null,true))
        assertEquals(wlan,preferredRecordingSource(listOf(n76,wlan,mic),"n76",false))
    }

    @Test fun rememberedAvailableSourceOverridesAutomaticOrdering() {
        val all=listOf(n76,usb,ext,wlan,mic)
        assertEquals(mic,preferredRecordingSource(all,mic.id,true))
        assertEquals(ext,preferredRecordingSource(all,ext.id,false))
        assertEquals(usb,preferredRecordingSource(all,"unplugged-device",false))
    }

    @Test fun fallbackOrderAndEmptyStateAreDeterministic() {
        assertEquals(ext,preferredRecordingSource(listOf(mic,ext,wlan),null,false))
        assertEquals(wlan,preferredRecordingSource(listOf(mic,wlan),null,false))
        assertEquals(mic,preferredRecordingSource(listOf(mic),null,false))
        assertNull(preferredRecordingSource(listOf(n76),null,false))
        assertNull(preferredRecordingSource(emptyList(),null,true))
    }
}
