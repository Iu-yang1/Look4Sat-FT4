package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LoTWUploadRepositoryTest {
    private class MemoryStorage : LoTWStorage {
        val files = linkedMapOf<String, ByteArray>()
        override fun read(name: String) = files[name]?.copyOf()
        override fun write(name: String, data: ByteArray) { files[name] = data.copyOf() }
        override fun delete(name: String) { files.remove(name) }
    }
    private val station = LoTWStation("OL62AB", "24", "44", "GD")
    private fun password() = "test-only".toCharArray()

    @Test fun onlyExplicitAcceptanceIsSuccess() {
        assertEquals(LoTWUploadResult.Accepted(2), parseLoTWUploadResponse("<!-- .UPL. accepted -->", 2))
        assertEquals(LoTWUploadResult.Rejected(), parseLoTWUploadResponse("<!-- .UPL. rejected -->", 2))
        assertEquals(LoTWUploadResult.Rejected("Invalid certificate"), parseLoTWUploadResponse("<!-- .UPL. rejected --><!-- .UPLMESSAGE. Invalid certificate -->", 2))
        listOf("Upload complete", "<html>accepted</html>", "<!-- .UPL. accepted --><!-- .UPL. rejected -->", "").forEach {
            assertEquals(LoTWUploadResult.Unknown, parseLoTWUploadResponse(it, 2))
        }
    }
    @Test fun acceptsMultipartAndPersistsReceiptAcrossRepositoryRestart() = runBlocking {
        val storage = MemoryStorage()
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            posts++
            assertEquals("POST", chain.request().method)
            assertEquals("https://lotw.arrl.org/lotw/upload", chain.request().url.toString())
            assertTrue(storage.files.containsKey("receipts"))
            val body = chain.request().body as MultipartBody
            assertTrue(body.parts.single().headers!!["Content-Disposition"]!!.contains("name=\"upfile\""))
            assertNull(chain.request().url.queryParameter("password"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<!-- .UPL. accepted -->".toResponseBody()).build()
        }.build()
        fun repository() = LoTWUploadRepository(storage, ::configFixture, client) { TEST_NOW }
        val repo = repository()
        val bytes = certificateFixture()
        val pass = password()
        repo.importCertificate(bytes, pass)
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(pass.all { it == '\u0000' })
        assertEquals("N0TEST", repo.certificate()!!.callsign)
        val preview = repo.prepare(listOf(qsoFixture()), station, password(), false)
        assertEquals(0, posts)
        assertEquals(1, preview.count)
        assertEquals(LoTWUploadResult.Accepted(1), repo.upload(preview.id))
        assertEquals(LoTWUploadResult.ExpiredPreview, repo.upload(preview.id))
        assertEquals(1, posts)
        val repeated = repository().prepare(listOf(qsoFixture()), station, password(), false)
        assertEquals(0, repeated.count)
        val explicitRetry = repository().prepare(listOf(qsoFixture().copy(lotwReceived = true)), station, password(), true)
        assertEquals(1, explicitRetry.count)
        val modified = repository().prepare(listOf(qsoFixture().copy(txFrequencyHz = 145_901_000)), station, password(), false)
        assertEquals(1, modified.count)
    }
    @Test fun uncertainPostIsNotAutomaticallyRetriedAfterRestart() = runBlocking {
        val storage = MemoryStorage()
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { posts++; throw IOException("offline") }.build()
        fun repository() = LoTWUploadRepository(storage, ::configFixture, client) { TEST_NOW }
        val repo = repository()
        repo.importCertificate(certificateFixture(), password())
        val preview = repo.prepare(listOf(qsoFixture()), station, password(), false)
        assertEquals(LoTWUploadResult.Unknown, repo.upload(preview.id))
        assertEquals(1, posts)
        val fresh = repository()
        val skipped = fresh.prepare(listOf(qsoFixture()), station, password(), false)
        assertEquals(0, skipped.count)
        assertEquals(1, skipped.unknownSkipped)
        val retry = fresh.prepare(listOf(qsoFixture()), station, password(), true)
        assertEquals(1, retry.count)
        assertEquals(1, posts)
    }
    @Test fun downloadedContactsAreNotResubmittedAndDuplicateRecordsAreSignedOnce() = runBlocking {
        val repo = LoTWUploadRepository(MemoryStorage(), ::configFixture, OkHttpClient()) { TEST_NOW }
        repo.importCertificate(certificateFixture(), password())
        val qso = qsoFixture()
        val preview = repo.prepare(listOf(qso, qso.copy(id = 2), qso.copy(id = 3, lotwReceived = true)), station, password(), false)
        assertEquals(1, preview.count)
        assertEquals(2, preview.skipped)
    }
    @Test fun discardAndExpiryInvalidatePreviewWithoutPosting() = runBlocking {
        var time = TEST_NOW
        val client = OkHttpClient.Builder().addInterceptor { error("No POST expected") }.build()
        val repo = LoTWUploadRepository(MemoryStorage(), ::configFixture, client) { time }
        repo.importCertificate(certificateFixture(), password())
        val one = repo.prepare(listOf(qsoFixture()), station, password(), false)
        repo.discardPreview()
        assertEquals(LoTWUploadResult.ExpiredPreview, repo.upload(one.id))
        val two = repo.prepare(listOf(qsoFixture()), station, password(), false)
        time += 901_000
        assertEquals(LoTWUploadResult.ExpiredPreview, repo.upload(two.id))
    }
    @Test fun removingCertificateKeepsReceipts() = runBlocking {
        val storage = MemoryStorage()
        storage.files["receipts"] = byteArrayOf(1)
        val repo = LoTWUploadRepository(storage, ::configFixture) { TEST_NOW }
        repo.importCertificate(certificateFixture(), password())
        repo.removeCertificate()
        assertNull(repo.certificate())
        assertTrue(storage.files.containsKey("receipts"))
    }
}
