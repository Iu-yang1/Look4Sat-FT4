/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

/**
 * The records a resubmit actually re-signs: whatever the operator checked, complete rows only.
 *
 * Unlike a normal upload this deliberately keeps already-uploaded and confirmed records —
 * the point is to send them again under a corrected station location, and LoTW treats an
 * identical contact (call/band/mode/time/satellite) as an update of the existing record.
 */
fun resubmitCandidates(records: List<QsoRecord>, selectedIds: Set<Long>): List<QsoRecord> =
    records.filter { it.id in selectedIds && it.status == QsoStatus.COMPLETE }
