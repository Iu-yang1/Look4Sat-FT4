package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import com.rtbishop.look4sat.core.domain.model.N76FirmwareMode
import com.rtbishop.look4sat.core.domain.model.N76Power
import org.junit.Assert.*
import org.junit.Test

class N76OptionsJsonTest {
    @Test fun everyCatOptionSurvivesPreferenceRoundTrip() {
        val options = N76ControlOptions(firmwareMode = N76FirmwareMode.EXACT, sendSatelliteInfo = false,
            pollMillis = 750, power = N76Power.LOW, openSquelchOnTrack = true,
            forceRxCtcss = true, rxCtcssHz = 88.5, forceTxCtcss = true, txCtcssHz = 67.0,
            speakerMonitor = false, recordRadio = true, recordMicrophone = true, recordOnTrack = true,
            outputFolderUri = "content://recordings/tree/test", inputDeviceKey = "phone-mic", micGainDb = 18)
        assertEquals(options.normalized(), n76OptionsFromJson(options.toPreferenceJson()))
        assertFalse(n76OptionsFromJson(options.toPreferenceJson()).recordMicrophone)
    }
    @Test fun defaultsAndExplicitToneOffRemainDistinctFromFollowingTransponder() {
        assertEquals(N76ControlOptions(), n76OptionsFromJson(null))
        assertEquals(N76ControlOptions(), n76OptionsFromJson("invalid"))
        val options = N76ControlOptions(forceTxCtcss = true, txCtcssHz = null)
        val restored = n76OptionsFromJson(options.toPreferenceJson())
        assertTrue(restored.forceTxCtcss)
        assertNull(restored.txCtcssHz)
        assertEquals(N76Power.HIGH, n76OptionsFromJson("{\"power\":\"future\"}").power)
    }
    @Test fun oldSettingsKeepExplicitPowerAndMigrateMicrophoneOnlyRecording() {
        assertEquals(N76Power.HIGH, n76OptionsFromJson("{}").power)
        for (power in N76Power.entries.filterNot { it==N76Power.UNCHANGED }) assertEquals(power,
            n76OptionsFromJson("{\"power\":\"${power.name}\"}").power)
        val migrated = n76OptionsFromJson("{\"recordMic\":true}")
        assertTrue(migrated.recordRadio)
        assertFalse(migrated.recordMicrophone)
    }
    @Test fun legacyUnchangedDefaultMigratesToHighWithoutErasingOtherOptions() {
        val legacy=n76OptionsFromJson("{\"power\":\"UNCHANGED\",\"poll\":750,\"speaker\":false,\"forceTx\":true,\"txTone\":67.0}")
        assertEquals(N76Power.HIGH,legacy.power)
        assertEquals(750L,legacy.pollMillis)
        assertFalse(legacy.speakerMonitor)
        assertEquals(67.0,legacy.txCtcssHz!!,0.0)
        assertEquals(legacy,n76OptionsFromJson(legacy.toPreferenceJson()))
    }
    @Test fun newlyExplicitUnchangedSelectionSurvivesSaveReloadAndRestart() {
        val explicit=N76ControlOptions(power=N76Power.UNCHANGED)
        val saved=explicit.toPreferenceJson()
        assertEquals(N76Power.UNCHANGED,n76OptionsFromJson(saved).power)
        assertEquals(N76Power.UNCHANGED,n76OptionsFromJson(n76OptionsFromJson(saved).toPreferenceJson()).power)
    }
}
