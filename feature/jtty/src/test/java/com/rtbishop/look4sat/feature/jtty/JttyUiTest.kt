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
}
