package com.rtbishop.look4sat.core.data.astro

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded display-only cache. No clock, display name or location timestamp enters its key. */
internal class RadarTrackCache(private val maximumPoints: Int = 24_000) {
    private data class Key(val satellite: OrbitalObject, val observer: GeoPos, val start: Long, val end: Long)
    private val entries = LinkedHashMap<Key, List<OrbitalPos>>(32, .75f, true)
    private val locks = Array(32) { Mutex() }
    private var pointCount = 0

    suspend fun get(
        satellite: OrbitalObject, observer: GeoPos, start: Long, end: Long,
        calculate: suspend () -> List<OrbitalPos>
    ): Pair<List<OrbitalPos>, Boolean> {
        val key = Key(satellite, GeoPos(observer.latitude, observer.longitude, observer.altitude), start, end)
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            synchronized(entries) { entries[key] }?.let { return@withLock it to true }
            val result = calculate()
            if (result.isNotEmpty() && result.size <= maximumPoints) synchronized(entries) {
                entries[key] = result
                pointCount += result.size
                while (pointCount > maximumPoints || entries.size > 128) {
                    val oldest = entries.entries.iterator()
                    pointCount -= oldest.next().value.size
                    oldest.remove()
                }
            }
            result to false
        }
    }
}
