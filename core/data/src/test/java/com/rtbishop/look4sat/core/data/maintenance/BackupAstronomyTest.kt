package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.data.astro.AstroPackageFile
import com.rtbishop.look4sat.core.data.astro.AstroPackageMetadata
import com.rtbishop.look4sat.core.data.astro.AstroTestData
import com.rtbishop.look4sat.core.data.astro.OrekitRuntime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupAstronomyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun backupsIncludeOnlyDeclaredAstronomyDataAndManifest() {
        val metadata = AstroPackageMetadata("test", 0, Long.MAX_VALUE, 0,
            listOf(AstroPackageFile("UTC-TAI.history", 1, "0".repeat(64))))
        File(temp.root, "parsed-eop-v1.cache").writeText("disposable")
        assertEquals(listOf("manifest.json", "UTC-TAI.history"),
            BackupRepository.astronomyBackupFiles(temp.root, metadata).map { it.name })
        assertTrue(BackupRepository.astronomyBackupFiles(temp.root, metadata.copy(builtIn = true)).isEmpty())
    }

    @Test fun readOnlyValidationPreservesLegacyCacheWithDifferentDirectoryIdentity() {
        val source = AstroTestData.runtime
        val directory = File(temp.root, "astronomy")
        source.directory.copyRecursively(directory)
        val cache = File(directory, "parsed-eop-v1.cache")
        assertTrue(cache.isFile)
        val before = BackupArchive.digest(cache)
        val metadata = AstroPackageMetadata(source.version, source.coverageStartMillis,
            source.coverageEndMillis, source.eopEndMillis, emptyList())
        repeat(2) { OrekitRuntime(directory, metadata, writeCache = false).validateCoverage {} }
        assertEquals(before, BackupArchive.digest(cache))
    }
}
