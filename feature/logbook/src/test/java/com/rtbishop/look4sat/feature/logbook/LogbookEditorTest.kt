package com.rtbishop.look4sat.feature.logbook

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class LogbookEditorTest {
    @Test
    fun unchangedUtcTextKeepsOriginalFt4Milliseconds() {
        val original = QsoRecord(startUtcMillis = 1_725_189_313_500L, theirCallsign = "K1ABC", myCallsign = "BA7OPF")
        val saved = LogbookEditor(
            startUtcMillis = original.startUtcMillis, theirCallsign = original.theirCallsign, source = original
        ).toRecord()
        assertEquals(original.startUtcMillis, saved.startUtcMillis)
    }

    @Test
    fun removingSatelliteDoesNotRetainSatellitePropagation() {
        val original = QsoRecord(startUtcMillis = 1_725_189_313_500L, theirCallsign = "K1ABC", myCallsign = "BA7OPF", satelliteName = "SO-50")
        val saved = LogbookEditor(
            startUtcMillis = original.startUtcMillis, theirCallsign = original.theirCallsign, source = original
        ).toRecord()
        assertEquals("", saved.propagationMode)
        assertEquals("", saved.satelliteName)
        assertEquals("SSB", saved.mode)
        assertEquals("", saved.submode)
    }

    @Test(expected = java.text.ParseException::class)
    fun rejectsInvalidUtcDate() {
        LogbookEditor(startUtcMillis = 0L, theirCallsign = "K1ABC", utcText = "2026-02-30 12:00:00").toRecord()
    }
}
