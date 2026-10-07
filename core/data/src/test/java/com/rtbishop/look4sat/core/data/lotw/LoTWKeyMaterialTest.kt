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
import java.io.InputStream
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Certificate import must not depend on the platform PKCS12 parser. Android's parser accepts a
 * modern TQSL PBES2 export but can expose no key entry for it (observed with an empty-password
 * backup, which was then rejected as "not a valid LoTW certificate file" although every field of
 * the certificate is readable). These tests pin the fallback to our own PBES2 reader.
 */
class LoTWKeyMaterialTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    /** Stands in for a platform that parsed the container but offers no private-key entry. */
    private fun storeWithoutKeyEntry(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null as InputStream?, null as CharArray?)
    }

    private val now = System.currentTimeMillis()

    /** TQSL-style backup (empty password) imported although the platform delivers no key entry. */
    @Test
    fun importsCertificateWhenPlatformExposesNoKeyEntry() {
        val material = LoTWKeyMaterial.read(
            bytes = fixture("test_tqsl_empty.p12"),
            password = charArrayOf(),
            now = now,
            platformStore = { _, _ -> storeWithoutKeyEntry() },
        )
        assertEquals("BA7OPF", material.info.callsign)
        assertEquals(318, material.info.dxcc)
        assertEquals("2026-01-01", material.info.firstQsoDate)
    }

    /** The same backup still imports when the platform is the one that resolves the key entry. */
    @Test
    fun importsCertificateThroughPlatformWhenAvailable() {
        val material = LoTWKeyMaterial.read(fixture("test_tqsl_empty.p12"), charArrayOf(), now)
        assertEquals("BA7OPF", material.info.callsign)
    }

    /** A wrong password is still reported as a password problem, not as an invalid file. */
    @Test
    fun reportsWrongPasswordForPbes2Backup() {
        val error = assertThrows(LoTWOperationException::class.java) {
            LoTWKeyMaterial.read(fixture("test_tqsl_empty.p12"), "wrong-password".toCharArray(), now)
        }
        assertEquals(LoTWProblem.CERTIFICATE_PASSWORD, error.reason)
    }

    /** Something that is not a PKCS12 at all keeps the message the platform path produced. */
    @Test
    fun refusesNonPkcs12File() {
        val error = assertThrows(LoTWOperationException::class.java) {
            LoTWKeyMaterial.read("not a p12 at all".toByteArray(), "x".toCharArray(), now) { _, _ -> null }
        }
        assertEquals(LoTWProblem.CERTIFICATE_PASSWORD, error.reason)
    }

    /** Oversized input is rejected before any parser runs. */
    @Test
    fun refusesOversizedInput() {
        val bytes = ByteArray(MAX_CERTIFICATE_BYTES + 1)
        val error = assertThrows(LoTWOperationException::class.java) {
            LoTWKeyMaterial.read(bytes, charArrayOf(), now) { _, _ -> storeWithoutKeyEntry() }
        }
        assertEquals(LoTWProblem.CERTIFICATE_INVALID, error.reason)
    }
}
