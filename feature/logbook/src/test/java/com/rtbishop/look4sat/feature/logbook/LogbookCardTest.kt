package com.rtbishop.look4sat.feature.logbook

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogbookCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longPressEntersSelectionWithoutOpeningTheEditor() {
        var edits = 0
        var selections = 0
        compose.setContent {
            MaterialTheme {
                QsoCard(
                    record = record,
                    selectionMode = false,
                    selected = false,
                    enabled = true,
                    onClick = { edits++ },
                    onLongClick = { selections++ },
                    onDelete = {}
                )
            }
        }

        compose.onNodeWithText("K1ABC").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(0, edits)
            assertEquals(1, selections)
        }
    }

    @Test
    fun cardShowsStationGridsAndCountryWithoutFrequencyOrReports() {
        compose.setContent {
            MaterialTheme {
                QsoCard(record, false, false, true, {}, {}, {})
            }
        }

        compose.onNodeWithText("OL62/63", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Japan", substring = true).assertIsDisplayed()
        compose.onNodeWithText("599", substring = true).assertDoesNotExist()
        compose.onNodeWithText("MHz", substring = true).assertDoesNotExist()
    }

    @Test
    fun selectedCardClickTogglesSelection() {
        var selections = 0
        compose.setContent {
            MaterialTheme {
                QsoCard(record, true, true, true, { selections++ }, {}, {})
            }
        }

        compose.onNodeWithText("K1ABC").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    private val record = QsoRecord(
        id = 1L,
        startUtcMillis = 1_790_000_000_000,
        theirCallsign = "K1ABC",
        myCallsign = "BA7OPF",
        myGrid = "OL62,OL63",
        theirGrid = "PM95",
        country = "Japan",
        sentReport = "599",
        receivedReport = "599",
        satelliteName = "FO-29",
        mode = "CW",
        status = QsoStatus.COMPLETE
    )
}
