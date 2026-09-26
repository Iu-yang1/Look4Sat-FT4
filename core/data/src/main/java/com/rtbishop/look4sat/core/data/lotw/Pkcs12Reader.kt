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

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal PKCS#12 reader for the PBES2/AES-CBC format that OpenSSL 3 and modern
 * TQSL produce by default. Android's legacy bundled Bouncy Castle PKCS12 parser
 * cannot handle PBES2, so we parse the DER structure ourselves and decrypt with
 * the platform JCE. Android 7 does not expose PBKDF2WithHmacSHA256 through
 * SecretKeyFactory, so that primitive has an HMAC-based compatibility fallback.
 *
 * Handles both layouts:
 *  - OpenSSL `-certpbe NONE`: plaintext certificate bags + a PBES2-shrouded key
 *    inside a plaintext `data` ContentInfo.
 *  - Modern TQSL: the certificate SafeContents wrapped in an `encryptedData`
 *    ContentInfo (PBES2), plus the PBES2-shrouded key in a plaintext `data`
 *    ContentInfo.
 */
internal object Pkcs12Reader {

    private val OID_DATA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x01)
    private val OID_ENCRYPTED_DATA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x06)
    private val OID_PKCS8_SHROUDED_KEY_BAG = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x0c, 0x0a, 0x01, 0x02)
    private val OID_CERT_BAG = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x0c, 0x0a, 0x01, 0x03)
    private val OID_X509_CERT = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x09, 0x16, 0x01)
    private val OID_PBES2 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x05, 0x0d)
    private val OID_PBKDF2 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x05, 0x0c)
    private val OID_HMAC_SHA1 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x02, 0x07)
    private val OID_HMAC_SHA256 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x02, 0x09)
    private val OID_AES_256_CBC = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x2a)
    private val OID_AES_128_CBC = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x02)

    fun read(bytes: ByteArray, password: CharArray): Pair<PrivateKey, X509Certificate> {
        val pfx = DerReader.read(bytes)
        require(pfx.tag == 0x30) { "not a PFX" }
        val pfxChildren = DerReader.children(pfx.content)
        require(pfxChildren.size >= 2) { "PFX too small" }
        val authSafeCi = DerReader.children(pfxChildren[1].content)
        require(authSafeCi.size >= 2) { "authSafe malformed" }
        val explicit = DerReader.children(authSafeCi[1].content).first()
        val octet = DerReader.read(explicit.content)
        var privateKey: PrivateKey? = null
        val certs = mutableListOf<X509Certificate>()
        for (info in DerReader.children(octet.content)) {
            val parts = DerReader.children(info.content)
            if (parts.isEmpty()) continue
            when {
                parts[0].content.contentEquals(OID_DATA) ->
                    parseSafeContentsContent(parts, password, certs) { privateKey = it }
                parts[0].content.contentEquals(OID_ENCRYPTED_DATA) ->
                    parseEncryptedData(parts, password, certs)
            }
        }
        val key = privateKey ?: error("no private key bag found")
        require(certs.isNotEmpty()) { "no certificate bag found" }
        return key to certs[0]
    }

    private fun parseSafeContentsContent(
        parts: List<Der>,
        password: CharArray,
        certs: MutableList<X509Certificate>,
        onKey: (PrivateKey) -> Unit
    ) {
        val explicit = DerReader.children(parts[1].content).first()
        parseSafeBags(explicit.content, password, certs, onKey)
    }

    private fun parseEncryptedData(
        parts: List<Der>,
        password: CharArray,
        certs: MutableList<X509Certificate>
    ) {
        val explicit = DerReader.children(parts[1].content).first()
        val encryptedDataParts = DerReader.children(explicit.content)
        require(encryptedDataParts.size >= 2) { "EncryptedData malformed" }
        val encryptedContentInfo = DerReader.children(encryptedDataParts[1].content)
        require(encryptedContentInfo.size >= 3) { "EncryptedContentInfo malformed" }
        val safeContents = decryptPbes2(encryptedContentInfo[1], encryptedContentInfo[2].content, password)
        parseSafeBags(safeContents, password, certs) {}
    }

    private fun decryptPbes2(algorithm: Der, encrypted: ByteArray, password: CharArray): ByteArray {
        val algorithmParts = DerReader.children(algorithm.content)
        require(algorithmParts.size >= 2 && algorithmParts[0].content.contentEquals(OID_PBES2)) { "not PBES2" }
        val pbes2 = DerReader.children(algorithmParts[1].content)
        require(pbes2.size == 2) { "PBES2 parameters malformed" }
        val kdf = DerReader.children(pbes2[0].content)
        require(kdf.size == 2 && kdf[0].content.contentEquals(OID_PBKDF2)) { "unsupported KDF" }
        val kdfParams = DerReader.children(kdf[1].content)
        require(kdfParams.size >= 2) { "PBKDF2 parameters malformed" }
        val salt = kdfParams[0].content
        val iterations = readInt(kdfParams[1].content)
        require(iterations in 1..10_000_000) { "implausible PBKDF2 iteration count" }

        var keyLengthBytes: Int? = null
        var prfName = "PBKDF2WithHmacSHA1"
        for (parameter in kdfParams.drop(2)) {
            when (parameter.tag) {
                0x02 -> keyLengthBytes = readInt(parameter.content)
                0x30 -> {
                    val prf = DerReader.children(parameter.content)
                    prfName = when {
                        prf.isNotEmpty() && prf[0].content.contentEquals(OID_HMAC_SHA1) -> "PBKDF2WithHmacSHA1"
                        prf.isNotEmpty() && prf[0].content.contentEquals(OID_HMAC_SHA256) -> "PBKDF2WithHmacSHA256"
                        else -> error("unsupported PBKDF2 PRF")
                    }
                }
                else -> error("unsupported PBKDF2 parameter")
            }
        }

        val encryption = DerReader.children(pbes2[1].content)
        require(encryption.size >= 2) { "encryption scheme malformed" }
        val keyBytes = when {
            encryption[0].content.contentEquals(OID_AES_256_CBC) -> 32
            encryption[0].content.contentEquals(OID_AES_128_CBC) -> 16
            else -> error("unsupported cipher")
        }
        require(keyLengthBytes == null || keyLengthBytes == keyBytes) { "PBKDF2 key size does not match cipher" }
        val derived = deriveKey(password, salt, iterations, keyBytes, prfName)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), IvParameterSpec(encryption[1].content))
        return cipher.doFinal(encrypted)
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
        prfName: String
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBytes * 8)
        return try {
            SecretKeyFactory.getInstance(prfName).generateSecret(spec).encoded
        } catch (error: NoSuchAlgorithmException) {
            if (prfName != "PBKDF2WithHmacSHA256") throw error
            val passwordBytes = password.concatToString().toByteArray(Charsets.UTF_8)
            try {
                pbkdf2HmacSha256(passwordBytes, salt, iterations, keyLengthBytes)
            } finally {
                passwordBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun parseSafeBags(
        safeContentsDer: ByteArray,
        password: CharArray,
        certs: MutableList<X509Certificate>,
        onKey: (PrivateKey) -> Unit
    ) {
        val safeContents = DerReader.read(safeContentsDer)
        require(safeContents.tag == 0x30) { "SafeContents malformed" }
        for (bag in DerReader.children(safeContents.content)) {
            val bagParts = DerReader.children(bag.content)
            if (bagParts.size < 2) continue
            val bagOid = bagParts[0].content
            val bagValue = DerReader.children(bagParts[1].content).first()
            when {
                bagOid.contentEquals(OID_PKCS8_SHROUDED_KEY_BAG) ->
                    onKey(decryptShroudedKeyBag(bagValue, password))
                bagOid.contentEquals(OID_CERT_BAG) -> parseCertBag(bagValue, certs)
            }
        }
    }

    private fun decryptShroudedKeyBag(bagValue: Der, password: CharArray): PrivateKey {
        val parts = DerReader.children(bagValue.content)
        require(parts.size == 2) { "EncryptedPrivateKeyInfo malformed" }
        val pkcs8 = decryptPbes2(parts[0], parts[1].content, password)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun parseCertBag(bagValue: Der, certs: MutableList<X509Certificate>) {
        val parts = DerReader.children(bagValue.content)
        if (parts.size < 2 || !parts[0].content.contentEquals(OID_X509_CERT)) return
        val explicit = DerReader.children(parts[1].content).first()
        val factory = CertificateFactory.getInstance("X.509")
        certs.add(factory.generateCertificate(ByteArrayInputStream(explicit.content)) as X509Certificate)
    }

    private fun readInt(bytes: ByteArray): Int {
        var value = 0
        var start = 0
        if (bytes.size > 1 && bytes[0].toInt() == 0x00) start = 1
        for (index in start until bytes.size) value = (value shl 8) or (bytes[index].toInt() and 0xff)
        return value
    }

    private data class Der(val tag: Int, val content: ByteArray)

    private object DerReader {
        fun read(der: ByteArray, offset: Int = 0): Der {
            require(offset < der.size) { "DER truncated" }
            val tag = der[offset].toInt() and 0xff
            var index = offset + 1
            var length = der[index].toInt() and 0xff
            index++
            if (length and 0x80 != 0) {
                val byteCount = length and 0x7f
                length = 0
                repeat(byteCount) {
                    length = (length shl 8) or (der[index].toInt() and 0xff)
                    index++
                }
            }
            require(index + length <= der.size) { "DER length overflow" }
            return Der(tag, der.copyOfRange(index, index + length))
        }

        fun children(content: ByteArray): List<Der> {
            val result = mutableListOf<Der>()
            var offset = 0
            while (offset < content.size) {
                val value = read(content, offset)
                result.add(value)
                val step = headerSize(content, offset) + value.content.size
                if (step <= 0) break
                offset += step
            }
            return result
        }

        private fun headerSize(der: ByteArray, offset: Int): Int {
            var index = offset + 1
            val length = der[index].toInt() and 0xff
            index++
            if (length and 0x80 != 0) index += length and 0x7f
            return index - offset
        }
    }
}

/** PBKDF2-HMAC-SHA256 compatibility path for Android API 24-25. */
internal fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int
): ByteArray {
    require(iterations > 0 && keyLengthBytes > 0)
    val mac = Mac.getInstance("HmacSHA256")
    // Some providers reject an empty SecretKeySpec. A single zero byte produces
    // the same zero-padded HMAC key block as an empty password.
    mac.init(SecretKeySpec(password.takeUnless(ByteArray::isEmpty) ?: byteArrayOf(0), "HmacSHA256"))
    val output = ByteArray(keyLengthBytes)
    val input = ByteArray(salt.size + 4)
    salt.copyInto(input)
    var outputOffset = 0
    var block = 1
    while (outputOffset < output.size) {
        input[input.size - 4] = (block ushr 24).toByte()
        input[input.size - 3] = (block ushr 16).toByte()
        input[input.size - 2] = (block ushr 8).toByte()
        input[input.size - 1] = block.toByte()
        var u = mac.doFinal(input)
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (index in result.indices) result[index] = (result[index].toInt() xor u[index].toInt()).toByte()
        }
        val copied = minOf(result.size, output.size - outputOffset)
        result.copyInto(output, outputOffset, 0, copied)
        outputOffset += copied
        u.fill(0)
        result.fill(0)
        block++
    }
    input.fill(0)
    return output
}
