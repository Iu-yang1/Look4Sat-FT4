package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.model.UpdateSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AstroDataPackageTest {
    private val github = "https://github.com/Iu-yang1/Look4Sat-FT4/releases/download/orekit-data/orekit-data.zip"

    @Test fun mirrorsPrefixTheCompleteGithubUrlExactlyOnce() {
        for ((source, mirror) in listOf(
            UpdateSource.GHFAST to "https://ghfast.top/",
            UpdateSource.LLKK to "https://gh.llkk.cc/",
            UpdateSource.MOEYY to "https://github.moeyy.xyz/"
        )) {
            assertEquals(listOf(mirror + github), astroDownloadCandidates(github, source))
            assertEquals(listOf(mirror + github), astroDownloadCandidates(mirror + github, source))
        }
        assertEquals(4, astroDownloadCandidates(github, UpdateSource.AUTO).size)
        assertEquals(listOf("https://example.org/data.zip"),
            astroDownloadCandidates("https://example.org/data.zip", UpdateSource.GHFAST))
    }

    private fun manifest() = JSONObject().put("format", 1).put("version", "fixture")
        .put("coverageStartMillis", 946684800000L).put("coverageEndMillis", 4133980800000L)
        .put("eopStartMillis", 946684800000L).put("eopEndMillis", 1794000000000L)
        .put("files", JSONArray(listOf("lnxp2000.440", "UTC-TAI.history", "finals2000A.all").map {
            JSONObject().put("name", it).put("bytes", 1)
                .put("sha256", "00".repeat(32))
        }))

    @Test fun requiredCoverageAndFlatPathsAreValidatedBeforeExtraction() {
        assertEquals("fixture", AstroPackageMetadata.parse(manifest().toString()).version)
        assertThrows(IllegalArgumentException::class.java) {
            AstroPackageMetadata.parse(manifest().put("coverageEndMillis", 1794000000000L).toString())
        }
        val malicious = manifest()
        malicious.getJSONArray("files").getJSONObject(0).put("name", "../lnxp2000.440")
        assertThrows(IllegalArgumentException::class.java) { AstroPackageMetadata.parse(malicious.toString()) }
    }

    @Test fun corruptFileHashesNeverPassVerification() {
        val root = Files.createTempDirectory("astro-package-").toFile()
        try {
            val archive = java.io.File(root, "test.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                fun entry(name: String, contents: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(contents); zip.closeEntry()
                }
                entry("manifest.json", manifest().toString().toByteArray())
                listOf("lnxp2000.440", "UTC-TAI.history", "finals2000A.all").forEach {
                    entry(it, byteArrayOf(1))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                AstroDataPackage.extract(archive, java.io.File(root, "generation-test")) {}
            }
        } finally { root.deleteRecursively() }
    }
}
