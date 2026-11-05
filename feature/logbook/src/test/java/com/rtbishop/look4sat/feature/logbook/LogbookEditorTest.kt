package com.rtbishop.look4sat.feature.logbook

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class LogbookEditorTest {
    @Test
    fun newEditorUsesWavelogProfileWithoutLotwCertificate() {
        val editor = newLogbookEditor(
            0L, "DEFAULT", "FN31", "", "",
            WavelogUploadSettings(stationCallsign = "bh2vsq", stationGrid = "ol62ti,ol63")
        )
        assertEquals("BH2VSQ", editor.myCallsign)
        assertEquals("OL62TI", editor.myGrid)
    }

    @Test
    fun newEditorPrefersLotwIdentityAndLocation() {
        val editor = newLogbookEditor(
            0L, "DEFAULT", "FN31", "ba7opf", "ol87ex,ol88",
            WavelogUploadSettings(stationCallsign = "BH2VSQ", stationGrid = "OL62")
        )
        assertEquals("BA7OPF", editor.myCallsign)
        assertEquals("OL87,OL88", editor.myGrid)
    }

    @Test
    fun newEditorRetainsDefaultsWhenNoProfileIsConfigured() {
        val editor = newLogbookEditor(0L, "BA7OPF", "OL87EX", "", "", WavelogUploadSettings())
        assertEquals("BA7OPF", editor.myCallsign)
        assertEquals("OL87EX", editor.myGrid)
    }

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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidUtcDate() {
        LogbookEditor(startUtcMillis = 0L, theirCallsign = "K1ABC", utcText = "2026-02-30 12:00:00").toRecord()
    }
}
