// SPDX-License-Identifier: GPL-3.0-or-later
package com.rtbishop.look4sat.feature.ft4

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.rtbishop.look4sat.core.domain.audio.AudioHubState
import com.rtbishop.look4sat.core.domain.ft4.*
import com.rtbishop.look4sat.core.domain.model.Ft4Settings
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.time.ClockSnapshot
import com.rtbishop.look4sat.core.domain.time.ClockSource
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.SpectrumRenderState
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
class Ft4WorkbenchTest {
    @get:Rule val compose = createComposeRule()
    private val clock = ClockSnapshot(0, 0, 0.0, 0.0, 1.0, ClockSource.GNSS, 0, true)
    private fun state(transmit: Ft4TransmitState = Ft4TransmitState.Idle) = Ft4State(
        Ft4Settings(operatorCallsign = "BA7OPF"), Ft4Capability(), Ft4EngineState.Idle,
        emptyList(), clock, RadioTrackingState(), AudioHubState.Idle, transmit,
        targetCall = "BG5JSU", stationGrid = "OL87")

    @Test fun switchingManualAndAutomaticDoesNotArmOrTransmit() {
        val actions = mutableListOf<Ft4Action>()
        compose.setContent { MainTheme {
            Ft4Workbench(state(), Ft4TimingState(0, 0f, clock), SpectrumRenderState(),
                { actions += it }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("digitalTab_CONTACT").performClick()
        compose.onNodeWithText("Automatic", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Manual").performScrollTo().performClick()
        compose.onNodeWithText("BG5JSU").assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun waitingTransmissionCanBeStoppedFromAllTabs() {
        val actions = mutableListOf<Ft4Action>()
        compose.setContent { MainTheme {
            Ft4Workbench(state(Ft4TransmitState.Waiting("CQ BA7OPF OL87", 500)),
                Ft4TimingState(0, 0f, clock), SpectrumRenderState(), { actions += it }, {}, {}, {}, {}, {})
        } }
        listOf("SPECTRUM", "CONTACT", "LOG").forEach { tab ->
            compose.onNodeWithTag("digitalTab_$tab").performClick().performClick()
            compose.onNodeWithTag("digitalStopTransmit").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(List(3) { Ft4Action.EmergencyStop }, actions) }
    }

    @Test @Config(qualifiers = "en-w360dp-h640dp-port")
    fun lightThemeLargeFontRetainsTabAfterStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MainTheme(isDarkTheme = false) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Ft4Workbench(state(), Ft4TimingState(0, 0f, clock), SpectrumRenderState(), {}, {}, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("digitalTab_CONTACT").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("digitalTab_CONTACT").assertIsSelected()
        compose.onNodeWithText("BG5JSU").performScrollTo().assertIsDisplayed()
    }
}
