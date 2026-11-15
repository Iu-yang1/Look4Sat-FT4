package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.data.lotw.derivePkcs12Key
import com.rtbishop.look4sat.core.domain.maintenance.BackupCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
internal data class BackupEntry(val path: String, val bytes: Long, val sha256: String, val category: BackupCategory)

@Serializable
internal data class BackupManifest(
    val format: Int = 1,
    val createdAtMillis: Long,
    val appVersion: String,
    val categories: Set<BackupCategory>,
    val counts: Map<BackupCategory, Long>,
    val entries: List<BackupEntry>
)

internal val backupJson = Json { encodeDefaults = true }

/** A portable, authenticated container. Media is always copied in bounded chunks. */
internal object BackupArchive {
    private val magic = "L4SBKP01".toByteArray(Charsets.US_ASCII)
    private const val iterations = 600_000

    fun digest(file: File, check: () -> Unit = {}): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                check()
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun pack(root: File, manifest: BackupManifest, target: File, password: CharArray, check: () -> Unit) {
        require(BackupCategory.CREDENTIALS !in manifest.categories || password.isNotEmpty()) { "A password is required" }
        val encrypted = password.isNotEmpty()
        target.outputStream().buffered().use { raw ->
            val header = DataOutputStream(raw)
            header.write(magic)
            header.writeBoolean(encrypted)
            val payload: OutputStream = if (encrypted) {
                val salt = ByteArray(16).also(SecureRandom()::nextBytes)
                val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
                header.writeInt(iterations)
                header.write(salt)
                header.write(nonce)
                CipherOutputStream(raw, cipher(Cipher.ENCRYPT_MODE, password, salt, nonce))
            } else raw
            ZipOutputStream(payload).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(backupJson.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (entry in manifest.entries) {
                    check()
                    val file = safeFile(root, entry.path)
                    require(file.length() == entry.bytes && digest(file, check) == entry.sha256) { "Backup file changed" }
                    zip.putNextEntry(ZipEntry(entry.path))
                    file.inputStream().use { copy(it, zip, check = check) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun unpack(
        source: File, target: File, password: CharArray, availableBytes: Long,
        check: () -> Unit
    ): Pair<BackupManifest, Boolean> {
        require(target.mkdirs() || target.isDirectory)
        val decrypted = File(target.parentFile, "${target.name}.payload")
        val encrypted: Boolean
        try {
            source.inputStream().buffered().use { raw ->
                val header = DataInputStream(raw)
                val actual = ByteArray(magic.size)
                header.readFully(actual)
                require(actual.contentEquals(magic)) { "Unsupported backup format" }
                val flag = header.readUnsignedByte()
                require(flag in 0..1) { "Unsupported backup format" }
                encrypted = flag == 1
                if (encrypted) {
                    require(password.isNotEmpty()) { "Enter the backup password" }
                    require(header.readInt() == iterations) { "Unsupported password derivation" }
                    val salt = ByteArray(16).also(header::readFully)
                    val nonce = ByteArray(12).also(header::readFully)
                    CipherInputStream(raw, cipher(Cipher.DECRYPT_MODE, password, salt, nonce)).use { input ->
                        decrypted.outputStream().buffered().use { copy(input, it, availableBytes, check) }
                    }
                } else {
                    decrypted.outputStream().buffered().use { copy(raw, it, availableBytes, check) }
                }
            }
            // No entry is exposed to the restorer until GCM authentication has completed.
            ZipInputStream(decrypted.inputStream().buffered()).use { zip ->
                require(zip.nextEntry?.name == "manifest.json") { "Backup manifest is missing" }
                val manifestText = ByteArrayOutputStream()
                copy(zip, manifestText, 2 * 1024 * 1024, check)
                val manifest = backupJson.decodeFromString<BackupManifest>(manifestText.toString(Charsets.UTF_8.name()))
                require(manifest.format == 1 && manifest.categories.isNotEmpty()) { "Unsupported backup version" }
                require(manifest.counts.keys == manifest.categories && manifest.counts.values.all { it >= 0 }) {
                    "Invalid backup counts"
                }
                require(manifest.entries.size <= 100_000 && manifest.entries.map { it.path }.toSet().size == manifest.entries.size)
                require(BackupCategory.CREDENTIALS !in manifest.categories || encrypted) { "Unencrypted credentials are not accepted" }
                var total = 0L
                manifest.entries.forEach {
                    require(it.category in manifest.categories && it.bytes >= 0 && it.sha256.matches(Regex("[a-f0-9]{64}")))
                    total = Math.addExact(total, it.bytes)
                    safeFile(target, it.path)
                }
                require(total <= availableBytes - decrypted.length()) { "Not enough storage" }
                val expected = manifest.entries.associateBy { it.path }
                val seen = hashSetOf<String>()
                while (true) {
                    check()
                    val entry = zip.nextEntry ?: break
                    val description = requireNotNull(expected[entry.name]) { "Unexpected backup entry" }
                    require(!entry.isDirectory && seen.add(entry.name)) { "Duplicate backup entry" }
                    val file = safeFile(target, entry.name)
                    kotlin.check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    file.outputStream().buffered().use { copy(zip, it, description.bytes, check) }
                    require(file.length() == description.bytes && digest(file, check) == description.sha256) { "Backup checksum mismatch" }
                }
                require(seen == expected.keys) { "Backup is incomplete" }
                require("snapshot.json" in seen) { "Backup snapshot is missing" }
                return manifest to encrypted
            }
        } finally {
            decrypted.delete()
        }
    }

    internal fun safeFile(root: File, path: String): File {
        require(path.isNotBlank() && '\\' !in path && ':' !in path && !path.startsWith('/') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid backup path" }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.canonicalFile.path + File.separator))
        return file
    }

    internal fun copy(input: InputStream, output: OutputStream, limit: Long = Long.MAX_VALUE, check: () -> Unit): Long {
        val bytes = ByteArray(65536)
        var total = 0L
        while (true) {
            check()
            val count = input.read(bytes)
            if (count < 0) return total
            total = Math.addExact(total, count.toLong())
            require(total <= limit) { "Backup exceeds the available storage or declared size" }
            output.write(bytes, 0, count)
        }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val bytes = derivePkcs12Key("PBKDF2WithHmacSHA256", password, salt, iterations, 256)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(magic)
            }
        } finally { bytes.fill(0) }
    }
}
