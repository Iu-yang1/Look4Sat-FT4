package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.model.GridQso

/** Bridge a LoTW report QSO (confirmed, download side) into a logbook record
 *  so the logbook can show downloaded confirmations and mark local uploads
 *  as confirmed (matched by [sameConfirmedContact]). */
fun GridQso.toConfirmedRecord(accountCallsign: String): QsoRecord = QsoRecord(
    startUtcMillis = epochMs,
    theirCallsign = call,
    myCallsign = accountCallsign.trim().uppercase(),
    myGrid = (myGrids.firstOrNull() ?: myGrid).orEmpty(),
    vuccGrids = myGrids.toList(),
    band = bandUp,
    rxBand = bandDown,
    mode = mode,
    // On satellites, MODE=MFSK means FT4 (ADIF pairing); keep the sub-mode so
    // confirmations match local FT4 records (mode=MFSK, submode=FT4).
    submode = if (mode.equals("MFSK", true)) "FT4" else "",
    satelliteName = satName,
    propagationMode = "SAT",
    status = QsoStatus.COMPLETE,
    lotwConfirmed = true,
    dxcc = dxcc,
    country = country.orEmpty(),
    cqZone = cqz,
    region = state.orEmpty()
)
