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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
