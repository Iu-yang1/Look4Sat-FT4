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
package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWUploadAudit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview dialog is the only place carrying the callsign-conflict actions (rewrite / use another
 * certificate), so a conflict-only batch must NOT be short-circuited into the plain
 * "can't be uploaded" message: that dialog has no buttons to get out of.
 */
class LoTWUploadMessagesTest {

    @Test
    fun `conflict-only batch asks for the preview even with nothing pending`() {
        assertTrue(audit(pending = 0, mismatch = 107, satellite = 1).needsPreviewForConflicts())
    }

    @Test
    fun `blocked batch without conflicts keeps the plain message`() {
        assertFalse(audit(pending = 0, mismatch = 0, satellite = 3).needsPreviewForConflicts())
    }

    @Test
    fun `batch with something to upload never takes the conflict fallback`() {
        assertFalse(audit(pending = 5, mismatch = 2, satellite = 1).needsPreviewForConflicts())
    }

    private fun audit(pending: Int, mismatch: Int, satellite: Int) = LoTWUploadAudit(
        total = 108,
        pending = pending,
        uploaded = 0,
        unknown = 0,
        unavailable = 108 - pending,
        reasons = buildMap {
            if (mismatch > 0) put(LoTWProblem.CALLSIGN_MISMATCH, mismatch)
            if (satellite > 0) put(LoTWProblem.SATELLITE, satellite)
        }
    )
}
