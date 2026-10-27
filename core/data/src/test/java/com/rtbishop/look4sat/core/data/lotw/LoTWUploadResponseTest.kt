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
package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.repository.LoTWUploadResult
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoTWUploadResponseTest {

    @Test
    fun `accepted marker is definitive`() {
        val result = parseLoTWUploadResponse("<html><!-- .UPL. accepted --></html>", 3)
        assertEquals(LoTWUploadResult.Accepted(3), result)
    }

    @Test
    fun `rejected marker keeps server reason`() {
        val result = parseLoTWUploadResponse(
            "<!-- .UPL. rejected --><!-- .UPLMESSAGE. Invalid QSO date -->",
            1
        )
        assertTrue(result is LoTWUploadResult.Rejected)
        assertEquals("Invalid QSO date", (result as LoTWUploadResult.Rejected).message)
    }

    @Test
    fun `unrecognized response is unknown and summary is sanitized`() {
        val result = parseLoTWUploadResponse(
            "<html><script>do not expose this</script><p>Unexpected gateway page</p></html>",
            1
        )
        assertTrue(result is LoTWUploadResult.Unknown)
        assertEquals("Unexpected gateway page", (result as LoTWUploadResult.Unknown).responseSummary)
    }

    @Test
    fun `failure before request headers is safe to retry`() {
        val result = classifyLoTWUploadFailure(false, IOException("DNS lookup failed"))
        assertTrue(result is LoTWUploadResult.NotSent)
    }

    @Test
    fun `failure after request headers remains unknown`() {
        val result = classifyLoTWUploadFailure(true, IOException("read timed out"))
        assertTrue(result is LoTWUploadResult.Unknown)
    }
}
