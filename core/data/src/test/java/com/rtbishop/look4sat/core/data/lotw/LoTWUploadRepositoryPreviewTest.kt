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

import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * previewCertificate parses a .p12 without touching storage — just enough to
 * learn the certificate's DXCC entity, so the settings dialog can offer the
 * entity's Province/State dropdown before the operator confirms the import.
 *
 * Fixture test_tqsl_empty.p12 mirrors a modern TQSL export: PBES2/AES-256,
 * empty password, TQSL subject attribute 1.3.6.1.4.1.12348.1.1 = BA7OPF and
 * extensions .2/.3/.4 = first/last QSO date, DXCC 318 (China).
 */
class LoTWUploadRepositoryPreviewTest {

    private class MemStorage : LoTWStorage {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(name: String): ByteArray? = files[name]
        override fun write(name: String, data: ByteArray) { files[name] = data.copyOf() }
        override fun delete(name: String) { files.remove(name) }
    }

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun repository(storage: MemStorage) = LoTWUploadRepository(
        storage, { error("station config not needed") }, now = { System.currentTimeMillis() }
    )

    @Test
    fun `preview parses TQSL-style certificate without persisting it`() {
        runBlocking {
            val storage = MemStorage()
            val repo = repository(storage)
            val info = repo.previewCertificate(fixture("test_tqsl_empty.p12"), charArrayOf())
            assertEquals("BA7OPF", info.callsign)
            assertEquals(318, info.dxcc)
            assertEquals("2026-01-01", info.firstQsoDate)
            assertTrue("nothing written", storage.files.isEmpty())
            assertNull("no certificate stored", repo.certificate())
        }
    }

    @Test
    fun `wrong password fails as certificate password and wipes the attempt`() {
        runBlocking {
            val storage = MemStorage()
            val repo = repository(storage)
            val password = "definitely-wrong".toCharArray()
            val error = assertThrows(LoTWOperationException::class.java) {
                runBlocking { repo.previewCertificate(fixture("test_pbes2.p12"), password) }
            }
            assertEquals(LoTWProblem.CERTIFICATE_PASSWORD, error.reason)
            assertTrue("password wiped", password.all { it == '\u0000' })
            assertTrue("nothing written", storage.files.isEmpty())
        }
    }
}
