package com.rtbishop.look4sat.feature.radar

import androidx.lifecycle.viewModelScope
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.ILoTWUploadRepository
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.IWavelogUploadRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CwConsoleLogTest {
    private class Fixture(val viewModel: LogViewModel, val saved: MutableList<QsoRecord>, val operatorSettings: MutableStateFlow<com.rtbishop.look4sat.core.domain.model.Ft4Settings>, val wavelogSettings: MutableStateFlow<WavelogUploadSettings>, var failSave: Boolean = false)

    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private fun withFixture(test: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<QsoRecord>()
        lateinit var fixture: Fixture
        val records = MutableStateFlow<List<QsoRecord>>(emptyList())
        val qso = stub<IQsoRepository> { name, args ->
            when (name) {
                "getRecords" -> records
                "save" -> {
                    if (fixture.failSave) error("Storage unavailable")
                    saved += args[0] as QsoRecord
                    1L
                }
                else -> error("Unexpected QSO call $name")
            }
        }
        val wavelog = MutableStateFlow(WavelogUploadSettings(
            url = "https://wavelog.example", apiKey = "test-key", stationId = "1",
            stationCallsign = "BA7OPF", stationGrid = "PM01"
        ))
        val operatorSettings = MutableStateFlow(com.rtbishop.look4sat.core.domain.model.Ft4Settings(operatorCallsign="BA7OPF"))
        val position = MutableStateFlow(GeoPos(0.0, 0.0))
        val settings = stub<ISettingsRepo> { name, _ ->
            when (name) {
                "getWavelogUploadSettings" -> wavelog
                "getFt4Settings" -> operatorSettings
                "getStationPosition" -> position
                "getCurrentGrid" -> "PM01"
                "getSatelliteMode" -> "CW"
                else -> error("Unexpected settings call $name")
            }
        }
        val lotw = stub<ILoTWUploadRepository> { name, _ ->
            when (name) {
                "certificate", "station" -> null
                "satelliteCatalog" -> emptyList<String>()
                else -> error("Unexpected LoTW call $name")
            }
        }
        val upload = stub<IWavelogUploadRepository> { name, _ -> error("Unexpected upload $name") }
        val vm = LogViewModel(qso, lotw, upload, settings)
        fixture = Fixture(vm, saved, operatorSettings,wavelog)
        try {
            runCurrent()
            test(fixture)
        } finally {
            vm.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun LogViewModel.recordCw(overrides: LogRecordOverrides = LogRecordOverrides(mode = "CW")) =
        record("FO-29", "SSB", 145950000, 435850000, "PM01AB", null, overrides)

    @Test fun lotwLoggingStoresTheCompleteFourCharacterMainGridSet() = withFixture { f ->
        f.wavelogSettings.value=WavelogUploadSettings();runCurrent()
        f.viewModel.updateCallsign("JA1ABC")
        f.viewModel.record("FO-29","CW",145950000,435850000," ol62ti,ol63ab ",null)
        runCurrent()
        val record=f.saved.single()
        assertEquals("OL62,OL63",record.myGrid)
        assertEquals(listOf("OL62","OL63"),record.myFullGrids)
        assertEquals(listOf("OL62","OL63"),record.vuccGrids)
        val adif=com.rtbishop.look4sat.core.domain.logbook.AdifCodec.encode(listOf(record))
        assertTrue(adif.contains("<MY_GRIDSQUARE:9>OL62,OL63"))
        assertTrue(adif.contains("<MY_VUCC_GRIDS:9>OL62,OL63"))
    }

    @Test fun aSingleEightCharacterLocatorKeepsItsPrecisionWithoutAMultigridField() = withFixture { f ->
        f.viewModel.updateCallsign("JA1ABC")
        f.viewModel.record("FO-29","CW",145950000,435850000," ol62ab12 ",null)
        runCurrent()
        val record=f.saved.single()
        assertEquals("OL62AB12",record.myGrid)
        val adif=com.rtbishop.look4sat.core.domain.logbook.AdifCodec.encode(listOf(record))
        assertTrue(adif.contains("<MY_GRIDSQUARE:8>OL62AB12"))
        assertFalse(adif.contains("<MY_VUCC_GRIDS:"))
    }

    @Test fun settingsCallsignIsFollowedInsteadOfTheUploadStationAndBlankDoesNotFallBack() = withFixture { f ->
        f.operatorSettings.value=f.operatorSettings.value.copy(operatorCallsign="bg5jsu")
        runCurrent()
        assertEquals("BG5JSU",f.viewModel.uiState.value.ownCallsign)
        f.viewModel.updateCallsign("JA1ABC");f.viewModel.recordCw();runCurrent()
        assertEquals("BG5JSU",f.saved.single().myCallsign)
        f.operatorSettings.value=f.operatorSettings.value.copy(operatorCallsign="")
        runCurrent();assertEquals("",f.viewModel.uiState.value.ownCallsign)
    }

    @Test fun consoleOverridesOnlyAffectTheNewCwRecord() = withFixture { f ->
        val vm = f.viewModel
        vm.updateCallsign("bg5jsu/p")
        vm.recordCw(LogRecordOverrides("BA7OPF/P", "579", mode = "CW"))
        runCurrent()
        val qso = f.saved.single()
        assertEquals("BG5JSU/P", qso.theirCallsign)
        assertEquals("BA7OPF/P", qso.myCallsign)
        assertEquals("579", qso.sentReport)
        assertEquals("CW", qso.mode)
        assertEquals("BA7OPF", vm.uiState.value.ownCallsign)
        assertEquals("", vm.uiState.value.callsignInput)
    }

    @Test fun wavelogOnlyConsoleRecordsUseTheSelectedStationCallsign() = withFixture { f ->
        val vm = f.viewModel
        assertTrue(vm.uiState.value.wavelogMode)
        assertEquals("", vm.uiState.value.certificateCallsign)
        assertEquals("BA7OPF", vm.uiState.value.ownCallsign)
        vm.updateCallsign("BG5JSU")
        vm.recordCw()
        runCurrent()
        assertEquals("BA7OPF", f.saved.single().myCallsign)
    }

    @Test fun storageFailureRetainsCallsignAndCanBeRetried() = withFixture { f ->
        f.viewModel.updateCallsign("BG5JSU")
        f.failSave = true
        f.viewModel.recordCw()
        runCurrent()
        assertEquals("BG5JSU", f.viewModel.uiState.value.callsignInput)
        assertTrue(f.viewModel.uiState.value.recordSaveFailed)
        assertFalse(f.viewModel.uiState.value.recordSaving)
        f.failSave = false
        f.viewModel.recordCw()
        runCurrent()
        assertEquals(1, f.saved.size)
        assertFalse(f.viewModel.uiState.value.recordSaveFailed)
    }

    @Test fun pendingSaveNeverClearsANewlyEnteredCallsignOrCreatesDuplicateWrites() = withFixture { f ->
        f.viewModel.updateCallsign("BG5JSU")
        f.viewModel.recordCw()
        f.viewModel.recordCw()
        f.viewModel.updateCallsign("F4HWN")
        runCurrent()
        assertEquals(1, f.saved.size)
        assertEquals("BG5JSU", f.saved.single().theirCallsign)
        assertEquals("F4HWN", f.viewModel.uiState.value.callsignInput)
    }

    @Test fun outOfWindowConfirmationKeepsTheOriginalCwOverrides() = withFixture { f ->
        f.viewModel.updateCallsign("BG5JSU")
        f.viewModel.record("FO-29", "SSB", 145950000, 435850000, "PM01AB", 0L..120000L,
            LogRecordOverrides("BA7OPF/P", "579", mode = "CW"))
        assertEquals("BA7OPF/P", f.viewModel.uiState.value.outOfWindowLog?.overrides?.myCallsign)
        f.viewModel.confirmOutOfWindowLog()
        runCurrent()
        assertEquals("CW", f.saved.single().mode)
        assertEquals("579", f.saved.single().sentReport)
        assertEquals("BA7OPF/P", f.saved.single().myCallsign)
    }

    @Test fun samePassRetainsTheSharedCallsignButNewPassAndLeavingClearIt() = withFixture { f ->
        val vm = f.viewModel
        vm.selectPass(24278, 1000)
        vm.updateCallsign("BG5JSU")
        vm.selectPass(24278, 1000)
        assertEquals("BG5JSU", vm.uiState.value.callsignInput)
        vm.selectPass(24278, 2000)
        assertEquals("", vm.uiState.value.callsignInput)
        vm.updateCallsign("F4HWN")
        vm.clearPassDraft()
        assertEquals("", vm.uiState.value.callsignInput)
    }
}
