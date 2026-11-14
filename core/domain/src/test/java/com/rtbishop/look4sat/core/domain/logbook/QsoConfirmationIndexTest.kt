package com.rtbishop.look4sat.core.domain.logbook

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class QsoConfirmationIndexTest {
    @Test fun fixedSeedCorporaRetainTraversalAmbiguityAndContentPriority() {
        repeat(100) { seed ->
            val random = Random(seed)
            val records = List(120) { index ->
                QsoRecord(id = index.toLong(), startUtcMillis = 1794614400000L +
                    random.nextLong(-120000, 120000), theirCallsign = listOf("JA1ABC", "ja1abc", " W1AW ")[random.nextInt(3)],
                    myCallsign = listOf("", "BA7OPF", "ba7opf", "W1AW")[random.nextInt(4)],
                    satelliteName = listOf("SAUDISAT 1C", "SO-50", "JAS 2", "FO-29", "OBJECT XY", "")[random.nextInt(6)],
                    mode = listOf("FT4", "MFSK", "FM")[random.nextInt(3)],
                    lotwConfirmed = random.nextBoolean(),
                    band = listOf("", "2M", "70CM")[random.nextInt(3)],
                    rxBand = listOf("", "2M", "70CM")[random.nextInt(3)],
                    myGrid = if (random.nextBoolean()) "OL62" else "",
                    comment = if (random.nextBoolean()) "Portable" else "",
                    txFrequencyHz = if (random.nextBoolean()) 145900000 else null)
            }
            assertEquals("seed=$seed", reference(records), splitConfirmationPairs(records))
        }
    }

    @Test fun adjacentMinuteAndStrictSixtySecondBoundaryMatchReference() {
        val local = QsoRecord(startUtcMillis = 59999, theirCallsign = "JA1ABC",
            myCallsign = "", satelliteName = "SO-50", band = "2M", rxBand = "70CM")
        val records = listOf(local, local.copy(startUtcMillis = 60001, lotwConfirmed = true,
            myCallsign = "BA7OPF", band = "70CM", rxBand = "2M"),
            local.copy(startUtcMillis = 119999, lotwConfirmed = true))
        assertEquals(reference(records), splitConfirmationPairs(records))
    }

    /** Pre-optimization traversal, kept in tests as an independent indexing oracle. */
    private fun reference(records: List<QsoRecord>): List<SplitConfirmationPair> {
        val taken = mutableSetOf<Int>()
        val pairs = mutableListOf<SplitConfirmationPair>()
        val pending = records.indices.filter { !records[it].lotwConfirmed }
        records.indices.filter { records[it].lotwConfirmed }.forEach { confirmation ->
            val local = pending.firstOrNull { it !in taken &&
                sameContactIdentity(records[it], records[confirmation]) &&
                bandsMatchOrMirror(records[it], records[confirmation]) } ?: return@forEach
            taken += local
            taken += confirmation
            pairs += SplitConfirmationPair(local, confirmation)
        }
        val confirmed = records.indices.filter { records[it].lotwConfirmed && it !in taken }
        confirmed.forEach { index ->
            if (index in taken) return@forEach
            val cluster = mutableListOf(index)
            confirmed.forEach { candidate ->
                if (candidate != index && candidate !in taken && cluster.all {
                    sameContactIdentity(records[it], records[candidate]) &&
                        bandsMatchOrMirror(records[it], records[candidate])
                }) cluster += candidate
            }
            if (cluster.size < 2) return@forEach
            taken.addAll(cluster)
            val keep = cluster.maxBy { contentScore(records[it]) }
            cluster.filter { it != keep }.forEach { pairs += SplitConfirmationPair(keep, it) }
        }
        return pairs
    }
}
