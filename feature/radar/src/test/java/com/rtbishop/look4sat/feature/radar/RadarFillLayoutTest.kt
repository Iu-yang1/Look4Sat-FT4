/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * Small-window radar sizing.
 *
 * The plotted circle is `0.95 x min(cardWidth, cardHeight)`, so a radar card that is wider than
 * it is tall caps the circle by its height and shows empty bands on the sides. Full screen the
 * classic 1:1 split leaves the radar card ~0.88 x cardWidth tall, i.e. the circle spans ~84% of
 * the page width. These tests pin the split-screen / small-window layout to the same width share
 * instead of letting the circle shrink with the window height.
 */
class RadarFillLayoutTest {

    /** Circle diameter as a share of the page width (radar canvas rule: 0.95 * min(width, height)). */
    private fun circleShare(width: Dp, height: Dp, sizes: RadarFillSizes): Float {
        val availableHeight = if (sizes.pagerOverlaid) height else sizes.radarSide
        return 0.95f * min(width.value, availableHeight.value) / width.value
    }

    @Test
    fun fullScreenVerticalKeepsClassicSplit() {
        // 411 x 867dp full screen: the 1:1 split already gives the circle ~87% of the width,
        // so the full-screen layout must not change.
        assertFalse(useFillRadarLayout(isVertical = true, maxWidth = 411.dp, maxHeight = 867.dp))
        assertFalse(useFillRadarLayout(isVertical = true, maxWidth = 393.dp, maxHeight = 804.dp))
    }

    @Test
    fun splitScreenAndSmallWindowsUseFillLayout() {
        assertTrue(useFillRadarLayout(true, 411.dp, 426.dp)) // portrait split-screen half
        assertTrue(useFillRadarLayout(true, 411.dp, 500.dp)) // floating small window
        assertTrue(useFillRadarLayout(true, 411.dp, 676.dp)) // 70/30 split
        assertTrue(useFillRadarLayout(true, 393.dp, 560.dp))
    }

    @Test
    fun wideWindowsKeepSideBySideLayout() {
        assertFalse(useFillRadarLayout(isVertical = false, maxWidth = 700.dp, maxHeight = 400.dp))
    }

    @Test
    fun fillLayoutKeepsCircleAtFullScreenWidthShare() {
        // Previously these rendered the circle at ~36% of the page width.
        val cases = listOf(
            411.dp to 426.dp,
            411.dp to 500.dp,
            393.dp to 560.dp,
            411.dp to 676.dp
        )
        cases.forEach { (width, height) ->
            val share = circleShare(width, height, radarFillSizes(width, height))
            assertTrue("circle is only ${share * 100}% of the page width at ${width.value}x${height.value}",
                share >= 0.84f)
        }
    }

    @Test
    fun classicSplitWouldHaveSqueezedTheCircle() {
        // Regression guard documenting why the fill layout exists: the old compact branch left
        // a 411 x 156dp card, i.e. a circle at 36% of the page width, in the same split window.
        val classicCompactCardHeight = 426.dp - 96.dp - 132.dp - 18.dp
        val classicCompactShare = 0.95f * classicCompactCardHeight.value / 411f
        assertTrue(classicCompactShare < 0.45f)
    }

    @Test
    fun pagerIsOverlaidOnlyWhenAReservedBlockWouldBeUseless() {
        val shortWindow = radarFillSizes(411.dp, 426.dp)
        assertTrue(shortWindow.pagerOverlaid)
        assertTrue(shortWindow.radarSide == 411.dp)

        val tallWindow = radarFillSizes(411.dp, 676.dp)
        assertFalse(tallWindow.pagerOverlaid)
        assertEquals(411f, tallWindow.radarSide.value, 0.01f)
        assertEquals(259f, tallWindow.pagerSpace.value, 0.01f)
    }

    @Test
    fun pagerBlockIsKeptWhileTheCircleStaysAtTheFullScreenShare() {
        // 411 x 468dp: reserving 96dp for the pager still leaves the circle at ~85% of the page
        // width, so the pager stays as a real block instead of folding into the overlay strip.
        val sizes = radarFillSizes(411.dp, 468.dp)
        assertFalse(sizes.pagerOverlaid)
        assertEquals(366f, sizes.radarSide.value, 0.01f)
        assertEquals(96f, sizes.pagerSpace.value, 0.01f)
        val share = circleShare(411.dp, 468.dp, sizes)
        assertTrue("circle is only ${share * 100}% of the page width", share >= 0.84f)
    }
}
