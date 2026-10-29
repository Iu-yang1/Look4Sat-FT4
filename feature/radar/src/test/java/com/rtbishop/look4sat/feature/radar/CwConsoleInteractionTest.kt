package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.presentation.MainTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-port")
class CwConsoleInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var viewModel: LogViewModel? = null
    private val actions = mutableListOf<RadarAction>()

    private inline fun <reified T> stub(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            call(method.name)
        } as T

    private fun openConsole(dark: Boolean = true, fontScale: Float = 1f,
        presets:List<String> = CwTransmitPresets.defaults) {
        val qso = stub<IQsoRepository> { if (it == "getRecords") MutableStateFlow<List<QsoRecord>>(emptyList()) else null }
        val lotw = stub<ILoTWUploadRepository> { if (it == "satelliteCatalog") emptyList<String>() else null }
        val upload = stub<IWavelogUploadRepository> { null }
        val settings = stub<ISettingsRepo> {
            when (it) {
                "getWavelogUploadSettings" -> MutableStateFlow(WavelogUploadSettings())
                "getStationPosition" -> MutableStateFlow(GeoPos(0.0, 0.0))
                "getCurrentGrid" -> "OL87"
                else -> null
            }
        }
        val vm = LogViewModel(qso, lotw, upload, settings).also { viewModel = it }
        val state = mutableStateOf(RadarState(cwConsole = CwConsoleState(draft = CwConsoleDraft(myCallsign = "BA7OPF")),
            radioControl = RadioControlSubState(cwPresets=presets,
            remoteRadio = RemoteRadioState(capabilities = RemoteRadioCapabilities(10,92,10,45,1500,
                18000000,1300000000,15,9,true,true,true,true,false,false,cwOptions = true,handsetMacros = true),
                nativeCwOptions = NativeCwOptions(), connected = true, transmitStateKnown = true))))
        compose.setContent {
            MainTheme(isDarkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                CwConsole(state.value, vm, { action ->
                    actions += action
                    if (action is RadarAction.SaveCwPreset) {
                        val presets = state.value.radioControl.cwPresets.toMutableList()
                        presets[action.index] = action.text
                        state.value = state.value.copy(radioControl = state.value.radioControl.copy(cwPresets = presets))
                    }
                    if (action is RadarAction.SetCwConsoleDraft) {
                        state.value = state.value.copy(cwConsole = state.value.cwConsole.copy(draft = action.draft))
                    }
                }, {}, {})
                }
            }
        }
    }

    @After fun close() { compose.runOnIdle { viewModel?.viewModelScope?.cancel() } }

    @Test fun longPressEveryPresetOpensEditorAndCancelDoesNotSend() {
        openConsole()
        for (index in 1..4) {
            compose.onNodeWithText("F$index").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithText("Edit F$index preset").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun actionButtonsHaveMatchingHeight() {
        openConsole()
        val send = compose.onNodeWithText("Send").fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithText("Stop TX").fetchSemanticsNode().boundsInRoot
        val options = compose.onNodeWithText("CW settings").fetchSemanticsNode().boundsInRoot
        assertEquals(send.height, stop.height, 0.5f)
        assertEquals(send.height, options.height, 0.5f)
    }

    @Test fun logButtonFollowsStopTxOnTheSameActionRow() {
        openConsole()
        val stop = compose.onNodeWithText("Stop TX").fetchSemanticsNode().boundsInRoot
        val log = compose.onNodeWithTag("cwConsoleLogButton").fetchSemanticsNode().boundsInRoot
        assertTrue("Log should be to the right of Stop TX", log.left >= stop.right)
        assertEquals("Log should be on the same row as Stop TX", stop.center.y, log.center.y, 2f)
        compose.onAllNodesWithTag("cwConsoleLogButton").assertCountEquals(1)
    }

    @Test fun syncPlainPresetsFromCwSettingsDispatchesWithoutSendingCw() {
        openConsole()
        compose.onNodeWithText("CW settings").performClick()
        compose.onNodeWithText("Sync F1–F4").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(RadarAction.SyncHandsetCwPresets),actions) }
    }

    @Test fun syncWithTemplateShowsErrorWithoutDispatchingOrCrashing() {
        openConsole(presets=listOf("{CALL}","DE","K","RR"))
        compose.onNodeWithText("CW settings").performClick()
        compose.onNodeWithText("Sync F1–F4").performScrollTo().performClick()
        compose.onAllNodesWithText("F1 contains variables; handset sync requires plain text").onLast().assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun callsignVariablesUseSharedLogInputWhenPresetIsSelected() {
        openConsole()
        compose.onNodeWithText("F1").performTouchInput { longClick() }
        compose.onNodeWithTag("cwPresetText").performTextClearance()
        compose.onNodeWithText("Their callsign {CALL}").performClick()
        compose.onNodeWithText("My callsign {MYCALL}").performClick()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle {
            assertEquals("{CALL} {MYCALL}", (actions.single() as RadarAction.SaveCwPreset).text)
            viewModel!!.updateCallsign("BG5JSU/P")
        }
        compose.onNodeWithText("F1").performClick()
        compose.onNodeWithTag("cwConsoleMessage").assertTextContains("BG5JSU/P BA7OPF")
        compose.runOnIdle { viewModel!!.updateCallsign("F4HWN") }
        // An already expanded draft is not rewritten by later log input.
        compose.onNodeWithTag("cwConsoleMessage").assertTextContains("BG5JSU/P BA7OPF")
        compose.onNodeWithText("F1").performClick()
        compose.onNodeWithTag("cwConsoleMessage").assertTextContains("F4HWN BA7OPF")
        compose.runOnIdle { assertFalse(actions.any { it is RadarAction.SendCw }) }
    }

    @Test fun lightThemeEditorRejectsInvalidTemplateWithoutClosing() {
        openConsole(dark = false)
        compose.onNodeWithText("F4").performTouchInput { longClick() }
        compose.onNodeWithTag("cwPresetText").performTextReplacement("{UNKNOWN}")
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithText("Unknown variable: {UNKNOWN}").assertIsDisplayed()
        compose.onNodeWithText("Edit F4 preset").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-port")
    fun smallScreenAndLargeTextCanEditAndCancel() {
        openConsole(fontScale = 1.5f)
        compose.onNodeWithText("F1").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("cwPresetText").performTextReplacement("{CALL} DE {MYCALL}")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithText("F1").assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }
}
