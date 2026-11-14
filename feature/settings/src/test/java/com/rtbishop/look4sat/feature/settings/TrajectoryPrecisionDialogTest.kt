package com.rtbishop.look4sat.feature.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.rtbishop.look4sat.core.domain.model.TrajectoryPrecision
import com.rtbishop.look4sat.core.presentation.MainTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrajectoryPrecisionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sliderSelectsAllSixLevelsAndSavesOnlyOnConfirmation() {
        var saved: TrajectoryPrecision? = null
        compose.setContent {
            MainTheme {
                TrajectoryPrecisionDialog(TrajectoryPrecision.ULTRA, {}, { saved = it })
            }
        }
        compose.onNodeWithText("Accept").assertIsNotEnabled()
        TrajectoryPrecision.entries.forEach { level ->
            compose.onNodeWithContentDescription("Trajectory precision")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(level.ordinal.toFloat()) }
            compose.onNodeWithContentDescription("Trajectory precision").assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, level.label))
            compose.runOnIdle { assertNull(saved) }
        }
        compose.onNodeWithContentDescription("Trajectory precision")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle { assertEquals(TrajectoryPrecision.LIGHT, saved) }
    }

    @Test fun cancellingSliderChangesDoesNotSave() {
        var saved: TrajectoryPrecision? = null
        var dismissed = false
        compose.setContent {
            MainTheme {
                TrajectoryPrecisionDialog(
                    TrajectoryPrecision.ULTRA, { dismissed = true }, { saved = it }
                )
            }
        }
        compose.onNodeWithContentDescription("Trajectory precision")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(true, dismissed)
            assertNull(saved)
        }
    }
}
