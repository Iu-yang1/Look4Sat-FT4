package com.rtbishop.look4sat.feature.sunlight

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import com.rtbishop.look4sat.core.domain.sunlight.SunlightPrediction
import com.rtbishop.look4sat.core.domain.sunlight.SunlightRequest
import com.rtbishop.look4sat.core.presentation.MainTheme
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

    @Test fun narrowScreenCanReachPredictAndFiltersWithoutStartingASecondCalculation() {
        val actions = mutableListOf<SunlightAction>()
        val request = SunlightRequest(24278, 1_799_366_400_000L, 30)
        val interval = IlluminationInterval(Illumination.SUNLIGHT, request.startUtcMillis, request.endUtcMillis,
            startClipped = true, endClipped = true)
        val result = SunlightPrediction(request,
            OrbitalData("FO-29", 26316.0, 13.53, 0.035, 98.5, 140.0, 72.0, 290.0, 24278, 0.0),
            listOf(interval), request.endUtcMillis)
        compose.setContent {
            MainTheme {
                SunlightScreen(SunlightState(satellites = listOf(SatItem(24278, "FO-29")),
                    loadingSatellites = false, catalogNumber = 24278, prediction = result,
                    visibleIntervals = listOf(interval)), actions::add, {}, {})
            }
        }
        compose.onNodeWithTag("sunlight_predict").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Filter and sort").performScrollTo().performClick()
        compose.onNodeWithText("All states").assertIsDisplayed().performClick()
        compose.onNodeWithText("Accept").performClick()
        assertTrue(actions.single() is SunlightAction.Filter)
        assertTrue(actions.none { it is SunlightAction.Predict })
    }
}
