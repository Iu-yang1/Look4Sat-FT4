package com.rtbishop.look4sat.feature.logbook

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LogbookStateTest {
    @Test fun ownStationSelectionFiltersThePageWithoutChangingStoredRows() {
        val first=record(1,"FO-29","SAT").copy(myGrid="OL87")
        val second=first.copy(id=2,myGrid="PM01")
        val state=LogbookState(records=listOf(first,second),ownStationFilter=com.rtbishop.look4sat.core.domain.logbook.OwnStationFilter("BA7OPF","OL87"))
        assertEquals(listOf(first),state.filteredRecords)
        assertEquals(listOf(first,second),state.copy(ownStationFilter=null).filteredRecords)
        assertEquals(2,state.records.size)
    }
    @Test
    fun satelliteModeIsSelectedByDefault() {
        val satelliteFromLoTW = record(
            id = 1,
            satelliteName = "FO-29",
            propagationMode = "SAT"
        )
        val satelliteWithoutPropagation = record(
            id = 2,
            satelliteName = "SO-50",
            propagationMode = ""
        )
        val groundContact = record(id = 3)

        val state = LogbookState(records = listOf(satelliteFromLoTW, satelliteWithoutPropagation, groundContact))

        assertEquals(SATELLITE_MODE_FILTER, state.modeFilter)
        assertEquals(listOf(1L, 2L), state.filteredRecords.map(QsoRecord::id))
    }

    @Test
    fun allModesStillShowsSatelliteAndGroundContacts() {
        val records = listOf(
            record(id = 1, satelliteName = "FO-29", propagationMode = "SAT"),
            record(id = 2)
        )

        assertEquals(records, LogbookState(records = records, modeFilter = "").filteredRecords)
    }

    @Test
    fun satelliteFilterCombinesWithQueryAndConfirmation() {
        val confirmedSatellite = record(id = 1, satelliteName = "FO-29").copy(lotwConfirmed = true)
        val unconfirmedSatellite = record(id = 2, satelliteName = "FO-29")
        val confirmedGround = record(id = 3).copy(lotwConfirmed = true)
        val state = LogbookState(
            records = listOf(confirmedSatellite, unconfirmedSatellite, confirmedGround),
            query = "  k1abc  ",
            confirmationFilter = LogbookFilter.CONFIRMED
        )

        assertEquals(listOf(confirmedSatellite), state.filteredRecords)
    }

    @Test
    fun exactModeFilterCombinesWithQueryAndDraftStatus() {
        val draft = record(id = 1, satelliteName = "FO-29").copy(mode = "CW", status = QsoStatus.DRAFT)
        val complete = draft.copy(id = 2, status = QsoStatus.COMPLETE)
        val otherMode = draft.copy(id = 3, mode = "FM")
        val state = LogbookState(
            records = listOf(draft, complete, otherMode),
            query = "fo-29",
            modeFilter = "CW",
            confirmationFilter = LogbookFilter.DRAFTS
        )

        assertEquals(listOf(draft), state.filteredRecords)
    }

    private fun record(
        id: Long,
        satelliteName: String = "",
        propagationMode: String = ""
    ) = QsoRecord(
        id = id,
        startUtcMillis = id,
        theirCallsign = "K1ABC",
        myCallsign = "BA7OPF",
        satelliteName = satelliteName,
        propagationMode = propagationMode
    )
}
