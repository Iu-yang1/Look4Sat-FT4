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
            val checksum = MessageDigest.getInstance("SHA-256").apply { update(bytes, 32, bytes.size - 32) }.digest()
            require(MessageDigest.isEqual(bytes.copyOfRange(0, 32),
                checksum))
            DataInputStream(ByteArrayInputStream(bytes, 32, bytes.size - 32)).use { input ->
                require(input.readUTF() == FORMAT && input.readUTF() == identity)
                val count = input.readInt()
                require(count in 4..60000)
                var previousMjd = Int.MIN_VALUE
                val versions = mutableMapOf<Int, ITRFVersion>()
                val types = EopDataType.values().associateBy { it.name }
                List(count) {
                    val mjd = input.readInt()
                    require(mjd > previousMjd)
                    previousMjd = mjd
                    val ut1 = input.readDouble()
                    val lod = input.readDouble()
                    val x = input.readDouble()
                    val y = input.readDouble()
                    val xRate = input.readDouble()
                    val yRate = input.readDouble()
                    val ddPsi = input.readDouble()
                    val ddEps = input.readDouble()
                    val dx = input.readDouble()
                    val dy = input.readDouble()
                    val year = input.readInt()
                    val itrf = versions.getOrPut(year) { ITRFVersion.getITRFVersion(year) }
                    val type = requireNotNull(types[input.readUTF()])
                    EOPEntry(mjd, ut1, lod, x, y, xRate, yRate, ddPsi, ddEps, dx, dy, itrf,
                        AbsoluteDate.createMJDDate(mjd, 0.0, utc), type)
                }.also {
                    require(input.read() == -1)
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
