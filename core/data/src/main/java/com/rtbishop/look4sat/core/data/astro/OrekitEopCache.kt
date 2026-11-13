package com.rtbishop.look4sat.core.data.astro

import org.orekit.frames.EOPEntry
import org.orekit.frames.EopDataType
import org.orekit.frames.ITRFVersion
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScale
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Lossless cache of Orekit's parsed entries, not an alternative Earth-orientation model. */
internal object OrekitEopCache {
    private const val FORMAT = "orekit-13.1.4-eop-1"
    private const val FILE_NAME = "parsed-eop-v1.cache"

    fun read(directory: File, identity: String, utc: TimeScale): List<EOPEntry>? = try {
        val file = File(directory, FILE_NAME)
        if (!file.isFile || file.length() !in 32..8_000_000) null else {
            val bytes = file.readBytes()
            val payload = bytes.copyOfRange(32, bytes.size)
            require(MessageDigest.isEqual(bytes.copyOfRange(0, 32),
                MessageDigest.getInstance("SHA-256").digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readUTF() == FORMAT && input.readUTF() == identity)
                val count = input.readInt()
                require(count in 4..60000)
                List(count) {
                    val mjd = input.readInt()
                    val values = DoubleArray(10) { input.readDouble() }
                    val itrf = ITRFVersion.getITRFVersion(input.readInt())
                    val type = EopDataType.valueOf(input.readUTF())
                    EOPEntry(mjd, values[0], values[1], values[2], values[3], values[4],
                        values[5], values[6], values[7], values[8], values[9], itrf,
                        AbsoluteDate.createMJDDate(mjd, 0.0, utc), type)
                }.also { entries ->
                    require(input.read() == -1 && entries.zipWithNext().all { (a, b) -> a.mjd < b.mjd })
                }
            }
        }
    } catch (_: Exception) { null }

    fun write(directory: File, identity: String, entries: List<EOPEntry>) {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF(FORMAT)
            data.writeUTF(identity)
            data.writeInt(entries.size)
            entries.forEach { entry ->
                data.writeInt(entry.mjd)
                doubleArrayOf(entry.getUT1MinusUTC(), entry.lod, entry.x, entry.y, entry.xRate,
                    entry.yRate, entry.ddPsi, entry.ddEps, entry.dx, entry.dy).forEach(data::writeDouble)
                data.writeInt(entry.itrfType.year)
                data.writeUTF(entry.eopDataType.name)
            }
        }
        val payload = output.toByteArray()
        val temporary = File(directory, "$FILE_NAME.part")
        try {
            temporary.outputStream().buffered().use {
                it.write(MessageDigest.getInstance("SHA-256").digest(payload))
                it.write(payload)
            }
            val destination = File(directory, FILE_NAME)
            if (destination.exists()) check(destination.delete())
            check(temporary.renameTo(destination))
        } finally { temporary.delete() }
    }
}
