package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Unfiltered windows only. Station and data identities are part of every key. */
internal class OrbitPassCache(private val directory: File) {
    data class Window(
        val aos: Long, val aosAzimuth: Double, val los: Long, val losAzimuth: Double,
        val altitude: Int, val maximum: Double, val decayed: Boolean,
        val startClipped: Boolean, val endClipped: Boolean, val continuous: Boolean, val tca: Long
    ) {
        fun restore(target: OrbitalObject) = OrbitalPass(aos, aosAzimuth, los, losAzimuth,
            altitude, maximum, target, hasDecayed = decayed, startClipped = startClipped,
            endClipped = endClipped, continuousVisibility = continuous, tcaTime = tca)
    }
    data class Entry(val start: Long, val end: Long, val windows: List<Window>)

    private val memory = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 64
    }
    private var writes = 0
    private val locks = Array(64) { Any() }

    fun <T> withKey(key: String, block: () -> T): T =
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size], block)

    fun <T> withKeys(keys: List<String>, block: () -> T): T {
        val indices = keys.map { (it.hashCode() and Int.MAX_VALUE) % locks.size }.distinct().sorted()
        fun locked(index: Int): T = if (index == indices.size) block()
            else synchronized(locks[indices[index]]) { locked(index + 1) }
        return locked(0)
    }

    fun key(target: OrbitalObject, observer: GeoPos, generation: String): String =
        hex(digest(("orekit-passes-4|${target.data}|" +
            "${observer.latitude},${observer.longitude},${observer.altitude}|$generation")
            .toByteArray(Charsets.UTF_8)))

    fun get(key: String, start: Long, end: Long): Entry? {
        return peek(key)?.takeIf { it.start <= start && it.end >= end }
    }

    fun peek(key: String): Entry? = withKey(key) {
        synchronized(memory) { memory[key] } ?: read(key)?.also {
            synchronized(memory) { memory[key] = it }
        }
    }

    fun put(key: String, start: Long, end: Long, passes: List<OrbitalPass>) = withKey(key) {
        val entry = Entry(start, end, passes.map {
            Window(it.aosTime, it.aosAzimuth, it.losTime, it.losAzimuth, it.altitude,
                it.maxElevation, it.hasDecayed, it.startClipped, it.endClipped,
                it.continuousVisibility, it.tcaTime)
        })
        synchronized(memory) { memory[key] = entry }
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@withKey
            val payload = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeUTF(key)
                    output.writeLong(start)
                    output.writeLong(end)
                    output.writeInt(entry.windows.size)
                    entry.windows.forEach { window ->
                        output.writeLong(window.aos)
                        output.writeDouble(window.aosAzimuth)
                        output.writeLong(window.los)
                        output.writeDouble(window.losAzimuth)
                        output.writeInt(window.altitude)
                        output.writeDouble(window.maximum)
                        output.writeBoolean(window.decayed)
                        output.writeBoolean(window.startClipped)
                        output.writeBoolean(window.endClipped)
                        output.writeBoolean(window.continuous)
                        output.writeLong(window.tca)
                    }
                }
            }.toByteArray()
            val temporary = File(directory, "$key.${UUID.randomUUID()}.part")
            try {
                temporary.outputStream().buffered().use { output ->
                    output.write(digest(payload))
                    output.write(payload)
                }
                val destination = File(directory, "$key.cache")
                // Android rename replaces the destination atomically. Never delete the
                // valid file first: failed replacement must leave its coverage intact.
                if (!temporary.renameTo(destination)) throw java.io.IOException("Cache rename failed")
            } finally { temporary.delete() }
            val prune = synchronized(memory) { ++writes % 16 == 0 }
            if (prune) directory.listFiles().orEmpty()
                .filter { it.name.matches(Regex("[a-f0-9]{64}\\.cache")) }
                .sortedByDescending(File::lastModified).drop(128).forEach(File::delete)
        } catch (_: java.io.IOException) {
            // Cache storage is optional; the already computed result remains valid.
        }
        Unit
    }

    private fun read(key: String): Entry? = try {
        val file = File(directory, "$key.cache")
        if (!file.isFile || file.length() !in 32..2_000_000) null else {
            val bytes = file.readBytes()
            val payload = bytes.copyOfRange(32, bytes.size)
            require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readUTF() == key)
                val start = input.readLong()
                val end = input.readLong()
                require(end > start)
                val count = input.readInt()
                require(count in 0..10000)
                val windows = List(count) {
                    Window(input.readLong(), input.readDouble(), input.readLong(), input.readDouble(),
                        input.readInt(), input.readDouble(), input.readBoolean(), input.readBoolean(),
                        input.readBoolean(), input.readBoolean(), input.readLong()).also {
                        require(it.aos >= start && it.los <= end && it.los > it.aos &&
                            it.tca in it.aos..it.los && it.aosAzimuth.isFinite() &&
                            it.losAzimuth.isFinite() && it.maximum.isFinite())
                    }
                }
                require(input.read() == -1)
                Entry(start, end, windows)
            }
        }
    } catch (_: Exception) { null }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
