package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.Signature
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.io.encoding.Base64

internal fun certificateFixture(): ByteArray = checkNotNull(LoTWSignerTest::class.java.getResourceAsStream("/lotw/offline-test.p12")).use { it.readBytes() }
internal fun configFixture() = LoTWConfig(File("src/main/assets/lotw/config.tq6").inputStream())
internal const val TEST_NOW = 1_790_000_000_000L
internal fun qsoFixture() = QsoRecord(
    id = 1, startUtcMillis = 1_789_000_000_000L, theirCallsign = "K1ABC", myCallsign = "N0TEST", myGrid = "OL62AB",
    mode = "MFSK", submode = "FT4", txFrequencyHz = 145_900_000L, rxFrequencyHz = 435_800_000L,
    band = "2M", rxBand = "70CM", satelliteName = "AO-123", status = QsoStatus.COMPLETE
)

class LoTWSignerTest {
    private val config = configFixture()
    private val signer = LoTWSigner(config)
    private val key = LoTWKeyMaterial.read(certificateFixture(), "test-only".toCharArray(), TEST_NOW)
    private val station = config.stationFields(LoTWStation("ol62ab", "024", "044", "GD"), 318)

    @Test fun importsTqslCustomOidMetadataAndMatchingPrivateKey() {
        assertEquals("N0TEST", key.info.callsign)
        assertEquals(318, key.info.dxcc)
        assertEquals("2020-01-01", key.info.firstQsoDate)
        assertEquals("2030-12-31", key.info.lastQsoDate)
    }
    @Test fun invalidPasswordIsRejected() {
        assertProblem(LoTWProblem.CERTIFICATE_PASSWORD) { LoTWKeyMaterial.read(certificateFixture(), "wrong".toCharArray(), TEST_NOW) }
    }
    @Test fun stationOrderComesFromOfficialConfigAndNormalizesZones() {
        assertEquals("11.34", config.version)
        assertTrue(config.stationOrder.indexOf("CN_PROVINCE") < config.stationOrder.indexOf("CQZ"))
        assertEquals("24", station["CQZ"])
        assertEquals("44", station["ITUZ"])
    }
    @Test fun tq8HasVerifiableV2SignatureAndConsistentLengths() {
        val contact = signer.contact(qsoFixture(), key, station, TEST_NOW)
        val expected = "GD24OL62AB44" + "2M70CMK1ABC145.9435.8FT4SAT" +
            utc(qsoFixture().startUtcMillis, "yyyy-MM-dd") + utc(qsoFixture().startUtcMillis, "HH:mm:ss'Z'") + "AO-123"
        assertEquals(expected, contact.signData)
        val compressed = signer.sign(listOf(contact), key, station)
        val text = GZIPInputStream(compressed.inputStream()).bufferedReader().use { it.readText() }
        val records = parseLoTWFields("<EOH>" + text).records
        assertEquals(listOf("tCERT", "tSTATION", "tCONTACT"), records.map { it["REC_TYPE"] })
        assertEquals("N0TEST", records[1]["CALL"])
        assertEquals("318", records[1]["DXCC"])
        assertEquals(expected, records[2]["SIGNDATA"])
        val signature = Base64.Default.decode(records[2].getValue("SIGN_LOTW_V2.0").replace("\n", ""))
        assertTrue(Signature.getInstance("SHA1withRSA").run { initVerify(key.certificate); update(expected.toByteArray()); verify(signature) })
        assertFalse(text.contains("PRIVATE KEY"))
        assertFalse(text.contains("test-only"))
    }
    @Test fun fingerprintIgnoresLocalNotesButChangesForSignedFields() {
        val record = qsoFixture()
        fun hash(qso: QsoRecord) = signer.contact(qso, key, station, TEST_NOW).fingerprint
        assertEquals(hash(record), hash(record.copy(comment = "portable", rawMessages = listOf("FT4 detail"))))
        assertNotEquals(hash(record), hash(record.copy(txFrequencyHz = 145_901_000)))
        assertNotEquals(hash(record), signer.contact(record, key, station + ("CQZ" to "23"), TEST_NOW).fingerprint)
    }
    @Test fun validatesCallsignDateGridBandModeAndSatelliteBeforeSigning() {
        val record = qsoFixture()
        listOf(
            LoTWProblem.CALLSIGN_MISMATCH to record.copy(myCallsign = "N0OTHER"),
            LoTWProblem.QSO_DATE to record.copy(startUtcMillis = TEST_NOW + 60_000),
            LoTWProblem.LOCATION_MISMATCH to record.copy(myGrid = "FN31AA"),
            LoTWProblem.BAND to record.copy(band = "70CM"),
            LoTWProblem.MODE to record.copy(mode = "UNKNOWN", submode = ""),
            LoTWProblem.SATELLITE to record.copy(satelliteName = "NOT-A-SATELLITE"),
            LoTWProblem.INVALID_CONTACT to record.copy(status = QsoStatus.DRAFT)
        ).forEach { (problem, qso) -> assertProblem(problem) { signer.contact(qso, key, station, TEST_NOW) } }
    }
    @Test fun validatesStationFieldsAgainstDxccSpecificConfiguration() {
        assertProblem(LoTWProblem.STATION_REGION) { config.stationFields(LoTWStation("OL62", region = "ZZ"), 318) }
        assertProblem(LoTWProblem.STATION_ZONE) { config.stationFields(LoTWStation("OL62", cqZone = "41"), 318) }
        assertProblem(LoTWProblem.STATION_GRID) { config.stationFields(LoTWStation("XX99"), 318) }
        assertProblem(LoTWProblem.STATION_IOTA) { config.stationFields(LoTWStation("OL62", iota = "NONE"), 318) }
    }
    @Test fun derReaderRejectsTruncatedAndIndefiniteLengths() {
        listOf(byteArrayOf(4, 127), byteArrayOf(4, 0x80.toByte()), byteArrayOf(4, 0xff.toByte())).forEach {
            assertThrows(IllegalArgumentException::class.java) { DerValue.read(it) }
        }
    }
    private fun assertProblem(expected: LoTWProblem, block: () -> Unit) {
        assertEquals(expected, assertThrows(LoTWOperationException::class.java) { block() }.reason)
    }
}

private data class LoTWFields(val header: Map<String, String>, val records: List<Map<String, String>>)

/** Minimal length-aware ADIF reader used to verify the generated TQ8 payload. */
private fun parseLoTWFields(content: String): LoTWFields {
    val records = mutableListOf<Map<String, String>>()
    var header: Map<String, String>? = null
    var values = linkedMapOf<String, String>()
    var index = 0
    while (index < content.length) {
        val start = content.indexOf('<', index)
        if (start < 0) break
        val end = content.indexOf('>', start + 1)
        require(end >= 0)
        val parts = content.substring(start + 1, end).split(':')
        val name = parts.first().trim().uppercase(Locale.US)
        index = end + 1
        when (name) {
            "EOH" -> {
                require(header == null && records.isEmpty())
                header = values.toMap()
                values = linkedMapOf()
            }
            "EOR" -> {
                require(header != null && values.isNotEmpty())
                records += values.toMap()
                values = linkedMapOf()
            }
            "APP_LOTW_EOF" -> {
                require(values.isEmpty())
                val size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                require(size in 0..(content.length - index))
                index += size
                require(content.substring(index).isBlank())
            }
            else -> {
                val size = parts.getOrNull(1)?.toIntOrNull() ?: error("Missing field length")
                require(size in 0..(content.length - index))
                values[name] = content.substring(index, index + size)
                index += size
            }
        }
    }
    require(header != null && values.isEmpty())
    return LoTWFields(header, records)
}
