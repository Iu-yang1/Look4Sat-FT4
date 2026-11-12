package com.rtbishop.look4sat.feature.sunlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.sunlight.ISunlightPredictor
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import com.rtbishop.look4sat.core.domain.sunlight.SunlightPrediction
import com.rtbishop.look4sat.core.domain.sunlight.SunlightRequest
import com.rtbishop.look4sat.core.presentation.MainTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h640dp")
class SunlightScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: SunlightViewModel
    private val predictor = Predictor()
    private val store = ViewModelStore()

    @After fun tearDown() { compose.runOnIdle { store.clear() } }

    private class Predictor : ISunlightPredictor {
        val requests = mutableListOf<SunlightRequest>()
        var illumination = Illumination.SUNLIGHT
        override suspend fun satellites() = listOf(SatItem(24278, "FO-29"), SatItem(25544, "ISS"))
        override suspend fun predict(request: SunlightRequest, onProgress: (Double) -> Unit): SunlightPrediction {
            requests += request
            return SunlightPrediction(request,
                OrbitalData("FO-29", 26316.0, 13.53, 0.035, 98.5, 140.0, 72.0, 290.0, 24278, 0.0),
                listOf(IlluminationInterval(illumination, request.startUtcMillis, request.endUtcMillis,
                    startClipped = true, endClipped = true)), request.endUtcMillis)
        }
    }

    private fun show(restoration: StateRestorationTester? = null, dark: Boolean = false, fontScale: Float = 1f) {
        val content: @Composable () -> Unit = {
            model = remember {
                (store["sunlight"] as? SunlightViewModel) ?: SunlightViewModel(predictor,
                    MutableStateFlow(listOf(24278)), MutableStateFlow(true), SavedStateHandle())
                    .also { store.put("sunlight", it) }
            }
            val state by model.uiState.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MainTheme(isDarkTheme = dark) { SunlightScreen(state, model::onAction, {}, {}) }
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        compose.waitUntil { ::model.isInitialized && !model.uiState.value.loadingSatellites }
    }

    @Test fun draftNavigationAndDismissalKeepConfirmedConditions() {
        show()
        compose.onNodeWithTag("sunlight_conditions").performClick()
        compose.onNodeWithTag("sunlight_preset_90").performScrollTo().performClick()
        compose.onNodeWithTag("sunlight_satellite").performScrollTo().performClick()
        compose.onNodeWithText("ISS").performClick()
        compose.onNodeWithTag("sunlight_start_date").performScrollTo().performClick()
        compose.onNodeWithTag("sunlight_panel_back").performClick()
        compose.runOnIdle {
            assertEquals("90", model.uiState.value.page.draftDays)
            assertEquals(25544, model.uiState.value.page.draftCatalog)
            assertEquals("30", model.uiState.value.days)
            assertTrue(predictor.requests.isEmpty())
        }
        compose.onNodeWithTag("sunlight_panel_back").performClick()
        compose.onNodeWithTag("sunlight_conditions").performClick()
        compose.runOnIdle {
            assertEquals("30", model.uiState.value.page.draftDays)
            assertEquals(24278, model.uiState.value.page.draftCatalog)
        }
        compose.onNodeWithTag("sunlight_panel_accept").performClick()
        compose.waitUntil { predictor.requests.size == 1 && !model.uiState.value.calculating }
        compose.runOnIdle { assertEquals(SunlightPanelPage.NONE, model.uiState.value.page.panel) }
    }

    @Test fun statisticsDetailsAndFiltersDoNotStartAnotherCalculation() {
        show()
        compose.onNodeWithTag("sunlight_predict").performClick()
        compose.waitUntil { model.uiState.value.prediction != null }
        compose.onNodeWithTag("sunlight_statistics_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("sunlight_longest_details").performScrollTo().performClick()
        compose.onNodeWithTag("sunlight_panel_accept").assertDoesNotExist()
        compose.onNodeWithTag("sunlight_panel_back").performClick()
        compose.onNodeWithTag("sunlight_filter").performScrollTo().performClick()
        compose.onNodeWithText("All states").performScrollTo().performClick()
        compose.onNodeWithTag("sunlight_panel_accept").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, predictor.requests.size)
            assertEquals(null, model.uiState.value.filter)
        }
    }

    @Test fun retainedViewModelPreservesDraftAndPanelAcrossUiRestoration() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        compose.onNodeWithTag("sunlight_conditions").performClick()
        compose.onNodeWithTag("sunlight_preset_7").performScrollTo().performClick()
        compose.onNodeWithTag("sunlight_satellite").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("sunlight_satellite_search").assertIsDisplayed()
        compose.onNodeWithTag("sunlight_panel_back").performClick()
        compose.runOnIdle { assertEquals("7", model.uiState.value.page.draftDays) }
        compose.onNodeWithTag("sunlight_panel_accept").assertIsDisplayed()
    }

    @Test fun largeTextInDarkThemeKeepsPanelFooterReachable() {
        show(dark = true, fontScale = 2f)
        compose.onNodeWithTag("sunlight_conditions").performClick()
        compose.onNodeWithTag("sunlight_days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sunlight_panel_accept").assertIsDisplayed()
        compose.onNodeWithTag("sunlight_panel_back").assertIsDisplayed()
    }

    @Test fun fullUmbraRangeShowsAnExplicitSunlightEmptyState() {
        predictor.illumination = Illumination.UMBRA
        show()
        compose.onNodeWithTag("sunlight_predict").performClick()
        compose.waitUntil { model.uiState.value.prediction != null }
        compose.onNodeWithText("No full sunlight in the valid prediction range.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sunlight_longest_details").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(model.uiState.value.visibleIntervals.isEmpty())
            assertEquals(1, predictor.requests.size)
        }
    }
}
