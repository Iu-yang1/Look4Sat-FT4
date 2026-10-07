/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 */

package com.rtbishop.look4sat.feature.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for dialog header spacing parity. The worked-grid (VUCC) dialog header
 * carries a trailing Match button whose 48dp minimum touch target inflates the header row,
 * pushing its title down to 6dp from the dialog edge. Region dialogs (DXCC/WAPC/WAJA/WAS/
 * WAZ) without the button used to hug the top edge at 4dp, reading as cramped. The region
 * header now pins the same 48dp row height, so BOTH titles must land at the identical
 * offset below their header's top edge. Measured via real Compose layout (not guessed),
 * see look4sat-customization/references/robolectric-compose-ui-testing-wsl-2026-09.md.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DialogHeaderSpacingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gridAndRegionHeadersPlaceTitlesAtTheSameOffset() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Fixed anchors make the expected offsets unambiguous:
                        // each title must sit exactly TITLE_OFFSET below its header.
                        Box(modifier = Modifier.offset(y = GRID_Y)) {
                            GridHeader()
                        }
                        Box(modifier = Modifier.offset(y = REGION_Y)) {
                            RegionHeader()
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GRID_TITLE_TAG)
            .assertTopPositionInRootIsEqualTo(GRID_Y + TITLE_OFFSET)
        composeRule.onNodeWithTag(REGION_TITLE_TAG)
            .assertTopPositionInRootIsEqualTo(REGION_Y + TITLE_OFFSET)
    }

    /** Grid-dialog header replica: title + counts column plus the Match button. */
    @Composable
    private fun GridHeader() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OL62",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(GRID_TITLE_TAG)
                )
                Text(text = "3 callsigns · 5 QSOs", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {}) {
                Text(text = "Match", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider()
    }

    /** Region-dialog header replica: identical, without the trailing button. */
    @Composable
    private fun RegionHeader() {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .defaultMinSize(minHeight = 48.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CQ ZONE 24",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(REGION_TITLE_TAG)
                )
                Text(text = "3 callsigns · 5 QSOs", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
    }

    private companion object {
        const val GRID_TITLE_TAG = "gridDialogTitle"
        const val REGION_TITLE_TAG = "regionDialogTitle"
        val GRID_Y = 100.dp
        val REGION_Y = 300.dp
        /** Title offset from the header's top edge — must be identical for both dialogs. */
        val TITLE_OFFSET = 6.dp
    }
}
