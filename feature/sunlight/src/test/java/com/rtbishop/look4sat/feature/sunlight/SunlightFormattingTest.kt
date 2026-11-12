package com.rtbishop.look4sat.feature.sunlight

import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SunlightFormattingTest {
    @Test fun compactDurationShowsTwoLargestNonzeroUnitsAndDetailRetainsSeconds() {
        val duration = (2 * 86400L + 3 * 3600 + 4 * 60 + 5) * 1000
        val compact = durationText(duration, Locale.US)
        val exact = durationText(duration, Locale.US, compact = false)
        assertTrue(compact.contains("2") && compact.contains("3"))
        assertFalse(compact.contains("4") || compact.contains("5"))
        assertTrue(exact.contains("4") && exact.contains("5"))
        assertEquals(durationText(0, Locale.US), durationText(-1000, Locale.US))
        assertTrue(minuteText(Long.MAX_VALUE, Locale.US).isNotBlank())
    }

    @Test fun repeatedDstHourHasDistinctEndpointOffsetsAndPreciseSeconds() {
        val start = utc("2026-11-01T05:30:01")
        val end = utc("2026-11-01T06:30:02")
        val zone = TimeZone.getTimeZone("America/New_York")
        val times = intervalTimes(IlluminationInterval(Illumination.SUNLIGHT, start, end), Locale.US, zone)
        assertTrue(times.first.contains("-04:00"))
        assertTrue(times.second.contains("-05:00"))
        assertTrue(preciseTimeText(start, Locale.US, zone).contains("-04:00"))
        assertTrue(preciseTimeText(end, Locale.US, zone).contains("-05:00"))
    }

    @Test fun datesRetainBothYearsAndAllSupportedLocalesProduceDurations() {
        val interval = IlluminationInterval(Illumination.SUNLIGHT,
            utc("2026-12-31T23:59:01"), utc("2027-01-01T00:01:02"))
        val times = intervalTimes(interval, Locale.US, TimeZone.getTimeZone("UTC"))
        assertTrue(times.first.contains("2026"))
        assertTrue(times.second.contains("2027"))
        listOf("en", "es", "ru", "si", "tr", "uk", "zh-CN", "zh-Hant").forEach { tag ->
            assertTrue(durationText(interval.durationMillis, Locale.forLanguageTag(tag)).isNotBlank())
        }
    }

    @Test fun conditionRangeUsesThePredictionEndAndOmitsOnlyRepeatedYears() {
        val start = utc("2026-11-12T00:00:00")
        val dates = conditionDates(start, start + 30 * 86_400_000L, Locale.SIMPLIFIED_CHINESE)
        assertEquals("2026年11月12日", dates.first)
        assertEquals("12月12日", dates.second)
        val crossYear = conditionDates(utc("2026-12-31T00:00:00"), utc("2027-01-01T00:00:00"),
            Locale.SIMPLIFIED_CHINESE)
        assertEquals("2027年1月1日", crossYear.second)
    }

    @Test fun leapDayAndSingleDayRangesUseUtcDates() {
        val dates = conditionDates(utc("2028-02-28T00:00:00"), utc("2028-02-29T00:00:00"),
            Locale.SIMPLIFIED_CHINESE)
        assertEquals("2月29日", dates.second)
        listOf("en", "es", "ru", "si", "tr", "uk", "zh-CN", "zh-Hant").forEach { tag ->
            val localized = conditionDates(utc("2026-12-31T00:00:00"), utc("2027-01-01T00:00:00"),
                Locale.forLanguageTag(tag))
            assertTrue(localized.first.contains("2026"))
            assertTrue(localized.second.contains("2027"))
        }
    }

    private fun utc(text: String): Long = requireNotNull(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(text)
    ).time
}
