package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.QsoStatus
import com.rtbishop.look4sat.core.domain.logbook.isSatellite
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.Signature
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64

internal data class LoTWContact(val record: QsoRecord, val fields: Map<String, String>, val signData: String, val fingerprint: String)

internal class LoTWSigner(private val config: LoTWConfig) {
    fun contact(record: QsoRecord, key: LoTWKeyMaterial, station: Map<String, String>, now: Long): LoTWContact {
        val call = record.theirCallsign.trim().uppercase(Locale.US)
        if (record.status != QsoStatus.COMPLETE || !call.matches(Regex("[A-Z0-9]+(/[A-Z0-9]+)*")) ||
            !call.any(Char::isLetter) || !call.any(Char::isDigit)) fail(LoTWProblem.INVALID_CONTACT, call)
        if (!record.myCallsign.trim().equals(key.info.callsign, true)) fail(LoTWProblem.CALLSIGN_MISMATCH, call)
        val date = utc(record.startUtcMillis, "yyyy-MM-dd")
        if (date < key.info.firstQsoDate || (key.info.lastQsoDate.isNotBlank() && date > key.info.lastQsoDate) || record.startUtcMillis > now) {
            fail(LoTWProblem.QSO_DATE, call)
        }
        val grids = buildList {
            addAll(station.getValue("GRIDSQUARE").split(',').map(String::trim))
            station["MY_VUCC_GRIDS"]?.split(',')?.map(String::trim)?.let(::addAll)
        }.filter(String::isNotBlank)
        if (record.myGrid.isNotBlank() && grids.none { grid ->
            val local = record.myGrid.trim().uppercase(Locale.US)
            grid.startsWith(local) || local.startsWith(grid)
        }) fail(LoTWProblem.LOCATION_MISMATCH, call)
        fun mhz(hz: Long?): String = hz?.let { BigDecimal.valueOf(it, 6).stripTrailingZeros().toPlainString() }.orEmpty()
        val fields = linkedMapOf(
            "BAND" to config.band(record.band, record.txFrequencyHz, true),
            "BAND_RX" to config.band(record.rxBand, record.rxFrequencyHz, false),
            "CALL" to call,
            "FREQ" to mhz(record.txFrequencyHz),
            "FREQ_RX" to mhz(record.rxFrequencyHz),
            "MODE" to config.mode(record),
            "PROP_MODE" to if (record.isSatellite) "SAT" else config.propagation(record.propagationMode),
            "QSO_DATE" to date,
            "QSO_TIME" to utc(record.startUtcMillis, "HH:mm:ss'Z'"),
            "SAT_NAME" to if (record.isSatellite) config.satellite(record.satelliteName, date) else ""
        ).filterValues { it.isNotBlank() }
        val signData = (config.stationOrder.map { station[it].orEmpty() } + config.contactOrder.map { fields[it].orEmpty() }).joinToString("")
        val identity = field("CALL", key.info.callsign) + field("DXCC", key.info.dxcc.toString()) +
            station.toSortedMap().entries.joinToString("") { field(it.key, it.value) } +
            fields.entries.joinToString("") { field(it.key, it.value) }
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return LoTWContact(record, fields, signData, hash)
    }

    fun sign(contacts: List<LoTWContact>, key: LoTWKeyMaterial, station: Map<String, String>): ByteArray {
        val text = buildString {
            append(field("TQSL_IDENT", "Look4Sat LoTW Config: V${config.version} AllowDupes: false"))
            append(field("REC_TYPE", "tCERT")); append(field("CERT_UID", "1"))
            append(field("CERTIFICATE", Base64.Default.encode(key.certificate.encoded).chunked(64).joinToString("\n")))
            append("<eor>\n")
            append(field("REC_TYPE", "tSTATION")); append(field("STATION_UID", "1")); append(field("CERT_UID", "1"))
            append(field("CALL", key.info.callsign)); append(field("DXCC", key.info.dxcc.toString()))
            station.forEach { (name, value) -> append(field(name, value)) }
            append("<eor>\n")
            contacts.forEach { contact ->
                append(field("REC_TYPE", "tCONTACT")); append(field("STATION_UID", "1"))
                contact.fields.forEach { (name, value) -> append(field(name, value)) }
                // SHA-1 is required by the LoTW V2.0 wire format, not chosen for general-purpose signing.
                val signature = Signature.getInstance("SHA1withRSA").run {
                    initSign(key.key); update(contact.signData.toByteArray(Charsets.UTF_8)); sign()
                }
                val encoded = Base64.Default.encode(signature).chunked(64).joinToString("\n")
                append("<SIGN_LOTW_V2.0:${encoded.length}:6>$encoded\n")
                append(field("SIGNDATA", contact.signData)); append("<eor>\n")
            }
        }
        return ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
    }
}

private fun field(name: String, value: String): String = "<$name:${value.toByteArray(Charsets.UTF_8).size}>$value\n"
