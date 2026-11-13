package com.rtbishop.look4sat.core.data.astro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.astro.AstroDataPhase
import com.rtbishop.look4sat.core.domain.astro.AstroDataUnavailableException
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AstroDataRepositoryTest {
    @Test fun bundledDataStartsOfflineAndRemainsAvailableAfterFailedFullDownload() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val root = Files.createTempDirectory("astro-bundled-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503)
                .message("Unavailable").body("".toResponseBody()).build()
        }.build()
        try {
            var opens = 0
            val repository = AstroDataRepository(root, preferences, client, scope,
                ControlDiagnosticsBuffer(), { UpdateSource.GITHUB },
                bundledArchive = { opens++; context.assets.open("astronomy/orekit-data.zip") })
            withTimeout(30000) { repository.state.first { it.phase == AstroDataPhase.READY } }
            assertEquals(0, requests)
            assertEquals(1, opens)
            assertTrue(repository.state.value.builtIn)
            val pinned = repository.snapshot()
            assertEquals(1577836800000L, pinned.coverageStartMillis)
            assertEquals(2556144000000L, pinned.coverageEndMillis)
            val restored = AstroDataRepository(root, preferences, client, scope,
                ControlDiagnosticsBuffer(), { UpdateSource.GITHUB },
                bundledArchive = { error("Existing valid data must be reused") })
            withTimeout(30000) { restored.state.first { it.phase == AstroDataPhase.READY } }
            assertTrue(restored.state.value.builtIn)
            assertEquals(repository.state.value.generation, restored.state.value.generation)

            repository.download()
            withTimeout(10000) { repository.state.first { it.phase == AstroDataPhase.FAILED } }
            assertSame(pinned, repository.snapshot())
            assertTrue(repository.state.value.builtIn)
            assertTrue(repository.state.value.ready)
            assertEquals(1, requests)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun damagedSavedGenerationRecoversUsingTheBundledArchiveWithoutNetwork() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        preferences.edit().putString("activeDirectory", "generation-aabb").commit()
        val root = Files.createTempDirectory("astro-bundled-recovery-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient.Builder().addInterceptor { error("No network during bundled restore") }.build()
        try {
            val repository = AstroDataRepository(root, preferences, client, scope,
                ControlDiagnosticsBuffer(), { UpdateSource.GITHUB },
                bundledArchive = { context.assets.open("astronomy/orekit-data.zip") })
            withTimeout(30000) { repository.state.first { it.phase == AstroDataPhase.READY } }
            assertTrue(repository.state.value.builtIn)
            assertNotEquals("generation-aabb", repository.state.value.generation)
            assertEquals(repository.state.value.generation, preferences.getString("activeDirectory", null))
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun initialFailureStaysUnavailableAndUrlChangesOnlyAffectTheNextRequest() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val root = Files.createTempDirectory("astro-download-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(seen) { seen.add(chain.request().url.toString()) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404)
                .message("Not found").body("".toResponseBody()).build()
        }.build()
        try {
            val repository = AstroDataRepository(root, preferences, client, scope,
                ControlDiagnosticsBuffer(), { UpdateSource.AUTO })
            withTimeout(10000) { repository.state.first { it.phase == AstroDataPhase.MISSING } }
            repository.setDownloadUrl("https://example.org/data.zip")
            assertTrue(seen.isEmpty())
            repository.download()
            repository.download()
            withTimeout(10000) { repository.state.first { it.phase == AstroDataPhase.FAILED } }
            assertEquals(listOf("https://example.org/data.zip"), seen)
            assertFalse(repository.state.value.ready)
            assertThrows(AstroDataUnavailableException::class.java) { repository.snapshot() }
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun failedUpdateAndUrlEditPreserveThePinnedPreviousGeneration() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val root = Files.createTempDirectory("astro-update-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503)
                .message("Unavailable").body("".toResponseBody()).build()
        }.build()
        try {
            AstroTestData.runtime.directory.copyRecursively(java.io.File(root, "generation-aabb"))
            preferences.edit().putString("activeDirectory", "generation-aabb").commit()
            val repository = AstroDataRepository(root, preferences, client, scope,
                ControlDiagnosticsBuffer(), { UpdateSource.GITHUB })
            withTimeout(30000) { repository.state.first { it.phase == AstroDataPhase.READY } }
            val pinned = repository.snapshot()
            repository.setDownloadUrl("https://example.org/replacement.zip")
            assertSame(pinned, repository.snapshot())
            repository.download()
            withTimeout(10000) { repository.state.first { it.phase == AstroDataPhase.FAILED } }
            assertTrue(repository.state.value.ready)
            assertSame(pinned, repository.snapshot())
            assertEquals("generation-aabb", preferences.getString("activeDirectory", null))
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun successfulSameVersionUpdateKeepsOldSessionsPinnedAndChangesNewGeneration() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
        val root = Files.createTempDirectory("astro-generations-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldDirectory = java.io.File(root, "generation-aabb")
        AstroTestData.runtime.directory.copyRecursively(oldDirectory)
        preferences.edit().putString("activeDirectory", oldDirectory.name).commit()
        val archive = java.io.ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                oldDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(archive.toResponseBody()).build()
        }.build()
        try {
            val repository = AstroDataRepository(root, preferences, client, scope,
                ControlDiagnosticsBuffer(), { UpdateSource.GITHUB })
            withTimeout(30000) { repository.state.first { it.phase == AstroDataPhase.READY } }
            val pinned = repository.snapshot()
            val generation = repository.state.value.generation
            val engine = OrekitOrbitEngine(repository)
            val target = engine.bind(com.rtbishop.look4sat.core.domain.predict.OrbitalData(
                "Generation fixture", 26316.0, 15.5, .001, 51.6, 0.0, 0.0, 0.0, 99999, 0.0))
            val session = engine.openSession(target)
            val time = 1794484800000L
            val before = session.temeState(time)

            repository.download()
            withTimeout(30000) {
                repository.state.first { it.phase == AstroDataPhase.READY && it.generation != generation }
            }

            assertEquals(pinned.version, repository.snapshot().version)
            assertNotSame(pinned, repository.snapshot())
            assertSame(pinned, session.target.snapshot)
            assertEquals(before, session.temeState(time))
            assertTrue(oldDirectory.isDirectory)
            assertSame(repository.snapshot(), engine.bind(target.data).snapshot)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
