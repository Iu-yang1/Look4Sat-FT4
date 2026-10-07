/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import kotlin.math.abs

/** Nominal uplink frequency: the middle of the passband when one is published. */
fun SatRadio.uplinkHz(): Long? = uplinkLow?.let { low -> uplinkHigh?.let { high -> (low + high) / 2 } ?: low }

/** Nominal downlink frequency: the middle of the passband when one is published. */
fun SatRadio.downlinkHz(): Long? = downlinkLow?.let { low -> downlinkHigh?.let { high -> (low + high) / 2 } ?: low }

/**
 * The FM voice repeater of a satellite, if it has one.
 *
 * A voice repeater is the single-frequency FM transceiver the operator actually
 * talks through (SatNOGS type "Transceiver", e.g. SO-50 "Mode V/U FM Voice
 * CTCSS 67.0 Hz"). Beacons and telemetry carry no uplink, so requiring BOTH an
 * uplink and a downlink already filters them out; the FM mode check removes
 * AFSK/APRS and linear entries.
 *
 * Ranking when several FM duplex entries exist (ISS is the awkward case, it also
 * lists crew V/V channels):
 *  1. entries described as a repeater / voice channel,
 *  2. amateur service entries,
 *  3. cross-band pairs (a V/U repeater over a same-band V/V downlink).
 */
fun List<SatRadio>.voiceRepeater(): SatRadio? = asSequence()
    .filter { it.isAlive }
    .filter { it.uplinkHz() != null && it.downlinkHz() != null }
    .filter { it.uplinkMode.equals("FM", true) || it.downlinkMode.equals("FM", true) }
    .sortedWith(
        compareByDescending<SatRadio> { it.info.contains("repeater", true) || it.info.contains("voice", true) }
            .thenByDescending { it.service.equals("Amateur", true) }
            .thenByDescending { abs((it.downlinkHz() ?: 0L) - (it.uplinkHz() ?: 0L)) }
    )
    .firstOrNull()
