/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
