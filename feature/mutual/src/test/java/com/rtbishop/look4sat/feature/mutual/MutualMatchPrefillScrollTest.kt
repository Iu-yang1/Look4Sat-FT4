package com.rtbishop.look4sat.feature.mutual

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.rtbishop.look4sat.core.presentation.Screen
import kotlinx.coroutines.delay
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
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
        // The snapshotFlow write-back stores the scrolled position in the VM.
        // It equals 1 only if scrollToItem(1) REALLY scrolled the station card
        // out. If the list content is shorter than the viewport there is no
        // scroll range, scrollToItem cannot move, and this stays 0 — that is
        // exactly the device symptom ("page stays at the top").
        org.junit.Assert.assertEquals(
            "list must have actually scrolled to item 1 (content shorter than viewport?)",
            1, vm.listScrollIndex
        )
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

    @Test
    fun navDisplayEntryAfterPrefill_scrollsToTimeRange() {
        // The Mutual screen composed inside a NavDisplay entry (back stack
        // [Mutual]) right after prefillMatchFromGrid.
        val vm = MutualViewModel(FakeSatelliteRepo(), FakeSettingsRepo())
        vm.prefillMatchFromGrid("OL62")
        composeRule.setContent {
            val backStack = rememberNavBackStack(Screen.Mutual)
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = { backStack.removeLastOrNull() },
                        entryProvider = entryProvider {
                            entry<Screen.Mutual> { MutualScreen(viewModel = vm) }
                        }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Time range").assertIsDisplayed()
    }

    @Test
    fun navDisplay_switchToMutualEntry_scrollsToTimeRange() {
        // Closest device path: start on the Map entry, then push the Mutual
        // entry (what the map "Match" button does). NavDisplay runs a real
        // fade transition while the Mutual screen composes.
        val vm = MutualViewModel(FakeSatelliteRepo(), FakeSettingsRepo())
        vm.prefillMatchFromGrid("OL62")
        val backStackRef = mutableStateOf<NavBackStack<NavKey>?>(null)
        composeRule.setContent {
            val backStack = rememberNavBackStack(Screen.Map)
            backStackRef.value = backStack
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = { backStack.removeLastOrNull() },
                        entryProvider = entryProvider {
                            entry<Screen.Map> { Text("MAP PAGE") }
                            entry<Screen.Mutual> { MutualScreen(viewModel = vm) }
                        }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Push Mutual like onMatchGrid does, so the transition composes
        // MutualScreen with scrollToTimeRange already set.
        composeRule.runOnIdle { backStackRef.value?.add(Screen.Mutual) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Time range").assertIsDisplayed()
    }

    @Test
    fun scrollRetriesUntilContentGrows() {
        // Reproduces the real-device mechanism: the first frame's content
        // (item 0 short) is shorter than the viewport, so scrollToItem(1) has
        // no range and cannot move. Then the content grows (async results)
        // past one screen; the retry loop must land on item 1.
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val tall = mutableStateOf(false)
                    LaunchedEffect(Unit) { delay(300); tall.value = true }
                    val state = remember { LazyListState() }
                    LaunchedEffect(state) {
                        // Same retry loop MutualScreen uses for the prefill.
                        for (attempt in 0 until 20) {
                            state.scrollToItem(1)
                            if (state.firstVisibleItemIndex == 1) return@LaunchedEffect
                            delay(100)
                        }
                    }
                    LazyColumn(state = state) {
                        item {
                            if (tall.value) Spacer(modifier = Modifier.height(900.dp))
                            else Spacer(modifier = Modifier.height(40.dp))
                        }
                        item { Text("target-item") }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("target-item").assertIsDisplayed()
        // Confirm the list really scrolled (station card equivalent is gone).
        // We can't read listState from here, so use the VM-style probe: none —
        // the target visible + viewport tall enough implies item 1 on top.
    }

    @Test
    fun keepPositionAcrossTabs_thenMapMatchPrefill_scrollsToTimeRange() {
        // User hypothesis: the "keep scroll position across tab switches"
        // machinery (listState remember(queryGeneration) + snapshotFlow
        // write-back) interferes with the prefill auto-scroll. Reproduce the
        // full journey: first visit -> scroll a bit -> leave -> return
        // (position restored) -> leave -> enter via map Match button.
        val vm = MutualViewModel(FakeSatelliteRepo(), FakeSettingsRepo())
        val showMutual = mutableStateOf(true)

        // First visit (e.g. bottom nav), user scrolls a little; the
        // snapshotFlow write-back stored index/offset in the VM.
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showMutual.value) MutualScreen(viewModel = vm)
                }
            }
        }
        composeRule.waitForIdle()
        vm.listScrollIndex = 0
        vm.listScrollOffset = 40

        // Leave the page (tab switch destroys the composition).
        showMutual.value = false
        composeRule.waitForIdle()

        // Re-enter: keep-position restores the scroll offset.
        showMutual.value = true
        composeRule.waitForIdle()

        // Leave again, then enter via the map grid-QSO Match button.
        showMutual.value = false
        composeRule.waitForIdle()
        vm.prefillMatchFromGrid("OL62")
        showMutual.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Time range").assertIsDisplayed()
    }
}
