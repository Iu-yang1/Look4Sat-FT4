package com.rtbishop.look4sat.core.data.repository

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
