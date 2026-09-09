package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.model.AwardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedGridStoreTest {
    private fun contact() = QsoRecord(
        startUtcMillis = 1_750_000_000_000L, theirCallsign = "JA1ABC", myCallsign = "BA7OPF",
        mode = "CW", submode = "", satelliteName = "RS-44", propagationMode = "SAT",
        status = QsoStatus.COMPLETE, lotwConfirmed = true, dxcc = 339, cqZone = 25, region = "34"
    )

    @Test fun multipleGridsCountOnceForCountryAndRegionAwards() {
        val record = contact().copy(theirGrid = "PM74AB", vuccGrids = listOf("PM74", "PM75", "ZZ99"))
        val store = confirmedGridStore(listOf(record, contact().copy(lotwConfirmed = false, theirGrid = "OL62")))
        assertEquals(setOf("PM74", "PM75"), store.qsosByGrid.keys)
        val progress = store.awards.associateBy { it.type }
        assertEquals(2, progress.getValue(AwardType.VUCC).count)
        assertEquals(1, progress.getValue(AwardType.DXCC).count)
        assertEquals(1, progress.getValue(AwardType.WAJA).count)
        assertEquals(1, progress.getValue(AwardType.WAZ).count)
    }

    @Test fun confirmedContactWithoutGridStillCountsForAwards() {
        val store = confirmedGridStore(listOf(contact()))
        assertTrue(store.qsosByGrid.isEmpty())
        assertEquals(1, store.awards.first { it.type == AwardType.DXCC }.count)
    }

    @Test fun terrestrialAndUnfinishedContactsDoNotCount() {
        val contacts = listOf(contact().copy(propagationMode = "TR", satelliteName = ""), contact().copy(status = QsoStatus.DRAFT))
        assertTrue(confirmedGridStore(contacts).awards.all { it.count == 0 })
    }

    @Test fun adifRoundTripPreservesConfirmationAndNonFt4Mode() {
        val record = contact().copy(
            theirGrid = "PM74AB", vuccGrids = listOf("PM74", "PM75"),
            band = "2M", rxBand = "70CM", lotwQslDate = "20260909", country = "Japan", comment = "Portable contact"
        )
        val decoded = AdifCodec.decode(AdifCodec.encode(listOf(record))).single()
        assertEquals(record, decoded)
        assertFalse(AdifCodec.encode(listOf(record)).contains("FT4"))
    }

    @Test fun confirmationMatchingDoesNotUseMissingFrequencyOrOverwriteLocalMessages() {
        val local = contact().copy(lotwConfirmed = false, txFrequencyHz = 145_990_000, rawMessages = listOf("CQ BA7OPF OL62"))
        val remote = contact().copy(startUtcMillis = local.startUtcMillis + 30_000, theirGrid = "PM74")
        assertTrue(sameConfirmedContact(local, remote))
        val merged = local.withConfirmation(remote)
        assertEquals(local.rawMessages, merged.rawMessages)
        assertEquals(local.txFrequencyHz, merged.txFrequencyHz)
        assertEquals(local.startUtcMillis, merged.startUtcMillis)
        assertTrue(merged.lotwConfirmed)
        assertFalse(sameConfirmedContact(local, remote.copy(mode = "FM")))
        assertFalse(sameConfirmedContact(local, remote.copy(myCallsign = "K1XYZ")))
    }
}
