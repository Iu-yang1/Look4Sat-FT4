package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.astro.AstroDataPhase
import com.rtbishop.look4sat.core.domain.astro.IAstroDataRepository
import com.rtbishop.look4sat.core.domain.astro.OrbitEphemerisSnapshot
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.Sources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.orekit.data.DataSource
import org.orekit.files.ccsds.ndm.ParserBuilder
import org.orekit.files.ccsds.ndm.odm.oem.OemSegment
import org.orekit.time.AbsoluteDate
import org.orekit.utils.Constants
import org.orekit.utils.IERSConventions
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** CCSDS messages are recognized by content, not the .txt extension. */
internal fun looksLikeOem(input: BufferedInputStream): Boolean {
    input.mark(8192)
    val preview = ByteArray(8192)
    var count = 0
    try {
        while (count < preview.size) {
            val n = input.read(preview, count, preview.size - count)
            if (n <= 0) break
            count += n
        }
    } finally {
        input.reset()
    }
    val header = preview.decodeToString(0, count).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    return header.startsWith("CCSDS_OEM_VERS") ||
        Regex("<(?:[\\w.-]+:)?oem(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(header)
}

internal data class IssOemSnapshot(
    override val revision: String,
    val source: String,
    val creation: AbsoluteDate,
    val segments: List<OemSegment>
) : OrbitEphemerisSnapshot

internal fun parseIssOem(bytes: ByteArray, source: String, runtime: OrekitRuntime): IssOemSnapshot {
    val message = ParserBuilder(runtime.context)
        .withConventions(IERSConventions.IERS_2010)
        .withSimpleEOP(false)
        .withMu(Constants.WGS84_EARTH_MU)
        // NASA files may omit the interpolation declaration. Four PV samples (degree 3).
        .withDefaultInterpolationDegree(3)
        .buildOemParser()
        .parse(DataSource("ISS.oem", DataSource.StreamOpener { bytes.inputStream() }))
    val spacecraft = message.satellites.values.single()
    require(spacecraft.id.replace("-", "").equals("1998067A", ignoreCase = true)) {
        "OEM does not describe ISS (1998-067-A)"
    }
    val segments = spacecraft.segments.sortedBy { it.start }
    require(segments.isNotEmpty())
    segments.forEach { segment ->
        require(segment.frame == runtime.context.frames.eme2000) { "Expected geocentric EME2000 OEM" }
        val coordinates = segment.coordinates
        require(segment.start < segment.stop && coordinates.size >= 2)
        require(coordinates.first().date <= segment.start && coordinates.last().date >= segment.stop)
        require(coordinates.zipWithNext().all { (a, b) -> a.date < b.date })
        require(coordinates.all { it.position.norm.isFinite() && it.position.norm > Constants.WGS84_EARTH_EQUATORIAL_RADIUS &&
            it.velocity.norm.isFinite() })
        // The Orekit propagator requires as many records as the interpolation stencil.
        if (segment.metadata.interpolationMethod == null && coordinates.size < segment.interpolationSamples) {
            segment.metadata.setInterpolationDegree(coordinates.size - 1)
        }
        require(coordinates.size >= segment.interpolationSamples)
        segment.propagator.propagate(segment.start)
        segment.propagator.propagate(segment.stop)
    }
    return IssOemSnapshot(digest(bytes), source, message.header.creationDate, segments)
}

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** One atomically replaced, independently validated file per configured URL. */
class OemEphemerisRepository(
    private val directory: File,
    private val astronomy: IAstroDataRepository,
    private val settings: ISettingsRepo
) {
    companion object { const val LOCAL_SOURCE = "local:iss-oem" }

    private data class Parsed(val runtime: OrekitRuntime, val snapshot: IssOemSnapshot)
    private val parsed = mutableMapOf<String, Parsed>()
    private fun file(source: String) = File(directory, digest(source.toByteArray(Charsets.UTF_8)) + ".oem")

    internal suspend fun importOem(source: String, stream: InputStream): Boolean {
        return try {
            val bytes = stream.use { it.readBytes() }
            val state = astronomy.state.first {
                it.ready || it.phase == AstroDataPhase.FAILED || it.phase == AstroDataPhase.MISSING
            }
            if (!state.ready) return false
            val runtime = astronomy.snapshot() as OrekitRuntime
            val key = if (source == LOCAL_SOURCE) Sources.issOemUrl else source
            val candidate = parseIssOem(bytes, key, runtime)
            synchronized(this) {
                val previous = read(key, runtime)
                if (source != LOCAL_SOURCE && previous != null && previous.creation > candidate.creation) {
                    return@synchronized true
                }
                if (previous?.revision == candidate.revision) return@synchronized true
                check(directory.isDirectory || directory.mkdirs())
                val target = file(key)
                val temporary = File.createTempFile("incoming-", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(bytes)
                        output.fd.sync()
                    }
                    check(temporary.renameTo(target)) { "Unable to replace OEM cache" }
                    parsed[key] = Parsed(runtime, candidate)
                } finally {
                    temporary.delete()
                }
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            println("ISS OEM import failed: ${error.message}")
            false
        }
    }

    @Synchronized
    internal fun select(catalogNumber: Int, runtime: OrekitRuntime): IssOemSnapshot? {
        if (catalogNumber != Sources.issCatalogNumber) return null
        val configured = settings.dataSourcesSettings.value
        val enabled = configured.satelliteUrls.filterIndexed { index, url ->
            url.isNotBlank() && configured.isSatelliteEnabled(index)
        }.map { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        // Manual files use the same source slot and enable/disable semantics as NASA.
        return enabled.distinct().mapNotNull { read(it, runtime) }
            .maxByOrNull { it.creation }
    }

    private fun read(source: String, runtime: OrekitRuntime): IssOemSnapshot? {
        parsed[source]?.takeIf { it.runtime === runtime }?.let { return it.snapshot }
        val file = file(source)
        if (!file.isFile) return null
        return try {
            parseIssOem(file.readBytes(), source, runtime).also { parsed[source] = Parsed(runtime, it) }
        } catch (error: Exception) {
            println("ISS OEM cache unavailable: ${error.message}")
            null
        }
    }

    internal fun coversNow(): Boolean {
        if (!astronomy.state.value.ready) return false
        val runtime = astronomy.snapshot() as OrekitRuntime
        val time = AbsoluteDate(java.util.Date(), runtime.utc)
        return select(Sources.issCatalogNumber, runtime)?.segments?.any { time >= it.start && time <= it.stop } == true
    }

    @Synchronized
    internal fun clear() {
        directory.deleteRecursively()
        parsed.clear()
    }
}
