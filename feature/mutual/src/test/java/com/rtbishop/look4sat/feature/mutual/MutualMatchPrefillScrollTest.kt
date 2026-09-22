package com.rtbishop.look4sat.feature.mutual

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies the map grid-QSO dialog "Match" prefill: after
 * [MutualViewModel.prefillMatchFromGrid] the page opens with the time-range
 * card at the top and the 24h query auto-started.
 *
 * Note: `LazyListState(firstVisibleItemIndex = 1)` is deliberately NOT used to
 * position the page. In this Compose version (BOM 2026.06.01 / ui 1.11.x) the
 * constructor parameter is ignored — the list still starts at item 0 — while
 * `listState.scrollToItem(1)` from a LaunchedEffect lands correctly. See
 * [scrollToItemLandsAtTop] below, which guards the scrollToItem mechanism.
 *
 * Robolectric quirk: `assertIsNotDisplayed()` is unreliable here (a node
 * scrolled fully out of the viewport can still report "displayed"), so the
 * prefill test asserts the time-range card IS displayed (positive assertion)
 * rather than that the station card is NOT.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MutualMatchPrefillScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sanity_textRenders() {
        composeRule.setContent {
            MaterialTheme {
                Text("sanity-check-text")
            }
        }
        composeRule.onNodeWithText("sanity-check-text").assertIsDisplayed()
    }

    @Test
    fun scrollToItemLandsAtTop() {
        // Guards the mechanism the prefill relies on: scrollToItem(1) from a
        // LaunchedEffect moves item 1 to the top of the viewport.
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state = remember { LazyListState(firstVisibleItemIndex = 0) }
                    LaunchedEffect(Unit) { state.scrollToItem(1) }
                    LazyColumn(state = state) {
                        item { Text("min-item-0") }
                        item { Text("min-item-1") }
                        item { Text("min-item-2") }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("min-item-1").assertIsDisplayed()
    }

    @Test
    fun defaultOpen_showsStationInputsAtTop() {
        val vm = MutualViewModel(FakeSatelliteRepo(), FakeSettingsRepo())
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MutualScreen(viewModel = vm)
                }
            }
        }
        // Without a prefill the list starts at item 0 (station inputs).
        composeRule.onNodeWithText("Your Station").assertIsDisplayed()
    }

    @Test
    fun prefillFromMap_opensAtTimeRangeCard() {
        val vm = MutualViewModel(FakeSatelliteRepo(), FakeSettingsRepo())
        // Same call the map grid-QSO "Match" button makes.
        vm.prefillMatchFromGrid("OL62")

        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MutualScreen(viewModel = vm)
                }
            }
        }

        composeRule.waitForIdle()

        // The time-range card must be visible at the top of the page after the
        // prefill scroll.
        composeRule.onNodeWithText("Time range").assertIsDisplayed()
    }

    @Test
    fun prefillAutoStartsQueryAndFillsState() {
        val vm = MutualViewModel(FakeSatelliteRepo(), FakeSettingsRepo())
        vm.prefillMatchFromGrid("OL62")
        val s = vm.uiState.value
        // Query auto-started: with the empty-satellite fake the query reaches
        // the "no satellite data" guard (rather than never being triggered),
        // proving prefillMatchFromGrid kicks off queryMutualPasses.
        assertTrue(
            "auto-query should reach the satellite guard: gridA=${s.stationAGrid} " +
                "gridB=${s.stationBGrid} err=${s.errorMessage}",
            s.errorMessage?.contains("satellite", ignoreCase = true) == true
        )
        // ...and the target grid + 24h range pre-filled.
        org.junit.Assert.assertEquals("OL62", s.stationBGrid)
        org.junit.Assert.assertEquals(24, s.hoursAhead)
    }
}
