package com.rtbishop.look4sat.core.data.lotw

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoTWDeviceTest {
    @Test fun androidProviderImportsEncryptsReloadsAndSignsWithoutNetwork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val isolated = object : ContextWrapper(target) {
            private val testDirectory = File(target.cacheDir, "lotw-test-${UUID.randomUUID()}")
            override fun getNoBackupFilesDir(): File = testDirectory.also { it.mkdirs() }
        }
        val storage = AndroidLoTWStorage(isolated)
        val client = OkHttpClient.Builder().addInterceptor { error("Offline test must not make network requests") }.build()
        fun repository() = LoTWUploadRepository(storage, { LoTWConfig(target.assets.open("lotw/config.tq6")) }, client) { 1_790_000_000_000 }
        try {
            val fixture = instrumentation.context.assets.open("lotw/offline-test.p12").use { it.readBytes() }
            val info = repository().importCertificate(fixture, "test-only".toCharArray())
            assertEquals("N0TEST", info.callsign)
            assertEquals(318, info.dxcc)
            assertTrue(fixture.all { it == 0.toByte() })
            assertEquals(info, repository().certificate())
            val encrypted = File(isolated.noBackupFilesDir, "lotw/certificate").readBytes()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("N0TEST"))
            val record = QsoRecord(
                id = 1, startUtcMillis = 1_789_000_000_000, theirCallsign = "K1ABC", myCallsign = "N0TEST", myGrid = "OL62AB",
                mode = "MFSK", submode = "FT4", txFrequencyHz = 145_900_000, rxFrequencyHz = 435_800_000,
                band = "2M", rxBand = "70CM", satelliteName = "AO-123", status = QsoStatus.COMPLETE
            )
            val profile = LoTWStation("OL62AB", "24", "44", "GD")
            val preview = repository().prepare(listOf(record), profile, "test-only".toCharArray(), false)
            assertEquals(1, preview.count)
            assertEquals(profile, repository().station())
        } finally {
            storage.delete("certificate")
            storage.delete("station")
        }
    }
}
