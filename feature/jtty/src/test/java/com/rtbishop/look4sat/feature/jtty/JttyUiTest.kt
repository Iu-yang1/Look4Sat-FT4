package com.rtbishop.look4sat.feature.jtty

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import com.rtbishop.look4sat.core.domain.jtty.*
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="en-w411dp-h891dp-port")
class JttyUiTest {
    @get:Rule val compose=createComposeRule()
    private fun editor(dark: Boolean,scale: Float) {
        val actions=mutableListOf<JttyAction>()
        compose.setContent {
            MainTheme(isDarkTheme=dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,scale)) {
                    JttyScreen(JttyState(presets=listOf("CQ {MYCALL}","DE","599","73")),{actions+=it},{},{},{},{})
                }
            }
        }
        compose.onNodeWithTag("digitalTab_CONTACT").performClick()
        compose.onNodeWithText("F1").performScrollTo().performTouchInput {longClick()}
        compose.onNodeWithText("Template").assertExists()
        compose.onNodeWithTag("jttyPresetText").performTextReplacement("{CALL} DE {MYCALL}")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertTrue(actions.any {it==JttyAction.SavePreset(0,"{CALL} DE {MYCALL}")})
            assertFalse(actions.any {it==JttyAction.Send})
        }
    }
    @Test fun longPressEditorInDarkThemeDoesNotTransmit()=editor(true,1f)
    @Test fun longPressEditorInLightThemeAndLargeFontDoesNotCrash()=editor(false,1.5f)
    @Test @Config(qualifiers="en-w320dp-h540dp-port")
    fun smallWindowPresetEditorRemainsScrollable()=editor(false,1.2f)
    @Test fun rxCarrierClickCannotChangeTxFrequency() {
        val actions=mutableListOf<JttyAction>()
        compose.setContent { MainTheme {
            JttyScreen(JttyState(receive=JttyReceiveState(carriers=listOf(JttyCarrier(1,1300f,12f,0))),presets=emptyList()),{actions+=it},{},{},{},{})
        } }
        compose.onNodeWithText("1300 Hz · 12 dB").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(listOf(JttyAction.RxFrequency(1300f)),actions)}
    }
    @Test fun sharedRadarSizeButtonChangesLayoutAndKeepsPositionUpdates() {
        val large=mutableStateOf(false);val position=mutableStateOf(OrbitalPos(azimuth=1.0,elevation=0.5))
        compose.setContent { MainTheme {
            androidx.compose.foundation.layout.Column {
                DigitalRadarSizeButton(large.value,{large.value=it})
                DigitalSatellitePanel("RS-44","Linear",position.value,emptyList(),large.value)
            }
        } }
        compose.onNodeWithContentDescription("Use large radar").performClick()
        compose.runOnIdle {assertTrue(large.value);position.value=position.value.copy(azimuth=2.0)}
        compose.onNodeWithContentDescription("Use small radar").assertExists().performClick()
        compose.runOnIdle {assertFalse(large.value)}
    }
    @Test fun radarChoiceSurvivesSavedStateRestoration() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MainTheme { JttyScreen(JttyState(),{},{},{},{},{}) } }
        compose.onNodeWithContentDescription("Use large radar").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Use small radar").assertExists()
    }
    @Test fun sharedSpectrumGestureSelectsRxWithoutChangingTx() {
        val actions=mutableListOf<JttyAction>()
        compose.setContent {MainTheme {JttyScreen(JttyState(),{actions+=it},{},{},{},{})}}
        compose.onNodeWithContentDescription("JTTY spectrum and waterfall; select RX frequency")
            .performScrollTo().performTouchInput {click(center)}
        compose.runOnIdle {
            assertTrue(actions.any {it is JttyAction.RxFrequency})
            assertFalse(actions.any {it is JttyAction.TxFrequency || it==JttyAction.Send})
        }
    }

    @Test fun smallRadarHidesAnglesAndLargeRadarKeepsThem() {
        val large=mutableStateOf(false)
        compose.setContent {MainTheme {
            DigitalSatellitePanel("RS-44","",OrbitalPos(azimuth=1.0,elevation=0.5),emptyList(),large.value)
        }}
        compose.onAllNodesWithText("Azimuth",substring=true).assertCountEquals(0)
        compose.onAllNodesWithText("Elevation",substring=true).assertCountEquals(0)
        compose.runOnIdle {large.value=true}
        compose.onNodeWithText("Azimuth",substring=true).assertExists()
        compose.onNodeWithText("Elevation",substring=true).assertExists()
    }

    @Test fun repeatedTabChangesDoNotConnectReceiveSendOrSave() {
        val actions = mutableListOf<JttyAction>()
        var receiveCalls = 0
        var connectCalls = 0
        compose.setContent { MainTheme {
            JttyScreen(JttyState(draft = "CQ BG5JSU", presets = listOf("CQ", "DE", "K", "73")),
                { actions += it }, {}, {}, { receiveCalls++ }, { connectCalls++ })
        } }
        repeat(3) {
            compose.onNodeWithTag("digitalTab_CONTACT").performClick().performClick()
            compose.onNodeWithTag("digitalTab_LOG").performClick()
            compose.onNodeWithTag("digitalTab_SPECTRUM").performClick()
        }
        compose.onNodeWithTag("digitalTab_CONTACT").performClick()
        compose.onNodeWithText("CQ BG5JSU").assertExists()
        compose.runOnIdle {
            assertTrue(actions.isEmpty())
            assertEquals(0, receiveCalls)
            assertEquals(0, connectCalls)
        }
    }

    @Test fun stopTransmitRemainsAccessibleOnEveryTab() {
        val actions = mutableListOf<JttyAction>()
        compose.setContent { MainTheme {
            JttyScreen(JttyState(busy = true), { actions += it }, {}, {}, {}, {})
        } }
        listOf("SPECTRUM", "CONTACT", "LOG").forEach { tab ->
            compose.onNodeWithTag("digitalTab_$tab").performClick()
            compose.onNodeWithTag("digitalStopTransmit").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(List(3) { JttyAction.EmergencyStop }, actions) }
    }

    @Test fun selectedTabAndDraftSurviveRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MainTheme {
            JttyScreen(JttyState(draft = "BG5JSU DE BA7OPF", presets = emptyList()), {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("digitalTab_CONTACT").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("digitalTab_CONTACT").assertIsSelected()
        compose.onNodeWithText("BG5JSU DE BA7OPF").assertExists()
    }

    @Test fun allSatelliteLogFilterSurvivesLeavingTabWithoutSavingAnotherQso() {
        val actions = mutableListOf<JttyAction>()
        val record = com.rtbishop.look4sat.core.domain.logbook.QsoRecord(
            id = 1, startUtcMillis = 0, theirCallsign = "BG5JSU",
            myCallsign = "BA7OPF", mode = "JTTY", satelliteName = "RS-44")
        compose.setContent { MainTheme {
            JttyScreen(JttyState(records = listOf(record)), { actions += it }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("digitalTab_LOG").performClick()
        compose.onNodeWithText("Current satellite").performScrollTo().performClick()
        compose.onNodeWithText("All satellites").performClick()
        compose.onNodeWithText("BG5JSU").assertExists()
        compose.onNodeWithTag("digitalTab_CONTACT").performClick()
        compose.onNodeWithTag("digitalTab_LOG").performClick()
        compose.onNodeWithText("BG5JSU").assertExists()
        compose.runOnIdle { assertFalse(actions.contains(JttyAction.SaveLog)) }
    }
}
