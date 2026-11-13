package com.rtbishop.look4sat.core.data.astro

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OrekitEopCacheTest {
    @Test fun cachedRuntimePreservesFullEarthOrientationTransforms() {
        val original = AstroTestData.runtime
        val restored = OrekitRuntime(original.directory, AstroPackageMetadata(
            original.version, original.coverageStartMillis, original.coverageEndMillis,
            original.eopEndMillis, emptyList()))
        for (time in listOf(maxOf(original.eopStartMillis, original.coverageStartMillis) + 123456789,
            original.eopEndMillis - 123456789, 1700000000000L)) {
            val date = original.date(time)
            val expected = original.teme.getTransformTo(original.terrestrial, date)
            val actual = restored.teme.getTransformTo(restored.terrestrial, date)
            assertEquals(0.0, org.hipparchus.geometry.euclidean.threed.Rotation.distance(
                expected.rotation, actual.rotation), 1e-14)
            assertEquals(0.0, expected.rotationRate.distance(actual.rotationRate), 1e-16)
        }
    }

    @Test fun parsedEntriesRoundTripEveryFieldWithoutLosingPrecision() {
        val runtime = AstroTestData.runtime
        val entries = runtime.context.frames.getEOPHistory(
            org.orekit.utils.IERSConventions.IERS_2010, false).entries
        val directory = Files.createTempDirectory("eop-cache-").toFile()
        try {
            OrekitEopCache.write(directory, "verified-data", entries)
            val restored = requireNotNull(OrekitEopCache.read(directory, "verified-data", runtime.utc))
            assertEquals(entries.size, restored.size)
            entries.zip(restored).forEach { (a, b) ->
                assertEquals(a.mjd, b.mjd)
                assertEquals(0.0, a.date.durationFrom(b.date), 0.0)
                assertEquals(a.getUT1MinusUTC(), b.getUT1MinusUTC(), 0.0)
                assertEquals(a.lod, b.lod, 0.0)
                assertEquals(a.x, b.x, 0.0)
                assertEquals(a.y, b.y, 0.0)
                assertEquals(a.xRate, b.xRate, 0.0)
                assertEquals(a.yRate, b.yRate, 0.0)
                assertEquals(a.ddPsi, b.ddPsi, 0.0)
                assertEquals(a.ddEps, b.ddEps, 0.0)
                assertEquals(a.dx, b.dx, 0.0)
                assertEquals(a.dy, b.dy, 0.0)
                assertEquals(a.itrfType, b.itrfType)
                assertEquals(a.eopDataType, b.eopDataType)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun changedIdentityAndDamagedBytesAreRejected() {
        val runtime = AstroTestData.runtime
        val entries = runtime.context.frames.getEOPHistory(
            org.orekit.utils.IERSConventions.IERS_2010, false).entries
        val directory = Files.createTempDirectory("eop-invalid-").toFile()
        try {
            OrekitEopCache.write(directory, "old", entries)
            assertNull(OrekitEopCache.read(directory, "new", runtime.utc))
            val file = directory.listFiles()!!.single()
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertNull(OrekitEopCache.read(directory, "old", runtime.utc))
        } finally { directory.deleteRecursively() }
    }
}
