package com.rtbishop.look4sat.core.domain.logbook

import java.util.Locale
import kotlin.math.abs

/** LoTW official name vs SatNOGS name ("SO-50 (SaudiOSCAR 50)"): compare the
 *  parenthetical-free prefix so local records and downloaded confirmations match. */
fun satMatchKey(name: String): String = name.substringBefore('(').trim().uppercase(Locale.US)

/** LoTW omits frequency and may round time to a minute. Only merge an unambiguous contact. */
fun sameConfirmedContact(local: QsoRecord, remote: QsoRecord): Boolean =
    local.theirCallsign.trim().equals(remote.theirCallsign.trim(), true) &&
        (local.myCallsign.isBlank() || remote.myCallsign.isBlank() || local.myCallsign.equals(remote.myCallsign, true)) &&
        satMatchKey(local.satelliteName) == satMatchKey(remote.satelliteName) &&
        local.isSatellite == remote.isSatellite &&
        local.displayMode == remote.displayMode &&
        (local.band.isBlank() || remote.band.isBlank() || local.band.equals(remote.band, true)) &&
        abs(local.startUtcMillis - remote.startUtcMillis) < 60_000L

fun QsoRecord.confirmationLookupKey(): String = listOf(
    theirCallsign.trim().uppercase(Locale.US), satMatchKey(satelliteName), displayMode
).joinToString("|")

fun QsoRecord.withConfirmation(confirmed: QsoRecord): QsoRecord = copy(
    myCallsign = myCallsign.ifBlank { confirmed.myCallsign },
    theirGrid = confirmed.theirGrid.ifBlank { theirGrid },
    myGrid = myGrid.ifBlank { confirmed.myGrid },
    sentReport = sentReport.ifBlank { confirmed.sentReport },
    receivedReport = receivedReport.ifBlank { confirmed.receivedReport },
    txFrequencyHz = txFrequencyHz ?: confirmed.txFrequencyHz,
    rxFrequencyHz = rxFrequencyHz ?: confirmed.rxFrequencyHz,
    band = band.ifBlank { confirmed.band },
    rxBand = rxBand.ifBlank { confirmed.rxBand },
    propagationMode = propagationMode.ifBlank { confirmed.propagationMode },
    status = QsoStatus.COMPLETE,
    lotwConfirmed = lotwConfirmed || confirmed.lotwConfirmed,
    lotwReceived = lotwReceived || confirmed.lotwReceived || confirmed.lotwConfirmed,
    lotwQslDate = confirmed.lotwQslDate.ifBlank { lotwQslDate },
    vuccGrids = confirmed.vuccGrids.ifEmpty { vuccGrids },
    dxcc = confirmed.dxcc ?: dxcc,
    country = confirmed.country.ifBlank { country },
    cqZone = confirmed.cqZone ?: cqZone,
    region = confirmed.region.ifBlank { region }
)

val QsoRecord.displayMode: String
    get() = submode.ifBlank { mode }.trim().uppercase(Locale.US)

val QsoRecord.isSatellite: Boolean
    get() = propagationMode.equals("SAT", true) || (propagationMode.isBlank() && satelliteName.isNotBlank())

fun frequencyBand(hz: Long?): String = when (hz) {
    null -> ""
    in 28_000_000L..29_700_000L -> "10M"
    in 50_000_000L..54_000_000L -> "6M"
    in 144_000_000L..148_000_000L -> "2M"
    in 219_000_000L..225_000_000L -> "1.25M"
    in 420_000_000L..450_000_000L -> "70CM"
    in 902_000_000L..928_000_000L -> "33CM"
    in 1_240_000_000L..1_300_000_000L -> "23CM"
    in 2_300_000_000L..2_450_000_000L -> "13CM"
    in 5_650_000_000L..5_925_000_000L -> "6CM"
    in 10_000_000_000L..10_500_000_000L -> "3CM"
    else -> ""
}
