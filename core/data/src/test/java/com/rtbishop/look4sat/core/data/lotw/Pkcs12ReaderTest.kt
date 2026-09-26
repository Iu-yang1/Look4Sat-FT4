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
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the PBES2/AES-256-CBC PKCS12 reader against a fixture generated with
 * `openssl pkcs12 -export -certpbe NONE` (plain certificate bags plus a
 * PBES2-shrouded private key), which Android's legacy Bouncy Castle parser
 * cannot read.
 */
class Pkcs12ReaderTest {

    private fun fixture(name: String = "test_pbes2.p12"): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Test
    fun readsPbes2KeyAndCertificate() {
        val (key, cert) = Pkcs12Reader.read(fixture(), "testpass123".toCharArray())
        assertEquals("RSA", key.algorithm)
        assertEquals("RSA", cert.publicKey.algorithm)
        assertNotNull(cert.subjectX500Principal)
    }

    @Test
    fun keyPairsWithCertificate() {
        val (key, cert) = Pkcs12Reader.read(fixture(), "testpass123".toCharArray())
        val challenge = "Look4Sat LoTW certificate key check".toByteArray(Charsets.US_ASCII)
        val signed = Signature.getInstance("SHA1withRSA").run {
            initSign(key); update(challenge); sign()
        }
        val valid = Signature.getInstance("SHA1withRSA").run {
            initVerify(cert); update(challenge); verify(signed)
        }
        assertTrue("private key must match certificate", valid)
    }

    @Test
    fun readsEncryptedCertificateSafeContents() {
        val (key, cert) = Pkcs12Reader.read(
            fixture("test_pbes2_encrypted.p12"),
            "testpass123".toCharArray()
        )
        assertEquals("RSA", key.algorithm)
        assertEquals("RSA", cert.publicKey.algorithm)
    }

    @Test
    fun wrongPasswordThrows() {
        assertThrows(Exception::class.java) {
            Pkcs12Reader.read(fixture(), "wrongpass".toCharArray())
        }
    }

    @Test
    fun wrongPbes2PasswordIsReportedAsPasswordProblem() {
        val error = assertThrows(LoTWOperationException::class.java) {
            LoTWKeyMaterial.read(fixture(), "wrongpass".toCharArray(), TEST_NOW)
        }
        assertEquals(LoTWProblem.CERTIFICATE_PASSWORD, error.reason)
    }

    @Test
    fun garbageBytesThrows() {
        assertThrows(Exception::class.java) {
            Pkcs12Reader.read("not a p12 file at all".toByteArray(), "x".toCharArray())
        }
    }

    @Test
    fun android7Sha256FallbackMatchesKnownVector() {
        val expected = "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(
            expected,
            pbkdf2HmacSha256("password".toByteArray(), "salt".toByteArray(), 1, 32)
        )
    }
}
