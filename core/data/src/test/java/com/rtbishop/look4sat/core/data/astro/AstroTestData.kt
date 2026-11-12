package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.AstroDataPhase
import com.rtbishop.look4sat.core.domain.astro.AstroDataState
import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** Full ephemerides stay outside Git; prepare them with the checked-in JPL converter. */
internal object AstroTestData {
    val runtime: OrekitRuntime by lazy {
        val candidates = listOfNotNull(System.getenv("ASTRO_TEST_DATA_ZIP")?.let(::File),
            File("../../dist/orekit-data/orekit-data.zip"), File("dist/orekit-data/orekit-data.zip"))
        val archive = candidates.firstOrNull(File::isFile)
        assumeTrue("Prepare orekit-data.zip or set ASTRO_TEST_DATA_ZIP", archive != null)
        val directory = Files.createTempDirectory("orekit-test-").toFile().apply { deleteOnExit() }
        ZipFile(requireNotNull(archive)).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && !it.name.contains('/') && !it.name.contains('\\') }
                .forEach { entry ->
                    File(directory, entry.name).also { output ->
                        zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
                        output.deleteOnExit()
                    }
                }
        }
        // No Android JSON API is needed by plain JVM orbital reference tests.
        val text = File(directory, "manifest.json").readText()
        fun number(key: String) = Regex("\"$key\"\\s*:\\s*(\\d+)").find(text)!!.groupValues[1].toLong()
        OrekitRuntime(directory, AstroPackageMetadata("test-DE440", number("coverageStartMillis"),
            number("coverageEndMillis"), number("eopEndMillis"), emptyList()))
    }
    val repository: IAstroDataRepository get() = object : IAstroDataRepository {
        override val state = MutableStateFlow(AstroDataState(phase = AstroDataPhase.READY, version = runtime.version))
        override fun snapshot() = runtime
        override fun setDownloadUrl(url: String) = error("Not a downloader")
        override fun download() = error("Not a downloader")
        override fun cancel() = Unit
    }
    val engine get() = OrekitOrbitEngine(repository)
}
