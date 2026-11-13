package com.rtbishop.look4sat.core.data.astro

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class AstroPackageMetadata(
    val version: String,
    val start: Long,
    val end: Long,
    val eopEnd: Long,
    val files: List<AstroPackageFile>,
    val eopStart: Long? = null,
    val builtIn: Boolean = false
) {
    companion object {
        fun parse(text: String): AstroPackageMetadata {
            val json = JSONObject(text)
            require(json.getInt("format") == 1) { "Unsupported astronomy package format" }
            val version = json.getString("version")
            require(version.isNotBlank())
            val entries = json.getJSONArray("files")
            val files = List(entries.length()) { index ->
                val item = entries.getJSONObject(index)
                val name = item.getString("name")
                require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
                val bytes = item.getLong("bytes")
                val hash = item.getString("sha256").lowercase()
                require(bytes > 0 && hash.matches(Regex("[a-f0-9]{64}")))
                AstroPackageFile(name, bytes, hash)
            }
            require(files.map { it.name }.distinct().size == files.size)
            require(files.any { it.name.matches(Regex("[lu]nxp\\d{4}\\.440")) })
            require(files.any { it.name == "UTC-TAI.history" })
            require(files.any { it.name == "finals2000A.all" })
            val start = json.getLong("coverageStartMillis")
            val end = json.getLong("coverageEndMillis")
            require(start <= 1577836800000L && end >= 2556144000000L) {
                "Astronomy package must cover 2020–2050"
            }
            require(json.getLong("eopStartMillis") < json.getLong("eopEndMillis"))
            return AstroPackageMetadata(version, start, end, json.getLong("eopEndMillis"), files,
                json.getLong("eopStartMillis"), json.optString("distribution") == "bundled")
        }
    }
}

internal data class AstroPackageFile(val name: String, val bytes: Long, val sha256: String)

internal object AstroDataPackage {
    fun metadata(directory: File): AstroPackageMetadata =
        AstroPackageMetadata.parse(File(directory, "manifest.json").readText())

    /**
     * Private installed generations are immutable. Rehash file contents only when their
     * manifest, sizes or modification times change, or there is no accepted receipt yet.
     */
    fun fingerprint(directory: File, metadata: AstroPackageMetadata): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(File(directory, "manifest.json").readBytes())
        metadata.files.forEach { entry ->
            val file = File(directory, entry.name)
            require(file.isFile && file.length() == entry.bytes) {
                "Package size mismatch: ${entry.name}"
            }
            digest.update("\n${entry.name}:${file.length()}:${file.lastModified()}".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun extract(archive: File, directory: File, checkCancelled: () -> Unit): AstroPackageMetadata {
        check(directory.mkdirs())
        ZipFile(archive).use { zip ->
            val manifests = zip.entries().asSequence().filter { it.name == "manifest.json" }.toList()
            require(manifests.size == 1 && manifests.single().size in 1..1_048_576)
            val text = zip.getInputStream(manifests.single()).bufferedReader().use { it.readText() }
            val metadata = AstroPackageMetadata.parse(text)
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.distinct().size == names.size) { "Duplicate archive entries" }
            metadata.files.forEach { file ->
                checkCancelled()
                val entry = requireNotNull(zip.getEntry(file.name)) { "Missing package file: ${file.name}" }
                require(entry.size == file.bytes)
                zip.getInputStream(entry).use { input ->
                    File(directory, file.name).outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= file.bytes)
                            output.write(buffer, 0, count)
                        }
                        require(total == file.bytes)
                    }
                }
            }
            File(directory, "manifest.json").writeText(text)
            verify(directory, checkCancelled)
            return metadata
        }
    }

    fun verify(directory: File, checkCancelled: () -> Unit): AstroPackageMetadata {
        val metadata = metadata(directory)
        metadata.files.forEach { file ->
            val source = File(directory, file.name)
            require(source.length() == file.bytes) { "Package size mismatch: ${file.name}" }
            val hash = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == file.sha256) {
                "Package digest mismatch: ${file.name}"
            }
        }
        return metadata
    }
}
