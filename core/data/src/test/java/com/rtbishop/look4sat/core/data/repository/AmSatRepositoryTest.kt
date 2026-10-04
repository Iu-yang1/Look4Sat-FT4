/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.SatSlot
import com.rtbishop.look4sat.core.domain.model.SatStatus
import com.rtbishop.look4sat.core.domain.model.SatStatusPage
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.source.NetworkResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AmSatRepositoryTest {

    @Test
    fun fetchStatusReturnsSeededCacheUntilCacheIsCleared() = runTest {
        val remoteSource = FakeAmSatRemoteSource()
        val repository = AmSatRepository(remoteSource)
        val cachedPage = SatStatusPage(
            fetchedAtUtcMs = 123L,
            statuses = listOf(SatStatus(name = "AO-7", days = emptyList())),
            reports = emptyMap()
        )
        repository.seedStatusCache(cachedPage)

        val firstPage = repository.fetchStatus()
        val secondPage = repository.fetchStatus()

        assertSame(cachedPage, firstPage)
        assertSame(cachedPage, secondPage)
        assertSame(cachedPage, repository.getCachedStatus())
        assertEquals(0, remoteSource.catalogRequests)
        assertEquals(0, remoteSource.reportRequests)

        repository.clearStatusCache()
        assertNull(repository.getCachedStatus())
        repository.fetchStatus()

        assertEquals(1, remoteSource.catalogRequests)
        assertEquals(1, remoteSource.reportRequests)
    }

    @Test
    fun forceRefreshBypassesCachedPage() = runTest {
        val remoteSource = FakeAmSatRemoteSource()
        val repository = AmSatRepository(remoteSource)
        repository.seedStatusCache(
            SatStatusPage(
                fetchedAtUtcMs = 123L,
                statuses = listOf(SatStatus(name = "AO-7", days = emptyList())),
                reports = emptyMap()
            )
        )

        repository.fetchStatus(forceRefresh = true)

        assertEquals(1, remoteSource.catalogRequests)
        assertEquals(1, remoteSource.reportRequests)
    }

    @Test
    fun fetchStatusSharesStartupPrefetchRequest() = runTest {
        val remoteSource = FakeAmSatRemoteSource(responseDelayMillis = 50)
        val repository = AmSatRepository(remoteSource)

        val prefetch = async { repository.prefetchStatus() }
        val pageFetch = async { repository.fetchStatus() }
        prefetch.await()
        val page = pageFetch.await()

        assertSame(page, repository.getCachedStatus())
        assertEquals(1, remoteSource.catalogRequests)
        assertEquals(1, remoteSource.reportRequests)
    }

    @Test
    fun buildStatusesStreakCountsConsecutiveSameStatusReportsPerDay() = runTest {
        val nowSec = System.currentTimeMillis() / 1000
        val remoteSource = FakeAmSatRemoteSource(
            reportsJson = amSatReportsJson(
                // 当天(day 0)最新槽(slot 0)混合状态:最新 Heard、次新 Heard、再旧 Not Heard → 连续=2
                report("r1", "AO-7", "Heard", nowSec - 3600),
                report("r2", "AO-7", "Heard", nowSec - 5400),
                report("r3", "AO-7", "Not Heard", nowSec - 7000),
                // 空时段(无报告)不打断:slot 8 的 Heard 更旧,不应计入(被 r3 打断)
                report("r4", "AO-7", "Heard", nowSec - 63000),
                // 前一天(day 1):最新 Heard,更旧的 Not Heard → 连续=1,且不跨天合并
                report("r5", "AO-7", "Heard", nowSec - 90000),
                report("r6", "AO-7", "Not Heard", nowSec - 100000)
                // day 2 无报告 → 0
            )
        )
        val repository = AmSatRepository(remoteSource)

        val page = repository.fetchStatus()
        val status = page?.statuses?.single()
        assertEquals("AO-7", status?.name)
        assertEquals(3, status?.days?.size)

        assertEquals(2, status?.days?.get(0)?.streakCount)
        assertEquals(1, status?.days?.get(1)?.streakCount)
        assertEquals(0, status?.days?.get(2)?.streakCount)
    }

    @Test
    fun buildStatusesStreakCountsAllReportsWhenStatusNeverChanges() = runTest {
        val nowSec = System.currentTimeMillis() / 1000
        val remoteSource = FakeAmSatRemoteSource(
            reportsJson = amSatReportsJson(
                report("r1", "AO-7", "Heard", nowSec - 1800),
                report("r2", "AO-7", "Heard", nowSec - 3600),
                report("r3", "AO-7", "Heard", nowSec - 5400),
                report("r4", "AO-7", "Heard", nowSec - 30000)
            )
        )
        val repository = AmSatRepository(remoteSource)

        val page = repository.fetchStatus()
        assertEquals(4, page?.statuses?.single()?.days?.get(0)?.streakCount)
    }

    @Test
    fun buildStatusesStrictMajorityTakesItsColorAndCount() = runTest {
        val nowSec = System.currentTimeMillis() / 1000

        val heardMost = firstSlotStatus(
            report("h1", "AO-7", "Heard", nowSec - 3600),
            report("h2", "AO-7", "Heard", nowSec - 4500),
            report("t1", "AO-7", "Telemetry Only", nowSec - 5400)
        )
        assertEquals(0xFF648FFFL, heardMost?.statusColor) // blue
        assertEquals(2, heardMost?.count) // majority count, not the block total

        val telemetryMost = firstSlotStatus(
            report("t1", "AO-7", "Telemetry Only", nowSec - 3600),
            report("t2", "AO-7", "Telemetry Only", nowSec - 4500),
            report("h1", "AO-7", "Heard", nowSec - 5400)
        )
        assertEquals(0xFFFFB000L, telemetryMost?.statusColor) // amber
        assertEquals(2, telemetryMost?.count)

        val notHeardMost = firstSlotStatus(
            report("n1", "AO-7", "Not Heard", nowSec - 3600),
            report("n2", "AO-7", "Not Heard", nowSec - 4500),
            report("n3", "AO-7", "Not Heard", nowSec - 5400),
            report("t1", "AO-7", "Telemetry Only", nowSec - 6000)
        )
        assertEquals(0xFFDC267FL, notHeardMost?.statusColor) // pink
        assertEquals(3, notHeardMost?.count)

        val single = firstSlotStatus(report("h1", "AO-7", "Heard", nowSec - 3600))
        assertEquals(0xFF648FFFL, single?.statusColor)
        assertEquals(1, single?.count)
    }

    @Test
    fun buildStatusesShowsConflictWhenNoStrictMajority() = runTest {
        val nowSec = System.currentTimeMillis() / 1000

        val oneToOne = firstSlotStatus(
            report("h1", "AO-7", "Heard", nowSec - 3600),
            report("n1", "AO-7", "Not Heard", nowSec - 4500)
        )
        assertEquals(0xFFFE6100L, oneToOne?.statusColor) // conflicting
        assertEquals(2, oneToOne?.count)

        val twoToTwo = firstSlotStatus(
            report("h1", "AO-7", "Heard", nowSec - 3600),
            report("h2", "AO-7", "Heard", nowSec - 4500),
            report("t1", "AO-7", "Telemetry Only", nowSec - 5400),
            report("t2", "AO-7", "Telemetry Only", nowSec - 6000)
        )
        assertEquals(0xFFFE6100L, twoToTwo?.statusColor)

        val threeWay = firstSlotStatus(
            report("h1", "AO-7", "Heard", nowSec - 3600),
            report("n1", "AO-7", "Not Heard", nowSec - 4500),
            report("t1", "AO-7", "Telemetry Only", nowSec - 5400)
        )
        assertEquals(0xFFFE6100L, threeWay?.statusColor)
    }

    @Test
    fun buildStatusesCrewActiveWinsWithCrewPlusHeardCount() = runTest {
        val nowSec = System.currentTimeMillis() / 1000

        val crewWithHeard = firstSlotStatus(
            report("c1", "AO-7", "Crew Active", nowSec - 3600),
            report("h1", "AO-7", "Heard", nowSec - 4500),
            report("h2", "AO-7", "Heard", nowSec - 5400),
            report("h3", "AO-7", "Heard", nowSec - 6000)
        )
        assertEquals(0xFF785EF0L, crewWithHeard?.statusColor) // purple
        assertEquals(4, crewWithHeard?.count) // crew + heard

        val crewAlone = firstSlotStatus(
            report("c1", "AO-7", "Crew Active", nowSec - 3600),
            report("n1", "AO-7", "Not Heard", nowSec - 4500),
            report("n2", "AO-7", "Not Heard", nowSec - 5400)
        )
        assertEquals(0xFF785EF0L, crewAlone?.statusColor)
        assertEquals(1, crewAlone?.count) // crew + heard (0)
    }

    @Test
    fun buildStatusesMarksOnlyNoStrictMajoritySlotsAsConflicted() = runTest {
        val nowSec = System.currentTimeMillis() / 1000

        val majority = firstSlotStatus(
            report("h1", "AO-7", "Heard", nowSec - 3600),
            report("h2", "AO-7", "Heard", nowSec - 4500),
            report("t1", "AO-7", "Telemetry Only", nowSec - 5400)
        )
        assertEquals(false, majority?.isConflicted)

        val tie = firstSlotStatus(
            report("h1", "AO-7", "Heard", nowSec - 3600),
            report("t1", "AO-7", "Telemetry Only", nowSec - 4500)
        )
        assertEquals(true, tie?.isConflicted)

        val crew = firstSlotStatus(
            report("c1", "AO-7", "Crew Active", nowSec - 3600),
            report("n1", "AO-7", "Not Heard", nowSec - 4500)
        )
        assertEquals(false, crew?.isConflicted)

        val empty = firstSlotStatus()
        assertEquals(false, empty?.isConflicted)
    }
}

