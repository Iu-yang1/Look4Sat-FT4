package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import java.util.Locale

enum class QuickLogError { CALLSIGN, GRID, REPORT, FREQUENCY, UTC, SATELLITE, STORAGE }

data class QuickQsoDetails(
    val startUtcMillis: Long? = null,
    val theirGrid: String = "",
    val txFrequencyHz: Long? = null,
    val rxFrequencyHz: Long? = null,
    val satelliteMode: String = "",
    val comment: String = "",
    val status: QsoStatus = QsoStatus.COMPLETE
)

fun quickLogValidation(call: String, sent: String, received: String, mode: String): QuickLogError? {
    val normalized = call.trim().uppercase(Locale.US)
    if (!normalized.matches(Regex("[A-Z0-9]+(/[A-Z0-9]+)*")) ||
        !normalized.any(Char::isLetter) || !normalized.any(Char::isDigit)) return QuickLogError.CALLSIGN
    val reports = listOf(sent.trim(), received.trim())
    val valid = when (mode.uppercase(Locale.US)) {
        "CW", "RTTY" -> reports.all { Regex("[1-5][1-9][1-9]").matches(it) }
        "FT4", "FT8" -> reports.all { report ->
            Regex("[+-]?[0-9]{1,2}").matches(report) &&
                report.toIntOrNull()?.let { it in -50..49 } == true
        }
        else -> reports.all { Regex("[1-5][1-9](\\+[0-9]{1,2})?").matches(it) }
    }
    return if (valid) null else QuickLogError.REPORT
}

fun quickQsoRecord(
    now: Long,
    pass: OrbitalPass,
    callsign: String,
    sent: String,
    received: String,
    mode: String,
    myCallsign: String,
    myGrid: String,
    radio: RadioTrackingState,
    transponder: SatRadio? = null,
    details: QuickQsoDetails = QuickQsoDetails()
): QsoRecord {
    val normalizedMode = mode.uppercase(Locale.US)
    require(normalizedMode in listOf("CW", "SSB", "FM", "AM", "FT4", "FT8", "RTTY"))
    require(quickLogValidation(callsign, sent, received, mode) == null)
    val tracking = radio.takeIf { it.isActive && it.currentPass?.catNum == pass.catNum }
    val channel = transponder ?: tracking?.selectedTransponder
    // A transponder passband edge is not the operator's tuned frequency.
    val fixedUplink = channel?.uplinkLow?.takeIf { channel.uplinkHigh == null || channel.uplinkHigh == it }
    val fixedDownlink = channel?.downlinkLow?.takeIf { channel.downlinkHigh == null || channel.downlinkHigh == it }
    val tx = details.txFrequencyHz ?: tracking?.txFrequencyHz ?: fixedUplink
    val rx = details.rxFrequencyHz ?: tracking?.rxFrequencyHz ?: fixedDownlink
    val started = details.startUtcMillis ?: now
    val isDigitalWeakSignal = normalizedMode == "FT4" || normalizedMode == "FT8"
    return QsoRecord(
        startUtcMillis = started,
        endUtcMillis = started.takeIf { details.status == QsoStatus.COMPLETE },
        theirCallsign = callsign.trim().uppercase(Locale.US),
        myCallsign = myCallsign.trim().uppercase(Locale.US),
        theirGrid = details.theirGrid.trim().uppercase(Locale.US),
        myGrid = myGrid.trim().uppercase(Locale.US),
        sentReport = sent.trim(),
        receivedReport = received.trim(),
        txFrequencyHz = tx,
        rxFrequencyHz = rx,
        band = frequencyBand(tx ?: channel?.uplinkLow),
        rxBand = frequencyBand(rx ?: channel?.downlinkLow),
        mode = if (isDigitalWeakSignal) "MFSK" else normalizedMode,
        submode = normalizedMode.takeIf { isDigitalWeakSignal }.orEmpty(),
        satelliteName = pass.name,
        transponderName = channel?.info.orEmpty(),
        satelliteMode = details.satelliteMode.ifBlank {
            listOfNotNull(channel?.uplinkMode, channel?.downlinkMode)
                .filter(String::isNotBlank)
                .joinToString("/")
        },
        passAosUtcMillis = pass.aosTime,
        propagationMode = "SAT",
        status = details.status,
        comment = details.comment.trim()
    )
}
