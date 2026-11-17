package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.domain.maintenance.BackupCategory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.rtbishop.look4sat.core.data.lotw.derivePkcs12Key
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(credentials: Boolean = false): Pair<File, BackupManifest> {
        val root = temp.newFolder()
        File(root, "snapshot.json").writeText("""{"preferences":{}}""")
        val categories = setOf(if (credentials) BackupCategory.CREDENTIALS else BackupCategory.SETTINGS)
        val entry = BackupEntry("snapshot.json", File(root, "snapshot.json").length(),
            BackupArchive.digest(File(root, "snapshot.json")), categories.single())
        return root to BackupManifest(createdAtMillis = 123, appVersion = "test",
            categories = categories, counts = categories.associateWith { 1L }, entries = listOf(entry))
    }

    @Test fun plainAndEncryptedRoundTripPreserveManifestAndContent() {
        for (password in listOf(charArrayOf(), "different-device-password".toCharArray())) {
            val (root, manifest) = fixture(password.isNotEmpty())
            val archive = temp.newFile()
            BackupArchive.pack(root, manifest, archive, password) {}
            val output = temp.newFolder()
            val (actual, encrypted) = BackupArchive.unpack(archive, output, password, 10_000_000) {}
            assertEquals(manifest, actual)
            assertEquals(password.isNotEmpty(), encrypted)
            assertArrayEquals(File(root, "snapshot.json").readBytes(), File(output, "snapshot.json").readBytes())
        }
    }

    @Test fun credentialsCannotBePackedWithoutPassword() {
        val (root, manifest) = fixture(true)
        assertTrue(runCatching { BackupArchive.pack(root, manifest, temp.newFile(), charArrayOf()) {} }.isFailure)
        assertTrue(runCatching {
            BackupArchive.unpack(rawArchive(manifest, root), temp.newFolder(), charArrayOf(), 10_000_000) {}
        }.isFailure)
    }

    @Test fun wrongPasswordTamperingAndTruncationExposeNoSnapshot() {
        val (root, manifest) = fixture(true)
        val archive = temp.newFile()
        val password = "correct".toCharArray()
        BackupArchive.pack(root, manifest, archive, password) {}
        val original = archive.readBytes()
        val attempts = listOf(
            original to "wrong".toCharArray(),
            original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to password,
            original.copyOf(original.size / 2) to password
        )
        for ((bytes, key) in attempts) {
            val input = temp.newFile().apply { writeBytes(bytes) }
            val output = temp.newFolder()
            assertTrue(runCatching { BackupArchive.unpack(input, output, key, 10_000_000) {} }.isFailure)
            assertFalse(File(output, "snapshot.json").exists())
            assertFalse(File(output.parent, output.name + ".payload").exists())
        }
    }

    @Test fun refusesTraversalAbsolutePathsAndDuplicateManifestEntries() {
        for (path in listOf("../outside", "/absolute", "C:/outside", "a\\b", "a/../b", "a//b", ".")) {
            assertTrue(path, runCatching { BackupArchive.safeFile(temp.root, path) }.isFailure)
        }
        val (root, manifest) = fixture()
        val input = rawArchive(manifest.copy(entries = manifest.entries + manifest.entries), root)
        assertTrue(runCatching { BackupArchive.unpack(input, temp.newFolder(), charArrayOf(), 10_000_000) {} }.isFailure)
    }

    @Test fun mismatchedChecksumUnexpectedEntriesAndStorageLimitsAreRejected() {
        val (root, manifest) = fixture()
        for (bad in listOf(
            manifest.copy(entries = manifest.entries.map { it.copy(sha256 = "0".repeat(64)) }),
            manifest.copy(entries = emptyList())
        )) {
            assertTrue(runCatching { BackupArchive.unpack(rawArchive(bad, root), temp.newFolder(),
                charArrayOf(), 10_000_000) {} }.isFailure)
        }
        val archive = temp.newFile()
        BackupArchive.pack(root, manifest, archive, charArrayOf()) {}
        assertTrue(runCatching { BackupArchive.unpack(archive, temp.newFolder(), charArrayOf(), 5) {} }.isFailure)
    }

    @Test fun modifiedSourceAndCancellationAbortArchiveCreation() {
        val (root, manifest) = fixture()
        File(root, "snapshot.json").appendText("changed")
        assertTrue(runCatching { BackupArchive.pack(root, manifest, temp.newFile(), charArrayOf()) {} }.isFailure)
        val result = runCatching {
            BackupArchive.copy("data".byteInputStream(), java.io.ByteArrayOutputStream()) {
                throw CancellationException("cancelled")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun encryptedArchivesRemainCompatibleWithLegacyJcaInBothDirections() {
        val (root, manifest) = fixture(true)
        val archive = temp.newFile()
        val password = "compatible-password".toCharArray()
        BackupArchive.pack(root, manifest, archive, password) {}
        val payload = DataInputStream(archive.inputStream()).use { input ->
            assertEquals("L4SBKP01", ByteArray(8).also(input::readFully).toString(Charsets.US_ASCII))
            assertTrue(input.readBoolean())
            val iterations = input.readInt()
            val salt = ByteArray(16).also(input::readFully)
            val nonce = ByteArray(12).also(input::readFully)
            val key = derivePkcs12Key("PBKDF2WithHmacSHA256", password, salt, iterations, 256)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD("L4SBKP01".toByteArray())
                cipher.doFinal(input.readBytes())
            } finally { key.fill(0) }
        }
        val legacy = temp.newFile()
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 20).toByte() }
        val key = derivePkcs12Key("PBKDF2WithHmacSHA256", password, salt, 600_000, 256)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD("L4SBKP01".toByteArray())
            DataOutputStream(legacy.outputStream()).use {
                it.writeBytes("L4SBKP01"); it.writeBoolean(true); it.writeInt(600_000)
                it.write(salt); it.write(nonce); it.write(cipher.doFinal(payload))
            }
        } finally { key.fill(0); payload.fill(0) }
        val output = temp.newFolder()
        assertEquals(manifest, BackupArchive.unpack(legacy, output, password, 10_000_000) {}.first)
        assertArrayEquals(File(root, "snapshot.json").readBytes(), File(output, "snapshot.json").readBytes())
    }

    @Test fun streamingCipherEmitsCiphertextBeforeFinalization() {
        val output = ByteArrayOutputStream()
        val cipher = BackupArchive.cipher(true, "password".toCharArray(), ByteArray(16), ByteArray(12))
        org.bouncycastle.crypto.io.CipherOutputStream(output, cipher).use {
            it.write(ByteArray(65536))
            assertTrue("Cipher must not retain the complete backup until close", output.size() >= 65520)
        }
        assertEquals(65536 + 16, output.size())
    }

    @Test fun largeIncompressibleEncryptedFileRoundTripsInBoundedChunks() {
        val (root, base) = fixture(true)
        val data = File(root, "recordings/Recordings/test.wav").apply { parentFile.mkdirs() }
        val random = Random(12345)
        data.outputStream().buffered().use { output ->
            val chunk = ByteArray(65536)
            repeat(256) { random.nextBytes(chunk); output.write(chunk) }
        }
        val manifest = base.copy(
            categories = base.categories + BackupCategory.RECORDINGS,
            counts = base.counts + (BackupCategory.RECORDINGS to 1L),
            entries = base.entries + BackupEntry("recordings/Recordings/test.wav", data.length(),
                BackupArchive.digest(data), BackupCategory.RECORDINGS)
        )
        val archive = temp.newFile()
        BackupArchive.pack(root, manifest, archive, "password".toCharArray()) {}
        val output = temp.newFolder()
        assertEquals(manifest, BackupArchive.unpack(archive, output, "password".toCharArray(), 100_000_000) {}.first)
        assertEquals(BackupArchive.digest(data), BackupArchive.digest(File(output, "recordings/Recordings/test.wav")))
    }

    private fun rawArchive(manifest: BackupManifest, root: File): File = temp.newFile().apply {
        outputStream().use { stream ->
            DataOutputStream(stream).apply { writeBytes("L4SBKP01"); writeBoolean(false) }
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(backupJson.encodeToString(manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("snapshot.json"))
                zip.write(File(root, "snapshot.json").readBytes())
                zip.closeEntry()
            }
        }
    }
}
