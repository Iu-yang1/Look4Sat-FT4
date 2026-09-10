package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
        assertTrue(repo.certificate()!!.passwordSaved)
        assertEquals(station, repo.saveStation(station))
        assertEquals(1, repo.audit(listOf(qsoFixture())).pending)
        val preview = repo.prepare(listOf(qsoFixture()), false)
        assertEquals(0, posts)
        assertEquals(1, preview.count)
        assertEquals(LoTWUploadResult.Accepted(1), repo.upload(preview.id))
        assertEquals(LoTWUploadResult.ExpiredPreview, repo.upload(preview.id))
        assertEquals(1, posts)
        val afterUpload = repository().audit(listOf(qsoFixture()))
        assertEquals(0, afterUpload.pending)
        assertEquals(1, afterUpload.uploaded)
        val repeated = repository().prepare(listOf(qsoFixture()), false)
        assertEquals(0, repeated.count)
        val explicitRetry = repository().prepare(listOf(qsoFixture().copy(lotwReceived = true)), true)
        assertEquals(1, explicitRetry.count)
        val modified = repository().prepare(listOf(qsoFixture().copy(txFrequencyHz = 145_901_000)), false)
        assertEquals(1, modified.count)
    }
    @Test fun uncertainPostIsNotAutomaticallyRetriedAfterRestart() = runBlocking {
        val storage = MemoryStorage()
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { posts++; throw IOException("offline") }.build()
        fun repository() = LoTWUploadRepository(storage, ::configFixture, client) { TEST_NOW }
        val repo = repository()
        repo.importCertificate(certificateFixture(), password())
        repo.saveStation(station)
        val preview = repo.prepare(listOf(qsoFixture()), false)
        assertEquals(LoTWUploadResult.Unknown, repo.upload(preview.id))
        assertEquals(1, posts)
        val fresh = repository()
        val audit = fresh.audit(listOf(qsoFixture()))
        assertEquals(0, audit.pending)
        assertEquals(1, audit.unknown)
        val skipped = fresh.prepare(listOf(qsoFixture()), false)
        assertEquals(0, skipped.count)
        assertEquals(1, skipped.unknownSkipped)
        val retry = fresh.prepare(listOf(qsoFixture()), true)
        assertEquals(1, retry.count)
        assertEquals(1, posts)
    }
    @Test fun downloadedContactsAreNotResubmittedAndDuplicateRecordsAreSignedOnce() = runBlocking {
        val repo = LoTWUploadRepository(MemoryStorage(), ::configFixture, OkHttpClient()) { TEST_NOW }
        repo.importCertificate(certificateFixture(), password())
        repo.saveStation(station)
        val qso = qsoFixture()
        val records = listOf(
            qso,
            qso.copy(id = 2),
            qso.copy(id = 3, lotwReceived = true),
            qso.copy(id = 4, status = QsoStatus.DRAFT)
        )
        val audit = repo.audit(records)
        assertEquals(4, audit.total)
        assertEquals(1, audit.pending)
        assertEquals(1, audit.uploaded)
        assertEquals(0, audit.unknown)
        assertEquals(2, audit.unavailable)
        val preview = repo.prepare(records, false)
        assertEquals(1, preview.count)
        assertEquals(3, preview.skipped)
    }
    @Test fun discardAndExpiryInvalidatePreviewWithoutPosting() = runBlocking {
        var time = TEST_NOW
        val client = OkHttpClient.Builder().addInterceptor { error("No POST expected") }.build()
        val repo = LoTWUploadRepository(MemoryStorage(), ::configFixture, client) { time }
        repo.importCertificate(certificateFixture(), password())
        repo.saveStation(station)
        val one = repo.prepare(listOf(qsoFixture()), false)
        repo.discardPreview()
        assertEquals(LoTWUploadResult.ExpiredPreview, repo.upload(one.id))
        val two = repo.prepare(listOf(qsoFixture()), false)
        time += 901_000
        assertEquals(LoTWUploadResult.ExpiredPreview, repo.upload(two.id))
    }
    @Test fun legacyCertificateCanSavePasswordAndMigrateWithoutReimport() = runBlocking {
        val storage = MemoryStorage()
        val seed = LoTWUploadRepository(storage, ::configFixture) { TEST_NOW }
        val info = seed.importCertificate(certificateFixture(), password())
        storage.files["certificate"] = legacyBundle(info, certificateFixture())
        val repo = LoTWUploadRepository(storage, ::configFixture) { TEST_NOW }
        assertFalse(repo.certificate()!!.passwordSaved)
        val saved = repo.saveCertificatePassword(password())
        assertTrue(saved.passwordSaved)
        repo.saveStation(station)
        assertEquals(1, repo.prepare(listOf(qsoFixture()), false).count)
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

    private fun legacyBundle(info: com.rtbishop.look4sat.core.domain.repository.LoTWCertificate, p12: ByteArray): ByteArray =
        ByteArrayOutputStream().also { stream -> DataOutputStream(stream).use { output ->
            output.writeInt(1)
            output.writeUTF(info.callsign); output.writeInt(info.dxcc); output.writeUTF(info.serial)
            output.writeUTF(info.expires); output.writeUTF(info.firstQsoDate); output.writeUTF(info.lastQsoDate)
            output.writeInt(p12.size); output.write(p12)
        } }.toByteArray()
}
