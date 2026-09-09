package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import java.util.Locale

enum class QuickLogError { CALLSIGN, REPORT, SATELLITE, STORAGE }

fun quickLogValidation(call: String, sent: String, received: String, mode: String): QuickLogError? {
    val normalized = call.trim().uppercase(Locale.US)
    if (!normalized.matches(Regex("[A-Z0-9]+(/[A-Z0-9]+)*")) ||
        !normalized.any(Char::isLetter) || !normalized.any(Char::isDigit)) return QuickLogError.CALLSIGN
    val pattern = if (mode == "CW") Regex("[1-5][1-9][1-9]") else Regex("[1-5][1-9](\\+[0-9]{1,2})?")
    return if (pattern.matches(sent.trim()) && pattern.matches(received.trim())) null else QuickLogError.REPORT
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
    transponder: SatRadio? = null
): QsoRecord {
    require(mode in listOf("CW", "SSB", "FM"))
    require(quickLogValidation(callsign, sent, received, mode) == null)
    val tracking = radio.takeIf { it.isActive && it.currentPass?.catNum == pass.catNum }
    val channel = tracking?.selectedTransponder ?: transponder
    // A transponder passband edge is not the operator's tuned frequency.
    val fixedUplink = channel?.uplinkLow?.takeIf { channel.uplinkHigh == null || channel.uplinkHigh == it }
    val fixedDownlink = channel?.downlinkLow?.takeIf { channel.downlinkHigh == null || channel.downlinkHigh == it }
    val tx = tracking?.txFrequencyHz ?: fixedUplink
    val rx = tracking?.rxFrequencyHz ?: fixedDownlink
    return QsoRecord(
        startUtcMillis = now,
        endUtcMillis = now,
        theirCallsign = callsign.trim().uppercase(Locale.US),
        myCallsign = myCallsign.trim().uppercase(Locale.US),
        myGrid = myGrid,
        sentReport = sent.trim(),
        receivedReport = received.trim(),
        txFrequencyHz = tx,
        rxFrequencyHz = rx,
        band = frequencyBand(tx ?: channel?.uplinkLow),
        rxBand = frequencyBand(rx ?: channel?.downlinkLow),
        mode = mode,
        submode = "",
        satelliteName = pass.name,
        transponderName = channel?.info.orEmpty(),
        passAosUtcMillis = pass.aosTime,
        propagationMode = "SAT",
        status = QsoStatus.COMPLETE
    )
}