private suspend fun firstSlotStatus(vararg reports: String): SatSlot? {
    val page = AmSatRepository(
        FakeAmSatRemoteSource(reportsJson = amSatReportsJson(*reports))
    ).fetchStatus()
    return page?.statuses?.single()?.days?.get(0)?.slots?.get(0)
}

private fun AmSatRepository.seedStatusCache(page: SatStatusPage) {
    val cacheField = AmSatRepository::class.java.getDeclaredField("statusCache")
    cacheField.isAccessible = true
    cacheField.set(this, page)
}

private class FakeAmSatRemoteSource(
    private val responseDelayMillis: Long = 0L,
    private val reportsJson: String = """{"data":[]}"""
) : IRemoteSource {
    var catalogRequests = 0
    var reportRequests = 0

    override suspend fun getFileStream(uri: String): InputStream? = null

    override suspend fun getNetworkStream(url: String): NetworkResult = NetworkResult(404, null)

    override suspend fun getAmSatCatalog(): String? {
        if (responseDelayMillis > 0L) delay(responseDelayMillis)
        catalogRequests += 1
        return """{"data":[{"name":"AO-7"}]}"""
    }

    override suspend fun getAmSatReports(hours: Int, limit: Int): String? {
        if (responseDelayMillis > 0L) delay(responseDelayMillis)
        reportRequests += 1
        return reportsJson
    }

    override suspend fun submitAmSatReport(payloadJson: String): Pair<Int, String>? = null
}

private fun amSatReportsJson(vararg reports: String): String =
    """{"data":[${reports.joinToString(",")}]}"""

private fun report(id: String, name: String, status: String, epochSec: Long): String =
    """{"id":"$id","name":"$name","callsign":"BA7OPF","report":"$status","grid_square":"OL62","reported_time":"${isoUtc(epochSec)}"}"""

private fun isoUtc(epochSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochSec * 1000))
