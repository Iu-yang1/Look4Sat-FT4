package com.rtbishop.look4sat.feature.mutual

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationChartTimeTest {
    @Test
    fun `touching the start and end of the plotted curve maps to the right time`() {
        assertEquals(0f, chartProgressAtX(40f, 300f), 0.0001f)
        assertEquals(0.5f, chartProgressAtX(165f, 300f), 0.0001f)
        assertEquals(1f, chartProgressAtX(290f, 300f), 0.0001f)
    }

    @Test
    fun `touches outside plot margins clamp to pass interval`() {
        assertEquals(0f, chartProgressAtX(0f, 300f), 0.0001f)
        assertEquals(1f, chartProgressAtX(300f, 300f), 0.0001f)
        assertEquals(0f, chartProgressAtX(20f, 30f), 0.0001f)
    }
}
