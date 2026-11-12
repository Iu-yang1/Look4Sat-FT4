package com.rtbishop.look4sat.feature.sunlight

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun durationText(millis: Long, locale: Locale, compact: Boolean = true): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val measures = buildList {
        if (seconds >= 86_400) add(Measure(seconds / 86_400, MeasureUnit.DAY))
        if (seconds / 3600 % 24 > 0) add(Measure(seconds / 3600 % 24, MeasureUnit.HOUR))
        if (seconds / 60 % 60 > 0) add(Measure(seconds / 60 % 60, MeasureUnit.MINUTE))
        if (seconds % 60 > 0 || isEmpty()) add(Measure(seconds % 60, MeasureUnit.SECOND))
    }
    val displayed = if (compact) measures.take(2) else measures
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT)
        .formatMeasures(*displayed.toTypedArray())
}

internal fun minuteText(minutes: Long, locale: Locale): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT)
        .format(Measure(minutes, MeasureUnit.MINUTE))

internal fun utcDateText(millis: Long, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))

internal fun conditionDates(startMillis: Long, endMillis: Long, locale: Locale): Pair<String, String> {
    val utc = TimeZone.getTimeZone("UTC")
    val calendar = Calendar.getInstance(utc)
    calendar.timeInMillis = startMillis
    val startYear = calendar.get(Calendar.YEAR)
    calendar.timeInMillis = endMillis
    val end = if (calendar.get(Calendar.YEAR) == startYear) {
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
            .apply { timeZone = utc }.format(Date(endMillis))
    } else utcDateText(endMillis, locale)
    return utcDateText(startMillis, locale) to end
}

internal fun intervalTimes(interval: IlluminationInterval, locale: Locale, zone: TimeZone): Pair<String, String> {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        .apply { timeZone = zone }
    val showOffsets = zone.getOffset(interval.startMillis) != zone.getOffset(interval.endMillis)
    fun text(time: Long) = formatter.format(Date(time)) +
        if (showOffsets) " (${offsetText(time, zone)})" else ""
    return text(interval.startMillis) to text(interval.endMillis)
}

internal fun resultTimeFormatter(locale: Locale, zone: TimeZone): DateFormat =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.LONG, locale).apply { timeZone = zone }

internal fun preciseTimeText(millis: Long, locale: Locale, zone: TimeZone): String =
    resultTimeFormatter(locale, zone).format(Date(millis)) + " (${offsetText(millis, zone)})"

private fun offsetText(millis: Long, zone: TimeZone): String =
    SimpleDateFormat("XXX", Locale.ROOT).apply { timeZone = zone }.format(Date(millis))
